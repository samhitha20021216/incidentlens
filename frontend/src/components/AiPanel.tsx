import { useState } from "react";
import { api } from "../api";
import { usePolling } from "../usePolling";
import type { Investigation } from "../types";
import { Markdown } from "./Markdown";

export function AiPanel({ incidentId, onCite }: { incidentId: string; onCite: (id: string) => void }) {
  const { data: history, refresh } = usePolling(() => api.investigations(incidentId), 30_000, [incidentId]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [question, setQuestion] = useState("");

  const run = async (fn: () => Promise<Investigation>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
      await refresh();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const items = history ?? [];
  const hasSummary = items.some((i) => i.kind === "SUMMARY");

  return (
    <section className="card ai">
      <header>
        <h3>AI investigation</h3>
        <span className="muted small">The model sees only the evidence pack, and every answer is checked against it.</span>
      </header>
      {!hasSummary && (
        <button className="primary" disabled={busy} onClick={() => run(() => api.investigate(incidentId))}>
          {busy ? "Investigating…" : "Generate investigation summary"}
        </button>
      )}
      {items.map((i) => (
        <article key={i.id} className={`answer ${i.kind.toLowerCase()}`}>
          {i.question && <p className="question">Q: {i.question}</p>}
          <Markdown text={i.answer} onCite={onCite} />
          {i.warnings.length > 0 && (
            <ul className="warnings">
              {i.warnings.map((w) => <li key={w}>⚠ {w}</li>)}
            </ul>
          )}
          <p className="meta">
            {i.provider} · {i.model}
            {i.inputTokens ? ` · ${i.inputTokens}→${i.outputTokens} tokens` : ""}
            {i.packChars ? ` · evidence pack ${(i.packChars / 1024).toFixed(1)} KB` : ""}
            {i.warnings.length === 0 && !i.refused ? " · ✓ all citations verified" : ""}
          </p>
        </article>
      ))}
      {hasSummary && (
        <form className="ask" onSubmit={(e) => {
          e.preventDefault();
          if (question.trim()) void run(async () => {
            const r = await api.ask(incidentId, question.trim());
            setQuestion("");
            return r;
          });
        }}>
          <input value={question} onChange={(e) => setQuestion(e.target.value)} maxLength={1000}
            placeholder="Ask a follow-up, e.g. “Was anything deployed before this?”" disabled={busy} />
          <button disabled={busy || !question.trim()}>{busy ? "…" : "Ask"}</button>
        </form>
      )}
      {error && <p className="error">{error}</p>}
    </section>
  );
}
