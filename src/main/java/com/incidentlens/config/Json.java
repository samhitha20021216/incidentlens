package com.incidentlens.config;

import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class Json {

    private final JsonMapper mapper;

    public Json(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    public <T> T read(String json, Class<T> type) {
        return json == null ? null : mapper.readValue(json, type);
    }

    public <T> T read(String json, TypeReference<T> type) {
        return json == null ? null : mapper.readValue(json, type);
    }

    public JsonMapper mapper() {
        return mapper;
    }
}
