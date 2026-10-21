package com.incidentlens.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.incidentlens.engine.DependencyGraph;
import com.incidentlens.engine.EngineService;
import com.incidentlens.engine.ErrorGroupView;
import com.incidentlens.engine.ServiceHealth;
import com.incidentlens.simulator.SimulatorRunner;
import com.incidentlens.store.LogStore;

@RestController
@RequestMapping("/api")
public class ObservabilityController {

    private final EngineService engine;
    private final LogStore logs;
    private final SimulatorRunner simulator;

    public ObservabilityController(EngineService engine, LogStore logs, SimulatorRunner simulator) {
        this.engine = engine;
        this.logs = logs;
        this.simulator = simulator;
    }

    @GetMapping("/services")
    public List<ServiceHealth> services() {
        return engine.health();
    }

    @GetMapping("/graph")
    public List<DependencyGraph.Edge> graph() {
        return engine.graph();
    }

    @GetMapping("/error-groups")
    public List<ErrorGroupView> errorGroups(@RequestParam(required = false) String service,
                                            @RequestParam(defaultValue = "50") int limit) {
        return engine.errorGroups(service, Math.clamp(limit, 1, 500));
    }

    @GetMapping("/logs")
    public List<LogStore.LogRow> logs(@RequestParam(required = false) String service,
                                      @RequestParam(required = false) String traceId,
                                      @RequestParam(required = false) String fingerprint,
                                      @RequestParam(required = false) String level,
                                      @RequestParam(required = false) Instant from,
                                      @RequestParam(required = false) Instant to,
                                      @RequestParam(defaultValue = "100") int limit) {
        return logs.query(new LogStore.LogQuery(service, traceId, fingerprint, level, from, to, limit));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("eventsIngested", engine.eventsIngested(), "lateEvents", engine.lateEvents(),
                "baselineReady", engine.baselineReady());
    }

    @GetMapping("/simulator")
    public SimulatorRunner.Status simulator() {
        return simulator.status();
    }

    @PostMapping("/simulator/scenarios/{name}")
    public SimulatorRunner.Status inject(@PathVariable String name, @RequestParam(defaultValue = "5") int minutes) {
        return simulator.start(name, Duration.ofMinutes(Math.clamp(minutes, 1, 30)));
    }

    @PostMapping("/simulator/stop")
    public SimulatorRunner.Status stop() {
        return simulator.stopFaults();
    }
}
