package com.incidentlens.ingest;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.incidentlens.model.ChangeEvent;
import com.incidentlens.model.TelemetryEvent;

/** HTTP entry point for services (or log shippers) that push telemetry and change events. */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private static final int MAX_BATCH = 10_000;

    private final TelemetryPublisher publisher;

    public IngestController(TelemetryPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> events(@RequestBody List<TelemetryEvent> events) {
        if (events.size() > MAX_BATCH) {
            throw new IllegalArgumentException("Batch too large (max " + MAX_BATCH + ")");
        }
        for (TelemetryEvent e : events) {
            if (e.timestamp() == null || e.service() == null || e.level() == null) {
                throw new IllegalArgumentException("Every event needs timestamp, service and level");
            }
        }
        publisher.publish(events);
        return ResponseEntity.accepted().body(Map.of("accepted", events.size()));
    }

    @PostMapping("/changes")
    public ResponseEntity<Map<String, Object>> change(@RequestBody ChangeEvent change) {
        if (change.timestamp() == null || change.service() == null || change.type() == null) {
            throw new IllegalArgumentException("A change needs timestamp, service and type");
        }
        publisher.publish(change);
        return ResponseEntity.accepted().body(Map.of("accepted", 1));
    }
}
