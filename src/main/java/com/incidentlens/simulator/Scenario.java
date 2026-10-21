package com.incidentlens.simulator;

import java.time.Duration;
import java.util.List;

import com.incidentlens.model.ChangeEvent;

/**
 * A failure with a known answer, used for demos and for scoring the root-cause ranker.
 *
 * @param rootCause service an expert would name as the root cause
 * @param changes   changes emitted relative to fault start (negative offset = before the fault)
 */
public record Scenario(String name, String title, String description, String rootCause, List<Fault> faults,
                       List<ScheduledChange> changes) {

    public record ScheduledChange(Duration offset, String service, ChangeEvent.ChangeType type, String description) {
    }
}
