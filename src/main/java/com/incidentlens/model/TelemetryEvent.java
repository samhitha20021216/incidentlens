package com.incidentlens.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One structured log record emitted at the end of a unit of work (an OpenTelemetry-style span log).
 *
 * @param service      emitting service ({@code service.name})
 * @param parentSpanId span of the caller, used to infer the service dependency graph
 * @param latencyMs    present on request/span logs; absent on plain log lines
 * @param peerService  downstream component involved, e.g. the service or database a failed call targeted
 */
public record TelemetryEvent(
        Instant timestamp,
        String service,
        Level level,
        String message,
        String traceId,
        String spanId,
        String parentSpanId,
        String endpoint,
        Integer status,
        Long latencyMs,
        String peerService,
        String exceptionType,
        List<String> stackFrames,
        Map<String, String> attributes) {

    public TelemetryEvent {
        stackFrames = stackFrames == null ? List.of() : List.copyOf(stackFrames);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public boolean isRequest() {
        return latencyMs != null;
    }

    public boolean isError() {
        return level == Level.ERROR || (status != null && status >= 500);
    }
}
