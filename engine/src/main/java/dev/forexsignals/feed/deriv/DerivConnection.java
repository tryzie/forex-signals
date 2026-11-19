package dev.forexsignals.feed.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One WebSocket to the Deriv public market-data endpoint (no authentication; market data only).
 * Requests carry a req_id and are matched to their response; messages without a waiting request
 * (subscription streams such as "tick") go to the stream handler. A ping every 30 s keeps the socket alive.
 *
 * Only market-data calls are ever sent: ping, active_symbols, ticks, ticks_history, forget_all.
 */
public final class DerivConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DerivConnection.class);
    static final ObjectMapper JSON = new ObjectMapper();

    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong reqIds = new AtomicLong();
    private final AtomicBoolean open = new AtomicBoolean();
    private final StringBuilder partial = new StringBuilder();
    private final Consumer<JsonNode> streamHandler;
    private final Consumer<String> onClosed;
    private WebSocket ws;
    private Thread pinger;

    private DerivConnection(Consumer<JsonNode> streamHandler, Consumer<String> onClosed) {
        this.streamHandler = streamHandler;
        this.onClosed = onClosed;
    }

    public static DerivConnection open(String endpoint, String appId, Consumer<JsonNode> streamHandler, Consumer<String> onClosed)
            throws Exception {
        DerivConnection c = new DerivConnection(streamHandler, onClosed);
        String url = endpoint;
        if (appId != null && !appId.isBlank()) {
            url += (url.contains("?") ? "&" : "?") + "app_id=" + URLEncoder.encode(appId, StandardCharsets.UTF_8);
        }
        c.ws = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(url), c.listener())
                .get(20, TimeUnit.SECONDS);
        c.open.set(true);
        c.pinger = Thread.ofVirtual().name("deriv-ping").start(() -> {
            while (c.open.get()) {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                    c.request(Map.of("ping", 1), Duration.ofSeconds(20));
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    log.warn("deriv ping failed: {}", e.toString());
                    c.abort("ping failed: " + e.getMessage());
                    return;
                }
            }
        });
        return c;
    }

    private WebSocket.Listener listener() {
        return new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                String text = null;
                synchronized (partial) {
                    partial.append(data);
                    if (last) {
                        text = partial.toString();
                        partial.setLength(0);
                    }
                }
                if (text != null) dispatch(text);
                w.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
                closed("closed by server: " + code + " " + reason);
                return null;
            }

            @Override
            public void onError(WebSocket w, Throwable error) {
                closed("socket error: " + error);
            }
        };
    }

    private void dispatch(String text) {
        JsonNode msg;
        try {
            msg = JSON.readTree(text);
        } catch (Exception e) {
            log.warn("deriv: unparseable message ({} chars)", text.length());
            return;
        }
        JsonNode reqId = msg.path("req_id");
        if (reqId.isNumber()) {
            CompletableFuture<JsonNode> f = pending.remove(reqId.asLong());
            if (f != null) {
                f.complete(msg);
                // A subscribe request's first answer is also the first stream message.
                if (msg.has("subscription")) streamHandler.accept(msg);
                return;
            }
        }
        streamHandler.accept(msg);
    }

    /** Sends a request and waits for its response. Deriv error responses throw {@link DerivError}. */
    public JsonNode request(Map<String, ?> body, Duration timeout) throws Exception {
        if (!open.get()) throw new IllegalStateException("deriv connection is closed");
        long id = reqIds.incrementAndGet();
        ObjectNode req = JSON.valueToTree(body);
        req.put("req_id", id);
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        synchronized (this) {
            ws.sendText(JSON.writeValueAsString(req), true).get(10, TimeUnit.SECONDS);
        }
        JsonNode resp;
        try {
            resp = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            pending.remove(id);
        }
        if (resp.has("error")) throw new DerivError(resp.path("error").path("code").asText(), resp.path("error").path("message").asText());
        return resp;
    }

    public boolean isOpen() { return open.get(); }

    private void closed(String why) {
        if (open.compareAndSet(true, false)) {
            pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException(why)));
            pending.clear();
            if (pinger != null) pinger.interrupt();
            onClosed.accept(why);
        }
    }

    private void abort(String why) {
        if (ws != null) ws.abort();
        closed(why);
    }

    @Override
    public void close() {
        if (open.get() && ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                ws.abort();
            }
        }
        closed("closed by client");
    }

    /** An error answer from Deriv (code such as RateLimit, InvalidSymbol, MarketIsClosed). */
    public static final class DerivError extends Exception {
        public final String code;

        public DerivError(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public boolean isRateLimit() { return "RateLimit".equalsIgnoreCase(code); }
    }
}
