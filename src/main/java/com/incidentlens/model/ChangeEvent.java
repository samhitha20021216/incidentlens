package com.incidentlens.model;

import java.time.Instant;

/** A deploy, config change or feature-flag flip: the "what changed?" half of every investigation. */
public record ChangeEvent(Instant timestamp, String service, ChangeType type, String description) {

    public enum ChangeType { DEPLOY, CONFIG, FEATURE_FLAG }
}
