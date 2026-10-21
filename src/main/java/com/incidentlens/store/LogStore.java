package com.incidentlens.store;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.incidentlens.config.Json;
import com.incidentlens.model.ChangeEvent;
import com.incidentlens.model.TelemetryEvent;

@Repository
public class LogStore {

    public record LogRow(long id, Instant ts, String service, String level, String message, String traceId,
                         String spanId, String parentSpanId, String endpoint, Integer status, Long latencyMs,
                         String peerService, String exceptionType, String fingerprint) {
    }

    public record Fingerprinted(TelemetryEvent event, String fingerprint) {
    }

    public record LogQuery(String service, String traceId, String fingerprint, String level, Instant from, Instant to,
                           int limit) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbc;
    private final Json json;

    public LogStore(JdbcTemplate jdbcTemplate, JdbcClient jdbc, Json json) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbc = jdbc;
        this.json = json;
    }

    public void saveBatch(List<Fingerprinted> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(rows.size());
        for (Fingerprinted f : rows) {
            TelemetryEvent e = f.event();
            args.add(new Object[]{Timestamp.from(e.timestamp()), e.service(), e.level().name(), e.message(), e.traceId(),
                    e.spanId(), e.parentSpanId(), e.endpoint(), e.status(), e.latencyMs(), e.peerService(),
                    e.exceptionType(), f.fingerprint(), e.stackFrames().isEmpty() ? null : json.write(e.stackFrames())});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO log_events (ts, service, level, message, trace_id, span_id, parent_span_id, endpoint, status,
                                        latency_ms, peer_service, exception_type, fingerprint, stack)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, args);
    }

    public void saveChange(ChangeEvent c) {
        jdbc.sql("INSERT INTO change_events (ts, service, type, description) VALUES (:ts, :service, :type, :d)")
                .param("ts", Timestamp.from(c.timestamp()))
                .param("service", c.service())
                .param("type", c.type().name())
                .param("d", c.description())
                .update();
    }

    public List<LogRow> query(LogQuery q) {
        return jdbc.sql("""
                        SELECT * FROM log_events
                        WHERE (CAST(:service AS varchar) IS NULL OR service = :service)
                          AND (CAST(:trace AS varchar) IS NULL OR trace_id = :trace)
                          AND (CAST(:fp AS varchar) IS NULL OR fingerprint = :fp)
                          AND (CAST(:level AS varchar) IS NULL OR level = :level)
                          AND (CAST(:from AS timestamptz) IS NULL OR ts >= :from)
                          AND (CAST(:to AS timestamptz) IS NULL OR ts <= :to)
                        ORDER BY ts DESC
                        LIMIT :limit
                        """)
                .param("service", q.service())
                .param("trace", q.traceId())
                .param("fp", q.fingerprint())
                .param("level", q.level())
                .param("from", q.from() == null ? null : Timestamp.from(q.from()))
                .param("to", q.to() == null ? null : Timestamp.from(q.to()))
                .param("limit", Math.clamp(q.limit(), 1, 1000))
                .query((rs, i) -> new LogRow(rs.getLong("id"), rs.getObject("ts", OffsetDateTime.class).toInstant(),
                        rs.getString("service"), rs.getString("level"), rs.getString("message"), rs.getString("trace_id"),
                        rs.getString("span_id"), rs.getString("parent_span_id"), rs.getString("endpoint"),
                        (Integer) rs.getObject("status"), (Long) rs.getObject("latency_ms"), rs.getString("peer_service"),
                        rs.getString("exception_type"), rs.getString("fingerprint")))
                .list();
    }

    public int deleteOlderThan(Duration age) {
        return jdbc.sql("DELETE FROM log_events WHERE ts < :cutoff")
                .param("cutoff", Timestamp.from(Instant.now().minus(age)))
                .update();
    }
}
