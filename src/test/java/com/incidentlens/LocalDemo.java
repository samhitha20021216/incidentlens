package com.incidentlens;

import org.springframework.boot.SpringApplication;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Runs the whole product locally without Docker: in-process Postgres and Kafka, live simulator on,
 * dashboard served from frontend/dist (build it first with npm run build).
 *
 * <pre>./mvnw -q test-compile exec:java -Dexec.mainClass=com.incidentlens.LocalDemo -Dexec.classpathScope=test</pre>
 */
public final class LocalDemo {

    private LocalDemo() {
    }

    public static void main(String[] args) throws Exception {
        EmbeddedPostgres postgres = EmbeddedPostgres.start();
        EmbeddedKafkaKraftBroker kafka = new EmbeddedKafkaKraftBroker(1, 6, "telemetry.events", "telemetry.changes");
        kafka.afterPropertiesSet();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            kafka.destroy();
            try {
                postgres.close();
            } catch (Exception ignored) {
                // shutting down anyway
            }
        }));

        // System properties outrank application.yml (default properties would not).
        java.util.Map.of(
                "spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"),
                "spring.datasource.username", "postgres",
                "spring.datasource.password", "postgres",
                "spring.kafka.bootstrap-servers", kafka.getBrokersAsString(),
                "incidentlens.simulator.enabled", "true",
                "spring.web.resources.static-locations", "file:frontend/dist/,classpath:/static/",
                "logging.level.org.apache.kafka", "WARN",
                "logging.level.kafka", "WARN").forEach(System::setProperty);
        SpringApplication.run(IncidentLensApplication.class, args);
    }
}
