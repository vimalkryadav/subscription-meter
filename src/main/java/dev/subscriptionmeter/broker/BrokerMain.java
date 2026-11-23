package dev.subscriptionmeter.broker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Independent OpenAI key broker process. Run from the application image with
 * {@code -Dloader.main=dev.subscriptionmeter.broker.BrokerMain}. It has no gateway, no web UI and
 * no schema mutation; it is the only container given the real OpenAI key.
 */
public final class BrokerMain {
    private BrokerMain() {}

    static String env(String name, String fallback) { String v = System.getenv(name); return v == null || v.isBlank() ? fallback : v; }
    static String required(String name) { String v = System.getenv(name); if (v == null || v.isBlank()) throw new IllegalStateException("Missing " + name); return v; }

    /** Re-read on every use, so replacing the file takes effect without a restart. Never logged. */
    static Supplier<Optional<String>> secretFile(String path, Pattern shape) {
        return () -> {
            if (path == null || path.isBlank()) return Optional.empty();
            try (var in = Files.newInputStream(Path.of(path))) {
                byte[] bytes = in.readNBytes(1025);
                if (bytes.length > 1024) return Optional.empty();
                String value = new String(bytes, StandardCharsets.UTF_8).strip();
                return shape.matcher(value).matches() ? Optional.of(value) : Optional.empty();
            } catch (IOException e) { return Optional.empty(); }
        };
    }

    public static void main(String[] args) {
        try { run(); }
        catch (Exception failure) {
            System.err.println("Key broker stopped: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        String url = required("SPRING_DATASOURCE_URL");
        if (!url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("Key broker requires PostgreSQL");
        url += (url.contains("?") ? "&" : "?") + "connectTimeout=5&socketTimeout=30&tcpKeepAlive=true";
        var pool = new com.zaxxer.hikari.HikariConfig();
        pool.setJdbcUrl(url); pool.setUsername(required("SPRING_DATASOURCE_USERNAME")); pool.setPassword(required("SPRING_DATASOURCE_PASSWORD"));
        pool.setMaximumPoolSize(4); pool.setMinimumIdle(1); pool.setConnectionTimeout(5000); pool.setPoolName("openai-broker");
        var ds = new com.zaxxer.hikari.HikariDataSource(pool);
        var jdbc = new JdbcTemplate(ds); jdbc.setQueryTimeout(10);
        Integer schema = jdbc.queryForObject("SELECT COUNT(*) FROM control_schema_history WHERE name LIKE '%V010__openai_key_broker.sql'", Integer.class);
        if (schema == null || schema != 1) throw new IllegalStateException("Broker schema migration V010 has not been applied");
        var adminToken = secretFile(env("BROKER_ADMIN_TOKEN_FILE", "/run/secrets/broker-admin-token"), Pattern.compile("[A-Za-z0-9_-]{32,200}"));
        var server = new BrokerServer(new BrokerStore(jdbc, adminToken),
                secretFile(env("OPENAI_API_KEY_FILE", "/run/secrets/openai-api-key"), Pattern.compile("sk-[A-Za-z0-9_-]{20,500}")),
                adminToken,
                env("OPENAI_UPSTREAM_URL", "https://api.openai.com"), java.time.Clock.systemUTC());
        var address = server.start(env("BROKER_BIND", "0.0.0.0"), Integer.parseInt(env("BROKER_PORT", "8190")));
        System.out.println("Key broker listening on port " + address.getPort());
        var stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(Integer.parseInt(env("BROKER_DRAIN_SECONDS", "300")));
            ds.close();
            stopped.countDown();
        }));
        stopped.await();
    }
}
