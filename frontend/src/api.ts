import type {
  ErrorGroup, Edge, IncidentReport, IncidentSummary, Investigation, LogRow, ServiceHealth, SimulatorStatus,
} from "./types";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
  });
  if (!res.ok) {
    let detail = res.statusText;
    try {
      detail = (await res.json()).detail ?? detail;
    } catch {
      /* not JSON */
    }
    throw new Error(detail);
  }
  return res.json() as Promise<T>;
}

export const api = {
  incidents: () => request<IncidentSummary[]>("/api/incidents"),
  incident: (id: string) => request<IncidentReport>(`/api/incidents/${id}`),
  investigations: (id: string) => request<Investigation[]>(`/api/incidents/${id}/investigations`),
  investigate: (id: string) => request<Investigation>(`/api/incidents/${id}/investigate`, { method: "POST" }),
  ask: (id: string, question: string) =>
    request<Investigation>(`/api/incidents/${id}/ask`, { method: "POST", body: JSON.stringify({ question }) }),
  services: () => request<ServiceHealth[]>("/api/services"),
  graph: () => request<Edge[]>("/api/graph"),
  errorGroups: (service?: string) =>
    request<ErrorGroup[]>(`/api/error-groups${service ? `?service=${encodeURIComponent(service)}` : ""}`),
  logs: (params: Record<string, string>) => request<LogRow[]>(`/api/logs?${new URLSearchParams(params)}`),
  stats: () => request<{ eventsIngested: number; lateEvents: number; baselineReady: boolean }>("/api/stats"),
  simulator: () => request<SimulatorStatus>("/api/simulator"),
  inject: (name: string, minutes: number) =>
    request<SimulatorStatus>(`/api/simulator/scenarios/${name}?minutes=${minutes}`, { method: "POST" }),
  stopFaults: () => request<SimulatorStatus>("/api/simulator/stop", { method: "POST" }),
};
