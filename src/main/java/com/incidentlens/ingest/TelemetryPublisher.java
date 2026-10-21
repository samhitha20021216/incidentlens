package com.incidentlens.ingest;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.incidentlens.config.IncidentLensProperties;
import com.incidentlens.config.Json;
import com.incidentlens.model.ChangeEvent;
import com.incidentlens.model.TelemetryEvent;

/** Everything enters through Kafka, so ingestion is decoupled from (and buffered against) analysis. */
@Component
public class TelemetryPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final Json json;
    private final IncidentLensProperties.Topics topics;

    public TelemetryPublisher(KafkaTemplate<String, String> kafka, Json json, IncidentLensProperties props) {
        this.kafka = kafka;
        this.json = json;
        this.topics = props.topics();
    }

    public void publish(List<TelemetryEvent> events) {
        CompletableFuture<?>[] sends = events.stream()
                .map(e -> kafka.send(topics.events(), e.service(), json.write(e)))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(sends).orTimeout(30, TimeUnit.SECONDS).join();
    }

    public void publish(ChangeEvent change) {
        kafka.send(topics.changes(), change.service(), json.write(change)).orTimeout(10, TimeUnit.SECONDS).join();
    }
}
