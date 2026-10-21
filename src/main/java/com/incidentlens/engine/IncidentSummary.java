package com.incidentlens.engine;

import java.time.Instant;
import java.util.List;

public record IncidentSummary(String id, Incident.Status status, String title, Instant startedAt, Instant detectedAt,
                              Instant resolvedAt, List<String> affectedServices, String likelyRootCause,
                              IncidentReport.Confidence confidence) {
}
