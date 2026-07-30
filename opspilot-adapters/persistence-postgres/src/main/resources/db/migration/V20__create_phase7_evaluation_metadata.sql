ALTER TABLE opspilot.evaluation_result
    ADD COLUMN profile_id text,
    ADD COLUMN profile_version text,
    ADD COLUMN scenario_id text,
    ADD COLUMN profile_snapshot_sha256 char(64),
    ADD COLUMN result_digest char(64),
    ADD COLUMN json_report_artifact_id uuid REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    ADD COLUMN markdown_report_artifact_id uuid REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    ADD COLUMN report_json jsonb,
    ADD COLUMN report_markdown text;

CREATE UNIQUE INDEX uq_evaluation_result_run_profile_version
    ON opspilot.evaluation_result (run_id, profile_id, profile_version)
    WHERE profile_id IS NOT NULL AND profile_version IS NOT NULL;

ALTER TABLE opspilot.evaluation_result
    ADD CONSTRAINT ck_evaluation_profile_version
        CHECK (profile_version IS NULL OR profile_version ~ '^[0-9]+\\.[0-9]+\\.[0-9]+$'),
    ADD CONSTRAINT ck_evaluation_profile_snapshot_sha256
        CHECK (profile_snapshot_sha256 IS NULL OR profile_snapshot_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_evaluation_result_digest
        CHECK (result_digest IS NULL OR result_digest ~ '^[0-9a-f]{64}$');

GRANT SELECT, INSERT ON opspilot.evaluation_result TO evaluation_role;
