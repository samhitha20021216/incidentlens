import { useEffect, useState } from "react";
import { api } from "./api";
import { IncidentDetail } from "./components/IncidentDetail";
import { ServiceGraph } from "./components/ServiceGraph";
import { ago, pct } from "./format";
import { usePolling } from "./usePolling";

function useHashRoute() {
  const [hash, setHash] = useState(window.location.hash);
  useEffect(() => {
    const on = () => setHash(window.location.hash);
    window.addEventListener("hashchange", on);
    return () => window.removeEventListener("hashchange", on);
  }, []);
  return hash.replace(/^#\/?/, "");
}

export default function App() {
  const route = useHashRoute();
  const incidents = usePolling(api.incidents, 3000);
  const services = usePolling(api.services, 3000);
  const graph = usePolling(api.graph, 10_000);
  const sim = usePolling(api.simulator, 5000);
  const stats = usePolling(api.stats, 5000);
  const [injecting, setInjecting] = useState(false);
  const selected = route.startsWith("incidents/") ? route.slice("incidents/".length) : null;
  const unhealthy = (services.data ?? []).filter((s) => s.openAnomalies.length > 0).map((s) => s.service);

  const inject = async (name: string) => {
    setInjecting(true);
    try {
      await api.inject(name, 5);
      await sim.refresh();
    } finally {
      setInjecting(false);
    }
  };

  return (
    <div className="app">
      <header className="topbar">
        <a href="#/" className="brand">◎ IncidentLens</a>
        <span className="muted small">production incident intelligence</span>
        {stats.data && (
          <span className="small stats">
            {stats.data.eventsIngested.toLocaleString()} events analysed
            {!stats.data.baselineReady && <span className="warming"> · learning baseline (about 2 min)…</span>}
          </span>
        )}
        {incidents.error && <span className="error small">API unreachable: {incidents.error}</span>}
      </header>

      <main className="layout">
        <aside>
          <section className="card">
            <h3>Incidents</h3>
            {(incidents.data ?? []).length === 0 && <p className="muted small">No incidents. Inject a failure below to see one.</p>}
            <ul className="incident-list">
              {(incidents.data ?? []).map((i) => (
                <li key={i.id} className={selected === i.id ? "active" : ""}>
                  <a href={`#/incidents/${i.id}`}>
                    <span className={`pill ${i.status.toLowerCase()}`}>{i.status}</span>
                    <strong>{i.id}</strong> <span className="muted small">{ago(i.detectedAt)}</span>
                    <div className="small">likely <strong>{i.likelyRootCause}</strong> · {i.affectedServices.length} services · {i.confidence.toLowerCase()} confidence</div>
                  </a>
                </li>
              ))}
            </ul>
          </section>

          <section className="card">
            <h3>Services <span className="muted small">last 60s</span></h3>
            <table className="health">
              <thead><tr><th>Service</th><th>req/s</th><th>errors</th><th>p95</th></tr></thead>
              <tbody>
                {(services.data ?? []).map((s) => (
                  <tr key={s.service} className={s.openAnomalies.length ? "bad" : ""} title={s.openAnomalies.join("\n")}>
                    <td>{s.openAnomalies.length ? "● " : ""}{s.service}</td>
                    <td>{s.requestsPerSecond.toFixed(1)}</td>
                    <td>{pct(s.errorRate)}</td>
                    <td>{Math.round(s.p95LatencyMs)} ms</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          <section className="card">
            <h3>Failure injection</h3>
            {sim.data && !sim.data.enabled && <p className="muted small">Simulator disabled. Start with INCIDENTLENS_SIMULATOR_ENABLED=true.</p>}
            {sim.data?.activeScenario && (
              <p className="active-fault">Active: <strong>{sim.data.activeScenario}</strong>{" "}
                <button className="link" onClick={() => api.stopFaults().then(sim.refresh)}>stop</button></p>
            )}
            <ul className="scenarios">
              {(sim.data?.scenarios ?? []).map((s) => (
                <li key={s.name}>
                  <button disabled={!sim.data?.enabled || injecting || !!sim.data?.activeScenario}
                    onClick={() => inject(s.name)} title={s.description}>{s.title}</button>
                </li>
              ))}
            </ul>
          </section>
        </aside>

        <section className="content">
          {selected ? <IncidentDetail id={selected} /> : (
            <div className="card">
              <h3>Live dependency graph</h3>
              <p className="muted small">Inferred from trace parent/child spans and failing calls. Red = currently anomalous.</p>
              <ServiceGraph edges={graph.data ?? []} unhealthy={unhealthy} />
            </div>
          )}
        </section>
      </main>
    </div>
  );
}
