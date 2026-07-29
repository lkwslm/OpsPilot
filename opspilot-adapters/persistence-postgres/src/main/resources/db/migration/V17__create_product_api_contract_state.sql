ALTER TABLE opspilot.incident
    ADD COLUMN principal_id text NOT NULL DEFAULT 'legacy',
    ADD COLUMN resource_ids text[] NOT NULL DEFAULT '{}',
    ADD COLUMN scenario_id text,
    ADD COLUMN title text NOT NULL DEFAULT 'Legacy incident',
    ADD COLUMN severity text NOT NULL DEFAULT 'MEDIUM',
    ADD COLUMN ticket_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN input_artifact_ids uuid[] NOT NULL DEFAULT '{}',
    ADD CONSTRAINT ck_incident_principal_nonblank CHECK (btrim(principal_id) <> ''),
    ADD CONSTRAINT ck_incident_title_nonblank CHECK (btrim(title) <> ''),
    ADD CONSTRAINT ck_incident_severity CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL'));

CREATE INDEX ix_incident_principal ON opspilot.incident (principal_id, incident_id);

ALTER TABLE opspilot.incident_run
    ADD COLUMN outcome text,
    ADD COLUMN evaluation_profile text NOT NULL DEFAULT 'mvp-v1',
    ADD COLUMN token_budget bigint,
    ADD COLUMN deadline_seconds integer NOT NULL DEFAULT 600,
    ADD CONSTRAINT ck_incident_run_outcome CHECK (
        outcome IS NULL OR outcome IN ('CONCLUSIVE','PARTIAL','INCONCLUSIVE')),
    ADD CONSTRAINT ck_incident_run_token_budget CHECK (token_budget IS NULL OR token_budget > 0),
    ADD CONSTRAINT ck_incident_run_deadline CHECK (deadline_seconds BETWEEN 60 AND 3600);

ALTER TABLE opspilot.api_idempotency
    DROP CONSTRAINT api_idempotency_pkey;
ALTER TABLE opspilot.api_idempotency
    RENAME COLUMN scope TO principal_id;

ALTER TABLE opspilot.api_idempotency
    ADD COLUMN operation_id text NOT NULL DEFAULT 'legacy',
    ADD COLUMN processing_status text NOT NULL DEFAULT 'PROCESSING',
    ADD COLUMN response_headers jsonb,
    ADD COLUMN response_body bytea,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD CONSTRAINT api_idempotency_pkey PRIMARY KEY (principal_id, operation_id, idempotency_key),
    ADD CONSTRAINT ck_api_idempotency_principal_nonblank CHECK (btrim(principal_id) <> ''),
    ADD CONSTRAINT ck_api_idempotency_operation_nonblank CHECK (btrim(operation_id) <> ''),
    ADD CONSTRAINT ck_api_idempotency_key CHECK (
        length(idempotency_key) BETWEEN 16 AND 128
        AND idempotency_key ~ '^[A-Za-z0-9._:-]+$'),
    ADD CONSTRAINT ck_api_idempotency_status CHECK (
        processing_status IN ('PROCESSING','COMPLETED','FAILED')),
    ADD CONSTRAINT ck_api_idempotency_completed_response CHECK (
        processing_status <> 'COMPLETED'
        OR (response_status IS NOT NULL AND response_headers IS NOT NULL AND response_body IS NOT NULL));

CREATE INDEX ix_api_idempotency_updated ON opspilot.api_idempotency (processing_status, updated_at);

ALTER TABLE opspilot.approval
    ADD COLUMN decision_reason text;

ALTER TABLE opspilot.rca_report
    ADD COLUMN report_json jsonb,
    ADD COLUMN report_markdown text;

CREATE INDEX ix_sse_event_run_sequence ON opspilot.sse_event (run_id, sequence_no);
