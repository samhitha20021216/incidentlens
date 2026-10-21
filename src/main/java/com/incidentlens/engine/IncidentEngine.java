package com.incidentlens.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.incidentlens.model.ChangeEvent;
import com.incidentlens.model.TelemetryEvent;

/**
 * Streaming incident detector. Feed it telemetry and change events; call {@link #advance} to close time
 * buckets up to a watermark. Everything is driven by event time, so the same code runs live and in
 * accelerated replays (the evaluation harness pushes an hour of simulated traffic through in seconds).
 *
 * <p>Pipeline per closed bucket: per-service metrics → anomaly detection against a rolling baseline →
 * hysteresis → grouping of anomalies into incidents via the dependency graph. Root-cause ranking runs
 * on demand when a report is requested.
 *
 * <p>Not thread-safe by design; callers serialize access (see {@code EngineService}).
 */
public final class IncidentEngine implements RootCauseRanker.Context {

    public record EngineEvent(Type type, String incidentId) {
        /** MERGED: this incident was folded into an older related one and no longer exists. */
        public enum Type { OPENED, UPDATED, RESOLVED, MERGED }
    }

    private static final int MAX_INCIDENTS = 500;
    private static final int RECENT_BUCKETS = 3;

    private final EngineConfig config;
    private final Fingerprinter fingerprinter;
    private final RootCauseRanker ranker;
    private final DependencyGraph graph = new DependencyGraph();
    private final Map<String, ServiceState> services = new TreeMap<>();
    private final Map<String, ErrorGroup> groups = new HashMap<>();
    private final List<ChangeEvent> changes = new ArrayList<>();
    private final Map<String, Incident> incidents = new LinkedHashMap<>();
    private final long bucketMillis;

    private int incidentSeq;
    private Instant firstEventAt;
    private Instant maxEventAt;
    private Long lastClosedBucket;
    private long eventsIngested;
    private long lateEvents;

    public IncidentEngine(EngineConfig config) {
        this.config = config;
        this.fingerprinter = new Fingerprinter(config.inAppPrefixes());
        this.ranker = new RootCauseRanker(config);
        this.bucketMillis = config.bucket().toMillis();
    }

    // ------------------------------------------------------------------ ingestion

    public void ingest(TelemetryEvent e) {
        eventsIngested++;
        if (firstEventAt == null) {
            firstEventAt = e.timestamp();
        }
        if (maxEventAt == null || e.timestamp().isAfter(maxEventAt)) {
            maxEventAt = e.timestamp();
        }
        graph.observe(e);
        ServiceState state = services.computeIfAbsent(e.service(), ServiceState::new);
        long b = bucketOf(e.timestamp());

        if (lastClosedBucket != null && b <= lastClosedBucket) {
            lateEvents++; // metrics for that bucket were already judged; still fingerprint below
        } else {
            Bucket bucket = state.buckets.computeIfAbsent(b, k -> new Bucket());
            if (e.isRequest()) {
                bucket.requests++;
                bucket.latencies.add(e.latencyMs());
            }
            if (e.isError()) {
                bucket.errors++;
                if (e.peerService() != null) {
                    bucket.errorsByPeer.merge(e.peerService(), 1, Integer::sum);
                }
            }
        }
        if (e.isError()) {
            String fp = fingerprinter.fingerprint(e);
            groups.computeIfAbsent(fp, k -> new ErrorGroup(k, e, fingerprinter.template(e.message()))).record(e, b);
        }
    }

    public void ingest(ChangeEvent change) {
        changes.add(change);
        changes.sort(Comparator.comparing(ChangeEvent::timestamp));
    }

    /** Watermark derived from the data itself: newest event time minus allowed lateness. */
    public Instant eventWatermark() {
        return maxEventAt == null ? null : maxEventAt.minus(config.lateness());
    }

    // ------------------------------------------------------------------ detection

    /** Closes every bucket that ends at or before {@code watermark} and returns incident changes. */
    public List<EngineEvent> advance(Instant watermark) {
        List<EngineEvent> events = new ArrayList<>();
        if (firstEventAt == null || watermark == null) {
            return events;
        }
        long lastComplete = bucketOf(watermark) - 1;
        if (lastClosedBucket == null) {
            lastClosedBucket = bucketOf(firstEventAt) - 1;
        }
        if (lastComplete <= lastClosedBucket) {
            return events;
        }
        for (long b = lastClosedBucket + 1; b <= lastComplete; b++) {
            closeBucket(b, events);
            lastClosedBucket = b;
            mergeRelatedIncidents(bucketStart(b + 1), events);
        }
        Instant now = bucketStart(lastClosedBucket + 1);
        resolveQuietIncidents(now, events);
        prune(now);
        return events;
    }

    private void closeBucket(long b, List<EngineEvent> events) {
        Instant start = bucketStart(b);
        Instant end = start.plus(config.bucket());
        for (ServiceState state : services.values()) {
            Bucket current = state.buckets.get(b);
            Bucket cur = current == null ? new Bucket() : current;
            Collection<Bucket> history = state.buckets.subMap(b - config.baselineBuckets(), true, b, false).values();

            // Error rate
            List<Double> rates = history.stream()
                    .filter(h -> h.requests > 0 && !h.anomalous.contains(Signal.ERROR_RATE))
                    .map(Bucket::errorRate).toList();
            boolean errAnomalous = false;
            double errBaseline = mean(rates);
            if (rates.size() >= config.minBaselineBuckets()) {
                double threshold = errBaseline + Math.max(config.errorRateSigma() * std(rates, errBaseline),
                        config.errorRateMinDelta());
                errAnomalous = cur.errors >= config.minErrors() && cur.errorRate() > threshold;
            } else {
                // Cold start: without history, fall back to an absolute rule. Otherwise an incident that is already
                // in progress when we start would be learned as "normal" and never flagged.
                errAnomalous = cur.errors >= config.minErrors() && cur.errorRate() >= config.coldStartErrorRate();
                errBaseline = Math.min(errBaseline, config.coldStartErrorRate());
            }
            judge(state, Signal.ERROR_RATE, errAnomalous, cur.errorRate(), errBaseline, start, end, current, events);

            // Latency (p95)
            List<Double> p95s = history.stream()
                    .filter(h -> h.latencies.size() >= 5 && !h.anomalous.contains(Signal.LATENCY_P95))
                    .map(Bucket::p95).toList();
            boolean latAnomalous = false;
            double latBaseline = mean(p95s);
            if (p95s.size() >= config.minBaselineBuckets() && cur.latencies.size() >= 5) {
                double p95 = cur.p95();
                latAnomalous = p95 > latBaseline * config.latencyFactor()
                        && p95 > latBaseline + config.latencySigma() * std(p95s, latBaseline)
                        && p95 - latBaseline >= config.latencyMinDeltaMs();
            }
            judge(state, Signal.LATENCY_P95, latAnomalous, cur.p95(), latBaseline, start, end, current, events);
        }
    }

    private void judge(ServiceState state, Signal signal, boolean anomalous, double value, double baseline,
                       Instant start, Instant end, Bucket bucket, List<EngineEvent> events) {
        if (anomalous && bucket != null) {
            bucket.anomalous.add(signal);
        }
        AnomalyTracker tracker = state.trackers.computeIfAbsent(signal, s -> new AnomalyTracker(state.name, s, config));
        Anomaly before = tracker.current();
        switch (tracker.update(anomalous, value, baseline, start, end)) {
            case OPENED -> attach(tracker.current(), events);
            case CLOSED -> incidentOf(before).ifPresent(i -> events.add(new EngineEvent(EngineEvent.Type.UPDATED, i.id())));
            case NONE -> {
            }
        }
    }

    /**
     * Joins the anomaly to an incident that is <i>still actively failing</i>, started a new anomaly recently,
     * and contains a service <i>causally related</i> to this one (see {@link #related}); otherwise opens a new
     * incident. Grouping on call-graph distance alone merged unrelated blips into real incidents.
     */
    private void attach(Anomaly anomaly, List<EngineEvent> events) {
        Instant now = anomaly.detectedAt();
        Incident target = incidents.values().stream()
                .filter(i -> isActive(i, anomaly.onset()))
                .filter(i -> i.services().stream().anyMatch(s -> related(s, anomaly.service(), now)))
                .max(Comparator.comparing(Incident::detectedAt))
                .orElse(null);
        if (target == null) {
            target = new Incident(String.format("INC-%04d", ++incidentSeq), anomaly);
            incidents.put(target.id(), target);
            events.add(new EngineEvent(EngineEvent.Type.OPENED, target.id()));
        } else {
            target.add(anomaly);
            events.add(new EngineEvent(EngineEvent.Type.UPDATED, target.id()));
        }
    }

    /** Two active incidents whose services turn out to be related are one incident. Keep the older id. */
    private void mergeRelatedIncidents(Instant now, List<EngineEvent> events) {
        boolean merged = true;
        while (merged) {
            merged = false;
            List<Incident> active = incidents.values().stream().filter(i -> isActive(i, now)).toList();
            outer:
            for (int x = 0; x < active.size(); x++) {
                for (int y = x + 1; y < active.size(); y++) {
                    Incident a = active.get(x);
                    Incident b = active.get(y);
                    boolean overlap = !a.latestOnset().plus(config.groupingWindow()).isBefore(b.startedAt())
                            && !b.latestOnset().plus(config.groupingWindow()).isBefore(a.startedAt());
                    if (overlap && a.services().stream().anyMatch(s -> b.services().stream().anyMatch(t -> related(s, t, now)))) {
                        Incident keep = a.detectedAt().isAfter(b.detectedAt()) ? b : a;
                        Incident drop = keep == a ? b : a;
                        drop.anomalies().forEach(keep::add);
                        incidents.remove(drop.id());
                        events.removeIf(e -> e.incidentId().equals(drop.id()));
                        events.add(new EngineEvent(EngineEvent.Type.MERGED, drop.id()));
                        events.add(new EngineEvent(EngineEvent.Type.UPDATED, keep.id()));
                        merged = true;
                        break outer;
                    }
                }
            }
        }
    }

    private boolean isActive(Incident i, Instant at) {
        return i.status() == Incident.Status.OPEN
                && i.anomalies().stream().anyMatch(Anomaly::isOpen)
                && !i.latestOnset().plus(config.groupingWindow()).isBefore(at.minus(config.bucket().multipliedBy(config.openAfterBuckets())));
    }

    /**
     * Evidence that a failure in one service is connected to a failure in the other: one calls the other
     * directly, and either the caller's recent errors point at the callee, or both are slow (latency
     * propagates up synchronous calls). A caller with (almost) no errors has nothing to blame with; there the
     * call edge is the only evidence available, so it is trusted. Only the last few buckets count, so
     * background errors from before the incident don't dilute the signal.
     */
    boolean related(String a, String b, Instant now) {
        if (a.equals(b)) {
            return true;
        }
        Instant from = now.minus(config.bucket().multipliedBy(RECENT_BUCKETS));
        return calls(a, b) && (blames(a, b, from, now) || bothSlow(a, b))
                || calls(b, a) && (blames(b, a, from, now) || bothSlow(b, a));
    }

    private boolean calls(String caller, String callee) {
        return graph.downstream(caller).contains(callee);
    }

    private boolean bothSlow(String a, String b) {
        return hasOpen(a, Signal.LATENCY_P95) && hasOpen(b, Signal.LATENCY_P95);
    }

    private boolean hasOpen(String service, Signal signal) {
        ServiceState s = services.get(service);
        AnomalyTracker t = s == null ? null : s.trackers.get(signal);
        return t != null && t.current() != null;
    }

    private boolean blames(String caller, String callee, Instant from, Instant to) {
        long errors = errorCount(caller, from, to);
        if (errors < config.minErrors()) {
            return true;
        }
        return errorsByPeer(caller, from, to).getOrDefault(callee, 0L) >= RootCauseRanker.BLAME_SHARE * errors;
    }

    private Optional<Incident> incidentOf(Anomaly anomaly) {
        return incidents.values().stream().filter(i -> i.anomalies().contains(anomaly)).findFirst();
    }

    private void resolveQuietIncidents(Instant now, List<EngineEvent> events) {
        for (Incident i : incidents.values()) {
            if (i.status() == Incident.Status.OPEN && i.allClosed()
                    && !i.lastClosedAt().plus(config.resolveAfter()).isAfter(now)) {
                i.resolve(i.lastClosedAt());
                events.add(new EngineEvent(EngineEvent.Type.RESOLVED, i.id()));
            }
        }
    }

    private void prune(Instant now) {
        long oldest = bucketOf(now.minus(config.retention()));
        services.values().forEach(s -> s.buckets.headMap(oldest).clear());
        groups.values().forEach(g -> g.perBucket.headMap(oldest).clear());
        changes.removeIf(c -> c.timestamp().isBefore(now.minus(config.retention())));
        while (incidents.size() > MAX_INCIDENTS) {
            incidents.remove(incidents.keySet().iterator().next());
        }
    }

    // ------------------------------------------------------------------ reports

    public Optional<IncidentReport> report(String id) {
        Incident incident = incidents.get(id);
        if (incident == null) {
            return Optional.empty();
        }
        Instant windowEnd = incident.resolvedAt() != null ? incident.resolvedAt() : watermarkTime();
        List<RootCauseCandidate> candidates = ranker.rank(incident, this, windowEnd);
        RootCauseCandidate top = candidates.getFirst();
        String title = "Errors in " + String.join(", ", incident.services()) + " (likely " + top.service()
                + (top.suspectedDependency() == null ? "" : " / " + top.suspectedDependency()) + ")";
        return Optional.of(new IncidentReport(incident.id(), incident.status(), title, incident.startedAt(),
                incident.detectedAt(), incident.resolvedAt(), List.copyOf(incident.services()),
                incident.anomalies().stream().sorted(Comparator.comparing(Anomaly::onset))
                        .map(a -> new IncidentReport.AnomalyView(a.service(), a.signal(), a.onset(), a.detectedAt(),
                                a.closedAt(), a.baseline(), a.peak(), a.describe())).toList(),
                RootCauseRanker.confidence(candidates), candidates, timeline(incident, windowEnd),
                errorGroupsFor(incident, windowEnd), graph.edges()));
    }

    public List<IncidentSummary> incidents() {
        List<IncidentSummary> result = new ArrayList<>();
        for (Incident i : incidents.values()) {
            IncidentReport r = report(i.id()).orElseThrow();
            result.add(new IncidentSummary(r.id(), r.status(), r.title(), r.startedAt(), r.detectedAt(),
                    r.resolvedAt(), r.affectedServices(), r.topCandidate().service(), r.confidence()));
        }
        result.sort(Comparator.comparing(IncidentSummary::detectedAt).reversed());
        return result;
    }

    private List<TimelineEntry> timeline(Incident incident, Instant windowEnd) {
        Instant from = incident.startedAt().minus(config.changeLookback());
        List<TimelineEntry> entries = new ArrayList<>();
        for (ChangeEvent c : changes(null, from, windowEnd)) {
            entries.add(new TimelineEntry(c.timestamp(), TimelineEntry.Kind.CHANGE, c.service(),
                    c.type() + " " + c.service() + ": " + c.description()));
        }
        for (Anomaly a : incident.anomalies()) {
            entries.add(new TimelineEntry(a.onset(), TimelineEntry.Kind.ANOMALY, a.service(),
                    a.service() + " " + a.describe()));
        }
        for (String s : incident.services()) {
            for (ErrorGroupView g : newErrorGroups(s, incident.startedAt().minus(config.bucket()), windowEnd)) {
                entries.add(new TimelineEntry(g.firstSeen(), TimelineEntry.Kind.NEW_ERROR, s,
                        "New error in " + s + ": " + (g.exceptionType() == null ? "" : g.exceptionType() + ": ")
                                + g.template()));
            }
        }
        entries.add(new TimelineEntry(incident.detectedAt(), TimelineEntry.Kind.DETECTED, null,
                "Incident detected (" + incident.anomalies().size() + " anomalies so far)"));
        if (incident.resolvedAt() != null) {
            entries.add(new TimelineEntry(incident.resolvedAt(), TimelineEntry.Kind.RESOLVED, null,
                    "All signals back to baseline"));
        }
        entries.sort(Comparator.comparing(TimelineEntry::at).thenComparing(TimelineEntry::kind));
        return entries;
    }

    private List<ErrorGroupView> errorGroupsFor(Incident incident, Instant windowEnd) {
        Instant from = incident.startedAt().minus(config.bucket());
        Set<String> affected = incident.services();
        return groups.values().stream()
                .filter(g -> affected.contains(g.service))
                .map(g -> view(g, from, windowEnd))
                .filter(v -> v.countInWindow() > 0)
                .sorted(Comparator.comparingLong(ErrorGroupView::countInWindow).reversed())
                .limit(10)
                .toList();
    }

    public List<ErrorGroupView> errorGroups(String service, int limit) {
        Instant to = watermarkTime();
        Instant from = to == null ? Instant.EPOCH : to.minus(Duration.ofMinutes(15));
        return groups.values().stream()
                .filter(g -> service == null || g.service.equals(service))
                .map(g -> view(g, from, to == null ? Instant.now() : to))
                .sorted(Comparator.comparing(ErrorGroupView::lastSeen).reversed())
                .limit(limit)
                .toList();
    }

    public List<ServiceHealth> serviceHealth() {
        List<ServiceHealth> result = new ArrayList<>();
        if (lastClosedBucket == null) {
            return result;
        }
        int window = 6;
        for (ServiceState s : services.values()) {
            Collection<Bucket> recent = s.buckets.subMap(lastClosedBucket - window + 1, true, lastClosedBucket, true).values();
            int req = recent.stream().mapToInt(b -> b.requests).sum();
            int err = recent.stream().mapToInt(b -> b.errors).sum();
            List<Long> lat = recent.stream().flatMap(b -> b.latencies.stream()).sorted().toList();
            double p95 = lat.isEmpty() ? 0 : lat.get((int) Math.ceil(0.95 * lat.size()) - 1);
            List<String> open = s.trackers.values().stream().map(AnomalyTracker::current)
                    .filter(a -> a != null).map(Anomaly::describe).toList();
            result.add(new ServiceHealth(s.name, req / (window * config.bucket().toSeconds() * 1.0),
                    req == 0 ? 0 : (double) err / req, p95, open));
        }
        return result;
    }

    public List<DependencyGraph.Edge> graphEdges() {
        return graph.edges();
    }

    public long eventsIngested() {
        return eventsIngested;
    }

    public long lateEvents() {
        return lateEvents;
    }

    /** True once enough history exists for statistical baselines (before that only the cold-start rule applies). */
    public boolean baselineReady() {
        return firstEventAt != null && lastClosedBucket != null
                && lastClosedBucket - bucketOf(firstEventAt) >= config.minBaselineBuckets();
    }

    // ------------------------------------------------------------------ RootCauseRanker.Context

    @Override
    public DependencyGraph graph() {
        return graph;
    }

    @Override
    public Map<String, Long> errorsByPeer(String service, Instant from, Instant to) {
        Map<String, Long> result = new HashMap<>();
        bucketsOf(service, from, to).forEach(b -> b.errorsByPeer.forEach((k, v) -> result.merge(k, (long) v, Long::sum)));
        return result;
    }

    @Override
    public long errorCount(String service, Instant from, Instant to) {
        return bucketsOf(service, from, to).stream().mapToLong(b -> b.errors).sum();
    }

    @Override
    public List<ErrorGroupView> newErrorGroups(String service, Instant from, Instant to) {
        Instant baselineEnd = firstEventAt.plus(config.warmup());
        return groups.values().stream()
                .filter(g -> g.service.equals(service))
                .filter(g -> g.firstSeen.isAfter(baselineEnd))
                .filter(g -> !g.firstSeen.isBefore(from.minus(Duration.ofMinutes(1))) && !g.firstSeen.isAfter(to))
                .map(g -> view(g, from, to))
                .filter(v -> v.countInWindow() >= config.minErrors())
                .sorted(Comparator.comparingLong(ErrorGroupView::countInWindow).reversed())
                .toList();
    }

    @Override
    public List<ChangeEvent> changes(String service, Instant from, Instant to) {
        return changes.stream()
                .filter(c -> service == null || c.service().equals(service))
                .filter(c -> !c.timestamp().isBefore(from) && !c.timestamp().isAfter(to))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private ErrorGroupView view(ErrorGroup g, Instant from, Instant to) {
        Instant baselineEnd = firstEventAt.plus(config.warmup());
        return new ErrorGroupView(g.fingerprint, g.service, g.exceptionType, g.template, g.sampleMessage, g.topFrame,
                g.mainPeer(), g.firstSeen, g.lastSeen, g.count, g.countBetween(bucketOf(from), bucketOf(to)),
                g.firstSeen.isAfter(baselineEnd));
    }

    private Collection<Bucket> bucketsOf(String service, Instant from, Instant to) {
        ServiceState s = services.get(service);
        if (s == null) {
            return List.of();
        }
        return s.buckets.subMap(bucketOf(from), true, bucketOf(to), true).values();
    }

    private Instant watermarkTime() {
        return lastClosedBucket == null ? maxEventAt : bucketStart(lastClosedBucket + 1);
    }

    private long bucketOf(Instant t) {
        return Math.floorDiv(t.toEpochMilli(), bucketMillis);
    }

    private Instant bucketStart(long b) {
        return Instant.ofEpochMilli(b * bucketMillis);
    }

    private static double mean(List<Double> xs) {
        return xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double std(List<Double> xs, double mean) {
        return Math.sqrt(xs.stream().mapToDouble(x -> (x - mean) * (x - mean)).average().orElse(0));
    }

    private static final class ServiceState {
        final String name;
        final TreeMap<Long, Bucket> buckets = new TreeMap<>();
        final Map<Signal, AnomalyTracker> trackers = new EnumMap<>(Signal.class);

        ServiceState(String name) {
            this.name = name;
        }
    }
}
