ALTER TABLE opspilot.api_idempotency
    ADD COLUMN request_id uuid NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN trace_id uuid NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE opspilot.artifact
    ADD COLUMN request_id uuid,
    ADD COLUMN trace_id uuid,
    ADD COLUMN step_id uuid,
    ADD COLUMN a2a_task_id text,
    ADD COLUMN invocation_id uuid;

ALTER TABLE opspilot.call_audit
    ADD COLUMN principal_id text,
    ADD COLUMN request_id uuid,
    ADD COLUMN trace_id uuid,
    ADD COLUMN step_id uuid,
    ADD COLUMN a2a_task_id text,
    ADD COLUMN invocation_id uuid,
    ADD COLUMN permission_summary jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE opspilot.tool_call
    ADD COLUMN request_id uuid,
    ADD COLUMN trace_id uuid,
    ADD COLUMN step_id uuid,
    ADD COLUMN a2a_task_id text,
    ADD COLUMN invocation_id uuid;

ALTER TABLE opspilot.model_call
    ADD COLUMN request_id uuid,
    ADD COLUMN trace_id uuid,
    ADD COLUMN step_id uuid,
    ADD COLUMN a2a_task_id text,
    ADD COLUMN invocation_id uuid;

ALTER TABLE opspilot.sse_event
    ADD COLUMN request_id uuid,
    ADD COLUMN trace_id uuid,
    ADD COLUMN step_id uuid,
    ADD COLUMN a2a_task_id text,
    ADD COLUMN invocation_id uuid;

ALTER TABLE opspilot.outbox_event
    ADD COLUMN request_id uuid,
    ADD COLUMN trace_id uuid,
    ADD COLUMN step_id uuid,
    ADD COLUMN a2a_task_id text,
    ADD COLUMN invocation_id uuid;

ALTER TABLE opspilot_a2a.task
    ADD COLUMN request_id uuid,
    ADD COLUMN trace_id uuid,
    ADD COLUMN step_id uuid,
    ADD COLUMN invocation_id uuid;

CREATE TABLE opspilot.correlation_audit (
    audit_id uuid PRIMARY KEY,
    parent_audit_id uuid REFERENCES opspilot.correlation_audit (audit_id) ON DELETE RESTRICT,
    principal_id text NOT NULL,
    incident_id uuid NOT NULL REFERENCES opspilot.incident (incident_id) ON DELETE RESTRICT,
    run_id uuid REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    step_id uuid,
    request_id uuid NOT NULL,
    trace_id uuid NOT NULL,
    a2a_task_id text,
    invocation_id uuid,
    boundary text NOT NULL CHECK (boundary IN (
        'REST','SUPERVISOR','A2A','AGENT_RUNTIME','TOOL','PROVIDER',
        'ARTIFACT','EVIDENCE','ANALYSIS_SEAL','RCA','SSE','IDEMPOTENCY')),
    action_fingerprint char(64) NOT NULL CHECK (action_fingerprint ~ '^[0-9a-f]{64}$'),
    permission_summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    result_code text NOT NULL CHECK (result_code ~ '^[A-Z0-9_]+$'),
    error_code text CHECK (error_code IS NULL OR error_code ~ '^[A-Z0-9_]+$'),
    summary text NOT NULL CHECK (length(summary) <= 256),
    log_artifact_id uuid REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CHECK (NOT opspilot.jsonb_has_sensitive_key(permission_summary))
);

CREATE INDEX ix_correlation_audit_owned_request
    ON opspilot.correlation_audit (principal_id, incident_id, request_id, occurred_at);
CREATE INDEX ix_correlation_audit_trace
    ON opspilot.correlation_audit (trace_id, occurred_at);

GRANT SELECT, INSERT ON opspilot.correlation_audit TO opspilot_app_role;

CREATE TRIGGER correlation_audit_append_only
BEFORE UPDATE OR DELETE ON opspilot.correlation_audit
FOR EACH ROW EXECUTE FUNCTION opspilot.reject_append_only_change();
