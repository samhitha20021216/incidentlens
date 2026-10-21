package com.incidentlens.ai;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerValidatorTest {

    private final EvidencePack pack = new EvidencePack(Map.of(), Set.of("E1", "E2", "T1", "G1"),
            Set.of("payment-service", "order-service", "payment-db"));

    @Test
    void groundedAnswerPasses() {
        AnswerValidator.Result r = AnswerValidator.validate(
                "payment-service is failing because payment-db times out [E1][T1].", pack, "payment-service", true);
        assertThat(r.ok()).isTrue();
        assertThat(r.citations()).containsExactly("E1", "T1");
    }

    @Test
    void flagsMadeUpCitationsAndComponents() {
        AnswerValidator.Result r = AnswerValidator.validate(
                "The redis-cache is overloaded [E9].", pack, "payment-service", true);
        assertThat(r.warnings()).anyMatch(w -> w.contains("[E9]"))
                .anyMatch(w -> w.contains("redis-cache"))
                .anyMatch(w -> w.contains("top-ranked candidate"));
    }

    @Test
    void flagsUncitedAnswers() {
        assertThat(AnswerValidator.validate("Probably the database.", pack, null, false).warnings())
                .anyMatch(w -> w.contains("cites no evidence"));
    }
}
