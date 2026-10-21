package com.incidentlens.ingest;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.incidentlens.config.Json;
import com.incidentlens.engine.EngineService;
import com.incidentlens.model.ChangeEvent;
import com.incidentlens.model.TelemetryEvent;
import com.incidentlens.store.LogStore;

/**
 * Single analyzer consumer: the engine is a stateful, in-memory model of the whole system, so one instance
 * sees every event. Batches keep the per-event overhead low.
 */
@Component
public class TelemetryConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryConsumer.class);

    private final EngineService engine;
    private final LogStore logs;
    private final Json json;

    public TelemetryConsumer(EngineService engine, LogStore logs, Json json) {
        this.engine = engine;
        this.logs = logs;
        this.json = json;
    }

    @KafkaListener(topics = "${incidentlens.topics.events:telemetry.events}", groupId = "incidentlens-analyzer", batch = "true")
    public void onEvents(List<String> payloads) {
        List<TelemetryEvent> events = new ArrayList<>(payloads.size());
        List<LogStore.Fingerprinted> rows = new ArrayList<>(payloads.size());
        for (String p : payloads) {
            try {
                TelemetryEvent e = json.read(p, TelemetryEvent.class);
                events.add(e);
                rows.add(new LogStore.Fingerprinted(e, engine.fingerprint(e)));
            } catch (RuntimeException ex) {
                log.warn("Skipping malformed telemetry event: {}", ex.getMessage());
            }
        }
        engine.ingest(events);
        logs.saveBatch(rows);
    }

    @KafkaListener(topics = "${incidentlens.topics.changes:telemetry.changes}", groupId = "incidentlens-analyzer", batch = "true")
    public void onChanges(List<String> payloads) {
        for (String p : payloads) {
            ChangeEvent c = json.read(p, ChangeEvent.class);
            engine.ingest(c);
            logs.saveChange(c);
        }
    }
}
