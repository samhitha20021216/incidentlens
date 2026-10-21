package com.incidentlens.engine;

public enum Signal {
    ERROR_RATE, LATENCY_P95;

    public String describe(double value) {
        return this == ERROR_RATE ? String.format("%.1f%%", value * 100) : String.format("%,.0f ms", value);
    }

    public String label() {
        return this == ERROR_RATE ? "error rate" : "p95 latency";
    }
}
