package com.incidentlens.engine;

import java.time.Instant;

/** A sustained deviation of one signal of one service. */
public final class Anomaly {

    private final String service;
    private final Signal signal;
    private final Instant onset;
    private final Instant detectedAt;
    private final double baseline;
    private double peak;
    private Instant lastAnomalousAt;
    private Instant closedAt;

    Anomaly(String service, Signal signal, Instant onset, Instant detectedAt, double baseline, double peak) {
        this.service = service;
        this.signal = signal;
        this.onset = onset;
        this.detectedAt = detectedAt;
        this.baseline = baseline;
        this.peak = peak;
        this.lastAnomalousAt = detectedAt;
    }

    void observe(double value, Instant at) {
        peak = Math.max(peak, value);
        lastAnomalousAt = at;
    }

    void close(Instant at) {
        closedAt = at;
    }

    public String service() {
        return service;
    }

    public Signal signal() {
        return signal;
    }

    /** Start of the first anomalous bucket: when the problem began, not when we noticed. */
    public Instant onset() {
        return onset;
    }

    public Instant detectedAt() {
        return detectedAt;
    }

    public double baseline() {
        return baseline;
    }

    public double peak() {
        return peak;
    }

    public Instant lastAnomalousAt() {
        return lastAnomalousAt;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public boolean isOpen() {
        return closedAt == null;
    }

    public String describe() {
        return signal.label() + " " + signal.describe(peak) + " (baseline " + signal.describe(baseline) + ")";
    }
}
