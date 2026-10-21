package com.incidentlens.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.incidentlens.model.Level;
import com.incidentlens.model.TelemetryEvent;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprinterTest {

    private final Fingerprinter fp = new Fingerprinter(List.of("com.shop."));

    @Test
    void templateMasksVariableParts() {
        assertThat(fp.template("Order 8812 for user bob@example.com failed after 2003ms at 10.0.3.17:8080"))
                .isEqualTo("Order <num> for user <email> failed after <num>ms at <ip>");
        assertThat(fp.template("Trace 3f2a9c1e-77d4-4b0a-9a51-0c1d2e3f4a5b, key 'sku-1'"))
                .isEqualTo("Trace <uuid>, key <str>");
    }

    @Test
    void sameErrorWithDifferentIdsGroupsTogether() {
        assertThat(fp.fingerprint(error("Order 1 failed after 2003ms", List.of())))
                .isEqualTo(fp.fingerprint(error("Order 99 failed after 1998ms", List.of())));
    }

    @Test
    void stackTraceGroupingIgnoresLineNumbersAndFrameworkFrames() {
        TelemetryEvent a = error("boom", List.of("org.spring.Proxy.invoke(Proxy.java:10)",
                "com.shop.inventory.ReservationService.reserve(ReservationService.java:57)"));
        TelemetryEvent b = error("different message", List.of("org.spring.Other.call(Other.java:99)",
                "com.shop.inventory.ReservationService.reserve(ReservationService.java:61)"));
        assertThat(fp.fingerprint(a)).isEqualTo(fp.fingerprint(b));
    }

    @Test
    void failedCallsToDifferentDependenciesAreDifferentGroups() {
        List<String> clientFrames = List.of("com.shop.order.client.DownstreamClient.post(DownstreamClient.java:64)");
        TelemetryEvent toPayment = new TelemetryEvent(Instant.EPOCH, "order-service", Level.ERROR, "Call failed", null,
                null, null, null, 502, 10L, "payment-service", "HttpServerErrorException", clientFrames, Map.of());
        TelemetryEvent toInventory = new TelemetryEvent(Instant.EPOCH, "order-service", Level.ERROR, "Call failed", null,
                null, null, null, 502, 10L, "inventory-service", "HttpServerErrorException", clientFrames, Map.of());
        assertThat(fp.fingerprint(toPayment)).isNotEqualTo(fp.fingerprint(toInventory));
    }

    @Test
    void differentServicesNeverShareAGroup() {
        TelemetryEvent a = error("timeout", List.of());
        TelemetryEvent b = new TelemetryEvent(a.timestamp(), "other-service", Level.ERROR, "timeout", null, null, null,
                null, 500, 10L, null, "X", List.of(), Map.of());
        assertThat(fp.fingerprint(a)).isNotEqualTo(fp.fingerprint(b));
    }

    private static TelemetryEvent error(String message, List<String> frames) {
        return new TelemetryEvent(Instant.EPOCH, "inventory-service", Level.ERROR, message, null, null, null, null, 500,
                10L, null, "NullPointerException", frames, Map.of());
    }
}
