import { useState } from "react";
import { api } from "../api";
import { duration, time } from "../format";
import { usePolling } from "../usePolling";
import type { ErrorGroup } from "../types";
import { AiPanel } from "./AiPanel";
import { LogDrawer } from "./LogDrawer";
import { ServiceGraph } from "./ServiceGraph";

const KIND_LABEL = { CHANGE: "change", ANOMALY: "anomaly", NEW_ERROR: "new error", DETECTED: "detected", RESOLVED: "resolved" };

export function IncidentDetail({ id }: { id: string }) {
  const { data: r, error } = usePolling(() => api.incident(id), 4000, [id]);
  const [highlight, setHighlight] = useState<string | null>(null);
  const [group, setGroup] = useState<ErrorGroup | null>(null);

  if (error) return <p className="error">{error}</p>;
  if (!r) return <p className="muted">Loading…</p>;
  const top = r.candidates[0];

  const cite = (cid: string) => {
    setHighlight(cid);
    document.getElementById(`ev-${cid}`)?.scrollIntoView({ behavior: "smooth", block: "center" });
  };

  return (
    <div className="detail">
      <header className="detail-head">
        <div>
          <span className={`pill ${r.status.toLowerCase()}`}>{r.status}</span>
          <h2>{r.id} · {r.title}</h2>
          <p className="muted">
            Started {time(r.startedAt)} · detected {time(r.detectedAt)} · {r.resolvedAt ? `resolved after ${duration(r.startedAt, r.resolvedAt)}` : `ongoing for ${duration(r.startedAt, null)}`}
          </p>
        </div>
        <div className={`confidence ${r.confidence.toLowerCase()}`}>
          <span className="small">Ranking confidence</span>
          <strong>{r.confidence}</strong>
        </div>
      </header>

      <div className="grid2">
        <section className="card">
          <h3>Likely root cause</h3>
          {r.candidates.map((c, idx) => (
            <div key={c.service} className={`candidate ${idx === 0 ? "top" : ""}`}>
              <div className="cand-head">
                <span className="rank">#{idx + 1}</span>
                <strong>{c.service}</strong>
                {c.suspectedDependency && <span className="tag">via {c.suspectedDependency}</span>}
                <span className="score"><span style={{ width: `${c.score * 100}%` }} /></span>
                <span className="small">{c.score.toFixed(2)}</span>
              </div>
              {idx < 2 && (
                <ul className="evidence">
                  {c.evidence.map((e) => (
                    <li key={e.id} id={`ev-${e.id}`} className={highlight === e.id ? "flash" : ""}>
                      <span className="eid">{e.id}</span>
                      <span className="factor">{e.factor.replaceAll("_", " ").toLowerCase()}</span>
                      {e.text}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          ))}
        </section>

        <AiPanel incidentId={r.id} onCite={cite} />
      </div>

      <div className="grid2">
        <section className="card">
          <h3>Timeline</h3>
          <ol className="timeline">
            {r.timeline.map((t, i) => (
              <li key={i} id={`ev-T${i + 1}`} className={`${t.kind.toLowerCase()} ${highlight === `T${i + 1}` ? "flash" : ""}`}>
                <time>{time(t.at)}</time>
                <span className="kind">{KIND_LABEL[t.kind]}</span>
                <span>{t.text}</span>
              </li>
            ))}
          </ol>
        </section>
        <section className="card">
          <h3>Blast radius</h3>
          <ServiceGraph edges={r.graph} affected={r.affectedServices} rootCause={top?.service}
            suspected={top?.suspectedDependency} />
          <p className="legend small"><span className="dot root" /> likely root cause <span className="dot suspect" /> suspected dependency <span className="dot bad" /> affected</p>
        </section>
      </div>

      <section className="card">
        <h3>Error signatures during the incident</h3>
        <table className="groups">
          <thead><tr><th></th><th>Service</th><th>Error</th><th>Involves</th><th>Count</th><th>First seen</th></tr></thead>
          <tbody>
            {r.errorGroups.map((g, i) => (
              <tr key={g.fingerprint} id={`ev-G${i + 1}`} onClick={() => setGroup(g)}
                className={highlight === `G${i + 1}` ? "flash" : ""} title="Show raw log lines">
                <td><span className="eid">G{i + 1}</span>{g.isNew && <span className="tag new">new</span>}</td>
                <td>{g.service}</td>
                <td><strong>{g.exceptionType}</strong> <span className="muted">{g.template}</span></td>
                <td>{g.peerService ?? ""}</td>
                <td>{g.countInWindow}</td>
                <td>{time(g.firstSeen)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      {group && <LogDrawer group={group} onClose={() => setGroup(null)} />}
    </div>
  );
}
