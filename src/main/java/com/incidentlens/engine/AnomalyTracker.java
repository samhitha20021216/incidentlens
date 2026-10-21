package com.incidentlens.engine;

import java.time.Instant;

/** Hysteresis for one (service, signal): open after N bad buckets in a row, close after M good ones. */
final class AnomalyTracker {

    private final String service;
    private final Signal signal;
    private final EngineConfig config;

    private int anomalousStreak;
    private int normalStreak;
    private Instant streakStart;
    private double streakPeak;
    private Anomaly open;

    AnomalyTracker(String service, Signal signal, EngineConfig config) {
        this.service = service;
        this.signal = signal;
        this.config = config;
    }

    enum Transition { NONE, OPENED, CLOSED }

    Transition update(boolean anomalous, double value, double baseline, Instant bucketStart, Instant bucketEnd) {
        if (anomalous) {
            if (anomalousStreak == 0) {
                streakStart = bucketStart;
                streakPeak = value;
            }
            anomalousStreak++;
            normalStreak = 0;
            streakPeak = Math.max(streakPeak, value);
            if (open != null) {
                open.observe(value, bucketEnd);
                return Transition.NONE;
            }
            if (anomalousStreak >= config.openAfterBuckets()) {
                open = new Anomaly(service, signal, streakStart, bucketEnd, baseline, streakPeak);
                return Transition.OPENED;
            }
            return Transition.NONE;
        }
        anomalousStreak = 0;
        if (open != null && ++normalStreak >= config.closeAfterBuckets()) {
            open.close(bucketEnd);
            open = null;
            normalStreak = 0;
            return Transition.CLOSED;
        }
        return Transition.NONE;
    }

    Anomaly current() {
        return open;
    }
}
