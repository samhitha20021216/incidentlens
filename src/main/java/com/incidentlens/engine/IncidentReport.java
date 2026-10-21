package com.incidentlens.engine;

import java.time.Instant;
import java.util.List;

/** Everything the deterministic pipeline knows about an incident. This is what the LLM gets to see. */
public record IncidentReport(
        String id,
        Incident.Status status,
        String title,
        Instant startedAt,
        Instant detectedAt,
        Instant resolvedAt,
        List<String> affectedServices,
        List<AnomalyView> anomalies,
        Confidence confidence,
        List<RootCauseCandidate> candidates,
        List<TimelineEntry> timeline,
        List<ErrorGroupView> errorGroups,
        List<DependencyGraph.Edge> graph) {

    public enum Confidence { HIGH, MEDIUM, LOW }

    public record AnomalyView(String service, Signal signal, Instant onset, Instant detectedAt, Instant closedAt,
                              double baseline, double peak, String description) {
    }

    public RootCauseCandidate topCandidate() {
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    public List<Evidence> allEvidence() {
        return candidates.stream().flatMap(c -> c.evidence().stream()).toList();
    }
}
