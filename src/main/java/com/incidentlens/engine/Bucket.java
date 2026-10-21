package com.incidentlens.engine;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Aggregates for one service over one time bucket. */
final class Bucket {

    int requests;
    int errors;
    final List<Long> latencies = new ArrayList<>();
    final Map<String, Integer> errorsByPeer = new HashMap<>();
    /** Signals that were anomalous in this bucket; such buckets are excluded from future baselines. */
    final Set<Signal> anomalous = EnumSet.noneOf(Signal.class);

    double errorRate() {
        if (requests == 0) {
            return errors > 0 ? 1.0 : 0.0;
        }
        return Math.min(1.0, (double) errors / requests);
    }

    double p95() {
        if (latencies.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(null);
        return sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1);
    }
}
