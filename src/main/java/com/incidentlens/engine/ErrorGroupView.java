package com.incidentlens.engine;

import java.time.Instant;

public record ErrorGroupView(
        String fingerprint,
        String service,
        String exceptionType,
        String template,
        String sampleMessage,
        String topFrame,
        String peerService,
        Instant firstSeen,
        Instant lastSeen,
        long totalCount,
        long countInWindow,
        boolean isNew) {
}
