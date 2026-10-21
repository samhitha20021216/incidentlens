package com.incidentlens.simulator;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.incidentlens.config.IncidentLensProperties;
import com.incidentlens.ingest.TelemetryPublisher;

/**
 * Live demo traffic: generates telemetry for the simulated shop every second and pushes it through the same
 * Kafka path as real ingestion. Faults can be injected from the dashboard.
 */
@Component
public class SimulatorRunner {

    public record Status(boolean enabled, String activeScenario, Instant activeUntil, List<Scenario> scenarios) {
    }

    private static final Logger log = LoggerFactory.getLogger(SimulatorRunner.class);

    private final TelemetryPublisher publisher;
    private final IncidentLensProperties.Simulator config;
    private final TrafficSimulator simulator;
    private Instant last = Instant.now();
    private String activeScenario;
    private Instant activeUntil;

    public SimulatorRunner(TelemetryPublisher publisher, IncidentLensProperties props) {
        this.publisher = publisher;
        this.config = props.simulator();
        this.simulator = new TrafficSimulator(System.nanoTime(), config.requestsPerSecond());
        this.simulator.enableNoiseBursts(config.noiseBurstsPerMinute());
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (!config.enabled()) {
            return;
        }
        TrafficSimulator.Output out;
        synchronized (this) {
            Instant now = Instant.now();
            out = simulator.tick(last, now);
            last = now;
            if (activeUntil != null && now.isAfter(activeUntil)) {
                activeScenario = null;
                activeUntil = null;
            }
        }
        try {
            out.changes().forEach(publisher::publish);
            publisher.publish(out.telemetry());
        } catch (RuntimeException e) {
            log.warn("Simulator could not publish: {}", e.getMessage());
        }
    }

    public synchronized Status start(String name, Duration duration) {
        requireEnabled();
        Scenario scenario = ScenarioCatalog.byName(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown scenario '" + name + "'"));
        Instant now = Instant.now();
        simulator.schedule(scenario, now, duration);
        activeScenario = name;
        activeUntil = now.plus(duration);
        log.info("Injected scenario {} for {}", name, duration);
        return status();
    }

    public synchronized Status stopFaults() {
        simulator.clearScenarios();
        activeScenario = null;
        activeUntil = null;
        return status();
    }

    public synchronized Status status() {
        return new Status(config.enabled(), activeScenario, activeUntil, ScenarioCatalog.ALL);
    }

    private void requireEnabled() {
        if (!config.enabled()) {
            throw new IllegalStateException("Simulator is disabled (set INCIDENTLENS_SIMULATOR_ENABLED=true)");
        }
    }
}
