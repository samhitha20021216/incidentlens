package com.incidentlens.simulator;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The simulated shop:
 * <pre>
 *  api-gateway ─▶ order-service ─▶ inventory-service ─▶ inventory-db
 *                       │  │
 *                       │  └────▶ payment-service ──▶ payment-db
 *                       │                        └─▶ card-network (external)
 *                       ├─▶ order-db
 *                       └─(async, Kafka)─▶ notification-service ─▶ email-provider (external)
 * </pre>
 */
public final class Topology {

    public record Call(String target, String endpoint, long timeoutMs) {
    }

    public record Infra(String name, long medianLatencyMs, long timeoutMs, double baseFailure,
                        String timeoutException, String timeoutMessage) {
    }

    public static final String GATEWAY = "api-gateway";
    public static final String ORDER = "order-service";
    public static final String INVENTORY = "inventory-service";
    public static final String PAYMENT = "payment-service";
    public static final String NOTIFICATION = "notification-service";

    static final Map<String, String> ENDPOINTS = Map.of(
            GATEWAY, "POST /api/orders",
            ORDER, "POST /orders",
            INVENTORY, "POST /inventory/reserve",
            PAYMENT, "POST /payments/charge",
            NOTIFICATION, "consume order-events");

    static final Map<String, Long> OWN_LATENCY_MS = Map.of(
            GATEWAY, 6L, ORDER, 18L, INVENTORY, 12L, PAYMENT, 25L, NOTIFICATION, 10L);

    /** Synchronous calls, in order. Services in {@link #SERVICES}; anything else is infrastructure. */
    static final Map<String, List<Call>> CALLS = Map.of(
            GATEWAY, List.of(new Call(ORDER, "POST /orders", 3000)),
            ORDER, List.of(new Call("order-db", null, 2000), new Call(INVENTORY, "POST /inventory/reserve", 2000),
                    new Call(PAYMENT, "POST /payments/charge", 2000)),
            INVENTORY, List.of(new Call("inventory-db", null, 2000)),
            PAYMENT, List.of(new Call("payment-db", null, 2000), new Call("card-network", null, 2500)),
            NOTIFICATION, List.of(new Call("email-provider", null, 3000)));

    static final Map<String, Infra> INFRA = Map.of(
            "order-db", new Infra("order-db", 6, 2000, 0.0, "SQLTransientConnectionException",
                    "Connection is not available, request timed out after %dms"),
            "inventory-db", new Infra("inventory-db", 5, 2000, 0.0, "SQLTransientConnectionException",
                    "Connection is not available, request timed out after %dms"),
            "payment-db", new Infra("payment-db", 8, 2000, 0.0, "SQLTransientConnectionException",
                    "Connection is not available, request timed out after %dms"),
            "card-network", new Infra("card-network", 120, 2500, 0.001, "SocketTimeoutException",
                    "Read timed out after %dms calling card-network"),
            "email-provider", new Infra("email-provider", 90, 3000, 0.002, "SocketTimeoutException",
                    "Read timed out after %dms calling email-provider"));

    public static final Set<String> SERVICES = Set.of(GATEWAY, ORDER, INVENTORY, PAYMENT, NOTIFICATION);

    private Topology() {
    }
}
