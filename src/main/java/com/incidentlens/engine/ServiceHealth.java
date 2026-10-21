package com.incidentlens.engine;

import java.util.List;

public record ServiceHealth(String service, double requestsPerSecond, double errorRate, double p95LatencyMs,
                            List<String> openAnomalies) {
}
