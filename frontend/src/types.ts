export type Status = "OPEN" | "RESOLVED";
export type Confidence = "HIGH" | "MEDIUM" | "LOW";

export interface IncidentSummary {
  id: string;
  status: Status;
  title: string;
  startedAt: string;
  detectedAt: string;
  resolvedAt: string | null;
  affectedServices: string[];
  likelyRootCause: string;
  confidence: Confidence;
}

export interface Evidence {
  id: string;
  service: string;
  factor: string;
  score: number;
  text: string;
  at: string | null;
}

export interface Candidate {
  service: string;
  score: number;
  suspectedDependency: string | null;
  evidence: Evidence[];
}

export interface TimelineEntry {
  at: string;
  kind: "CHANGE" | "ANOMALY" | "NEW_ERROR" | "DETECTED" | "RESOLVED";
  service: string | null;
  text: string;
}

export interface ErrorGroup {
  fingerprint: string;
  service: string;
  exceptionType: string | null;
  template: string;
  sampleMessage: string;
  topFrame: string | null;
  peerService: string | null;
  firstSeen: string;
  lastSeen: string;
  totalCount: number;
  countInWindow: number;
  isNew: boolean;
}

export interface Edge {
  from: string;
  to: string;
  calls: number;
}

export interface IncidentReport {
  id: string;
  status: Status;
  title: string;
  startedAt: string;
  detectedAt: string;
  resolvedAt: string | null;
  affectedServices: string[];
  confidence: Confidence;
  candidates: Candidate[];
  timeline: TimelineEntry[];
  errorGroups: ErrorGroup[];
  graph: Edge[];
}

export interface Investigation {
  id: number;
  incidentId: string;
  kind: "SUMMARY" | "QA";
  question: string | null;
  answer: string;
  provider: string;
  model: string;
  inputTokens: number | null;
  outputTokens: number | null;
  packChars: number | null;
  refused: boolean;
  citations: string[];
  warnings: string[];
  createdAt: string;
}

export interface ServiceHealth {
  service: string;
  requestsPerSecond: number;
  errorRate: number;
  p95LatencyMs: number;
  openAnomalies: string[];
}

export interface Scenario {
  name: string;
  title: string;
  description: string;
  rootCause: string;
}

export interface SimulatorStatus {
  enabled: boolean;
  activeScenario: string | null;
  activeUntil: string | null;
  scenarios: Scenario[];
}

export interface LogRow {
  id: number;
  ts: string;
  service: string;
  level: string;
  message: string;
  traceId: string | null;
  status: number | null;
  latencyMs: number | null;
  peerService: string | null;
  exceptionType: string | null;
}
