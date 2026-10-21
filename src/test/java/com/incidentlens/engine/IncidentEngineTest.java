package com.incidentlens.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.incidentlens.engine.Evidence.Factor;
import com.incidentlens.model.ChangeEvent;
import com.incidentlens.simulator.ScenarioCatalog;
import com.incidentlens.simulator.TrafficSimulator;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentEngineTest {

    private static final Instant T0 = Instant.parse("2026-03-02T10:00:00Z");

    @Test
    void healthyTrafficProducesNoIncidents() {
        IncidentEngine engine = run(null, 1);
        assertThat(engine.incidents()).isEmpty();
    }

    @Test
    void cascadingFailureIsOneIncidentWithTheRootCauseOnTop() {
        IncidentEngine engine = run("payment-db-saturation", 3);

        assertThat(engine.incidents()).hasSize(1);
        IncidentReport report = engine.report(engine.incidents().getFirst().id()).orElseThrow();
        assertThat(report.affectedServices()).contains("payment-service", "order-service", "api-gateway");
        RootCauseCandidate top = report.topCandidate();
        assertThat(top.service()).isEqualTo("payment-service");
        assertThat(top.suspectedDependency()).isEqualTo("payment-db");
        assertThat(top.evidence()).extracting(Evidence::factor)
                .contains(Factor.DOWNSTREAM_HEALTHY, Factor.EXPLAINS_OTHERS, Factor.CALLERS_BLAME_IT);
        assertThat(report.status()).isEqualTo(Incident.Status.RESOLVED);
    }

    @Test
    void badDeployIsAttributedWithChangeAndNewErrorEvidence() {
        IncidentEngine engine = run("inventory-bad-deploy", 4);

        IncidentReport report = engine.report(engine.incidents().getFirst().id()).orElseThrow();
        assertThat(report.topCandidate().service()).isEqualTo("inventory-service");
        assertThat(report.topCandidate().evidence()).extracting(Evidence::factor)
                .contains(Factor.RECENT_CHANGE, Factor.NEW_ERROR_SIGNATURE);
        assertThat(report.timeline()).extracting(TimelineEntry::kind)
                .contains(TimelineEntry.Kind.CHANGE, TimelineEntry.Kind.ANOMALY, TimelineEntry.Kind.NEW_ERROR);
        assertThat(report.errorGroups()).anyMatch(g -> g.service().equals("inventory-service")
                && "NullPointerException".equals(g.exceptionType()) && g.isNew());
    }

    @Test
    void evidenceIdsAreUniqueAcrossCandidates() {
        IncidentEngine engine = run("payment-db-saturation", 5);
        IncidentReport report = engine.report(engine.incidents().getFirst().id()).orElseThrow();
        List<String> ids = report.allEvidence().stream().map(Evidence::id).toList();
        assertThat(ids).doesNotHaveDuplicates().allMatch(id -> id.matches("E\\d+"));
    }

    @Test
    void dependencyGraphIsInferredFromTraces() {
        IncidentEngine engine = run(null, 2);
        assertThat(engine.graphEdges()).extracting(e -> e.from() + "->" + e.to())
                .contains("api-gateway->order-service", "order-service->payment-service",
                        "order-service->inventory-service", "order-service->notification-service");
    }

    @Test
    void failingCallsAddEdgesToUninstrumentedDependencies() {
        IncidentEngine engine = run("payment-db-saturation", 2);
        assertThat(engine.graphEdges()).extracting(e -> e.from() + "->" + e.to()).contains("payment-service->payment-db");
    }

    /** 12 minutes healthy, then (optionally) a 4 minute fault, then 5 minutes of recovery. No noise. */
    private static IncidentEngine run(String scenario, long seed) {
        IncidentEngine engine = new IncidentEngine(EngineConfig.defaults());
        TrafficSimulator sim = new TrafficSimulator(seed, 15);
        Instant faultStart = T0.plus(Duration.ofMinutes(12));
        if (scenario != null) {
            sim.schedule(ScenarioCatalog.byName(scenario).orElseThrow(), faultStart, Duration.ofMinutes(4));
        }
        Instant end = faultStart.plus(Duration.ofMinutes(9));
        for (Instant t = T0; t.isBefore(end); t = t.plusSeconds(1)) {
            TrafficSimulator.Output out = sim.tick(t, t.plusSeconds(1));
            out.changes().forEach((ChangeEvent c) -> engine.ingest(c));
            out.telemetry().forEach(engine::ingest);
            engine.advance(engine.eventWatermark());
        }
        return engine;
    }
}
