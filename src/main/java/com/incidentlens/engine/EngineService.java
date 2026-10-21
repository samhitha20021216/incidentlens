package com.incidentlens.engine;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.incidentlens.config.IncidentLensProperties;
import com.incidentlens.model.ChangeEvent;
import com.incidentlens.model.TelemetryEvent;
import com.incidentlens.store.IncidentStore;

/**
 * Owns the single in-memory {@link IncidentEngine}, serializes access to it, advances time on a schedule
 * and persists incident reports as they change.
 */
@Service
public class EngineService {

    private static final Logger log = LoggerFactory.getLogger(EngineService.class);

    private final IncidentEngine engine;
    private final Fingerprinter fingerprinter;
    private final IncidentStore store;
    private final IncidentLensProperties props;
    private final EngineConfig config;

    public EngineService(EngineConfig config, IncidentStore store, IncidentLensProperties props) {
        this.engine = new IncidentEngine(config);
        this.fingerprinter = new Fingerprinter(config.inAppPrefixes());
        this.store = store;
        this.props = props;
        this.config = config;
    }

    public String fingerprint(TelemetryEvent e) {
        return e.isError() ? fingerprinter.fingerprint(e) : null;
    }

    public synchronized void ingest(List<TelemetryEvent> events) {
        events.forEach(engine::ingest);
    }

    public synchronized void ingest(ChangeEvent change) {
        engine.ingest(change);
    }

    @Scheduled(fixedDelayString = "${incidentlens.engine.tick-ms:2000}")
    public void tick() {
        Instant watermark = props.engine().clock() == IncidentLensProperties.Clock.EVENT
                ? engine.eventWatermark()
                : Instant.now().minus(config.lateness());
        List<IncidentEngine.EngineEvent> events;
        List<IncidentReport> toSave;
        Set<String> merged = new HashSet<>();
        synchronized (this) {
            events = engine.advance(watermark);
            events.stream().filter(e -> e.type() == IncidentEngine.EngineEvent.Type.MERGED)
                    .forEach(e -> merged.add(e.incidentId()));
            // Open incidents keep evolving (new evidence, new services), so refresh them every tick.
            Set<String> ids = new HashSet<>();
            engine.incidents().stream().filter(i -> i.status() == Incident.Status.OPEN).forEach(i -> ids.add(i.id()));
            events.forEach(e -> ids.add(e.incidentId()));
            ids.removeAll(merged);
            toSave = ids.stream().map(engine::report).flatMap(Optional::stream).toList();
        }
        merged.forEach(store::delete);
        toSave.forEach(store::upsert);
        events.stream().filter(e -> e.type() != IncidentEngine.EngineEvent.Type.UPDATED)
                .forEach(e -> log.info("Incident {} {}", e.incidentId(), e.type()));
    }

    /** Live report from the engine, or the last persisted snapshot (e.g. after a restart). */
    public Optional<IncidentReport> report(String id) {
        Optional<IncidentReport> live;
        synchronized (this) {
            live = engine.report(id);
        }
        return live.isPresent() ? live : store.report(id);
    }

    public synchronized List<ServiceHealth> health() {
        return engine.serviceHealth();
    }

    public synchronized List<DependencyGraph.Edge> graph() {
        return engine.graphEdges();
    }

    public synchronized List<ErrorGroupView> errorGroups(String service, int limit) {
        return engine.errorGroups(service, limit);
    }

    public synchronized long eventsIngested() {
        return engine.eventsIngested();
    }

    public synchronized boolean baselineReady() {
        return engine.baselineReady();
    }

    /** Events that arrived after their time bucket was already judged (pipeline lag above the lateness budget). */
    public synchronized long lateEvents() {
        return engine.lateEvents();
    }
}
