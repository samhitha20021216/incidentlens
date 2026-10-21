package com.incidentlens.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.incidentlens.engine.ErrorGroupView;
import com.incidentlens.engine.Evidence;
import com.incidentlens.engine.IncidentReport;
import com.incidentlens.engine.RootCauseCandidate;
import com.incidentlens.engine.TimelineEntry;

/**
 * The bounded, citable slice of an incident the LLM is allowed to see. Instead of thousands of raw log
 * lines, the model gets the ranked hypotheses with their evidence (E-ids), a timeline (T-ids) and the top
 * error signatures (G-ids). Typically 1-3k tokens regardless of traffic volume.
 */
public record EvidencePack(Map<String, Object> content, Set<String> citableIds, Set<String> knownComponents) {

    static final int MAX_CANDIDATES = 3;
    static final int MAX_TIMELINE = 25;
    static final int MAX_ERRORS = 6;

    public static EvidencePack from(IncidentReport r) {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> components = new LinkedHashSet<>(r.affectedServices());
        r.graph().forEach(e -> {
            components.add(e.from());
            components.add(e.to());
        });

        Map<String, Object> incident = new LinkedHashMap<>();
        incident.put("id", r.id());
        incident.put("status", r.status().name());
        incident.put("startedAt", String.valueOf(r.startedAt()));
        incident.put("detectedAt", String.valueOf(r.detectedAt()));
        incident.put("resolvedAt", r.resolvedAt() == null ? null : r.resolvedAt().toString());
        incident.put("affectedServices", r.affectedServices());
        incident.put("rankingConfidence", r.confidence().name());

        List<Map<String, Object>> candidates = new ArrayList<>();
        int rank = 0;
        for (RootCauseCandidate c : r.candidates().subList(0, Math.min(MAX_CANDIDATES, r.candidates().size()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", ++rank);
            m.put("service", c.service());
            m.put("score", c.score());
            m.put("suspectedDependency", c.suspectedDependency());
            List<Map<String, Object>> ev = new ArrayList<>();
            for (Evidence e : c.evidence()) {
                ids.add(e.id());
                ev.add(Map.of("id", e.id(), "factor", e.factor().label(), "text", e.text()));
            }
            m.put("evidence", ev);
            candidates.add(m);
        }

        List<Map<String, Object>> timeline = new ArrayList<>();
        int t = 0;
        for (TimelineEntry e : r.timeline()) {
            if (timeline.size() >= MAX_TIMELINE) {
                break;
            }
            String id = "T" + (++t);
            ids.add(id);
            timeline.add(Map.of("id", id, "at", e.at().toString(), "kind", e.kind().name(), "text", e.text()));
        }

        List<Map<String, Object>> errors = new ArrayList<>();
        int g = 0;
        for (ErrorGroupView e : r.errorGroups().subList(0, Math.min(MAX_ERRORS, r.errorGroups().size()))) {
            String id = "G" + (++g);
            ids.add(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("service", e.service());
            m.put("exception", e.exceptionType());
            m.put("message", e.template());
            m.put("occurrencesDuringIncident", e.countInWindow());
            m.put("involves", e.peerService());
            m.put("newSignature", e.isNew());
            errors.add(m);
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("incident", incident);
        content.put("rankedRootCauseCandidates", candidates);
        content.put("timeline", timeline);
        content.put("topErrorSignatures", errors);
        return new EvidencePack(content, ids, components);
    }
}
