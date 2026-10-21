package com.incidentlens.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.incidentlens.ai.InvestigationService;
import com.incidentlens.engine.EngineService;
import com.incidentlens.engine.IncidentReport;
import com.incidentlens.engine.IncidentSummary;
import com.incidentlens.store.IncidentStore;
import com.incidentlens.store.IncidentStore.Investigation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    public record AskRequest(@NotBlank @Size(max = 1000) String question) {
    }

    private final IncidentStore store;
    private final EngineService engine;
    private final InvestigationService investigations;

    public IncidentController(IncidentStore store, EngineService engine, InvestigationService investigations) {
        this.store = store;
        this.engine = engine;
        this.investigations = investigations;
    }

    @GetMapping
    public List<IncidentSummary> list(@RequestParam(defaultValue = "50") int limit) {
        return store.list(Math.clamp(limit, 1, 200));
    }

    /** Full deterministic analysis: ranked candidates with evidence, timeline, error groups, graph. */
    @GetMapping("/{id}")
    public IncidentReport get(@PathVariable String id) {
        return engine.report(id).orElseThrow(() -> new InvestigationService.IncidentNotFoundException(id));
    }

    @PostMapping("/{id}/investigate")
    public Investigation investigate(@PathVariable String id) {
        return investigations.investigate(id);
    }

    @PostMapping("/{id}/ask")
    public Investigation ask(@PathVariable String id, @Valid @RequestBody AskRequest request) {
        return investigations.ask(id, request.question());
    }

    @GetMapping("/{id}/investigations")
    public List<Investigation> history(@PathVariable String id) {
        return investigations.history(id);
    }
}
