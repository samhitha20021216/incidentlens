package com.incidentlens.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A group of related anomalies, connected in time and through the call graph. */
public final class Incident {

    public enum Status { OPEN, RESOLVED }

    private final String id;
    private final Instant detectedAt;
    private final List<Anomaly> anomalies = new ArrayList<>();
    private Status status = Status.OPEN;
    private Instant resolvedAt;

    Incident(String id, Anomaly first) {
        this.id = id;
        this.detectedAt = first.detectedAt();
        anomalies.add(first);
    }

    void add(Anomaly anomaly) {
        anomalies.add(anomaly);
    }

    void resolve(Instant at) {
        status = Status.RESOLVED;
        resolvedAt = at;
    }

    public String id() {
        return id;
    }

    public Status status() {
        return status;
    }

    public Instant detectedAt() {
        return detectedAt;
    }

    public Instant resolvedAt() {
        return resolvedAt;
    }

    public Instant startedAt() {
        return anomalies.stream().map(Anomaly::onset).min(Comparator.naturalOrder()).orElseThrow();
    }

    public List<Anomaly> anomalies() {
        return List.copyOf(anomalies);
    }

    public Set<String> services() {
        Set<String> services = new LinkedHashSet<>();
        anomalies.stream().sorted(Comparator.comparing(Anomaly::onset)).forEach(a -> services.add(a.service()));
        return services;
    }

    public Instant latestOnset() {
        return anomalies.stream().map(Anomaly::onset).max(Comparator.naturalOrder()).orElseThrow();
    }

    public Instant onsetOf(String service) {
        return anomalies.stream().filter(a -> a.service().equals(service))
                .map(Anomaly::onset).min(Comparator.naturalOrder()).orElse(null);
    }

    boolean allClosed() {
        return anomalies.stream().noneMatch(Anomaly::isOpen);
    }

    Instant lastClosedAt() {
        return anomalies.stream().map(Anomaly::closedAt).max(Comparator.naturalOrder()).orElse(null);
    }
}
