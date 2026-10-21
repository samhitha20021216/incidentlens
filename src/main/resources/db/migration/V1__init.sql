-- Raw telemetry for drill-down. The engine keeps aggregates in memory; this table answers
-- "show me the actual log lines" for a service, trace or error fingerprint.
CREATE TABLE log_events (
    id             BIGSERIAL PRIMARY KEY,
    ts             TIMESTAMPTZ  NOT NULL,
    service        VARCHAR(100) NOT NULL,
    level          VARCHAR(10)  NOT NULL,
    message        TEXT,
    trace_id       VARCHAR(64),
    span_id        VARCHAR(32),
    parent_span_id VARCHAR(32),
    endpoint       VARCHAR(200),
    status         INT,
    latency_ms     BIGINT,
    peer_service   VARCHAR(100),
    exception_type VARCHAR(200),
    fingerprint    VARCHAR(16),
    stack          JSONB
);
CREATE INDEX log_events_service_ts_idx ON log_events (service, ts DESC);
CREATE INDEX log_events_trace_idx ON log_events (trace_id);
CREATE INDEX log_events_fp_idx ON log_events (fingerprint, ts DESC) WHERE fingerprint IS NOT NULL;
CREATE INDEX log_events_ts_idx ON log_events (ts);

CREATE TABLE change_events (
    id          BIGSERIAL PRIMARY KEY,
    ts          TIMESTAMPTZ  NOT NULL,
    service     VARCHAR(100) NOT NULL,
    type        VARCHAR(20)  NOT NULL,
    description TEXT
);

-- Latest report snapshot per incident, so incidents survive restarts and can be investigated later.
CREATE TABLE incidents (
    id           VARCHAR(20) PRIMARY KEY,
    status       VARCHAR(10)  NOT NULL,
    title        TEXT         NOT NULL,
    started_at   TIMESTAMPTZ  NOT NULL,
    detected_at  TIMESTAMPTZ  NOT NULL,
    resolved_at  TIMESTAMPTZ,
    likely_cause VARCHAR(100),
    confidence   VARCHAR(10),
    report       JSONB        NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX incidents_detected_idx ON incidents (detected_at DESC);

-- Every LLM interaction is stored with what the validator thought of it.
CREATE TABLE investigations (
    id            BIGSERIAL PRIMARY KEY,
    incident_id   VARCHAR(20)  NOT NULL,
    kind          VARCHAR(10)  NOT NULL,
    question      TEXT,
    answer        TEXT         NOT NULL,
    provider      VARCHAR(20)  NOT NULL,
    model         VARCHAR(100),
    input_tokens  BIGINT,
    output_tokens BIGINT,
    pack_chars    INT,
    refused       BOOLEAN      NOT NULL DEFAULT false,
    citations     JSONB,
    warnings      JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX investigations_incident_idx ON investigations (incident_id, id);
