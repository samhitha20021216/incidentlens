package com.incidentlens.simulator;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.incidentlens.model.ChangeEvent.ChangeType;
import com.incidentlens.simulator.Fault.DependencyFault;
import com.incidentlens.simulator.Fault.ServiceFault;
import com.incidentlens.simulator.Scenario.ScheduledChange;

import static com.incidentlens.simulator.Topology.GATEWAY;
import static com.incidentlens.simulator.Topology.INVENTORY;
import static com.incidentlens.simulator.Topology.NOTIFICATION;
import static com.incidentlens.simulator.Topology.ORDER;
import static com.incidentlens.simulator.Topology.PAYMENT;

/**
 * Failure scenarios with known root causes. Several include red herrings (an unrelated deploy, a
 * latency-only fault, an async boundary) because those are what make real incidents confusing.
 */
public final class ScenarioCatalog {

    private static final List<String> HIKARI = List.of(
            "com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)",
            "com.shop.payment.PaymentRepository.save(PaymentRepository.java:41)",
            "com.shop.payment.ChargeService.charge(ChargeService.java:88)");

    public static final List<Scenario> ALL = List.of(
            new Scenario("payment-db-saturation",
                    "Payment database saturated",
                    "payment-db slows down and its connection pool times out. Payment failures cascade into "
                            + "order-service and api-gateway.",
                    PAYMENT,
                    List.of(new DependencyFault(PAYMENT, "payment-db", 1200, 0.35, "SQLTransientConnectionException",
                            "HikariPool-1 - Connection is not available, request timed out after %dms", HIKARI)),
                    List.of()),

            new Scenario("inventory-bad-deploy",
                    "Bad inventory-service deploy",
                    "inventory-service v2.3.1 throws NullPointerException for SKUs without a warehouse mapping.",
                    INVENTORY,
                    List.of(new ServiceFault(INVENTORY, 0.4, 500, "NullPointerException",
                            "Cannot invoke \"Warehouse.getId()\" because \"mapping\" is null",
                            List.of("com.shop.inventory.ReservationService.reserve(ReservationService.java:57)",
                                    "com.shop.inventory.InventoryController.reserve(InventoryController.java:32)"))),
                    List.of(new ScheduledChange(Duration.ofSeconds(-40), INVENTORY, ChangeType.DEPLOY,
                            "v2.3.0 -> v2.3.1 (warehouse mapping refactor)"))),

            new Scenario("email-provider-outage",
                    "Email provider outage",
                    "The external email provider returns 503s. Orders keep working because notifications are "
                            + "asynchronous; only notification-service is affected.",
                    NOTIFICATION,
                    List.of(new DependencyFault(NOTIFICATION, "email-provider", 0, 0.85, "HttpServerErrorException",
                            "503 Service Unavailable from email-provider POST /v3/mail/send",
                            List.of("com.shop.notify.client.ExternalClient.send(ExternalClient.java:88)",
                                    "com.shop.notify.OrderEmailListener.onOrderPlaced(OrderEmailListener.java:40)"))),
                    List.of()),

            new Scenario("order-pool-misconfig",
                    "order-service connection pool misconfigured",
                    "A config change shrinks order-service's DB pool from 50 to 2; under normal load requests "
                            + "start timing out waiting for a connection.",
                    ORDER,
                    List.of(new DependencyFault(ORDER, "order-db", 900, 0.3, "SQLTransientConnectionException",
                            "HikariPool-1 - Connection is not available, request timed out after %dms",
                            List.of("com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)",
                                    "com.shop.order.OrderRepository.insert(OrderRepository.java:52)"))),
                    List.of(new ScheduledChange(Duration.ofSeconds(-25), ORDER, ChangeType.CONFIG,
                            "spring.datasource.hikari.maximum-pool-size: 50 -> 2"))),

            new Scenario("payment-db-with-decoy-deploy",
                    "Payment DB saturation with an unrelated deploy",
                    "Same failure as payment-db-saturation, but inventory-service was deployed 90 seconds "
                            + "earlier. A naive 'blame the last deploy' heuristic gets this wrong.",
                    PAYMENT,
                    List.of(new DependencyFault(PAYMENT, "payment-db", 1200, 0.35, "SQLTransientConnectionException",
                            "HikariPool-1 - Connection is not available, request timed out after %dms", HIKARI)),
                    List.of(new ScheduledChange(Duration.ofSeconds(-90), INVENTORY, ChangeType.DEPLOY,
                            "v2.4.0 -> v2.4.1 (logging changes only)"))),

            new Scenario("inventory-db-slow",
                    "inventory-db latency spike",
                    "inventory-db queries slow down without failing outright. inventory-service only gets slow, "
                            + "while order-service starts hitting its 2s timeout.",
                    INVENTORY,
                    List.of(new DependencyFault(INVENTORY, "inventory-db", 1700, 0.0, "", "", List.of())),
                    List.of()),

            new Scenario("gateway-route-misconfig",
                    "api-gateway route misconfiguration",
                    "A gateway config push drops the route for a share of order traffic; downstream services "
                            + "see less traffic but no errors.",
                    GATEWAY,
                    List.of(new ServiceFault(GATEWAY, 0.3, 503, "NoRouteException",
                            "No healthy upstream for route 'orders-v2'",
                            List.of("com.shop.gateway.RouteLocator.resolve(RouteLocator.java:118)"))),
                    List.of(new ScheduledChange(Duration.ofSeconds(-20), GATEWAY, ChangeType.CONFIG,
                            "routes.yaml: orders-v1 -> orders-v2 weight 30%"))));

    private ScenarioCatalog() {
    }

    public static Optional<Scenario> byName(String name) {
        return ALL.stream().filter(s -> s.name().equals(name)).findFirst();
    }
}
