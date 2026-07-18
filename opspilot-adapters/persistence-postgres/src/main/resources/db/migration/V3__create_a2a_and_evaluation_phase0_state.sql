CREATE TABLE opspilot_a2a.task (
    task_id text PRIMARY KEY,
    server_agent_id text NOT NULL,
    message_id text NOT NULL,
    request_hash text NOT NULL,
    state text NOT NULL,
    payload_json jsonb NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (server_agent_id, message_id)
);

CREATE TABLE opspilot_a2a.task_event (
    event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id text NOT NULL REFERENCES opspilot_a2a.task (task_id) ON DELETE CASCADE,
    event_type text NOT NULL,
    payload_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE opspilot_a2a.agent_runtime_state (
    server_agent_id text NOT NULL,
    user_id text NOT NULL,
    session_id text NOT NULL,
    schema_version text NOT NULL,
    state_json jsonb NOT NULL,
    version bigint NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (server_agent_id, user_id, session_id)
);

CREATE TABLE opspilot_eval.ground_truth (
    scenario_id text PRIMARY KEY,
    root_cause_code text NOT NULL,
    expected_evidence jsonb NOT NULL,
    expected_actions jsonb NOT NULL,
    artifact_id uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON
    opspilot_a2a.task,
    opspilot_a2a.task_event,
    opspilot_a2a.agent_runtime_state
TO professional_agent_role;
GRANT USAGE, SELECT ON SEQUENCE opspilot_a2a.task_event_event_id_seq TO professional_agent_role;

GRANT SELECT, INSERT, UPDATE, DELETE ON opspilot_eval.ground_truth TO fault_lab_role;
GRANT SELECT ON opspilot_eval.ground_truth TO evaluation_role;
