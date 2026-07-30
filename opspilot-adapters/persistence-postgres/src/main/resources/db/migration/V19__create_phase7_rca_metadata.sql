ALTER TABLE opspilot.evidence
    ADD COLUMN evidence_code text;

UPDATE opspilot.evidence
SET evidence_code = attributes ->> 'evidenceCode',
    observed_at = COALESCE(observed_at, created_at)
WHERE evidence_code IS NULL OR observed_at IS NULL;

ALTER TABLE opspilot.evidence
    ALTER COLUMN observed_at SET DEFAULT now(),
    ALTER COLUMN observed_at SET NOT NULL,
    ADD CONSTRAINT ck_evidence_code_format CHECK (
        evidence_code IS NULL OR evidence_code ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){2,}$');

ALTER TABLE opspilot.rca_report
    ADD COLUMN run_version bigint,
    ADD COLUMN analysis_sealed_at timestamptz,
    ADD COLUMN schema_version text NOT NULL DEFAULT '1.0.0',
    ADD COLUMN input_digest char(64),
    ADD COLUMN object_digest char(64),
    ADD COLUMN markdown_artifact_id uuid REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT;

UPDATE opspilot.rca_report report
SET run_version = run.run_version,
    analysis_sealed_at = run.analysis_sealed_at,
    input_digest = repeat('0', 64),
    object_digest = repeat('0', 64)
FROM opspilot.incident_run run
WHERE run.run_id = report.run_id;

ALTER TABLE opspilot.rca_report
    ALTER COLUMN run_version SET NOT NULL,
    ALTER COLUMN input_digest SET NOT NULL,
    ALTER COLUMN object_digest SET NOT NULL,
    DROP CONSTRAINT rca_report_run_id_key,
    ADD CONSTRAINT uq_rca_report_run_version UNIQUE (run_id, run_version),
    ADD CONSTRAINT ck_rca_report_input_digest CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_rca_report_object_digest CHECK (object_digest ~ '^[0-9a-f]{64}$');

COMMENT ON TABLE opspilot.rca_report IS
    'Metadata for one RCA object rendered to JSON (report_artifact_id) and Markdown without a duplicate input snapshot';
