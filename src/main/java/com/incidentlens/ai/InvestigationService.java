package com.incidentlens.ai;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.incidentlens.config.IncidentLensProperties;
import com.incidentlens.config.Json;
import com.incidentlens.engine.EngineService;
import com.incidentlens.engine.IncidentReport;
import com.incidentlens.store.IncidentStore;
import com.incidentlens.store.IncidentStore.Investigation;

/**
 * The "AI investigation assistant": turns the deterministic report into a narrative and answers follow-up
 * questions. The model only ever sees the evidence pack, and every answer is validated before it is shown.
 */
@Service
public class InvestigationService {

    public static class IncidentNotFoundException extends RuntimeException {
        public IncidentNotFoundException(String id) {
            super("Incident " + id + " not found");
        }
    }

    private static final int MAX_HISTORY = 3;

    private final EngineService engine;
    private final IncidentStore store;
    private final LlmClient llm;
    private final Json json;
    private final IncidentLensProperties props;

    public InvestigationService(EngineService engine, IncidentStore store, LlmClient llm, Json json,
                                IncidentLensProperties props) {
        this.engine = engine;
        this.store = store;
        this.llm = llm;
        this.json = json;
        this.props = props;
    }

    public Investigation investigate(String incidentId) {
        IncidentReport report = report(incidentId);
        EvidencePack pack = EvidencePack.from(report);
        String packJson = json.write(pack.content());
        LlmClient.Response r = llm.complete(new LlmClient.Request(Prompts.INVESTIGATE_SYSTEM,
                List.of(new LlmClient.Turn(LlmClient.Role.USER, Prompts.evidenceBlock(packJson)
                        + "\n\nWrite the investigation summary for this incident.")),
                props.llm().maxTokens()));
        return save(incidentId, "SUMMARY", null, r, pack, packJson, report, true);
    }

    public Investigation ask(String incidentId, String question) {
        IncidentReport report = report(incidentId);
        EvidencePack pack = EvidencePack.from(report);
        String packJson = json.write(pack.content());

        List<Investigation> previous = store.investigations(incidentId).stream()
                .filter(i -> "QA".equals(i.kind()) && !i.refused()).toList();
        previous = previous.subList(Math.max(0, previous.size() - MAX_HISTORY), previous.size());
        List<LlmClient.Turn> turns = new ArrayList<>();
        String preamble = Prompts.evidenceBlock(packJson) + "\n\n";
        for (Investigation p : previous) {
            turns.add(new LlmClient.Turn(LlmClient.Role.USER, (turns.isEmpty() ? preamble : "") + p.question()));
            turns.add(new LlmClient.Turn(LlmClient.Role.ASSISTANT, p.answer()));
        }
        turns.add(new LlmClient.Turn(LlmClient.Role.USER, (turns.isEmpty() ? preamble : "") + question));

        LlmClient.Response r = llm.complete(new LlmClient.Request(Prompts.ASK_SYSTEM, turns, props.llm().maxTokens()));
        return save(incidentId, "QA", question, r, pack, packJson, report, false);
    }

    public List<Investigation> history(String incidentId) {
        report(incidentId);
        return store.investigations(incidentId);
    }

    private Investigation save(String incidentId, String kind, String question, LlmClient.Response r, EvidencePack pack,
                               String packJson, IncidentReport report, boolean expectRootCause) {
        String top = report.topCandidate() == null ? null : report.topCandidate().service();
        AnswerValidator.Result v = r.refused()
                ? new AnswerValidator.Result(List.of(), List.of("The model declined to answer this request."))
                : AnswerValidator.validate(r.text(), pack, top, expectRootCause);
        String answer = r.refused() ? "_The model declined to answer. The deterministic analysis above is unaffected._" : r.text();
        return store.saveInvestigation(incidentId, kind, question, answer, llm.provider(), r.model(), r.inputTokens(),
                r.outputTokens(), packJson.length(), r.refused(), v.citations(), v.warnings());
    }

    private IncidentReport report(String incidentId) {
        return engine.report(incidentId).orElseThrow(() -> new IncidentNotFoundException(incidentId));
    }
}
