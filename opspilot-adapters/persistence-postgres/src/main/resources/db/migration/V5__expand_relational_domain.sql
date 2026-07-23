ALTER TABLE opspilot.incident_run
    ADD COLUMN analysis_sealed_at timestamptz,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE opspilot.artifact
    ADD COLUMN storage_provider text NOT NULL DEFAULT 'local-volume',
    ADD COLUMN object_key text,
    ADD COLUMN size_bytes bigint,
    ADD COLUMN lifecycle_status text NOT NULL DEFAULT 'AVAILABLE',
    ADD COLUMN retention_class text NOT NULL DEFAULT 'INCIDENT_RUN',
    ADD COLUMN expires_at timestamptz,
    ADD COLUMN metadata_json jsonb NOT NULL DEFAULT '{"schemaVersion":"1.0.0"}'::jsonb,
    ADD CONSTRAINT ck_artifact_size_nonnegative CHECK (size_bytes IS NULL OR size_bytes >= 0),
    ADD CONSTRAINT ck_artifact_access_level CHECK (access_level IN ('RUN_PRIVATE', 'TASK_PRIVATE', 'INTERNAL', 'EVALUATION', 'GROUND_TRUTH')),
    ADD CONSTRAINT ck_artifact_lifecycle CHECK (lifecycle_status IN ('AVAILABLE', 'DELETE_PENDING', 'DELETED'));
ALTER TABLE opspilot.artifact
    ADD CONSTRAINT ck_artifact_retention_class CHECK (
        retention_class IN ('INCIDENT_RUN','AUDIT','RAW_OBSERVATION','RCA','EVALUATION','GROUND_TRUTH'));
UPDATE opspilot.artifact
SET object_key = artifact_id::text,
    size_bytes = 0
WHERE object_key IS NULL OR size_bytes IS NULL;
ALTER TABLE opspilot.artifact
    ALTER COLUMN object_key SET NOT NULL,
    ALTER COLUMN size_bytes SET NOT NULL,
    ADD CONSTRAINT uq_artifact_provider_object UNIQUE (storage_provider, object_key);

UPDATE opspilot.evidence
SET attributes = jsonb_set(attributes, '{schemaVersion}', '"1.0.0"'::jsonb, true)
WHERE NOT attributes ? 'schemaVersion';
ALTER TABLE opspilot.evidence
    ALTER COLUMN attributes SET DEFAULT '{"schemaVersion":"1.0.0"}'::jsonb,
    ADD COLUMN source_type text NOT NULL DEFAULT 'ARTIFACT',
    ADD COLUMN source_id text,
    ADD COLUMN observed_at timestamptz,
    ADD CONSTRAINT ck_evidence_json_schema_version CHECK (attributes ? 'schemaVersion');

UPDATE opspilot.evaluation_result
SET metrics_json = jsonb_set(metrics_json, '{schemaVersion}', '"1.0.0"'::jsonb, true)
WHERE NOT metrics_json ? 'schemaVersion';
ALTER TABLE opspilot.evaluation_result
    ADD COLUMN status text NOT NULL DEFAULT 'COMPLETED',
    ADD CONSTRAINT ck_evaluation_status CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    ADD CONSTRAINT ck_evaluation_json_schema_version CHECK (metrics_json ? 'schemaVersion');

CREATE TABLE opspilot.target_system (
    target_system_id text PRIMARY KEY,
    display_name text NOT NULL,
    metadata_json jsonb NOT NULL DEFAULT '{"schemaVersion":"1.0.0"}'::jsonb
        CHECK (metadata_json ? 'schemaVersion'),
    created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO opspilot.target_system (target_system_id, display_name)
SELECT DISTINCT target_system_id, target_system_id FROM opspilot.incident
ON CONFLICT DO NOTHING;
ALTER TABLE opspilot.incident
    ADD CONSTRAINT fk_incident_target_system FOREIGN KEY (target_system_id)
        REFERENCES opspilot.target_system (target_system_id) ON DELETE RESTRICT;

CREATE TABLE opspilot.resource (
    resource_id uuid PRIMARY KEY,
    target_system_id text NOT NULL REFERENCES opspilot.target_system (target_system_id) ON DELETE RESTRICT,
    resource_type text NOT NULL,
    external_key text NOT NULL,
    metadata_json jsonb NOT NULL DEFAULT '{"schemaVersion":"1.0.0"}'::jsonb
        CHECK (metadata_json ? 'schemaVersion'),
    UNIQUE (target_system_id, resource_type, external_key)
);
CREATE TABLE opspilot.resource_relation (
    source_resource_id uuid NOT NULL REFERENCES opspilot.resource (resource_id) ON DELETE RESTRICT,
    target_resource_id uuid NOT NULL REFERENCES opspilot.resource (resource_id) ON DELETE RESTRICT,
    relation_type text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source_resource_id, target_resource_id, relation_type),
    CHECK (source_resource_id <> target_resource_id)
);
CREATE TABLE opspilot.observability_source (
    source_id uuid PRIMARY KEY,
    target_system_id text NOT NULL REFERENCES opspilot.target_system (target_system_id) ON DELETE RESTRICT,
    source_type text NOT NULL,
    config_identity_sha256 char(64) NOT NULL CHECK (config_identity_sha256 ~ '^[0-9a-f]{64}$'),
    config_json jsonb NOT NULL CHECK (config_json ? 'schemaVersion'),
    UNIQUE (target_system_id, source_type, config_identity_sha256)
);
CREATE TABLE opspilot.code_source (
    code_source_id uuid PRIMARY KEY,
    target_system_id text NOT NULL REFERENCES opspilot.target_system (target_system_id) ON DELETE RESTRICT,
    source_type text NOT NULL,
    config_identity_sha256 char(64) NOT NULL CHECK (config_identity_sha256 ~ '^[0-9a-f]{64}$'),
    config_json jsonb NOT NULL CHECK (config_json ? 'schemaVersion'),
    UNIQUE (target_system_id, source_type, config_identity_sha256)
);
CREATE TABLE opspilot.code_repository (
    repository_id uuid PRIMARY KEY,
    code_source_id uuid NOT NULL REFERENCES opspilot.code_source (code_source_id) ON DELETE RESTRICT,
    external_key text NOT NULL,
    default_branch text NOT NULL,
    UNIQUE (code_source_id, external_key)
);
CREATE TABLE opspilot.deployment_revision (
    deployment_revision_id uuid PRIMARY KEY,
    resource_id uuid NOT NULL REFERENCES opspilot.resource (resource_id) ON DELETE RESTRICT,
    repository_id uuid NOT NULL REFERENCES opspilot.code_repository (repository_id) ON DELETE RESTRICT,
    commit_sha char(40) NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    image_digest text NOT NULL CHECK (image_digest ~ '^sha256:[0-9a-f]{64}$'),
    deployed_at timestamptz NOT NULL,
    UNIQUE (resource_id, repository_id, commit_sha, image_digest)
);
CREATE TABLE opspilot.resource_code_binding (
    resource_id uuid NOT NULL REFERENCES opspilot.resource (resource_id) ON DELETE RESTRICT,
    repository_id uuid NOT NULL REFERENCES opspilot.code_repository (repository_id) ON DELETE RESTRICT,
    deployment_revision_id uuid NOT NULL REFERENCES opspilot.deployment_revision (deployment_revision_id) ON DELETE RESTRICT,
    valid_from timestamptz NOT NULL,
    valid_to timestamptz,
    PRIMARY KEY (resource_id, repository_id, deployment_revision_id),
    CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE TABLE opspilot.code_snapshot (
    code_snapshot_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    repository_id uuid NOT NULL REFERENCES opspilot.code_repository (repository_id) ON DELETE RESTRICT,
    deployment_revision_id uuid NOT NULL REFERENCES opspilot.deployment_revision (deployment_revision_id) ON DELETE RESTRICT,
    commit_sha char(40) NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    manifest_artifact_id uuid REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, repository_id, deployment_revision_id)
);

CREATE TABLE opspilot.incident_step (
    step_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE CASCADE,
    step_type text NOT NULL,
    status text NOT NULL CHECK (status IN ('PENDING','DISPATCHING','RECONCILING','QUEUED','RUNNING','WAITING_INPUT','WAITING_AUTH','VALIDATING_RESULT','RETRY_SCHEDULED','CANCEL_REQUESTED','COMPLETED','FAILED','CANCELLED','REJECTED','SKIPPED')),
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (run_id, ordinal)
);
CREATE TABLE opspilot.step_attempt (
    attempt_id uuid PRIMARY KEY,
    step_id uuid NOT NULL REFERENCES opspilot.incident_step (step_id) ON DELETE CASCADE,
    attempt_number integer NOT NULL CHECK (attempt_number > 0),
    status text NOT NULL CHECK (status IN ('PENDING','DISPATCHING','RECONCILING','QUEUED','RUNNING','WAITING_INPUT','WAITING_AUTH','VALIDATING_RESULT','RETRY_SCHEDULED','CANCEL_REQUESTED','COMPLETED','FAILED','CANCELLED','REJECTED','SKIPPED')),
    remote_task_id text,
    idempotency_key text NOT NULL,
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (step_id, attempt_number),
    UNIQUE (step_id, idempotency_key)
);
CREATE UNIQUE INDEX uq_step_one_active_attempt ON opspilot.step_attempt (step_id)
WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED','REJECTED','SKIPPED');

CREATE TABLE opspilot.task (
    task_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    task_type text NOT NULL,
    status text NOT NULL CHECK (status IN ('PENDING','LEASED','RUNNING','RECOVERING','COMPLETED','FAILED','CANCELLED')),
    priority integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    lease_owner text,
    lease_until timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts integer NOT NULL CHECK (max_attempts > 0),
    idempotency_key text NOT NULL,
    side_effect_committed_at timestamptz,
    payload_json jsonb NOT NULL CHECK (payload_json ? 'schemaVersion'),
    UNIQUE (run_id, task_type, idempotency_key),
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL))
);
ALTER TABLE opspilot.artifact
    ADD COLUMN task_id uuid REFERENCES opspilot.task (task_id) ON DELETE RESTRICT;
CREATE TABLE opspilot_a2a.agent_scope_state (
    server_agent_id text NOT NULL,
    user_id text NOT NULL,
    session_id text NOT NULL,
    state_key text NOT NULL,
    state_type text NOT NULL,
    is_list boolean NOT NULL,
    schema_version text NOT NULL DEFAULT '1.0.0',
    state_json jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (server_agent_id, user_id, session_id, state_key),
    CONSTRAINT ck_agent_scope_state_schema CHECK (
        state_json ? 'schemaVersion'
        AND state_json ? 'payload'
        AND state_json ->> 'schemaVersion' = schema_version)
);
CREATE TABLE opspilot.api_idempotency (
    scope text NOT NULL,
    idempotency_key text NOT NULL,
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    response_status integer,
    response_artifact_id uuid REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, idempotency_key)
);
CREATE TABLE opspilot.agent_endpoint (
    server_agent_id text PRIMARY KEY,
    endpoint_uri text NOT NULL,
    state text NOT NULL CHECK (state IN ('UNKNOWN','PROBING','READY','UNAVAILABLE','DRAINING','DISABLED')),
    reason_code text,
    version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE opspilot.a2a_binding (
    binding_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    step_id uuid REFERENCES opspilot.incident_step (step_id) ON DELETE RESTRICT,
    server_agent_id text NOT NULL REFERENCES opspilot.agent_endpoint (server_agent_id) ON DELETE RESTRICT,
    remote_task_id text NOT NULL,
    message_id text NOT NULL,
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE (server_agent_id, remote_task_id),
    UNIQUE (server_agent_id, message_id)
);
CREATE TABLE opspilot.state_transition (
    transition_id uuid PRIMARY KEY,
    incident_id uuid NOT NULL REFERENCES opspilot.incident (incident_id) ON DELETE RESTRICT,
    run_id uuid REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    plane text NOT NULL CHECK (plane IN ('INCIDENT_RUN','STEP_ATTEMPT','A2A_TASK','AGENT_ENDPOINT')),
    subject_id text NOT NULL,
    from_state text,
    to_state text NOT NULL,
    reason_code text,
    occurred_at timestamptz NOT NULL,
    actor_id text NOT NULL
);

CREATE TABLE opspilot.observation_batch (
    batch_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    source_id uuid NOT NULL REFERENCES opspilot.observability_source (source_id) ON DELETE RESTRICT,
    artifact_id uuid NOT NULL REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    collected_at timestamptz NOT NULL,
    record_count integer NOT NULL CHECK (record_count >= 0)
);
CREATE TABLE opspilot.observation_record (
    observation_id uuid PRIMARY KEY,
    batch_id uuid NOT NULL REFERENCES opspilot.observation_batch (batch_id) ON DELETE CASCADE,
    resource_id uuid REFERENCES opspilot.resource (resource_id) ON DELETE RESTRICT,
    observation_type text NOT NULL,
    observed_at timestamptz NOT NULL,
    attributes jsonb NOT NULL CHECK (attributes ? 'schemaVersion')
);
CREATE TABLE opspilot.evidence_provenance (
    provenance_id uuid PRIMARY KEY,
    evidence_id uuid NOT NULL REFERENCES opspilot.evidence (evidence_id) ON DELETE RESTRICT,
    observation_id uuid REFERENCES opspilot.observation_record (observation_id) ON DELETE RESTRICT,
    code_snapshot_id uuid REFERENCES opspilot.code_snapshot (code_snapshot_id) ON DELETE RESTRICT,
    artifact_id uuid REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    UNIQUE NULLS NOT DISTINCT (evidence_id, observation_id, code_snapshot_id, artifact_id),
    CHECK (num_nonnulls(observation_id, code_snapshot_id, artifact_id) = 1)
);
CREATE TABLE opspilot.hypothesis (
    hypothesis_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    statement text NOT NULL,
    status text NOT NULL CHECK (status IN ('PROPOSED','SUPPORTED','CONFLICTED','VERIFIED','REJECTED')),
    confidence numeric(5,4) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE opspilot.hypothesis_evidence (
    hypothesis_id uuid NOT NULL REFERENCES opspilot.hypothesis (hypothesis_id) ON DELETE RESTRICT,
    evidence_id uuid NOT NULL REFERENCES opspilot.evidence (evidence_id) ON DELETE RESTRICT,
    relation text NOT NULL CHECK (relation IN ('SUPPORTS','CONFLICTS')),
    rationale_summary text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (hypothesis_id, evidence_id, relation)
);
CREATE TABLE opspilot.hypothesis_verification (
    verification_id uuid PRIMARY KEY,
    hypothesis_id uuid NOT NULL REFERENCES opspilot.hypothesis (hypothesis_id) ON DELETE RESTRICT,
    evidence_id uuid REFERENCES opspilot.evidence (evidence_id) ON DELETE RESTRICT,
    result text NOT NULL CHECK (result IN ('CONFIRMED','REFUTED','INCONCLUSIVE')),
    summary text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE opspilot.rca_report (
    report_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    report_artifact_id uuid NOT NULL REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    root_hypothesis_id uuid REFERENCES opspilot.hypothesis (hypothesis_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id)
);
CREATE TABLE opspilot.call_audit (
    audit_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    call_kind text NOT NULL CHECK (call_kind IN ('TOOL','MODEL','A2A')),
    action_fingerprint text NOT NULL,
    outcome_code text NOT NULL,
    occurred_at timestamptz NOT NULL
);
CREATE TABLE opspilot.reference_binding (
    binding_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    binding_type text NOT NULL CHECK (binding_type IN ('EVIDENCE','HYPOTHESIS','ARTIFACT')),
    reference_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, binding_type, reference_id)
);
CREATE TABLE opspilot.tool_call (
    tool_call_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    tool_name text NOT NULL,
    idempotency_key text NOT NULL,
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    result_artifact_id uuid REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    outcome_code text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, tool_name, idempotency_key)
);
CREATE TABLE opspilot.model_call (
    model_call_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    model_revision_id uuid,
    prompt_artifact_id uuid REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    response_artifact_id uuid REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    outcome_code text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE opspilot.chain_failure (
    failure_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    error_code text NOT NULL,
    retryable boolean NOT NULL,
    summary text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE opspilot.approval (
    approval_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    status text NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED')),
    decision_by text,
    decided_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE opspilot.sse_event (
    event_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    sequence_no bigint NOT NULL,
    event_type text NOT NULL,
    payload_json jsonb NOT NULL CHECK (payload_json ? 'schemaVersion'),
    committed_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, sequence_no)
);
CREATE TABLE opspilot.outbox_event (
    event_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    fact_type text NOT NULL,
    state_version bigint NOT NULL,
    payload_json jsonb NOT NULL CHECK (payload_json ? 'schemaVersion'),
    occurred_at timestamptz NOT NULL,
    published_at timestamptz,
    last_error text
);
CREATE TABLE opspilot.projection_receipt (
    event_id uuid NOT NULL REFERENCES opspilot.outbox_event (event_id) ON DELETE RESTRICT,
    projector_name text NOT NULL,
    status text NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','FAILED')),
    error_code text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, projector_name)
);

UPDATE opspilot_a2a.task
SET payload_json = jsonb_set(payload_json, '{schemaVersion}', '"1.0.0"'::jsonb, true)
WHERE NOT payload_json ? 'schemaVersion';
ALTER TABLE opspilot_a2a.task
    ADD COLUMN run_id uuid REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    ADD COLUMN context_id text NOT NULL DEFAULT 'legacy',
    ADD COLUMN artifact_id text,
    ADD COLUMN media_type text,
    ADD COLUMN schema_version text,
    ADD COLUMN sha256 char(64),
    ADD COLUMN artifact_payload text,
    ADD COLUMN revision bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_a2a_task_state CHECK (state IN ('UNSPECIFIED','SUBMITTED','WORKING','INPUT_REQUIRED','AUTH_REQUIRED','COMPLETED','FAILED','CANCELED','REJECTED')),
    ADD CONSTRAINT ck_a2a_task_payload_schema CHECK (payload_json ? 'schemaVersion'),
    ADD CONSTRAINT ck_a2a_task_artifact_sha CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$');
ALTER TABLE opspilot_a2a.task_event
    ADD COLUMN server_agent_id text,
    ADD COLUMN run_id uuid REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    ADD COLUMN context_id text,
    ADD COLUMN message_id text,
    ADD COLUMN state text,
    ADD COLUMN artifact_id text,
    ADD COLUMN media_type text,
    ADD COLUMN schema_version text,
    ADD COLUMN sha256 char(64),
    ADD COLUMN artifact_payload text,
    ADD COLUMN revision bigint;
UPDATE opspilot_a2a.task_event event
SET server_agent_id = task.server_agent_id,
    run_id = task.run_id,
    context_id = task.context_id,
    message_id = task.message_id,
    state = task.state,
    revision = task.revision
FROM opspilot_a2a.task task
WHERE task.task_id = event.task_id;
ALTER TABLE opspilot_a2a.task_event
    ALTER COLUMN server_agent_id SET NOT NULL,
    ALTER COLUMN context_id SET NOT NULL,
    ALTER COLUMN message_id SET NOT NULL,
    ALTER COLUMN state SET NOT NULL,
    ALTER COLUMN revision SET NOT NULL,
    ADD CONSTRAINT uq_a2a_task_event_revision UNIQUE (server_agent_id, task_id, revision),
    ADD CONSTRAINT ck_a2a_task_event_state CHECK (state IN ('UNSPECIFIED','SUBMITTED','WORKING','INPUT_REQUIRED','AUTH_REQUIRED','COMPLETED','FAILED','CANCELED','REJECTED')),
    ADD CONSTRAINT ck_a2a_task_event_artifact_sha CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$');
CREATE OR REPLACE FUNCTION opspilot_a2a.fill_legacy_task_event_fields() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE source_task opspilot_a2a.task%ROWTYPE;
BEGIN
    IF NEW.context_id IS NULL OR NEW.message_id IS NULL OR NEW.state IS NULL OR NEW.revision IS NULL THEN
        SELECT * INTO STRICT source_task FROM opspilot_a2a.task
        WHERE task_id = NEW.task_id AND server_agent_id = NEW.server_agent_id;
        NEW.context_id := coalesce(NEW.context_id, source_task.context_id);
        NEW.message_id := coalesce(NEW.message_id, source_task.message_id);
        NEW.state := coalesce(NEW.state, source_task.state);
        NEW.revision := coalesce(NEW.revision, (
            SELECT coalesce(max(revision), 0) + 1 FROM opspilot_a2a.task_event
            WHERE task_id = NEW.task_id AND server_agent_id = NEW.server_agent_id));
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER task_event_legacy_compatibility
BEFORE INSERT ON opspilot_a2a.task_event FOR EACH ROW
EXECUTE FUNCTION opspilot_a2a.fill_legacy_task_event_fields();
CREATE TABLE opspilot_a2a.message (
    server_agent_id text NOT NULL,
    message_id text NOT NULL,
    task_id text NOT NULL REFERENCES opspilot_a2a.task (task_id) ON DELETE CASCADE,
    role text NOT NULL CHECK (role IN ('USER','AGENT','TOOL')),
    payload_json jsonb NOT NULL CHECK (payload_json ? 'schemaVersion'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (server_agent_id, message_id)
);
CREATE TABLE opspilot_a2a.artifact (
    server_agent_id text NOT NULL,
    artifact_id text NOT NULL,
    task_id text NOT NULL REFERENCES opspilot_a2a.task (task_id) ON DELETE CASCADE,
    domain_artifact_id uuid REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    media_type text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (server_agent_id, artifact_id)
);

CREATE OR REPLACE FUNCTION opspilot.reject_append_only_change() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'APPEND_ONLY_RELATION:%', TG_TABLE_NAME USING ERRCODE = '55000'; END $$;
CREATE TRIGGER state_transition_append_only BEFORE UPDATE OR DELETE ON opspilot.state_transition
FOR EACH ROW EXECUTE FUNCTION opspilot.reject_append_only_change();
CREATE TRIGGER call_audit_append_only BEFORE UPDATE OR DELETE ON opspilot.call_audit
FOR EACH ROW EXECUTE FUNCTION opspilot.reject_append_only_change();
