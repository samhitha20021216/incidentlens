package com.incidentlens.ai;

import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;

/**
 * Claude via the official Anthropic Java SDK. Uses server-side refusal fallbacks so a request declined by
 * a safety classifier is retried on a suitable model instead of failing outright.
 */
public class AnthropicLlmClient implements LlmClient {

    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final String model;

    public AnthropicLlmClient(String apiKey, String model) {
        this.client = apiKey == null || apiKey.isBlank()
                ? AnthropicOkHttpClient.fromEnv()
                : AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        this.model = model;
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public Response complete(Request request) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(request.maxTokens())
                .system(request.system())
                // Summarising a bounded evidence pack doesn't need deep thinking; medium keeps latency low.
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
                .addBeta(FALLBACK_BETA)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        for (Turn t : request.turns()) {
            if (t.role() == Role.USER) {
                params.addUserMessage(t.text());
            } else {
                params.addAssistantMessage(t.text());
            }
        }
        try {
            BetaMessage message = client.beta().messages().create(params.build());
            boolean refused = message.stopReason().map(BetaStopReason.REFUSAL::equals).orElse(false);
            String text = message.content().stream()
                    .flatMap(b -> b.text().stream())
                    .map(t -> t.text())
                    .collect(Collectors.joining("\n"));
            return new Response(refused ? "" : text, message.model().asString(), message.usage().inputTokens(),
                    message.usage().outputTokens(), refused);
        } catch (RateLimitException e) {
            throw new LlmUnavailableException("Claude API rate limit reached; try again shortly", e);
        } catch (AnthropicServiceException e) {
            throw new LlmUnavailableException("Claude API error (HTTP " + e.statusCode() + ")", e);
        } catch (AnthropicIoException e) {
            throw new LlmUnavailableException("Could not reach the Claude API", e);
        }
    }
}
