CREATE TABLE opspilot.incident (
    incident_id uuid PRIMARY KEY,
    target_system_id text NOT NULL,
    status text NOT NULL CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED', 'FAILED')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE opspilot.incident_run (
    run_id uuid PRIMARY KEY,
    incident_id uuid NOT NULL REFERENCES opspilot.incident (incident_id),
    status text NOT NULL CHECK (status IN (
        'CREATED', 'QUEUED', 'PLANNING', 'COLLECTING_EVIDENCE', 'ANALYZING_CODE',
        'RETRIEVING_KNOWLEDGE', 'GENERATING_HYPOTHESES', 'VERIFYING_HYPOTHESES',
        'GENERATING_REMEDIATION', 'WAITING_INPUT', 'WAITING_APPROVAL',
        'RUNNING_SANDBOX_TEST', 'GENERATING_REPORT', 'CANCELLING',
        'COMPLETED', 'FAILED', 'CANCELLED'
    )),
    run_version bigint NOT NULL DEFAULT 0,
    started_at timestamptz NOT NULL DEFAULT now(),
    ended_at timestamptz
);

CREATE TABLE opspilot.agent_state (
    run_id uuid PRIMARY KEY REFERENCES opspilot.incident_run (run_id) ON DELETE CASCADE,
    schema_version text NOT NULL,
    state_json jsonb NOT NULL,
    version bigint NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE opspilot.artifact (
    artifact_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id),
    uri text NOT NULL,
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    media_type text NOT NULL,
    access_level text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE opspilot.evidence (
    evidence_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id),
    summary text NOT NULL,
    artifact_id uuid REFERENCES opspilot.artifact (artifact_id),
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE opspilot.evaluation_result (
    evaluation_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id),
    metrics_json jsonb NOT NULL,
    report_artifact_id uuid REFERENCES opspilot.artifact (artifact_id),
    created_at timestamptz NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON
    opspilot.incident,
    opspilot.incident_run,
    opspilot.agent_state,
    opspilot.artifact,
    opspilot.evidence
TO opspilot_app_role;

GRANT SELECT ON
    opspilot.incident,
    opspilot.incident_run,
    opspilot.artifact,
    opspilot.evidence
TO evaluation_role;

GRANT SELECT, INSERT ON opspilot.evaluation_result TO evaluation_role;
