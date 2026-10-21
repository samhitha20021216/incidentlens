package com.incidentlens.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.incidentlens.engine.EngineConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quality gate for the analysis engine: replays every scenario (3 severities x several seeds, with noise)
 * and fails the build if root-cause accuracy regresses. Writes the full table to target/evaluation.md.
 */
class EvaluationTest {

    @Test
    void rankerMeetsAccuracyTargetsAndBeatsBaselines() throws IOException {
        EvaluationReport report = new Evaluator(Evaluator.Settings.standard(), EngineConfig.defaults()).evaluateAll();
        Files.writeString(Path.of("target", "evaluation.md"), report.toMarkdown());

        EvaluationReport.Row overall = report.overall();
        assertThat(overall.detected()).isGreaterThanOrEqualTo(0.95);
        assertThat(overall.top1()).isGreaterThanOrEqualTo(0.85);
        assertThat(overall.top3()).isGreaterThanOrEqualTo(0.90);

        double fullSeverityTop1 = report.results().stream().filter(r -> r.severity() == 1.0)
                .filter(Evaluator.RunResult::top1).count()
                / (double) report.results().stream().filter(r -> r.severity() == 1.0).count();
        assertThat(fullSeverityTop1).isGreaterThanOrEqualTo(0.93);

        for (Evaluator.Baseline b : Evaluator.Baseline.values()) {
            double baseline = report.results().stream().filter(r -> r.baselineCorrect(b)).count()
                    / (double) report.results().size();
            assertThat(overall.top1()).as("ranker vs " + b).isGreaterThan(baseline + 0.3);
        }
    }
}
