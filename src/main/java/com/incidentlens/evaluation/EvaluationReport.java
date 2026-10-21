package com.incidentlens.evaluation;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.incidentlens.evaluation.Evaluator.RunResult;

public record EvaluationReport(List<RunResult> results) {

    public record Row(String scenario, int runs, double detected, double top1, double top3,
                      Duration medianDetection, int falseAlarms) {
    }

    public List<Row> rows() {
        Map<String, List<RunResult>> byScenario = results.stream()
                .collect(Collectors.groupingBy(r -> r.scenario() + severityLabel(r.severity()),
                        LinkedHashMap::new, Collectors.toList()));
        return byScenario.entrySet().stream().map(e -> row(e.getKey(), e.getValue())).toList();
    }

    public Row overall() {
        return row("all scenarios", results);
    }

    private static Row row(String name, List<RunResult> runs) {
        int n = runs.size();
        List<Duration> delays = runs.stream().filter(RunResult::detected).map(RunResult::detectionDelay)
                .filter(java.util.Objects::nonNull).sorted().toList();
        return new Row(name, n,
                runs.stream().filter(RunResult::detected).count() / (double) n,
                runs.stream().filter(RunResult::top1).count() / (double) n,
                runs.stream().filter(RunResult::top3).count() / (double) n,
                delays.isEmpty() ? null : delays.get(delays.size() / 2),
                runs.stream().mapToInt(RunResult::falseAlarms).sum());
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("| Scenario | Runs | Detected | Top-1 root cause | Top-3 | Median time to detect | Noise incidents |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (Row r : rows()) {
            append(sb, r, false);
        }
        append(sb, overall(), true);
        sb.append("\n| Method | Top-1 (full severity) | Top-1 (50%) | Top-1 (25%) | Top-1 (all) |\n|---|---:|---:|---:|---:|\n");
        sb.append(methodRow("**IncidentLens ranker**", RunResult::top1));
        for (Evaluator.Baseline b : Evaluator.Baseline.values()) {
            sb.append(methodRow("Baseline: " + b.name().toLowerCase().replace('_', ' '), r -> r.baselineCorrect(b)));
        }
        List<RunResult> misses = results.stream().filter(r -> !r.top1()).toList();
        if (!misses.isEmpty()) {
            sb.append("\nMisses:\n\n");
            for (RunResult m : misses) {
                sb.append("- `").append(m.scenario()).append(severityLabel(m.severity())).append("` seed ")
                        .append(m.seed()).append(": ")
                        .append(m.detected() ? "ranked " + m.predicted() + " first (expected " + m.expected()
                                + (m.rank() == null ? ", not ranked" : ", ranked #" + m.rank()) + ")" : "not detected")
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private String methodRow(String name, java.util.function.Predicate<RunResult> correct) {
        return "| " + name + " | " + pct(rate(results.stream().filter(r -> r.severity() == 1.0).toList(), correct))
                + " | " + pct(rate(results.stream().filter(r -> r.severity() == 0.5).toList(), correct))
                + " | " + pct(rate(results.stream().filter(r -> r.severity() == 0.25).toList(), correct))
                + " | " + pct(rate(results, correct)) + " |\n";
    }

    static String severityLabel(double severity) {
        return severity == 1.0 ? "" : " (" + Math.round(severity * 100) + "%)";
    }

    private static double rate(List<RunResult> runs, java.util.function.Predicate<RunResult> correct) {
        return runs.isEmpty() ? 0 : runs.stream().filter(correct).count() / (double) runs.size();
    }

    private static void append(StringBuilder sb, Row r, boolean bold) {
        String b = bold ? "**" : "";
        sb.append("| ").append(b).append(r.scenario()).append(b)
                .append(" | ").append(r.runs())
                .append(" | ").append(pct(r.detected()))
                .append(" | ").append(b).append(pct(r.top1())).append(b)
                .append(" | ").append(pct(r.top3()))
                .append(" | ").append(r.medianDetection() == null ? "-" : r.medianDetection().toSeconds() + "s")
                .append(" | ").append(r.falseAlarms())
                .append(" |\n");
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }
}
