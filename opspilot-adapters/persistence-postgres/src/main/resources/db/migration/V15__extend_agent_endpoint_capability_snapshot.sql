ALTER TABLE opspilot.agent_endpoint
    ADD COLUMN instance_id text NOT NULL DEFAULT 'default',
    ADD COLUMN retryable boolean NOT NULL DEFAULT true,
    ADD COLUMN card_digest char(64),
    ADD COLUMN directory_digest char(64),
    ADD COLUMN configuration_version text,
    ADD COLUMN expected_skill text,
    ADD COLUMN capability_snapshot jsonb,
    ADD COLUMN last_probe_at timestamptz,
    ADD COLUMN last_successful_probe_at timestamptz;

ALTER TABLE opspilot.agent_endpoint
    ADD CONSTRAINT ck_agent_endpoint_card_digest CHECK (
        card_digest IS NULL OR card_digest ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_agent_endpoint_directory_digest CHECK (
        directory_digest IS NULL OR directory_digest ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_agent_endpoint_capability_snapshot CHECK (
        capability_snapshot IS NULL
        OR (capability_snapshot ? 'schemaVersion'
            AND capability_snapshot::text NOT LIKE '%DEGRADED%')),
    ADD CONSTRAINT uq_agent_endpoint_instance UNIQUE (server_agent_id, instance_id);

CREATE INDEX ix_agent_endpoint_ready_resolution
    ON opspilot.agent_endpoint (server_agent_id, state, directory_digest, last_successful_probe_at DESC)
    WHERE state = 'READY';

COMMENT ON COLUMN opspilot.agent_endpoint.directory_digest IS
    'SHA-256 of the immutable Agent Directory used for this probe snapshot';
COMMENT ON COLUMN opspilot.agent_endpoint.capability_snapshot IS
    'Secret-free UP/DOWN-only capability snapshot';

GRANT USAGE ON SCHEMA opspilot_a2a TO
    evidence_agent_role, code_agent_role, knowledge_agent_role,
    diagnosis_agent_role, remediation_agent_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA opspilot_a2a TO
    evidence_agent_role, code_agent_role, knowledge_agent_role,
    diagnosis_agent_role, remediation_agent_role;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA opspilot_a2a TO
    evidence_agent_role, code_agent_role, knowledge_agent_role,
    diagnosis_agent_role, remediation_agent_role;

REVOKE ALL ON ALL TABLES IN SCHEMA opspilot FROM
    evidence_agent_role, knowledge_agent_role, diagnosis_agent_role, remediation_agent_role;
REVOKE ALL ON ALL TABLES IN SCHEMA opspilot FROM code_agent_role;
GRANT USAGE ON SCHEMA opspilot TO code_agent_role;
GRANT SELECT ON opspilot.code_analysis_scope TO code_agent_role;
