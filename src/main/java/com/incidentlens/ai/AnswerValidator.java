package com.incidentlens.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cheap, deterministic checks on model output. The LLM is a narrator, not the source of truth, so
 * anything it says that cannot be traced back to the evidence pack gets flagged in the UI.
 */
public final class AnswerValidator {

    public record Result(List<String> citations, List<String> warnings) {
        public boolean ok() {
            return warnings.isEmpty();
        }
    }

    private static final Pattern CITATION = Pattern.compile("\\[([ETG]\\d+)]");
    private static final Pattern COMPONENT = Pattern.compile(
            "\\b[a-z][a-z0-9]*(?:-[a-z0-9]+)*-(?:service|db|gateway|provider|network|api|queue|cache)\\b");

    private AnswerValidator() {
    }

    public static Result validate(String answer, EvidencePack pack, String topCandidate, boolean expectRootCause) {
        Set<String> cited = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            cited.add(m.group(1));
        }
        if (cited.isEmpty()) {
            warnings.add("The answer cites no evidence; treat it as unverified.");
        }
        for (String id : cited) {
            if (!pack.citableIds().contains(id)) {
                warnings.add("Cites [" + id + "], which does not exist in the evidence pack.");
            }
        }
        Set<String> unknown = new LinkedHashSet<>();
        Matcher c = COMPONENT.matcher(answer);
        while (c.find()) {
            if (!pack.knownComponents().contains(c.group())) {
                unknown.add(c.group());
            }
        }
        unknown.forEach(u -> warnings.add("Mentions '" + u + "', which does not appear in the telemetry."));
        if (expectRootCause && topCandidate != null && !answer.contains(topCandidate)) {
            warnings.add("Does not discuss the top-ranked candidate (" + topCandidate + "); compare with the ranking.");
        }
        return new Result(List.copyOf(cited), warnings);
    }
}
