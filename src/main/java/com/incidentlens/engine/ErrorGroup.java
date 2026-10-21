package com.incidentlens.engine;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import com.incidentlens.model.TelemetryEvent;

/** All occurrences of one error fingerprint. */
final class ErrorGroup {

    final String fingerprint;
    final String service;
    final String exceptionType;
    final String template;
    final String sampleMessage;
    final String topFrame;
    final Instant firstSeen;
    Instant lastSeen;
    long count;
    final Map<String, Long> peers = new HashMap<>();
    final TreeMap<Long, Integer> perBucket = new TreeMap<>();

    ErrorGroup(String fingerprint, TelemetryEvent first, String template) {
        this.fingerprint = fingerprint;
        this.service = first.service();
        this.exceptionType = first.exceptionType();
        this.template = template;
        this.sampleMessage = first.message();
        this.topFrame = first.stackFrames().isEmpty() ? null : first.stackFrames().getFirst();
        this.firstSeen = first.timestamp();
        this.lastSeen = first.timestamp();
    }

    void record(TelemetryEvent e, long bucketIndex) {
        count++;
        if (e.timestamp().isAfter(lastSeen)) {
            lastSeen = e.timestamp();
        }
        if (e.peerService() != null) {
            peers.merge(e.peerService(), 1L, Long::sum);
        }
        perBucket.merge(bucketIndex, 1, Integer::sum);
    }

    long countBetween(long fromBucket, long toBucket) {
        return perBucket.subMap(fromBucket, true, toBucket, true).values().stream().mapToLong(Integer::longValue).sum();
    }

    String mainPeer() {
        return peers.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }
}
