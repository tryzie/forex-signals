package dev.forexsignals.store;

import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One Percona PG18 container per test JVM (ADR 0008: same image tag as docker compose, passed by Failsafe
 * as -Dforexsignals.pgImage). Each caller gets its own fresh database, so ITs never share state.
 */
public final class TestDb {

    private static final String IMAGE = System.getProperty("forexsignals.pgImage", "percona/percona-distribution-postgresql:18.7.1-1");
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static PostgreSQLContainer container;

    private TestDb() {}

    public static synchronized PostgreSQLContainer container() {
        if (container == null) {
            PostgreSQLContainer c = new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("forex_it")
                    .withUsername("forex")
                    .withPassword("it-only-password")
                    // Same initdb settings as docker-compose.yml: UTF8 instead of the image default SQL_ASCII.
                    .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --locale=C.UTF-8")
                    // Percona's image sends server logs to its logging collector, so the default
                    // "ready to accept connections" log wait never fires (ADR 0008 amendment). The
                    // init-phase server listens only on the Unix socket, so a TCP pg_isready succeeds
                    // only once the final server is up.
                    .waitingFor(Wait.forSuccessfulCommand("pg_isready -h 127.0.0.1 -U forex -d forex_it")
                            .withStartupTimeout(Duration.ofMinutes(2)));
            c.start();
            container = c;
        }
        return container;
    }

    /** Creates an empty database and returns its name. */
    public static String createDatabase() {
        PostgreSQLContainer c = container();
        String name = "t" + ProcessHandle.current().pid() + "_" + COUNTER.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        } catch (Exception e) {
            throw new IllegalStateException("cannot create test database", e);
        }
        return name;
    }

    /** Creates an empty database and returns a migrated {@link Database} on it. */
    public static Database freshMigrated() {
        PostgreSQLContainer c = container();
        Database db = Database.connect(jdbcUrl(createDatabase()), c.getUsername(), c.getPassword(), 4);
        db.migrate();
        return db;
    }

    public static String jdbcUrl(String dbName) {
        PostgreSQLContainer c = container();
        return "jdbc:postgresql://" + c.getHost() + ":" + c.getMappedPort(5432) + "/" + dbName;
    }
}
