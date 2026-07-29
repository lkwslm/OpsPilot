ALTER TABLE opspilot.incident_run
    ADD COLUMN agent_profile_configuration_version text NOT NULL DEFAULT 'legacy',
    ADD COLUMN effective_agent_profile_configuration_json jsonb NOT NULL
        DEFAULT '{"schemaVersion":"1.0.0","legacy":true}'::jsonb,
    ADD CONSTRAINT ck_run_agent_profile_configuration_version_nonblank
        CHECK (btrim(agent_profile_configuration_version) <> ''),
    ADD CONSTRAINT ck_run_agent_profile_snapshot_schema
        CHECK (effective_agent_profile_configuration_json ? 'schemaVersion'),
    ADD CONSTRAINT ck_run_agent_profile_snapshot_non_sensitive
        CHECK (NOT opspilot.jsonb_has_sensitive_key(effective_agent_profile_configuration_json));
