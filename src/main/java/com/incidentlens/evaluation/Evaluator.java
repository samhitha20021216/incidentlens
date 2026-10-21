package com.incidentlens.evaluation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.incidentlens.engine.EngineConfig;
import com.incidentlens.engine.IncidentEngine;
import com.incidentlens.engine.IncidentReport;
import com.incidentlens.engine.IncidentSummary;
import com.incidentlens.engine.RootCauseCandidate;
import com.incidentlens.model.ChangeEvent;
import com.incidentlens.simulator.Scenario;
import com.incidentlens.simulator.ScenarioCatalog;
import com.incidentlens.simulator.Topology;
import com.incidentlens.simulator.TrafficSimulator;

/**
 * Replays every scenario with several seeds through the real engine, in accelerated event time, and
 * scores detection and root-cause ranking against the known answer.
 *
 * <p>Timeline of one run: 12 min healthy traffic (with an unrelated deploy at minute 6), 5 min fault,
 * 5 min recovery. Short unrelated error bursts hit random services throughout (about one every 4 minutes).
 */
public final class Evaluator {

    public record Settings(int seeds, double requestsPerSecond, Duration healthy, Duration fault, Duration recovery,
                           List<Double> severities, double noiseBurstsPerMinute) {
        public static Settings standard() {
            return new Settings(10, 15, Duration.ofMinutes(12), Duration.ofMinutes(5), Duration.ofMinutes(5),
                    List.of(1.0, 0.5, 0.25), 0.25);
        }
    }

    /** Simple heuristics an engineer might use, to check the ranker actually adds something. */
    public enum Baseline { MOST_ERRORS, LAST_CHANGE, FIRST_ALERT }

    public record RunResult(String scenario, double severity, long seed, String expected, boolean detected,
                            Integer rank, String predicted, IncidentReport.Confidence confidence,
                            Duration detectionDelay, int falseAlarms, long events,
                            java.util.Map<Baseline, String> baselines) {

        public boolean baselineCorrect(Baseline b) {
            return detected && expected.equals(baselines.get(b));
        }

        public boolean top1() {
            return rank != null && rank == 1;
        }

        public boolean top3() {
            return rank != null && rank <= 3;
        }
    }

    private static final Instant T0 = Instant.parse("2026-03-02T10:00:00Z");

    private final Settings settings;
    private final EngineConfig engineConfig;

    public Evaluator(Settings settings, EngineConfig engineConfig) {
        this.settings = settings;
        this.engineConfig = engineConfig;
    }

    public EvaluationReport evaluateAll() {
        List<RunResult> results = new ArrayList<>();
        for (double severity : settings.severities()) {
            for (Scenario scenario : ScenarioCatalog.ALL) {
                for (long seed = 1; seed <= settings.seeds(); seed++) {
                    results.add(run(scenario, severity, seed));
                }
            }
        }
        return new EvaluationReport(results);
    }

    public RunResult run(Scenario original, double severity, long seed) {
        Scenario scenario = scaled(original, severity);
        IncidentEngine engine = new IncidentEngine(engineConfig);
        TrafficSimulator sim = new TrafficSimulator(seed * 7919 + scenario.name().hashCode(), settings.requestsPerSecond());
        Instant faultStart = T0.plus(settings.healthy());
        Instant end = faultStart.plus(settings.fault()).plus(settings.recovery());
        sim.scheduleChange(new ChangeEvent(T0.plus(settings.healthy().dividedBy(2)), Topology.NOTIFICATION,
                ChangeEvent.ChangeType.DEPLOY, "v1.8.0 -> v1.8.1 (routine release)"));
        sim.schedule(scenario, faultStart, settings.fault());
        sim.enableNoiseBursts(settings.noiseBurstsPerMinute());

        long events = 0;
        for (Instant t = T0; t.isBefore(end); t = t.plusSeconds(1)) {
            TrafficSimulator.Output out = sim.tick(t, t.plusSeconds(1));
            out.changes().forEach(engine::ingest);
            out.telemetry().forEach(engine::ingest);
            events += out.telemetry().size();
            engine.advance(engine.eventWatermark());
        }
        engine.advance(end.plus(Duration.ofMinutes(5)));

        // The incident that captured the fault: among incidents with an anomaly active during the fault, the
        // longest-lasting one (noise bursts are short). This does not use knowledge of the root cause.
        List<IncidentSummary> incidents = engine.incidents();
        Instant windowTo = faultStart.plus(settings.fault());
        Optional<IncidentSummary> match = incidents.stream()
                .filter(i -> engine.report(i.id()).orElseThrow().anomalies().stream()
                        .anyMatch(a -> a.onset().isBefore(windowTo)
                                && (a.closedAt() == null || a.closedAt().isAfter(faultStart.plus(engineConfig.bucket())))))
                .max(Comparator.comparing((IncidentSummary i) -> Duration.between(i.startedAt(),
                        i.resolvedAt() == null ? end : i.resolvedAt())));
        int falseAlarms = (int) incidents.stream().filter(i -> match.map(m -> !m.id().equals(i.id())).orElse(true)).count();

        if (match.isEmpty()) {
            return new RunResult(scenario.name(), severity, seed, scenario.rootCause(), false, null, null, null, null,
                    falseAlarms, events, java.util.Map.of());
        }
        IncidentReport report = engine.report(match.get().id()).orElseThrow();
        List<String> ranked = report.candidates().stream().map(RootCauseCandidate::service).toList();
        int idx = ranked.indexOf(scenario.rootCause());
        return new RunResult(scenario.name(), severity, seed, scenario.rootCause(), true, idx < 0 ? null : idx + 1,
                ranked.getFirst(), report.confidence(), detectionDelay(report, faultStart),
                falseAlarms, events, baselines(engine, report));
    }

    /**
     * Baselines see the same detected incident (same affected services) and only differ in how they pick.
     * They look at every instrumented service, not just the affected ones, like a human scanning dashboards.
     */
    private java.util.Map<Baseline, String> baselines(IncidentEngine engine, IncidentReport report) {
        Instant from = report.startedAt().minus(engineConfig.bucket());
        Instant to = report.resolvedAt() != null ? report.resolvedAt() : report.startedAt().plus(settings.fault());
        Comparator<String> byErrors = Comparator.comparingLong(s -> engine.errorCount(s, from, to));
        String mostErrors = engine.graph().instrumentedServices().stream().max(byErrors).orElseThrow();
        String lastChange = engine.changes(null, report.startedAt().minus(engineConfig.changeLookback()), report.startedAt())
                .stream().max(Comparator.comparing(ChangeEvent::timestamp)).map(ChangeEvent::service).orElse(mostErrors);
        Instant firstOnset = report.timeline().stream()
                .filter(e -> e.kind() == com.incidentlens.engine.TimelineEntry.Kind.ANOMALY)
                .map(com.incidentlens.engine.TimelineEntry::at).min(Comparator.naturalOrder()).orElseThrow();
        String firstAlert = report.timeline().stream()
                .filter(e -> e.kind() == com.incidentlens.engine.TimelineEntry.Kind.ANOMALY && e.at().equals(firstOnset))
                .map(com.incidentlens.engine.TimelineEntry::service).max(byErrors).orElseThrow();
        return java.util.Map.of(Baseline.MOST_ERRORS, mostErrors, Baseline.LAST_CHANGE, lastChange,
                Baseline.FIRST_ALERT, firstAlert);
    }

    /**
     * Time from fault start until the first anomaly that <i>began</i> with the fault was flagged. Null when the
     * fault only extended anomalies that were already open (e.g. from a noise burst): there is no clean
     * detection time to measure, so such runs are left out of the median.
     */
    private Duration detectionDelay(IncidentReport report, Instant faultStart) {
        return report.anomalies().stream()
                .filter(a -> !a.onset().isBefore(faultStart))
                .map(IncidentReport.AnomalyView::detectedAt).min(Comparator.naturalOrder())
                .map(first -> Duration.between(faultStart, first)).orElse(null);
    }

    /** Weaker faults are harder to detect and to attribute. */
    static Scenario scaled(Scenario s, double severity) {
        if (severity == 1.0) {
            return s;
        }
        List<com.incidentlens.simulator.Fault> faults = s.faults().stream().map(f -> switch (f) {
            case com.incidentlens.simulator.Fault.DependencyFault d -> (com.incidentlens.simulator.Fault)
                    new com.incidentlens.simulator.Fault.DependencyFault(d.service(), d.dependency(),
                            Math.round(d.extraLatencyMs() * severity), d.failureProbability() * severity,
                            d.exceptionType(), d.message(), d.frames());
            case com.incidentlens.simulator.Fault.ServiceFault sf -> new com.incidentlens.simulator.Fault.ServiceFault(
                    sf.service(), sf.failureProbability() * severity, sf.status(), sf.exceptionType(), sf.message(),
                    sf.frames());
        }).toList();
        return new Scenario(s.name(), s.title(), s.description(), s.rootCause(), faults, s.changes());
    }

    public static void main(String[] args) {
        long started = System.nanoTime();
        EvaluationReport report = new Evaluator(Settings.standard(), EngineConfig.defaults()).evaluateAll();
        System.out.println(report.toMarkdown());
        System.out.printf("%nEvaluated %d runs in %.1fs%n", report.results().size(), (System.nanoTime() - started) / 1e9);
    }
}
