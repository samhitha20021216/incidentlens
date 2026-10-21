import { api } from "../api";
import { time } from "../format";
import { usePolling } from "../usePolling";
import type { ErrorGroup } from "../types";

export function LogDrawer({ group, onClose }: { group: ErrorGroup; onClose: () => void }) {
  const { data, error } = usePolling(() => api.logs({ fingerprint: group.fingerprint, limit: "50" }), 60_000,
    [group.fingerprint]);
  return (
    <div className="drawer" role="dialog" aria-label="Raw log lines">
      <header>
        <div>
          <h3>{group.exceptionType ?? "Error"} in {group.service}</h3>
          <code className="small">{group.template}</code>
        </div>
        <button onClick={onClose} aria-label="Close">✕</button>
      </header>
      {group.topFrame && <p className="muted small">at {group.topFrame}</p>}
      {error && <p className="error">{error}</p>}
      <table className="logs">
        <thead><tr><th>Time</th><th>Status</th><th>Latency</th><th>Trace</th><th>Message</th></tr></thead>
        <tbody>
          {(data ?? []).map((l) => (
            <tr key={l.id}>
              <td>{time(l.ts)}</td>
              <td>{l.status ?? ""}</td>
              <td>{l.latencyMs != null ? `${l.latencyMs} ms` : ""}</td>
              <td><code>{l.traceId?.slice(0, 8)}</code></td>
              <td className="msg">{l.message}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
