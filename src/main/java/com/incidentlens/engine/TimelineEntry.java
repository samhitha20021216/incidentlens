package com.incidentlens.engine;

import java.time.Instant;

public record TimelineEntry(Instant at, Kind kind, String service, String text) {

    public enum Kind { CHANGE, ANOMALY, NEW_ERROR, DETECTED, RESOLVED }
}
