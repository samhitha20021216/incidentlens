package com.incidentlens.engine;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.incidentlens.engine.Evidence.Factor;
import com.incidentlens.model.ChangeEvent;

/**
 * Scores every affected service as a root-cause hypothesis using independent, explainable factors.
 *
 * <p>The intuition is the one an on-call engineer uses: the root cause usually <b>started first</b>,
 * <b>its own dependencies are healthy</b>, it <b>sits downstream of the other failing services</b>,
 * <b>their errors name it</b>, and it often <b>changed recently</b> or shows a <b>brand-new error</b>.
 * No single factor decides; each one produces evidence that can be checked against raw data.
 */
public final class RootCauseRanker {

    /** Read-only view of engine state the ranker needs. */
    public interface Context {
        DependencyGraph graph();

        Map<String, Long> errorsByPeer(String service, Instant from, Instant to);

        long errorCount(String service, Instant from, Instant to);

        List<ErrorGroupView> newErrorGroups(String service, Instant from, Instant to);

        List<ChangeEvent> changes(String service, Instant from, Instant to);
    }

    static final double W_ONSET = 0.20;
    static final double W_DOWNSTREAM = 0.25;
    static final double W_EXPLAINS = 0.20;
    static final double W_BLAME = 0.15;
    static final double W_CHANGE = 0.10;
    static final double W_NEW_ERROR = 0.10;
    /** Share of a caller's errors that must target a hop for that hop to be "blamed". */
    static final double BLAME_SHARE = 0.3;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final double ONSET_DECAY_SECONDS = 90;

    private final EngineConfig config;

    public RootCauseRanker(EngineConfig config) {
        this.config = config;
    }

    public List<RootCauseCandidate> rank(Incident incident, Context ctx, Instant windowEnd) {
        Instant start = incident.startedAt();
        Instant from = start.minus(config.bucket());
        Set<String> affected = incident.services();
        List<RootCauseCandidate> candidates = new ArrayList<>();
        for (String service : affected) {
            candidates.add(score(service, incident, affected, ctx, from, windowEnd));
        }
        candidates.sort(Comparator.comparingDouble(RootCauseCandidate::score).reversed()
                .thenComparing(c -> incident.onsetOf(c.service())));
        return assignEvidenceIds(candidates);
    }

    private RootCauseCandidate score(String c, Incident incident, Set<String> affected, Context ctx,
                                     Instant from, Instant to) {
        DependencyGraph graph = ctx.graph();
        Scorecard card = new Scorecard(c);
        Instant start = incident.startedAt();
        Instant onset = incident.onsetOf(c);

        // 1. Temporal precedence
        long delaySec = Duration.between(start, onset).toSeconds();
        Anomaly first = incident.anomalies().stream().filter(a -> a.service().equals(c))
                .min(Comparator.comparing(Anomaly::onset)).orElseThrow();
        card.add(Factor.EARLIEST_ONSET, W_ONSET, Math.exp(-delaySec / ONSET_DECAY_SECONDS), delaySec == 0
                ? "First anomaly of the incident: " + c + " " + first.describe() + " from " + TIME.format(onset)
                : c + " became anomalous " + delaySec + "s after the incident started (" + first.describe() + ")",
                onset);

        // 2. Are its own dependencies healthy? If so, it did not inherit the failure.
        Set<String> deps = new TreeSet<>(graph.downstream(c));
        deps.removeIf(d -> !graph.isInstrumented(d));
        Set<String> failingDeps = new TreeSet<>(deps);
        failingDeps.retainAll(affected);
        double downstream = deps.isEmpty() ? 1.0 : 1.0 - (double) failingDeps.size() / deps.size();
        String depText = deps.isEmpty()
                ? c + " has no instrumented service dependencies, so it cannot have inherited the failure from one"
                : failingDeps.isEmpty()
                ? "All services " + c + " depends on are healthy (" + String.join(", ", deps) + ")"
                : c + " depends on services that are also failing: " + String.join(", ", failingDeps);
        card.add(Factor.DOWNSTREAM_HEALTHY, W_DOWNSTREAM, downstream, depText, null);

        // 3. Does it explain the other failing services? They must call it (directly or transitively) AND their
        //    errors must point down that path; a caller failing for its own reasons is not explained.
        if (affected.size() > 1) {
            Set<String> others = new TreeSet<>(affected);
            others.remove(c);
            Set<String> explained = new TreeSet<>();
            for (String u : others) {
                if (explainedBy(u, c, incident, ctx, from, to, new java.util.HashSet<>())) {
                    explained.add(u);
                }
            }
            double explains = (double) explained.size() / others.size();
            card.add(Factor.EXPLAINS_OTHERS, W_EXPLAINS, explains, explained.isEmpty()
                    ? "No other affected service (" + String.join(", ", others) + ") is failing because of calls toward " + c
                    : explained.size() + " of " + others.size() + " other affected services are failing or slow on calls "
                    + "toward " + c + " (" + String.join(", ", explained) + ")", null);
        }

        // 4. Do the failing callers' errors point at it?
        Set<String> failingCallers = new TreeSet<>(graph.callers(c));
        failingCallers.retainAll(affected);
        if (!failingCallers.isEmpty()) {
            List<String> parts = new ArrayList<>();
            double sum = 0;
            for (String caller : failingCallers) {
                long total = ctx.errorCount(caller, from, to);
                long blaming = ctx.errorsByPeer(caller, from, to).getOrDefault(c, 0L);
                double share = total == 0 ? 0 : (double) blaming / total;
                sum += share;
                parts.add(Math.round(share * 100) + "% of " + caller + " errors are failed calls to " + c);
            }
            card.add(Factor.CALLERS_BLAME_IT, W_BLAME, sum / failingCallers.size(), String.join("; ", parts), null);
        }

        // 5. Recent change to this service
        List<ChangeEvent> changes = ctx.changes(c, start.minus(config.changeLookback()), onset.plus(config.bucket()));
        ChangeEvent change = changes.stream().max(Comparator.comparing(ChangeEvent::timestamp)).orElse(null);
        if (change != null) {
            long gap = Math.max(0, Duration.between(change.timestamp(), onset).toSeconds());
            double s = Math.max(0, 1 - (double) gap / config.changeLookback().toSeconds());
            card.add(Factor.RECENT_CHANGE, W_CHANGE, s, change.type() + " to " + c + " " + gap
                    + "s before its first anomaly: " + change.description(), change.timestamp());
        } else {
            card.add(Factor.RECENT_CHANGE, W_CHANGE, 0, null, null);
        }

        // 6. Brand-new error signature (never seen during the baseline period)
        List<ErrorGroupView> fresh = ctx.newErrorGroups(c, from, to);
        if (!fresh.isEmpty()) {
            ErrorGroupView g = fresh.getFirst();
            card.add(Factor.NEW_ERROR_SIGNATURE, W_NEW_ERROR, 1.0, "New error signature in " + c + ": "
                    + (g.exceptionType() == null ? "" : g.exceptionType() + ": ") + g.template()
                    + " (first seen " + TIME.format(g.firstSeen()) + ", " + g.countInWindow() + " occurrences)",
                    g.firstSeen());
        } else {
            card.add(Factor.NEW_ERROR_SIGNATURE, W_NEW_ERROR, 0, null, null);
        }

        // Not scored: which uninstrumented dependency (DB, external API) its errors involve.
        String suspected = null;
        long ownErrors = ctx.errorCount(c, from, to);
        if (ownErrors > 0) {
            Map.Entry<String, Long> top = ctx.errorsByPeer(c, from, to).entrySet().stream()
                    .filter(e -> !graph.isInstrumented(e.getKey()))
                    .max(Map.Entry.comparingByValue()).orElse(null);
            if (top != null && top.getValue() >= ownErrors / 2.0) {
                suspected = top.getKey();
                card.hint(Math.round(100.0 * top.getValue() / ownErrors) + "% of " + c + " errors involve "
                        + suspected + ", which is not instrumented; it is the most likely failing component");
            }
        }
        return card.build(suspected);
    }

    /**
     * True if u's failures can be traced to c through a chain of calls where each hop is backed by evidence:
     * u's errors blame the next hop, or both u and the next hop are slow (latency propagates up synchronous
     * calls). A service with (almost) no errors and no latency anomaly gives nothing to check, so the call
     * graph is trusted.
     */
    private boolean explainedBy(String u, String c, Incident incident, Context ctx, Instant from, Instant to,
                                Set<String> visiting) {
        DependencyGraph graph = ctx.graph();
        if (!visiting.add(u) || !graph.upstream(c).contains(u)) {
            return false;
        }
        long total = ctx.errorCount(u, from, to);
        if (total < config.minErrors() && !slow(incident, u)) {
            return true;
        }
        Map<String, Long> blamed = ctx.errorsByPeer(u, from, to);
        for (String d : graph.downstream(u)) {
            if (!d.equals(c) && !incident.services().contains(d)) {
                continue;
            }
            boolean hop = (total > 0 && blamed.getOrDefault(d, 0L) >= BLAME_SHARE * total)
                    || (slow(incident, u) && slow(incident, d));
            if (hop && (d.equals(c) || explainedBy(d, c, incident, ctx, from, to, visiting))) {
                return true;
            }
        }
        return false;
    }

    private static boolean slow(Incident incident, String service) {
        return incident.anomalies().stream().anyMatch(a -> a.service().equals(service) && a.signal() == Signal.LATENCY_P95);
    }

    private static List<RootCauseCandidate> assignEvidenceIds(List<RootCauseCandidate> ranked) {
        int n = 0;
        List<RootCauseCandidate> result = new ArrayList<>();
        for (RootCauseCandidate c : ranked) {
            List<Evidence> numbered = new ArrayList<>();
            for (Evidence e : c.evidence()) {
                numbered.add(e.withId("E" + (++n)));
            }
            result.add(new RootCauseCandidate(c.service(), c.score(), c.suspectedDependency(), numbered));
        }
        return result;
    }

    public static IncidentReport.Confidence confidence(List<RootCauseCandidate> ranked) {
        if (ranked.isEmpty()) {
            return IncidentReport.Confidence.LOW;
        }
        double top = ranked.getFirst().score();
        double margin = ranked.size() == 1 ? top : top - ranked.get(1).score();
        if (top >= 0.6 && margin >= 0.15) {
            return IncidentReport.Confidence.HIGH;
        }
        return margin >= 0.05 ? IncidentReport.Confidence.MEDIUM : IncidentReport.Confidence.LOW;
    }

    /** Weighted average over the factors that apply to this candidate. */
    private static final class Scorecard {
        private final String service;
        private final List<Evidence> evidence = new ArrayList<>();
        private double weighted;
        private double weights;

        Scorecard(String service) {
            this.service = service;
        }

        void add(Factor factor, double weight, double score, String text, Instant at) {
            weighted += weight * score;
            weights += weight;
            if (text != null) {
                evidence.add(new Evidence(null, service, factor, round(score), text, at));
            }
        }

        void hint(String text) {
            evidence.add(new Evidence(null, service, Factor.DEPENDENCY_HINT, 1.0, text, null));
        }

        RootCauseCandidate build(String suspected) {
            return new RootCauseCandidate(service, round(weights == 0 ? 0 : weighted / weights), suspected,
                    evidence.stream().sorted(Comparator.comparingDouble(Evidence::score).reversed())
                            .collect(Collectors.toList()));
        }

        private static double round(double v) {
            return Math.round(v * 1000) / 1000.0;
        }
    }
}
