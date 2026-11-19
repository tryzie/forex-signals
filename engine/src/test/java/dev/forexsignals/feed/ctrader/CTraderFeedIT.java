package dev.forexsignals.feed.ctrader;

import dev.forexsignals.config.AppConfig;
import dev.forexsignals.config.ConfigCrypto;
import dev.forexsignals.market.SymbolCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * cTrader demo account check ("1b: pending" until Spotware approves the app). Runs only when
 * ~/.forexsignals/conf.xml (or $FOREXSIGNALS_CONF) holds cTrader credentials; otherwise skipped.
 * Read-only: authenticates, loads symbols, fetches one hour of M1 trendbars.
 */
@Tag("needs-feed")
class CTraderFeedIT {

    private static AppConfig cfg;

    @BeforeAll
    static void credentials() {
        Path conf = AppConfig.defaultConfFile();
        assumeTrue(Files.isRegularFile(conf), "no " + conf + ": cTrader credentials absent (1b pending)");
        cfg = AppConfig.load(conf, ConfigCrypto.loadOrCreateKey(ConfigCrypto.defaultKeyFile()));
        assumeTrue(cfg.ctrader().credentialsPresent(), "cTrader credentials not set in conf.xml (1b pending)");
    }

    @Test
    void authenticatesReadOnlyAndFetchesTrendbars() throws Exception {
        var cat = SymbolCatalog.load(cfg.symbolsFile());
        AppConfig.CTrader ct = cfg.ctrader();
        try (CTraderAdapter a = new CTraderAdapter(ct, new CTraderAdapter.TokenStore() {
            @Override public String accessToken() { return cfg.ctrader().accessToken().value(); }
            @Override public String refreshToken() { return cfg.ctrader().refreshToken().value(); }
            @Override public void save(String access, String refresh) {
                cfg.updateSecret("config/ctrader/accessToken", access);
                cfg.updateSecret("config/ctrader/refreshToken", refresh);
            }
        })) {
            var specs = a.loadSymbols(cat.onFeed("ctrader", Optional.empty()));
            assertThat(specs).containsKeys("EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "XAUUSD", "XAGUSD");
            Instant to = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).minusSeconds(12 * 3600);
            while (!dev.forexsignals.market.TradingHours.FX.isOpen(to)) to = to.minusSeconds(86_400);
            assertThat(a.fetchM1(cat.get("EURUSD"), to.minusSeconds(3600), to)).isNotEmpty();
        }
    }
}
