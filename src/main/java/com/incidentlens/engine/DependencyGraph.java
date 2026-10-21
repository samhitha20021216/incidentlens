package com.incidentlens.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.incidentlens.model.TelemetryEvent;

/**
 * Service call graph inferred from telemetry: parent span → child span gives "caller → callee";
 * {@code peerService} on a log line gives edges to uninstrumented components such as databases.
 *
 * <p>Children usually finish (and log) before their parent, so unresolved parent ids are parked until the
 * parent's own log line arrives.
 */
public final class DependencyGraph {

    public record Edge(String from, String to, long calls) {
    }

    private static final int MAX_SPANS = 200_000;

    private final Map<String, Map<String, Long>> edges = new TreeMap<>();
    private final Set<String> instrumented = new TreeSet<>();
    private final Map<String, String> spanService = lru(MAX_SPANS);
    private final Map<String, List<String>> waitingChildren = lru(MAX_SPANS);

    void observe(TelemetryEvent e) {
        instrumented.add(e.service());
        if (e.spanId() != null) {
            spanService.put(e.spanId(), e.service());
            List<String> children = waitingChildren.remove(e.spanId());
            if (children != null) {
                children.forEach(child -> addEdge(e.service(), child));
            }
        }
        if (e.parentSpanId() != null) {
            String parent = spanService.get(e.parentSpanId());
            if (parent != null) {
                addEdge(parent, e.service());
            } else {
                waitingChildren.computeIfAbsent(e.parentSpanId(), k -> new ArrayList<>(2)).add(e.service());
            }
        }
        if (e.peerService() != null && !e.peerService().equals(e.service())) {
            addEdge(e.service(), e.peerService());
        }
    }

    private void addEdge(String from, String to) {
        if (!from.equals(to)) {
            edges.computeIfAbsent(from, k -> new TreeMap<>()).merge(to, 1L, Long::sum);
        }
    }

    public boolean isInstrumented(String component) {
        return instrumented.contains(component);
    }

    public Set<String> downstream(String service) {
        return edges.getOrDefault(service, Map.of()).keySet();
    }

    public Set<String> callers(String service) {
        Set<String> result = new TreeSet<>();
        edges.forEach((from, tos) -> {
            if (tos.containsKey(service)) {
                result.add(from);
            }
        });
        return result;
    }

    /** Every service that (transitively) calls {@code service}. */
    public Set<String> upstream(String service) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(callers(service));
        while (!queue.isEmpty()) {
            String s = queue.poll();
            if (seen.add(s)) {
                queue.addAll(callers(s));
            }
        }
        seen.remove(service);
        return seen;
    }

    /** True if a and b are linked by a call path of at most {@code maxHops} edges in either direction. */
    public boolean connected(String a, String b, int maxHops) {
        if (a.equals(b)) {
            return true;
        }
        Set<String> seen = new HashSet<>(Set.of(a));
        Set<String> frontier = Set.of(a);
        for (int hop = 0; hop < maxHops && !frontier.isEmpty(); hop++) {
            Set<String> next = new HashSet<>();
            for (String s : frontier) {
                Set<String> neighbours = new HashSet<>(downstream(s));
                neighbours.addAll(callers(s));
                for (String n : neighbours) {
                    if (n.equals(b)) {
                        return true;
                    }
                    if (seen.add(n)) {
                        next.add(n);
                    }
                }
            }
            frontier = next;
        }
        return false;
    }

    public List<Edge> edges() {
        List<Edge> result = new ArrayList<>();
        edges.forEach((from, tos) -> tos.forEach((to, n) -> result.add(new Edge(from, to, n))));
        return result;
    }

    public Set<String> instrumentedServices() {
        return Set.copyOf(instrumented);
    }

    private static <K, V> Map<K, V> lru(int max) {
        return new LinkedHashMap<>(1024, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > max;
            }
        };
    }
}
