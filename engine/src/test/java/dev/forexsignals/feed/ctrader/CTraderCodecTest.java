package dev.forexsignals.feed.ctrader;

import com.xtrader.protocol.openapi.v2.ProtoOAErrorRes;
import com.xtrader.protocol.openapi.v2.ProtoOAGetAccountListByAccessTokenRes;
import com.xtrader.protocol.openapi.v2.ProtoOASpotEvent;
import com.xtrader.protocol.openapi.v2.ProtoOAVersionReq;
import com.xtrader.protocol.openapi.v2.model.ProtoOAClientPermissionScope;
import com.xtrader.protocol.openapi.v2.model.ProtoOACommissionType;
import com.xtrader.protocol.openapi.v2.model.ProtoOACtidTraderAccount;
import com.xtrader.protocol.openapi.v2.model.ProtoOAInterval;
import com.xtrader.protocol.openapi.v2.model.ProtoOAPayloadType;
import com.xtrader.protocol.openapi.v2.model.ProtoOASymbol;
import com.xtrader.protocol.openapi.v2.model.ProtoOATickData;
import com.xtrader.protocol.openapi.v2.model.ProtoOATrendbar;
import com.xtrader.protocol.proto.commons.ProtoMessage;
import dev.forexsignals.config.ConfigException;
import dev.forexsignals.feed.SymbolSpec;
import dev.forexsignals.market.Candle;
import dev.forexsignals.market.Tick;
import dev.forexsignals.market.Timeframe;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** cTrader wire handling without a network (the live feed is "1b: pending" Spotware approval). */
class CTraderCodecTest {

    @Test
    void framesRoundTripWithLengthPrefix() throws Exception {
        ProtoMessage m = CTraderConnection.wrap(ProtoOAPayloadType.PROTO_OA_VERSION_REQ_VALUE, ProtoOAVersionReq.newBuilder().build(), "fx-1");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CTraderConnection.writeFrame(bos, m);
        CTraderConnection.writeFrame(bos, m);
        byte[] bytes = bos.toByteArray();
        assertThat(((bytes[0] & 0xff) << 24) | ((bytes[1] & 0xff) << 16) | ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff))
                .isEqualTo(m.getSerializedSize());
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        assertThat(CTraderConnection.readFrame(in)).isEqualTo(m);
        assertThat(CTraderConnection.readFrame(in).getClientMsgId()).isEqualTo("fx-1");
    }

    @Test
    void errorResponsesBecomeTypedErrors() {
        ProtoMessage err = CTraderConnection.wrap(ProtoOAPayloadType.PROTO_OA_ERROR_RES_VALUE,
                ProtoOAErrorRes.newBuilder().setErrorCode("REQUEST_FREQUENCY_EXCEEDED").setDescription("slow down").build(), "x");
        assertThatThrownBy(() -> CTraderConnection.throwIfError(err))
                .isInstanceOfSatisfying(CTraderConnection.CTraderError.class, e -> assertThat(e.isRateLimit()).isTrue());
    }

    private static ProtoOAGetAccountListByAccessTokenRes accounts(ProtoOAClientPermissionScope scope, boolean live) {
        return ProtoOAGetAccountListByAccessTokenRes.newBuilder().setAccessToken("t").setPermissionScope(scope)
                .addCtidTraderAccount(ProtoOACtidTraderAccount.newBuilder().setCtidTraderAccountId(4242).setTraderLogin(1234567).setIsLive(live))
                .build();
    }

    @Test
    void readOnlyScopeAndDemoAccountAreEnforced() {
        assertThat(CTraderAdapter.chooseDemoAccount(accounts(ProtoOAClientPermissionScope.SCOPE_VIEW, false), "1234567")).isEqualTo(4242);
        assertThatThrownBy(() -> CTraderAdapter.chooseDemoAccount(accounts(ProtoOAClientPermissionScope.SCOPE_TRADE, false), "1234567"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("read-only token");
        assertThatThrownBy(() -> CTraderAdapter.chooseDemoAccount(accounts(ProtoOAClientPermissionScope.SCOPE_VIEW, true), "1234567"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("not marked as a demo");
        assertThatThrownBy(() -> CTraderAdapter.chooseDemoAccount(accounts(ProtoOAClientPermissionScope.SCOPE_VIEW, false), "999"))
                .hasMessageContaining("not among the accounts");
        // Empty accountNumber: never guess; list the accounts (numbers only) and stop.
        assertThatThrownBy(() -> CTraderAdapter.chooseDemoAccount(accounts(ProtoOAClientPermissionScope.SCOPE_VIEW, false), ""))
                .isInstanceOf(ConfigException.class).hasMessageContaining("1234567 (demo)").hasMessageContaining("Set accountNumber");
        // A response without any scope is treated as unsafe.
        ProtoOAGetAccountListByAccessTokenRes noScope = ProtoOAGetAccountListByAccessTokenRes.newBuilder().setAccessToken("t").build();
        assertThatThrownBy(() -> CTraderAdapter.chooseDemoAccount(noScope, "1234567")).isInstanceOf(ConfigException.class);
    }

    @Test
    void symbolSpecConvertsCentsToLots() {
        ProtoOASymbol s = ProtoOASymbol.newBuilder().setSymbolId(1).setDigits(5).setPipPosition(4)
                .setLotSize(10_000_000L).setMinVolume(100_000L).setStepVolume(100_000L).setMaxVolume(1_000_000_000L)
                .setCommissionType(ProtoOACommissionType.USD_PER_LOT).setPreciseTradingCommissionRate(300_000_000L)
                .setSwapLong(-7.1).setSwapShort(2.3).setScheduleTimeZone("UTC")
                .addSchedule(ProtoOAInterval.newBuilder().setStartSecond(79200).setEndSecond(165600)).build();
        SymbolSpec spec = CTraderAdapter.toSpec("EURUSD", "EURUSD", s);
        assertThat(spec.contractSize()).isEqualTo(100_000.0);
        assertThat(spec.minVolume()).isEqualTo(0.01);
        assertThat(spec.volumeStep()).isEqualTo(0.01);
        assertThat(spec.maxVolume()).isEqualTo(100.0);
        assertThat(spec.commission()).containsEntry("type", "USD_PER_LOT").containsEntry("preciseRate", 3.0);
        assertThat(spec.hash()).isEqualTo(CTraderAdapter.toSpec("EURUSD", "EURUSD", s).hash());
        assertThat(spec.hash()).isNotEqualTo(CTraderAdapter.toSpec("EURUSD", "EURUSD", s.toBuilder().setLotSize(1_000_000L).build()).hash());
    }

    @Test
    void specHashDoesNotDependOnMapOrder() {
        java.util.Map<String, Object> i1 = new java.util.LinkedHashMap<>(), i2 = new java.util.LinkedHashMap<>();
        i1.put("startSecond", 1); i1.put("endSecond", 2);
        i2.put("endSecond", 2); i2.put("startSecond", 1);
        java.util.Map<String, Object> h1 = new java.util.LinkedHashMap<>(), h2 = new java.util.LinkedHashMap<>();
        h1.put("timeZone", "America/New_York"); h1.put("intervals", List.of(i1));
        h2.put("intervals", List.of(i2)); h2.put("timeZone", "America/New_York");
        SymbolSpec a = new SymbolSpec("EURUSD", "ctrader", "EURUSD", 1L, 5, 4, 1e5, 0.01, 0.01, 100.0, Map.of("type", "USD_PER_LOT", "preciseRate", 3.0), -0.7, 0.1, h1, Map.of());
        SymbolSpec b = new SymbolSpec("EURUSD", "ctrader", "EURUSD", 1L, 5, 4, 1e5, 0.01, 0.01, 100.0, Map.of("preciseRate", 3.0, "type", "USD_PER_LOT"), -0.7, 0.1, h2, Map.of("other", 1));
        assertThat(a.hash()).isEqualTo(b.hash());
        SymbolSpec c = new SymbolSpec("EURUSD", "ctrader", "EURUSD", 1L, 5, 4, 1e5, 0.02, 0.01, 100.0, Map.of("type", "USD_PER_LOT", "preciseRate", 3.0), -0.7, 0.1, h1, Map.of());
        assertThat(c.hash()).as("min volume change is a spec change").isNotEqualTo(a.hash());
    }

    @Test
    void trendbarsDecodeFromLowPlusDeltas() {
        ProtoOATrendbar b = ProtoOATrendbar.newBuilder().setVolume(57).setLow(108_734L).setDeltaOpen(10).setDeltaHigh(25).setDeltaClose(3)
                .setUtcTimestampInMinutes((int) (Instant.parse("2024-01-17T10:05:00Z").getEpochSecond() / 60)).build();
        Candle c = CTraderAdapter.decodeTrendbars(List.of(b), "EURUSD", Timeframe.M5).getFirst();
        assertThat(c.openTime()).isEqualTo(Instant.parse("2024-01-17T10:05:00Z"));
        assertThat(new double[]{c.open(), c.high(), c.low(), c.close()}).containsExactly(1.08744, 1.08759, 1.08734, 1.08737);
        assertThat(c.tickCount()).isEqualTo(57);
    }

    @Test
    void tickDataDecodesNewestFirstDeltasAndMergesBidAsk() {
        // Newest first: 10:00:03 @108740, then -1000 ms / -2 points, then -2000 ms / +5 points.
        long t0 = Instant.parse("2024-01-17T10:00:03Z").toEpochMilli();
        List<long[]> bids = CTraderAdapter.decodeTickData(List.of(
                ProtoOATickData.newBuilder().setTimestamp(t0).setTick(108_740).build(),
                ProtoOATickData.newBuilder().setTimestamp(-1000).setTick(-2).build(),
                ProtoOATickData.newBuilder().setTimestamp(-2000).setTick(5).build()));
        assertThat(bids).extracting(x -> x[0] - t0 + ":" + x[1]).containsExactly("0:108740", "-1000:108738", "-3000:108743");

        List<long[]> bidsOldestFirst = List.of(bids.get(2), bids.get(1), bids.get(0));
        List<long[]> asks = List.of(new long[]{t0 - 3500, 108_750}, new long[]{t0 - 500, 108_748});
        List<Tick> ticks = CTraderAdapter.mergeBidAsk("EURUSD", bidsOldestFirst, asks, new AtomicLong());
        // One tick per bid OR ask change, like live spot events. The ask at -3500 ms comes before any bid: skipped.
        // The ask-only change at -500 ms carries the unchanged bid (this is what makes cTrader bars open there).
        assertThat(ticks).extracting(t -> t.ts().toEpochMilli() - t0).containsExactly(-3000L, -1000L, -500L, 0L);
        assertThat(ticks).extracting(Tick::bid).containsExactly(1.08743, 1.08738, 1.08738, 1.0874);
        assertThat(ticks).extracting(Tick::ask).containsExactly(1.0875, 1.0875, 1.08748, 1.08748);
    }

    @Test
    void spotEventsCarryOnlyChangedSides() {
        CTraderAdapter.SpotMerger m = new CTraderAdapter.SpotMerger(Map.of(1L, "EURUSD"));
        Instant now = Instant.parse("2024-01-17T10:00:00Z");
        assertThat(m.onSpot(ProtoOASpotEvent.newBuilder().setCtidTraderAccountId(1).setSymbolId(1).setBid(108_740).build(), now, 1))
                .as("ask not known yet").isNull();
        Tick t = m.onSpot(ProtoOASpotEvent.newBuilder().setCtidTraderAccountId(1).setSymbolId(1).setAsk(108_748).setTimestamp(now.toEpochMilli()).build(), now, 2);
        assertThat(t.bid()).isEqualTo(1.0874);
        assertThat(t.ask()).isEqualTo(1.08748);
        Tick t2 = m.onSpot(ProtoOASpotEvent.newBuilder().setCtidTraderAccountId(1).setSymbolId(1).setBid(108_741).build(), now.plusSeconds(1), 3);
        assertThat(t2.ask()).as("unchanged side carried over").isEqualTo(1.08748);
        assertThat(m.onSpot(ProtoOASpotEvent.newBuilder().setCtidTraderAccountId(1).setSymbolId(99).setBid(1).build(), now, 4)).isNull();
    }
}
