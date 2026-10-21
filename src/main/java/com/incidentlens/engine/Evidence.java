package com.incidentlens.engine;

import java.time.Instant;

/**
 * One verifiable fact supporting (or weakening) a root-cause hypothesis.
 *
 * @param id      stable reference ("E3") the LLM must cite
 * @param factor  which scoring factor produced it
 * @param score   that factor's value for this candidate, 0..1
 */
public record Evidence(String id, String service, Factor factor, double score, String text, Instant at) {

    public enum Factor {
        EARLIEST_ONSET("Started first"),
        DOWNSTREAM_HEALTHY("Its dependencies are healthy"),
        EXPLAINS_OTHERS("Explains other failures"),
        CALLERS_BLAME_IT("Callers' errors point at it"),
        RECENT_CHANGE("Changed right before"),
        NEW_ERROR_SIGNATURE("New error signature"),
        DEPENDENCY_HINT("Failing dependency");

        private final String label;

        Factor(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    Evidence withId(String newId) {
        return new Evidence(newId, service, factor, score, text, at);
    }
}
