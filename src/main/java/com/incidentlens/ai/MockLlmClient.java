package com.incidentlens.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deterministic stand-in for an LLM so the whole product runs, demos and tests without an API key. It reads
 * the same evidence pack a real model receives and writes a templated answer that follows the same rules
 * (sections, citations), which keeps the validation path honest.
 */
public class MockLlmClient implements LlmClient {

    private final JsonMapper json;

    public MockLlmClient(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public Response complete(Request request) {
        Map<String, Object> pack = extractPack(request);
        String lastQuestion = request.turns().getLast().text();
        String text = request.system().contains("## Summary") ? summary(pack) : answer(pack, lastQuestion);
        return new Response(text, "mock-llm (deterministic template)", 0, 0, false);
    }

    @SuppressWarnings("unchecked")
    private String summary(Map<String, Object> pack) {
        Map<String, Object> incident = (Map<String, Object>) pack.get("incident");
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) pack.get("rankedRootCauseCandidates");
        Map<String, Object> top = candidates.getFirst();
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) top.get("evidence");
        String dep = (String) top.get("suspectedDependency");
        String confidence = (String) incident.get("rankingConfidence");

        StringBuilder sb = new StringBuilder();
        sb.append("## Summary\n").append("Since ").append(incident.get("startedAt")).append(", ")
                .append(String.join(", ", (List<String>) incident.get("affectedServices")))
                .append(" have been degraded. The evidence points to **").append(top.get("service")).append("**")
                .append(dep == null ? "" : " and its dependency **" + dep + "**").append(" ").append(cite(evidence, 2))
                .append(". Ranking confidence is ").append(confidence.toLowerCase(Locale.ROOT)).append(".\n\n");
        sb.append("## Likely root cause\n").append(top.get("service"))
                .append(dep == null ? "" : " (failing calls to " + dep + ")").append(" ").append(cite(evidence, 1)).append("\n\n");
        sb.append("## Supporting evidence\n");
        for (Map<String, Object> e : evidence) {
            sb.append("- ").append(e.get("text")).append(" [").append(e.get("id")).append("]\n");
        }
        sb.append("\n## Impact\n");
        for (String s : (List<String>) incident.get("affectedServices")) {
            sb.append("- ").append(s).append(" is affected").append(timelineCite(pack, s)).append('\n');
        }
        sb.append("\n## Suggested next steps\n");
        if (dep != null) {
            sb.append("- Check the health, saturation and connection limits of ").append(dep).append(".\n");
        }
        if (evidence.stream().anyMatch(e -> "Changed right before".equals(e.get("factor")))) {
            sb.append("- Consider rolling back the recent change to ").append(top.get("service")).append(' ')
                    .append(citeFactor(evidence, "Changed right before")).append(".\n");
        }
        sb.append("- Open the top error signature for ").append(top.get("service"))
                .append(" in the log explorer and inspect a few full traces.\n");
        sb.append("- Confirm that callers recover once ").append(top.get("service")).append(" is healthy again.\n");
        sb.append("\n## Caveats\n");
        if (candidates.size() > 1) {
            Map<String, Object> second = candidates.get(1);
            sb.append("- The runner-up hypothesis is ").append(second.get("service")).append(" (score ")
                    .append(second.get("score")).append(" vs ").append(top.get("score")).append(").\n");
        }
        sb.append("- This summary was written by the deterministic mock model; configure a real LLM provider for richer analysis.\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String answer(Map<String, Object> pack, String question) {
        String q = question.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) pack.get("rankedRootCauseCandidates");
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) pack.get("timeline");
        Map<String, Object> top = candidates.getFirst();
        if (q.contains("deploy") || q.contains("change") || q.contains("release")) {
            List<String> lines = new ArrayList<>();
            for (Map<String, Object> t : timeline) {
                if ("CHANGE".equals(t.get("kind"))) {
                    lines.add("- " + t.get("at") + ": " + t.get("text") + " [" + t.get("id") + "]");
                }
            }
            return lines.isEmpty() ? "No deploys or config changes were recorded in the 15 minutes before the incident."
                    : "Changes recorded around the incident:\n" + String.join("\n", lines);
        }
        for (Map<String, Object> c : candidates) {
            if (q.contains(((String) c.get("service")).toLowerCase(Locale.ROOT)) && c != top) {
                return c.get("service") + " ranks #" + c.get("rank") + " with score " + c.get("score") + ":\n"
                        + bullets((List<Map<String, Object>>) c.get("evidence"));
            }
        }
        return "The strongest hypothesis is " + top.get("service") + " (score " + top.get("score") + "):\n"
                + bullets((List<Map<String, Object>>) top.get("evidence"));
    }

    private static String bullets(List<Map<String, Object>> evidence) {
        StringBuilder sb = new StringBuilder();
        evidence.forEach(e -> sb.append("- ").append(e.get("text")).append(" [").append(e.get("id")).append("]\n"));
        return sb.toString();
    }

    private static String cite(List<Map<String, Object>> evidence, int n) {
        StringBuilder sb = new StringBuilder();
        evidence.stream().limit(n).forEach(e -> sb.append('[').append(e.get("id")).append(']'));
        return sb.toString();
    }

    private static String citeFactor(List<Map<String, Object>> evidence, String factor) {
        return evidence.stream().filter(e -> factor.equals(e.get("factor"))).findFirst()
                .map(e -> "[" + e.get("id") + "]").orElse("");
    }

    @SuppressWarnings("unchecked")
    private static String timelineCite(Map<String, Object> pack, String service) {
        for (Map<String, Object> t : (List<Map<String, Object>>) pack.get("timeline")) {
            if ("ANOMALY".equals(t.get("kind")) && ((String) t.get("text")).startsWith(service)) {
                return ": " + t.get("text") + " [" + t.get("id") + "]";
            }
        }
        return "";
    }

    private Map<String, Object> extractPack(Request request) {
        for (Turn t : request.turns()) {
            int start = t.text().indexOf("<evidence>");
            int end = t.text().indexOf("</evidence>");
            if (start >= 0 && end > start) {
                return json.readValue(t.text().substring(start + "<evidence>".length(), end), new TypeReference<>() {
                });
            }
        }
        throw new IllegalArgumentException("No evidence pack in request");
    }
}
