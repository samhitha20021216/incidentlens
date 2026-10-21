package com.incidentlens.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.incidentlens.config.Json;
import com.incidentlens.engine.IncidentReport;
import com.incidentlens.engine.IncidentSummary;

import tools.jackson.core.type.TypeReference;

@Repository
public class IncidentStore {

    public record Investigation(long id, String incidentId, String kind, String question, String answer, String provider,
                                String model, Long inputTokens, Long outputTokens, Integer packChars, boolean refused,
                                List<String> citations, List<String> warnings, Instant createdAt) {
    }

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final Json json;

    public IncidentStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void upsert(IncidentReport r) {
        jdbc.sql("""
                        INSERT INTO incidents (id, status, title, started_at, detected_at, resolved_at, likely_cause,
                                               confidence, report, updated_at)
                        VALUES (:id, :status, :title, :started, :detected, :resolved, :cause, :confidence,
                                CAST(:report AS jsonb), now())
                        ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, title = EXCLUDED.title,
                            started_at = EXCLUDED.started_at, resolved_at = EXCLUDED.resolved_at,
                            likely_cause = EXCLUDED.likely_cause, confidence = EXCLUDED.confidence,
                            report = EXCLUDED.report, updated_at = now()
                        """)
                .param("id", r.id())
                .param("status", r.status().name())
                .param("title", r.title())
                .param("started", Timestamp.from(r.startedAt()))
                .param("detected", Timestamp.from(r.detectedAt()))
                .param("resolved", r.resolvedAt() == null ? null : Timestamp.from(r.resolvedAt()))
                .param("cause", r.topCandidate() == null ? null : r.topCandidate().service())
                .param("confidence", r.confidence().name())
                .param("report", json.write(r))
                .update();
    }

    public void delete(String id) {
        jdbc.sql("DELETE FROM incidents WHERE id = :id").param("id", id).update();
    }

    public Optional<IncidentReport> report(String id) {
        return jdbc.sql("SELECT report FROM incidents WHERE id = :id").param("id", id)
                .query((rs, i) -> json.read(rs.getString("report"), IncidentReport.class)).optional();
    }

    public List<IncidentSummary> list(int limit) {
        return jdbc.sql("SELECT report FROM incidents ORDER BY detected_at DESC LIMIT :limit")
                .param("limit", limit)
                .query((rs, i) -> json.read(rs.getString("report"), IncidentReport.class))
                .list().stream()
                .map(r -> new IncidentSummary(r.id(), r.status(), r.title(), r.startedAt(), r.detectedAt(),
                        r.resolvedAt(), r.affectedServices(),
                        r.topCandidate() == null ? null : r.topCandidate().service(), r.confidence()))
                .toList();
    }

    public Investigation saveInvestigation(String incidentId, String kind, String question, String answer,
                                           String provider, String model, long in, long out, int packChars,
                                           boolean refused, List<String> citations, List<String> warnings) {
        long id = jdbc.sql("""
                        INSERT INTO investigations (incident_id, kind, question, answer, provider, model, input_tokens,
                                                    output_tokens, pack_chars, refused, citations, warnings)
                        VALUES (:incident, :kind, :question, :answer, :provider, :model, :in, :out, :chars, :refused,
                                CAST(:citations AS jsonb), CAST(:warnings AS jsonb))
                        RETURNING id
                        """)
                .param("incident", incidentId)
                .param("kind", kind)
                .param("question", question)
                .param("answer", answer)
                .param("provider", provider)
                .param("model", model)
                .param("in", in)
                .param("out", out)
                .param("chars", packChars)
                .param("refused", refused)
                .param("citations", json.write(citations))
                .param("warnings", json.write(warnings))
                .query(Long.class).single();
        return investigations(incidentId).stream().filter(i -> i.id() == id).findFirst().orElseThrow();
    }

    public List<Investigation> investigations(String incidentId) {
        return jdbc.sql("SELECT * FROM investigations WHERE incident_id = :id ORDER BY id")
                .param("id", incidentId)
                .query((rs, i) -> new Investigation(rs.getLong("id"), rs.getString("incident_id"), rs.getString("kind"),
                        rs.getString("question"), rs.getString("answer"), rs.getString("provider"), rs.getString("model"),
                        (Long) rs.getObject("input_tokens"), (Long) rs.getObject("output_tokens"),
                        (Integer) rs.getObject("pack_chars"), rs.getBoolean("refused"),
                        json.read(rs.getString("citations"), STRINGS), json.read(rs.getString("warnings"), STRINGS),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
