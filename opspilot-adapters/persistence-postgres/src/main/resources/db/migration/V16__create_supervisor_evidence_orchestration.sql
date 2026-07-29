ALTER TABLE opspilot.step_attempt
    ADD COLUMN target_skill text,
    ADD COLUMN input_evidence_ids uuid[] NOT NULL DEFAULT '{}',
    ADD COLUMN input_artifact_ids uuid[] NOT NULL DEFAULT '{}',
    ADD COLUMN remaining_budget_json jsonb,
    ADD COLUMN parent_deadline timestamptz,
    ADD COLUMN capability_snapshot_json jsonb;

ALTER TABLE opspilot.step_attempt
    ADD CONSTRAINT ck_step_attempt_delegation_snapshot CHECK (
        (target_skill IS NULL AND remaining_budget_json IS NULL
            AND parent_deadline IS NULL AND capability_snapshot_json IS NULL)
        OR
        (target_skill IS NOT NULL
            AND remote_agent_id IS NOT NULL
            AND remaining_budget_json ? 'schemaVersion'
            AND capability_snapshot_json ? 'schemaVersion'
            AND parent_deadline IS NOT NULL));

CREATE TABLE opspilot.artifact_reception (
    artifact_id uuid PRIMARY KEY REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    task_id uuid NOT NULL REFERENCES opspilot.task (task_id) ON DELETE RESTRICT,
    status text NOT NULL CHECK (status = 'ACCEPTED'),
    accepted_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE opspilot.missing_evidence (
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    reason_code text NOT NULL,
    source_attempt_id uuid REFERENCES opspilot.step_attempt (attempt_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, reason_code)
);

CREATE OR REPLACE FUNCTION opspilot.reject_sealed_run_write() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE candidate_run uuid;
BEGIN
    candidate_run := COALESCE(NEW.run_id, OLD.run_id);
    IF EXISTS (SELECT 1 FROM opspilot.incident_run
               WHERE run_id = candidate_run AND analysis_sealed_at IS NOT NULL) THEN
        RAISE EXCEPTION 'ANALYSIS_SEALED' USING ERRCODE = '55000';
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$;

CREATE TRIGGER artifact_reception_sealed_guard
BEFORE INSERT OR UPDATE OR DELETE ON opspilot.artifact_reception
FOR EACH ROW EXECUTE FUNCTION opspilot.reject_sealed_run_write();

CREATE TRIGGER missing_evidence_sealed_guard
BEFORE INSERT OR UPDATE OR DELETE ON opspilot.missing_evidence
FOR EACH ROW EXECUTE FUNCTION opspilot.reject_sealed_run_write();

GRANT SELECT, INSERT, UPDATE ON
    opspilot.artifact_reception,
    opspilot.missing_evidence
TO opspilot_app_role;

COMMENT ON TABLE opspilot.artifact_reception IS
    'Atomic acceptance marker; late protocol artifacts are audited elsewhere after analysis seal';
COMMENT ON COLUMN opspilot.step_attempt.capability_snapshot_json IS
    'Secret-free effective capability snapshot committed before the A2A network request';
