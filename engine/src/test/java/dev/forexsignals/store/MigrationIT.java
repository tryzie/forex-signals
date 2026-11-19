package dev.forexsignals.store;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Flyway V1 applies on Percona PG18, partitions are created on demand, and the session is UTC. */
class MigrationIT {

    @Test
    void v1AppliesAndPartitionsAreIdempotent() throws Exception {
        try (Database db = TestDb.freshMigrated()) {
            assertThat(db.migrate()).as("second migrate is a no-op").isZero();

            int created = db.ensurePartitions(Instant.parse("2024-01-15T00:00:00Z"), Instant.parse("2024-04-01T00:00:00Z"));
            assertThat(created).isEqualTo(3 * 3); // Jan, Feb, Mar for tick, candle_m1, candle
            assertThat(db.ensurePartitions(Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-04-01T00:00:00Z"))).isZero();

            try (Connection c = db.connection(); Statement st = c.createStatement()) {
                List<String> parts = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("""
                        SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                        JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 'candle_m1' ORDER BY 1""")) {
                    while (rs.next()) parts.add(rs.getString(1));
                }
                assertThat(parts).containsExactly("candle_m1_y2024m01", "candle_m1_y2024m02", "candle_m1_y2024m03");

                try (ResultSet rs = st.executeQuery("SHOW TIME ZONE")) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("UTC");
                }
                try (ResultSet rs = st.executeQuery("SELECT commission_per_lot_side->>'XAG', notes FROM cost_profile WHERE name='pepperstone_razor_ctrader'")) {
                    rs.next();
                    assertThat(rs.getString(1)).as("XAG commission from the cTrader spec (V2)").isEqualTo("0.00");
                    assertThat(rs.getString(2)).contains("not yet confirmed by a demo statement");
                }
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN " +
                        "('symbol','symbol_feed','symbol_spec','tick','candle_m1','candle','import_progress','gap_report','spread_stats'," +
                        "'cost_profile','news_event','strategy','run','signal','signal_event','risk_rejection','paper_trade'," +
                        "'equity_snapshot','kill_switch_event','validation_result','feed_event')")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(21);
                }
            }
        }
    }
}
