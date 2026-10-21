package com.incidentlens.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.web.client.RestClient;

import com.incidentlens.ai.AnthropicLlmClient;
import com.incidentlens.ai.LlmClient;
import com.incidentlens.ai.MockLlmClient;
import com.incidentlens.ai.OpenAiLlmClient;
import com.incidentlens.engine.EngineConfig;

@Configuration
public class AppConfig {

    @Bean
    EngineConfig engineConfig() {
        return EngineConfig.defaults();
    }

    @Bean
    NewTopic eventsTopic(IncidentLensProperties props) {
        return TopicBuilder.name(props.topics().events()).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic changesTopic(IncidentLensProperties props) {
        return TopicBuilder.name(props.topics().changes()).partitions(1).replicas(1).build();
    }

    @Bean
    LlmClient llmClient(IncidentLensProperties props, Json json, RestClient.Builder rest) {
        IncidentLensProperties.Llm llm = props.llm();
        return switch (llm.provider().toLowerCase()) {
            case "anthropic", "claude" -> new AnthropicLlmClient(llm.apiKey(), llm.model());
            case "openai" -> new OpenAiLlmClient(rest, llm.baseUrl(), llm.apiKey(), llm.model());
            case "mock" -> new MockLlmClient(json.mapper());
            default -> throw new IllegalArgumentException("Unknown LLM provider '" + llm.provider()
                    + "' (expected mock, anthropic or openai)");
        };
    }
}
