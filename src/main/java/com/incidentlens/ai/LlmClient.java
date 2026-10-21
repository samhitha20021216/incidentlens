package com.incidentlens.ai;

import java.util.List;

/** Minimal provider-neutral chat interface; the investigation layer never sees provider SDK types. */
public interface LlmClient {

    enum Role { USER, ASSISTANT }

    record Turn(Role role, String text) {
    }

    record Request(String system, List<Turn> turns, int maxTokens) {
    }

    /** @param refused the provider's safety system declined to answer */
    record Response(String text, String model, long inputTokens, long outputTokens, boolean refused) {
    }

    String provider();

    Response complete(Request request);
}
