package dev.forexsignals.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.forexsignals.config.AppConfig;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Connection pool + Flyway (migrations from the classpath, so they ship inside forex-signals.jar)
 * + the monthly partition helper. Every session runs in UTC.
 */
public final class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);
    /** Tables partitioned by month (PLAN §11). */
    public static final List<String> PARTITIONED = List.of("tick", "candle_m1", "candle");

    private final HikariDataSource ds;

    private Database(HikariDataSource ds) { this.ds = ds; }

    public static Database connect(AppConfig.Database cfg) {
        return connect(cfg.jdbcUrl(), cfg.user(), cfg.password().value(), cfg.poolSize());
    }

    public static Database connect(String jdbcUrl, String user, String password, int poolSize) {
        HikariConfig hc = new HikariConfig();
        // reWriteBatchedInserts turns JDBC batches into multi-row INSERTs (much faster imports).
        hc.setJdbcUrl(jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "reWriteBatchedInserts=true&ApplicationName=forex-signals");
        hc.setUsername(user);
        hc.setPassword(password);
        hc.setMaximumPoolSize(poolSize);
        hc.setPoolName("forex-db");
        hc.setConnectionInitSql("SET TIME ZONE 'UTC'");
        return new Database(new HikariDataSource(hc));
    }

    public DataSource dataSource() { return ds; }

    public Connection connection() throws SQLException { return ds.getConnection(); }

    /** Applies pending Flyway migrations from classpath:db/migration and returns how many ran. */
    public int migrate() {
        MigrateResult r = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .failOnMissingLocations(true)
                .load()
                .migrate();
        log.info("flyway: {} migration(s) applied, schema version {}", r.migrationsExecuted,
                r.targetSchemaVersion != null ? r.targetSchemaVersion : r.initialSchemaVersion);
        return r.migrationsExecuted;
    }

    /** Ensures monthly partitions of every partitioned table cover [from, to). Idempotent. */
    public int ensurePartitions(Instant from, Instant to) {
        int created = 0;
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement("SELECT fx_ensure_month_partitions(?, ?, ?)")) {
            for (String table : PARTITIONED) {
                ps.setString(1, table);
                ps.setObject(2, utc(from));
                ps.setObject(3, utc(to));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    created += rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new StoreException("cannot create partitions " + from + " .. " + to, e);
        }
        if (created > 0) log.info("created {} monthly partition(s) for {} .. {}", created, from, to);
        return created;
    }

    /** Partitions for last month through three months ahead; called at startup of every mode. */
    public void ensurePartitionsAroundNow(Instant now) {
        Instant start = now.atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1).minusMonths(1).toInstant();
        ensurePartitions(start, start.atOffset(ZoneOffset.UTC).plusMonths(5).toInstant());
    }

    /** Binds an Instant to a timestamptz parameter independent of the JVM default zone. */
    public static OffsetDateTime utc(Instant t) { return t == null ? null : t.atOffset(ZoneOffset.UTC); }

    /** Reads a timestamptz column as an Instant. */
    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime o = rs.getObject(column, OffsetDateTime.class);
        return o == null ? null : o.toInstant();
    }

    @Override
    public void close() { ds.close(); }

    public static final class StoreException extends RuntimeException {
        public StoreException(String message, Throwable cause) { super(message, cause); }
    }
}
