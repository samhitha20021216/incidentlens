package com.incidentlens.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "incidentlens")
public record IncidentLensProperties(
        @DefaultValue Engine engine,
        @DefaultValue Simulator simulator,
        @DefaultValue Llm llm,
        @DefaultValue Topics topics,
        @DefaultValue("24h") Duration logRetention) {

    /**
     * WALL: buckets close on the wall clock (live traffic). EVENT: buckets close as event time advances
     * (replays and tests, where timestamps are not "now").
     */
    public enum Clock { WALL, EVENT }

    public record Engine(@DefaultValue("WALL") Clock clock) {
    }

    public record Simulator(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("15") double requestsPerSecond,
            @DefaultValue("0.1") double noiseBurstsPerMinute) {
    }

    public record Llm(
            @DefaultValue("mock") String provider,
            @DefaultValue("claude-opus-5-5") String model,
            String apiKey,
            @DefaultValue("https://api.openai.com") String baseUrl,
            @DefaultValue("16000") int maxTokens) {
    }

    public record Topics(
            @DefaultValue("telemetry.events") String events,
            @DefaultValue("telemetry.changes") String changes) {
    }
}
