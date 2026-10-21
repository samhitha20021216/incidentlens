package com.incidentlens;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.incidentlens.engine.EngineService;
import com.incidentlens.model.TelemetryEvent;
import com.incidentlens.simulator.ScenarioCatalog;
import com.incidentlens.simulator.TrafficSimulator;
import com.jayway.jsonpath.JsonPath;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full pipeline: HTTP ingest -> Kafka -> analyzer -> Postgres -> REST API -> (mock) LLM investigation,
 * running against real Postgres and Kafka in-process.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 6, topics = {"telemetry.events", "telemetry.changes"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class EndToEndTest {

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    EngineService engine;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Test
    void detectsRanksAndExplainsACascadingFailure() throws Exception {
        long sent = ingestScenario("payment-db-saturation");
        await().atMost(Duration.ofSeconds(60)).until(() -> engine.eventsIngested() >= sent);
        engine.tick();

        String incidents = mvc.perform(get("/api/incidents")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(incidents, "$")).hasSize(1);
        assertThat((String) JsonPath.read(incidents, "$[0].likelyRootCause")).isEqualTo("payment-service");
        String id = JsonPath.read(incidents, "$[0].id");

        mvc.perform(get("/api/incidents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].service").value("payment-service"))
                .andExpect(jsonPath("$.candidates[0].suspectedDependency").value("payment-db"))
                .andExpect(jsonPath("$.candidates[0].evidence[0].id").exists());

        // AI investigation: grounded in the evidence pack and passing validation.
        String investigation = mvc.perform(post("/api/incidents/{id}/investigate", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(investigation, "$.answer")).contains("payment-service", "## Summary", "[E1]");
        assertThat((List<?>) JsonPath.read(investigation, "$.warnings")).isEmpty();
        assertThat((Integer) JsonPath.read(investigation, "$.packChars")).isBetween(500, 20_000);

        mvc.perform(post("/api/incidents/{id}/ask", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"Was anything deployed before this started?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("QA"));
        mvc.perform(get("/api/incidents/{id}/investigations", id))
                .andExpect(jsonPath("$.length()").value(2));

        // Drill down from an error signature to the raw log lines.
        String groups = mvc.perform(get("/api/error-groups").param("service", "payment-service"))
                .andReturn().getResponse().getContentAsString();
        String fingerprint = JsonPath.read(groups, "$[0].fingerprint");
        mvc.perform(get("/api/logs").param("fingerprint", fingerprint).param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].service").value("payment-service"));

        mvc.perform(get("/api/graph")).andExpect(jsonPath("$[?(@.from == 'payment-service' && @.to == 'payment-db')]").exists());
    }

    @Test
    void rejectsMalformedEventsAndUnknownIncidents() throws Exception {
        mvc.perform(post("/api/ingest/events").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"message\": \"no service or timestamp\"}]"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/incidents/INC-9999")).andExpect(status().isNotFound());
        mvc.perform(post("/api/simulator/scenarios/payment-db-saturation")).andExpect(status().isConflict());
    }

    /** 12 min healthy + 4 min fault + 4 min recovery at 8 req/s, posted in batches through the HTTP API. */
    private long ingestScenario(String name) throws Exception {
        TrafficSimulator sim = new TrafficSimulator(42, 8);
        Instant t0 = Instant.parse("2026-03-02T10:00:00Z");
        Instant faultStart = t0.plus(Duration.ofMinutes(12));
        sim.schedule(ScenarioCatalog.byName(name).orElseThrow(), faultStart, Duration.ofMinutes(4));
        long sent = 0;
        List<TelemetryEvent> batch = new ArrayList<>();
        for (Instant t = t0; t.isBefore(faultStart.plus(Duration.ofMinutes(8))); t = t.plusSeconds(1)) {
            batch.addAll(sim.tick(t, t.plusSeconds(1)).telemetry());
            if (batch.size() >= 2000) {
                sent += send(batch);
                batch.clear();
            }
        }
        return sent + send(batch);
    }

    private int send(List<TelemetryEvent> batch) throws Exception {
        mvc.perform(post("/api/ingest/events").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(batch)))
                .andExpect(status().isAccepted());
        return batch.size();
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
