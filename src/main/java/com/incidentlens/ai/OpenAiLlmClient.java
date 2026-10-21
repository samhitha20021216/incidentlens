package com.incidentlens.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** OpenAI-compatible Chat Completions over HTTP (also works for local servers exposing that API). */
public class OpenAiLlmClient implements LlmClient {

    private final RestClient http;
    private final String model;

    public OpenAiLlmClient(RestClient.Builder builder, String baseUrl, String apiKey, String model) {
        this.http = builder.baseUrl(baseUrl).defaultHeader("Authorization", "Bearer " + apiKey).build();
        this.model = model;
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Response complete(Request request) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", request.system()));
        for (Turn t : request.turns()) {
            messages.add(Map.of("role", t.role() == Role.USER ? "user" : "assistant", "content", t.text()));
        }
        try {
            Map<String, Object> body = http.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "messages", messages, "max_completion_tokens", request.maxTokens()))
                    .retrieve().body(Map.class);
            Map<String, Object> choice = ((List<Map<String, Object>>) body.get("choices")).getFirst();
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            Map<String, Object> usage = (Map<String, Object>) body.getOrDefault("usage", Map.of());
            Object refusal = message.get("refusal");
            return new Response(refusal != null ? "" : String.valueOf(message.get("content")), String.valueOf(body.get("model")),
                    ((Number) usage.getOrDefault("prompt_tokens", 0)).longValue(),
                    ((Number) usage.getOrDefault("completion_tokens", 0)).longValue(), refusal != null);
        } catch (RestClientException e) {
            throw new LlmUnavailableException("OpenAI API error: " + e.getMessage(), e);
        }
    }
}
