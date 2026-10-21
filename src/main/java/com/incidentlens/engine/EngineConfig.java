package com.incidentlens.engine;

import java.time.Duration;
import java.util.List;

/**
 * Tuning knobs for detection. Defaults are deliberately conservative: an anomaly must clear a statistical
 * threshold <i>and</i> an absolute floor, for several consecutive buckets, before anyone gets paged.
 */
public record EngineConfig(
        Duration bucket,
        int baselineBuckets,
        int minBaselineBuckets,
        int openAfterBuckets,
        int closeAfterBuckets,
        double errorRateSigma,
        double errorRateMinDelta,
        int minErrors,
        double coldStartErrorRate,
        double latencyFactor,
        double latencySigma,
        long latencyMinDeltaMs,
        Duration lateness,
        Duration retention,
        Duration resolveAfter,
        Duration changeLookback,
        Duration groupingWindow,
        List<String> inAppPrefixes) {

    public static EngineConfig defaults() {
        return new EngineConfig(
                Duration.ofSeconds(10), // bucket
                30,                     // baseline: previous 5 minutes
                12,                     // need 2 minutes of history before judging
                2,                      // open after 2 anomalous buckets in a row
                6,                      // close after 1 minute of normal buckets
                4.0, 0.05, 3,           // error rate: > mean + max(4 sigma, 5 points), at least 3 errors
                0.10,                   // before a baseline exists: >= 10% errors is anomalous
                2.0, 4.0, 150,          // latency: p95 > 2x baseline, > mean + 4 sigma, +150 ms
                Duration.ofSeconds(5),
                Duration.ofMinutes(60),
                Duration.ofMinutes(2),
                Duration.ofMinutes(15),
                Duration.ofMinutes(2),  // a new anomaly joins an incident only if it starts within 2 min of its latest one
                List.of("com.shop."));
    }

    public Duration warmup() {
        return bucket.multipliedBy(minBaselineBuckets);
    }
}
