package com.incidentlens.simulator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

import com.incidentlens.model.ChangeEvent;
import com.incidentlens.model.Level;
import com.incidentlens.model.TelemetryEvent;
import com.incidentlens.simulator.Fault.DependencyFault;
import com.incidentlens.simulator.Fault.ServiceFault;
import com.incidentlens.simulator.Topology.Call;
import com.incidentlens.simulator.Topology.Infra;

/**
 * Generates realistic, trace-linked telemetry for the {@link Topology}: Poisson request arrivals,
 * log-normal latencies, timeouts that turn slowness into errors, async fan-out, background noise, and
 * whatever {@link Fault}s are active. Deterministic for a given seed.
 */
public final class TrafficSimulator {

    public record Output(List<TelemetryEvent> telemetry, List<ChangeEvent> changes) {
    }

    private record Active(Scenario scenario, Instant from, Instant to) {
        boolean covers(Instant t) {
            return !t.isBefore(from) && t.isBefore(to);
        }
    }

    private record Result(long latencyMs, boolean ok, int status) {
    }

    private record Failure(String peer, int status, String exceptionType, String message, List<String> frames) {
    }

    private static final double BASE_ERROR_RATE = 0.002;
    private static final double LATENCY_SIGMA = 0.35;
    private static final HexFormat HEX = HexFormat.of();

    private final Random random;
    private final double requestsPerSecond;
    private final PriorityQueue<TelemetryEvent> pendingTelemetry =
            new PriorityQueue<>(Comparator.comparing(TelemetryEvent::timestamp));
    private final PriorityQueue<ChangeEvent> pendingChanges =
            new PriorityQueue<>(Comparator.comparing(ChangeEvent::timestamp));
    private final List<Active> active = new ArrayList<>();
    private long orderSeq = 10_000;
    private double burstsPerMinute;
    private final List<String> services = List.copyOf(new java.util.TreeSet<>(Topology.SERVICES));

    public TrafficSimulator(long seed, double requestsPerSecond) {
        this.random = new Random(seed);
        this.requestsPerSecond = requestsPerSecond;
    }

    /** Activates a scenario's faults during [start, start + duration) and schedules its change events. */
    public void schedule(Scenario scenario, Instant start, Duration duration) {
        active.add(new Active(scenario, start, start.plus(duration)));
        for (Scenario.ScheduledChange c : scenario.changes()) {
            pendingChanges.add(new ChangeEvent(start.plus(c.offset()), c.service(), c.type(), c.description()));
        }
    }

    public void scheduleChange(ChangeEvent change) {
        pendingChanges.add(change);
    }

    /**
     * Unrelated, short-lived error bursts in random services (a pod restart, a flaky dependency) so that
     * detection and ranking have to cope with noise that is not part of the incident.
     */
    public void enableNoiseBursts(double perMinute) {
        this.burstsPerMinute = perMinute;
    }

    public void clearScenarios() {
        active.clear();
    }

    /** Simulates traffic arriving in [from, to) and returns every event that has completed before {@code to}. */
    public Output tick(Instant from, Instant to) {
        long windowMs = Duration.between(from, to).toMillis();
        if (burstsPerMinute > 0 && random.nextDouble() < burstsPerMinute * windowMs / 60_000.0) {
            String victim = services.get(random.nextInt(services.size()));
            Duration length = Duration.ofSeconds(15 + random.nextInt(25));
            active.add(new Active(new Scenario("noise", "noise", "transient burst", victim,
                    List.of(new ServiceFault(victim, 0.08 + 0.1 * random.nextDouble(), 503, "TransientDataAccessException",
                            "Connection reset by peer during " + victim + " request",
                            List.of("com.shop.common.Retry.execute(Retry.java:33)"))), List.of()), from, from.plus(length)));
        }
        int arrivals = poisson(requestsPerSecond * windowMs / 1000.0);
        for (int i = 0; i < arrivals; i++) {
            Instant start = from.plusMillis((long) (random.nextDouble() * windowMs));
            call(Topology.GATEWAY, newId(16), null, start);
        }
        backgroundNoise(from, windowMs);

        List<TelemetryEvent> telemetry = new ArrayList<>();
        while (!pendingTelemetry.isEmpty() && pendingTelemetry.peek().timestamp().isBefore(to)) {
            telemetry.add(pendingTelemetry.poll());
        }
        List<ChangeEvent> changes = new ArrayList<>();
        while (!pendingChanges.isEmpty() && pendingChanges.peek().timestamp().isBefore(to)) {
            changes.add(pendingChanges.poll());
        }
        return new Output(telemetry, changes);
    }

    // ------------------------------------------------------------------ request simulation

    private Result call(String service, String traceId, String parentSpanId, Instant start) {
        String spanId = newId(8);
        long elapsed = sampleLatency(Topology.OWN_LATENCY_MS.get(service));
        Failure failure = null;
        ServiceFault own = activeServiceFault(service, start);
        boolean ownCodeFails = own != null && random.nextDouble() < own.failureProbability();

        for (Call call : ownCodeFails ? List.<Call>of() : Topology.CALLS.get(service)) {
            Instant at = start.plusMillis(elapsed);
            if (Topology.SERVICES.contains(call.target())) {
                Result r = call(call.target(), traceId, spanId, at);
                if (r.latencyMs() > call.timeoutMs()) {
                    elapsed += call.timeoutMs();
                    failure = new Failure(call.target(), 504, "SocketTimeoutException",
                            "Call to " + call.target() + " " + call.endpoint() + " timed out after "
                                    + (call.timeoutMs() + random.nextInt(8)) + "ms",
                            clientFrames(service));
                    break;
                }
                elapsed += r.latencyMs();
                if (!r.ok()) {
                    failure = new Failure(call.target(), 502, "HttpServerErrorException",
                            "Call to " + call.target() + " " + call.endpoint() + " failed with " + r.status(),
                            clientFrames(service));
                    break;
                }
            } else {
                Infra infra = Topology.INFRA.get(call.target());
                DependencyFault fault = activeDependencyFault(service, infra.name(), at);
                long latency = sampleLatency(infra.medianLatencyMs()) + (fault == null ? 0 : jitter(fault.extraLatencyMs()));
                if (latency > infra.timeoutMs()) {
                    elapsed += infra.timeoutMs();
                    failure = new Failure(infra.name(), 503, infra.timeoutException(),
                            String.format(infra.timeoutMessage(), infra.timeoutMs() + random.nextInt(10)),
                            infraFrames(service, infra.name()));
                    break;
                }
                elapsed += latency;
                if (fault != null && random.nextDouble() < fault.failureProbability()) {
                    failure = new Failure(infra.name(), 503, fault.exceptionType(), fill(fault.message()), fault.frames());
                    break;
                }
                if (random.nextDouble() < infra.baseFailure()) {
                    failure = new Failure(infra.name(), 503, infra.timeoutException(),
                            String.format(infra.timeoutMessage(), infra.timeoutMs()), infraFrames(service, infra.name()));
                    break;
                }
            }
        }

        if (failure == null) {
            if (ownCodeFails) {
                failure = new Failure(null, own.status(), own.exceptionType(), fill(own.message()), own.frames());
            } else if (random.nextDouble() < BASE_ERROR_RATE) {
                failure = new Failure(null, 500, "IllegalStateException",
                        "Unexpected state for order " + (orderSeq++), List.of("com.shop.common.Guard.check(Guard.java:21)"));
            }
        }

        Instant end = start.plusMillis(elapsed);
        String endpoint = Topology.ENDPOINTS.get(service);
        if (failure == null) {
            emit(new TelemetryEvent(end, service, Level.INFO, endpoint + " completed", traceId, spanId, parentSpanId,
                    endpoint, 200, elapsed, null, null, null, Map.of()));
            if (service.equals(Topology.ORDER)) {
                // Async: order placed -> Kafka -> notification-service, same trace.
                Instant consumed = end.plusMillis(20 + random.nextInt(180));
                call(Topology.NOTIFICATION, traceId, spanId, consumed);
            }
            return new Result(elapsed, true, 200);
        }
        emit(new TelemetryEvent(end, service, Level.ERROR, failure.message(), traceId, spanId, parentSpanId,
                endpoint, failure.status(), elapsed, failure.peer(), failure.exceptionType(), failure.frames(), Map.of()));
        return new Result(elapsed, false, failure.status());
    }

    /** Things real systems log all the time that are not incidents. */
    private void backgroundNoise(Instant from, long windowMs) {
        for (int i = 0; i < poisson(0.8 * windowMs / 1000.0); i++) {
            emit(new TelemetryEvent(from.plusMillis(random.nextInt((int) Math.max(1, windowMs))), Topology.INVENTORY,
                    Level.WARN, "Cache miss for sku " + (1000 + random.nextInt(9000)), null, null, null, null, null,
                    null, null, null, null, Map.of()));
        }
        for (int i = 0; i < poisson(0.03 * windowMs / 1000.0); i++) {
            emit(new TelemetryEvent(from.plusMillis(random.nextInt((int) Math.max(1, windowMs))), Topology.NOTIFICATION,
                    Level.ERROR, "Template 'order-confirmation' missing locale fr-CA, falling back to en", null, null,
                    null, null, null, null, null, "MissingResourceException",
                    List.of("com.shop.notify.TemplateRenderer.render(TemplateRenderer.java:77)"), Map.of()));
        }
    }

    // ------------------------------------------------------------------ helpers

    private DependencyFault activeDependencyFault(String service, String dependency, Instant at) {
        for (Active a : active) {
            if (a.covers(at)) {
                for (Fault f : a.scenario().faults()) {
                    if (f instanceof DependencyFault d && d.service().equals(service) && d.dependency().equals(dependency)) {
                        return d;
                    }
                }
            }
        }
        return null;
    }

    private ServiceFault activeServiceFault(String service, Instant at) {
        for (Active a : active) {
            if (a.covers(at)) {
                for (Fault f : a.scenario().faults()) {
                    if (f instanceof ServiceFault s && s.service().equals(service)) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    private List<String> clientFrames(String service) {
        String pkg = service.replace("-service", "").replace("api-", "");
        return List.of("com.shop." + pkg + ".client.DownstreamClient.post(DownstreamClient.java:64)",
                "org.springframework.web.client.RestClient.retrieve(RestClient.java:512)");
    }

    private List<String> infraFrames(String service, String infra) {
        String pkg = service.replace("-service", "");
        return infra.endsWith("-db")
                ? List.of("com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)",
                "com.shop." + pkg + ".Repository.save(Repository.java:41)")
                : List.of("com.shop." + pkg + ".client.ExternalClient.send(ExternalClient.java:88)");
    }

    /** Replace %d with a plausible varying number so messages differ like real ones do. */
    private String fill(String template) {
        return template.contains("%d") ? String.format(template, 1990 + random.nextInt(25)) : template;
    }

    private void emit(TelemetryEvent e) {
        pendingTelemetry.add(e);
    }

    private long sampleLatency(long medianMs) {
        return Math.max(1, Math.round(medianMs * Math.exp(LATENCY_SIGMA * random.nextGaussian())));
    }

    private long jitter(long ms) {
        return ms == 0 ? 0 : Math.round(ms * (0.6 + 0.8 * random.nextDouble()));
    }

    private int poisson(double mean) {
        double l = Math.exp(-mean);
        int k = 0;
        double p = 1;
        if (mean > 30) { // normal approximation for large means
            return Math.max(0, (int) Math.round(mean + Math.sqrt(mean) * random.nextGaussian()));
        }
        do {
            k++;
            p *= random.nextDouble();
        } while (p > l);
        return k - 1;
    }

    private String newId(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HEX.formatHex(b);
    }
}
