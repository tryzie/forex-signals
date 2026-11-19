package dev.forexsignals.feed.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import dev.forexsignals.market.Candle;
import dev.forexsignals.market.PriceBasis;
import dev.forexsignals.market.SymbolCatalog;
import dev.forexsignals.market.Tick;
import dev.forexsignals.market.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Message shapes as returned by the new Deriv public endpoint (captured 2026-11-19, values shortened). */
class DerivParsingTest {

    private static JsonNode json(String s) throws Exception { return DerivConnection.JSON.readTree(s); }

    @Test
    void liveTickHasBidAskAndQuote() throws Exception {
        JsonNode msg = json("""
                {"echo_req":{"ticks":"frxEURUSD","subscribe":1},"msg_type":"tick","subscription":{"id":"d58e"},
                 "tick":{"ask":1.09866,"bid":1.09858,"epoch":1795091791,"id":"d58e","pip_size":5,"quote":1.09862,"symbol":"frxEURUSD"}}""");
        Tick t = DerivAdapter.parseTick(msg, Map.of("frxEURUSD", "EURUSD"), Instant.EPOCH, 7);
        assertThat(t.symbol()).isEqualTo("EURUSD");
        assertThat(t.ts()).isEqualTo(Instant.ofEpochSecond(1795091791));
        assertThat(t.bid()).isEqualTo(1.09858);
        assertThat(t.ask()).isEqualTo(1.09866);
        assertThat(t.price(PriceBasis.QUOTE)).isEqualTo(1.09862);
        assertThat(t.spread()).isCloseTo(0.00008, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(DerivAdapter.parseTick(json("{\"msg_type\":\"ping\",\"ping\":\"pong\"}"), Map.of(), Instant.EPOCH, 1)).isNull();
    }

    @Test
    void candlesAndHistoryTicks() throws Exception {
        List<Candle> cs = DerivAdapter.parseCandles(json("""
                {"candles":[{"close":1.09902,"epoch":1795089960,"high":1.09904,"low":1.09901,"open":1.09901},
                            {"close":1.09891,"epoch":1795090020,"high":1.09902,"low":1.09890,"open":1.09902}],"msg_type":"candles"}"""),
                "EURUSD", Timeframe.M1);
        assertThat(cs).hasSize(2);
        assertThat(cs.get(1).openTime()).isEqualTo(Instant.ofEpochSecond(1795090020));
        assertThat(cs.get(1).low()).isEqualTo(1.09890);

        List<Tick> ts = DerivAdapter.parseHistoryTicks(json("""
                {"history":{"prices":[1.09870,1.09872],"times":[1795091728,1795091729]},"msg_type":"history","pip_size":5}"""),
                "EURUSD", new AtomicLong());
        assertThat(ts).extracting(t -> t.price(PriceBasis.QUOTE)).containsExactly(1.09870, 1.09872);
        assertThat(ts.getFirst().hasBid()).isFalse();
        assertThat(Double.isNaN(ts.getFirst().spread())).isTrue();
    }

    @Test
    void requestWindowsStayBelowTheMeasuredThousandItemCap() {
        DerivAdapter d = new DerivAdapter("wss://unused", "");
        assertThat(DerivAdapter.MAX_COUNT).isEqualTo(1000);
        assertThat(d.maxSpan(Timeframe.M1).toMinutes()).isLessThan(1000);
        assertThat(d.maxSpan(Timeframe.M5).toMinutes() / 5).isLessThan(1000);
        assertThat(d.maxSpan(null).toSeconds()).as("ticks: ~1/s").isLessThan(1000);
    }

    @Test
    void activeSymbolsMapToCatalogSymbolsAndMissingOnesAreAbsent() throws Exception {
        var cat = SymbolCatalog.load(Optional.empty());
        JsonNode resp = json("""
                {"active_symbols":[
                  {"exchange_is_open":1,"market":"forex","pip_size":0.00001,"underlying_symbol":"frxEURUSD","trade_count":5},
                  {"exchange_is_open":1,"market":"forex","pip_size":0.001,"underlying_symbol":"frxUSDJPY"},
                  {"exchange_is_open":1,"market":"synthetic_index","pip_size":0.01,"underlying_symbol":"1HZ100V"}]}""");
        var specs = DerivAdapter.parseActiveSymbols(resp, List.of(cat.get("EURUSD"), cat.get("USDJPY"), cat.get("XAGUSD")));
        assertThat(specs).containsOnlyKeys("EURUSD", "USDJPY");
        assertThat(specs.get("EURUSD").digits()).isEqualTo(5);
        assertThat(specs.get("USDJPY").digits()).isEqualTo(3);
        assertThat(specs.get("EURUSD").raw()).doesNotContainKey("trade_count"); // changes constantly; would trigger SPEC_CHANGED
    }
}
