package dev.forexsignals.feed.ctrader;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.xtrader.protocol.openapi.v2.ProtoOAErrorRes;
import com.xtrader.protocol.openapi.v2.model.ProtoOAPayloadType;
import com.xtrader.protocol.proto.commons.ProtoErrorRes;
import com.xtrader.protocol.proto.commons.ProtoHeartbeatEvent;
import com.xtrader.protocol.proto.commons.ProtoMessage;
import com.xtrader.protocol.proto.commons.model.ProtoPayloadType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * cTrader Open API transport: TLS socket, frames of [4-byte big-endian length][ProtoMessage bytes].
 * A reader virtual thread dispatches responses to waiting requests by clientMsgId; everything else
 * (spot events, disconnect notices) goes to the event handler. A heartbeat is sent every 10 s.
 *
 * Only market-data and account-info messages are sent by this codebase; the order messages are not even
 * compiled (removed from the vendored proto, enforced by ReadOnlyGuardTest).
 */
public final class CTraderConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CTraderConnection.class);
    static final int MAX_FRAME = 16 * 1024 * 1024;

    private final SSLSocket socket;
    private final DataOutputStream out;
    private final Map<String, CompletableFuture<ProtoMessage>> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Consumer<ProtoMessage> events;
    private final Consumer<String> onClosed;
    private final Thread reader, heartbeat;

    private CTraderConnection(SSLSocket socket, Consumer<ProtoMessage> events, Consumer<String> onClosed) throws IOException {
        this.socket = socket;
        this.out = new DataOutputStream(socket.getOutputStream());
        this.events = events;
        this.onClosed = onClosed;
        DataInputStream in = new DataInputStream(socket.getInputStream());
        this.reader = Thread.ofVirtual().name("ctrader-reader").start(() -> readLoop(in));
        this.heartbeat = Thread.ofVirtual().name("ctrader-heartbeat").start(this::heartbeatLoop);
    }

    public static CTraderConnection open(String host, int port, Consumer<ProtoMessage> events, Consumer<String> onClosed) throws IOException {
        SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
        s.setSoTimeout(0);
        s.setKeepAlive(true);
        s.startHandshake();
        return new CTraderConnection(s, events, onClosed);
    }

    // ------------------------------------------------------------------------------------ framing (static, testable)

    static void writeFrame(OutputStream os, ProtoMessage m) throws IOException {
        byte[] body = m.toByteArray();
        DataOutputStream d = os instanceof DataOutputStream dos ? dos : new DataOutputStream(os);
        d.writeInt(body.length);
        d.write(body);
        d.flush();
    }

    static ProtoMessage readFrame(InputStream is) throws IOException {
        DataInputStream d = is instanceof DataInputStream dis ? dis : new DataInputStream(is);
        int len = d.readInt();
        if (len < 0 || len > MAX_FRAME) throw new IOException("bad frame length " + len);
        byte[] body = new byte[len];
        d.readFully(body);
        return ProtoMessage.parseFrom(body);
    }

    static ProtoMessage wrap(int payloadType, MessageLite payload, String clientMsgId) {
        ProtoMessage.Builder b = ProtoMessage.newBuilder().setPayloadType(payloadType).setPayload(ByteString.copyFrom(payload.toByteArray()));
        if (clientMsgId != null) b.setClientMsgId(clientMsgId);
        return b.build();
    }

    // ------------------------------------------------------------------------------------ I/O loops

    private void readLoop(DataInputStream in) {
        try {
            while (open.get()) {
                ProtoMessage m = readFrame(in);
                if (m.getPayloadType() == ProtoPayloadType.HEARTBEAT_EVENT_VALUE) continue;
                CompletableFuture<ProtoMessage> f = m.hasClientMsgId() ? pending.remove(m.getClientMsgId()) : null;
                if (f != null) f.complete(m);
                else events.accept(m);
            }
        } catch (EOFException e) {
            closed("connection closed by server");
        } catch (IOException e) {
            closed("read failed: " + e.getMessage());
        }
    }

    private void heartbeatLoop() {
        while (open.get()) {
            try {
                Thread.sleep(Duration.ofSeconds(10));
                send(wrap(ProtoPayloadType.HEARTBEAT_EVENT_VALUE, ProtoHeartbeatEvent.newBuilder().build(), null));
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                closed("heartbeat failed: " + e.getMessage());
                return;
            }
        }
    }

    private synchronized void send(ProtoMessage m) throws IOException {
        writeFrame(out, m);
    }

    /**
     * Sends a request and waits for its response. An error response (ProtoOAErrorRes / ProtoErrorRes)
     * becomes a {@link CTraderError}.
     */
    public ProtoMessage request(int payloadType, MessageLite payload, Duration timeout) throws Exception {
        if (!open.get()) throw new IOException("cTrader connection is closed");
        String id = "fx-" + ids.incrementAndGet();
        CompletableFuture<ProtoMessage> f = new CompletableFuture<>();
        pending.put(id, f);
        try {
            send(wrap(payloadType, payload, id));
            ProtoMessage resp = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            throwIfError(resp);
            return resp;
        } finally {
            pending.remove(id);
        }
    }

    static void throwIfError(ProtoMessage m) throws CTraderError, InvalidProtocolBufferException {
        if (m.getPayloadType() == ProtoOAPayloadType.PROTO_OA_ERROR_RES_VALUE) {
            ProtoOAErrorRes e = ProtoOAErrorRes.parseFrom(m.getPayload());
            throw new CTraderError(e.getErrorCode(), e.getDescription());
        }
        if (m.getPayloadType() == ProtoPayloadType.ERROR_RES_VALUE) {
            ProtoErrorRes e = ProtoErrorRes.parseFrom(m.getPayload());
            throw new CTraderError(e.getErrorCode(), e.getDescription());
        }
    }

    public boolean isOpen() { return open.get(); }

    private void closed(String why) {
        if (open.compareAndSet(true, false)) {
            try { socket.close(); } catch (IOException ignored) { /* closing anyway */ }
            pending.values().forEach(f -> f.completeExceptionally(new IOException(why)));
            pending.clear();
            heartbeat.interrupt();
            log.info("cTrader connection closed: {}", why);
            onClosed.accept(why);
        }
    }

    @Override
    public void close() {
        closed("closed by client");
        reader.interrupt();
    }

    /** An error answer from cTrader, e.g. REQUEST_FREQUENCY_EXCEEDED or CH_ACCESS_TOKEN_INVALID. */
    public static final class CTraderError extends Exception {
        public final String code;

        public CTraderError(String code, String description) {
            super(code + (description == null || description.isBlank() ? "" : ": " + description));
            this.code = code;
        }

        public boolean isRateLimit() { return "REQUEST_FREQUENCY_EXCEEDED".equals(code) || "BLOCKED_PAYLOAD_TYPE".equals(code); }

        public boolean isTokenProblem() { return code != null && (code.contains("ACCESS_TOKEN") || code.equals("OA_AUTH_TOKEN_EXPIRED")); }
    }
}
