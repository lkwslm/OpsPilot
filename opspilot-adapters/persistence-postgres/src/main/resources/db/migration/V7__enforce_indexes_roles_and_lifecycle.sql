UPDATE opspilot.agent_state
SET state_json = jsonb_set(
        jsonb_set(state_json, '{schemaVersion}', to_jsonb(schema_version), true),
        '{version}', to_jsonb(version), true)
WHERE NOT state_json ? 'schemaVersion' OR NOT state_json ? 'version';
ALTER TABLE opspilot.agent_state
    ADD CONSTRAINT ck_agent_state_schema_version CHECK (
        state_json ? 'schemaVersion'
        AND state_json ? 'version'
        AND state_json ->> 'schemaVersion' = schema_version
        AND (state_json ->> 'version')::bigint = version);

UPDATE opspilot_a2a.task_event
SET payload_json = jsonb_set(payload_json, '{schemaVersion}', '"1.0.0"'::jsonb, true)
WHERE NOT payload_json ? 'schemaVersion';
ALTER TABLE opspilot_a2a.task_event
    ADD CONSTRAINT ck_a2a_event_payload_schema CHECK (payload_json ? 'schemaVersion');
UPDATE opspilot_a2a.agent_runtime_state
SET state_json = jsonb_set(state_json, '{schemaVersion}', to_jsonb(schema_version), true)
WHERE NOT state_json ? 'schemaVersion';
ALTER TABLE opspilot_a2a.agent_runtime_state
    ADD CONSTRAINT ck_agent_runtime_schema_version CHECK (
        state_json ? 'schemaVersion' AND state_json ->> 'schemaVersion' = schema_version);

CREATE INDEX ix_incident_run_incident_status ON opspilot.incident_run (incident_id, status);
CREATE INDEX ix_task_claim ON opspilot.task (priority DESC, available_at, task_id)
    WHERE status IN ('PENDING','RECOVERING');
CREATE INDEX ix_task_lease_expiry ON opspilot.task (lease_until) WHERE status IN ('LEASED','RUNNING');
CREATE INDEX ix_evidence_run_created ON opspilot.evidence (run_id, created_at);
CREATE INDEX ix_observation_batch_run_collected ON opspilot.observation_batch (run_id, collected_at);
CREATE INDEX ix_observation_record_batch_observed ON opspilot.observation_record (batch_id, observed_at);
CREATE INDEX ix_code_source_target ON opspilot.code_source (target_system_id, source_type);
CREATE INDEX ix_repository_source ON opspilot.code_repository (code_source_id, external_key);
CREATE INDEX ix_deployment_resource_repository ON opspilot.deployment_revision (resource_id, repository_id, deployed_at DESC);
CREATE INDEX ix_code_snapshot_run_repository ON opspilot.code_snapshot (run_id, repository_id);
CREATE INDEX ix_hypothesis_run_status ON opspilot.hypothesis (run_id, status);
CREATE INDEX ix_hypothesis_evidence_evidence ON opspilot.hypothesis_evidence (evidence_id, relation);
CREATE INDEX ix_hypothesis_verification_hypothesis ON opspilot.hypothesis_verification (hypothesis_id, created_at);
CREATE INDEX ix_sse_replay ON opspilot.sse_event (run_id, sequence_no);
CREATE INDEX ix_outbox_pending ON opspilot.outbox_event (occurred_at, event_id) WHERE published_at IS NULL;
CREATE INDEX ix_model_call_run_created ON opspilot.model_call (run_id, created_at);
CREATE INDEX ix_knowledge_document_collection_status ON opspilot.knowledge_document (collection_id, status) WHERE deleted_at IS NULL;
CREATE INDEX ix_knowledge_version_document_status ON opspilot.knowledge_document_version (document_id, status);
CREATE INDEX ix_knowledge_chunk_search ON opspilot.knowledge_chunk (document_version_id, searchable, ordinal) WHERE deleted_at IS NULL;
CREATE INDEX ix_ingestion_job_recovery ON opspilot.knowledge_ingestion_job (status, updated_at);

CREATE OR REPLACE FUNCTION opspilot.current_server_agent_id() RETURNS text
LANGUAGE sql STABLE PARALLEL SAFE AS $$
SELECT CASE current_user
    WHEN 'evidence_agent_role' THEN 'evidence-collector'
    WHEN 'evidence_agent_login' THEN 'evidence-collector'
    WHEN 'code_agent_role' THEN 'code-analysis'
    WHEN 'code_agent_login' THEN 'code-analysis'
    WHEN 'knowledge_agent_role' THEN 'knowledge'
    WHEN 'knowledge_agent_login' THEN 'knowledge'
    WHEN 'diagnosis_agent_role' THEN 'diagnosis'
    WHEN 'diagnosis_agent_login' THEN 'diagnosis'
    WHEN 'remediation_agent_role' THEN 'remediation'
    WHEN 'remediation_agent_login' THEN 'remediation'
    ELSE NULL
END
$$;

ALTER TABLE opspilot_a2a.task ENABLE ROW LEVEL SECURITY;
ALTER TABLE opspilot_a2a.task FORCE ROW LEVEL SECURITY;
ALTER TABLE opspilot_a2a.task_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE opspilot_a2a.task_event FORCE ROW LEVEL SECURITY;
ALTER TABLE opspilot_a2a.message ENABLE ROW LEVEL SECURITY;
ALTER TABLE opspilot_a2a.message FORCE ROW LEVEL SECURITY;
ALTER TABLE opspilot_a2a.artifact ENABLE ROW LEVEL SECURITY;
ALTER TABLE opspilot_a2a.artifact FORCE ROW LEVEL SECURITY;
ALTER TABLE opspilot_a2a.agent_runtime_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE opspilot_a2a.agent_runtime_state FORCE ROW LEVEL SECURITY;
ALTER TABLE opspilot_a2a.agent_scope_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE opspilot_a2a.agent_scope_state FORCE ROW LEVEL SECURITY;
CREATE POLICY task_agent_isolation ON opspilot_a2a.task
    USING (server_agent_id = opspilot.current_server_agent_id())
    WITH CHECK (server_agent_id = opspilot.current_server_agent_id());
CREATE POLICY task_event_agent_isolation ON opspilot_a2a.task_event
    USING (server_agent_id = opspilot.current_server_agent_id())
    WITH CHECK (server_agent_id = opspilot.current_server_agent_id());
CREATE POLICY message_agent_isolation ON opspilot_a2a.message
    USING (server_agent_id = opspilot.current_server_agent_id())
    WITH CHECK (server_agent_id = opspilot.current_server_agent_id());
CREATE POLICY artifact_agent_isolation ON opspilot_a2a.artifact
    USING (server_agent_id = opspilot.current_server_agent_id())
    WITH CHECK (server_agent_id = opspilot.current_server_agent_id());
CREATE POLICY runtime_state_agent_isolation ON opspilot_a2a.agent_runtime_state
    USING (server_agent_id = opspilot.current_server_agent_id())
    WITH CHECK (server_agent_id = opspilot.current_server_agent_id());
CREATE POLICY agent_scope_state_isolation ON opspilot_a2a.agent_scope_state
    USING (server_agent_id = opspilot.current_server_agent_id())
    WITH CHECK (server_agent_id = opspilot.current_server_agent_id());

CREATE VIEW opspilot.code_analysis_scope
WITH (security_barrier = true) AS
SELECT snapshot.run_id,
       snapshot.repository_id,
       snapshot.code_snapshot_id,
       snapshot.commit_sha,
       snapshot.manifest_artifact_id
FROM opspilot.code_snapshot snapshot
JOIN (
    SELECT run_id, repository_id
    FROM opspilot.code_snapshot
    GROUP BY run_id, repository_id
    HAVING count(*) = 1
) unambiguous USING (run_id, repository_id);

REVOKE ALL ON ALL TABLES IN SCHEMA opspilot FROM professional_agent_role;
REVOKE ALL ON ALL TABLES IN SCHEMA opspilot FROM evidence_agent_role, code_agent_role, knowledge_agent_role, diagnosis_agent_role, remediation_agent_role;
GRANT USAGE ON SCHEMA opspilot TO code_agent_role;
GRANT SELECT ON opspilot.code_analysis_scope TO code_agent_role;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA opspilot TO opspilot_app_role;
GRANT SELECT ON public.flyway_schema_history TO opspilot_app_role, professional_agent_role;
REVOKE ALL ON opspilot.evaluation_result FROM opspilot_app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA opspilot_a2a TO professional_agent_role;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA opspilot_a2a TO professional_agent_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA sample TO sample_app_role;

REVOKE ALL ON ALL TABLES IN SCHEMA opspilot_eval FROM PUBLIC, opspilot_app_role, professional_agent_role,
    evidence_agent_role, code_agent_role, knowledge_agent_role, diagnosis_agent_role, remediation_agent_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA opspilot_eval TO fault_lab_role;
GRANT SELECT ON ALL TABLES IN SCHEMA opspilot_eval TO evaluation_role;
REVOKE ALL ON ALL TABLES IN SCHEMA opspilot FROM evaluation_role;
GRANT SELECT, INSERT ON opspilot.evaluation_result TO evaluation_role;
GRANT SELECT, INSERT ON opspilot.artifact TO evaluation_role;

ALTER DEFAULT PRIVILEGES FOR ROLE opspilot_migrator IN SCHEMA opspilot REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE opspilot_migrator IN SCHEMA opspilot_a2a REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE opspilot_migrator IN SCHEMA sample REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE opspilot_migrator IN SCHEMA opspilot_eval REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE opspilot_migrator IN SCHEMA opspilot GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO opspilot_app_role;
ALTER DEFAULT PRIVILEGES FOR ROLE opspilot_migrator IN SCHEMA opspilot_a2a GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO professional_agent_role;
ALTER DEFAULT PRIVILEGES FOR ROLE opspilot_migrator IN SCHEMA sample GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sample_app_role;
ALTER DEFAULT PRIVILEGES FOR ROLE opspilot_migrator IN SCHEMA opspilot_eval GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO fault_lab_role;
ALTER DEFAULT PRIVILEGES FOR ROLE opspilot_migrator IN SCHEMA opspilot_eval GRANT SELECT ON TABLES TO evaluation_role;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
DO $$
BEGIN
    EXECUTE format(
        'REVOKE CREATE ON DATABASE %I FROM opspilot_app_role, sample_app_role, fault_lab_role, evaluation_role, professional_agent_role, evidence_agent_role, code_agent_role, knowledge_agent_role, diagnosis_agent_role, remediation_agent_role',
        current_database());
END $$;

CREATE TRIGGER tool_call_append_only BEFORE UPDATE OR DELETE ON opspilot.tool_call
FOR EACH ROW EXECUTE FUNCTION opspilot.reject_append_only_change();
CREATE TRIGGER model_call_append_only BEFORE UPDATE OR DELETE ON opspilot.model_call
FOR EACH ROW EXECUTE FUNCTION opspilot.reject_append_only_change();
CREATE TRIGGER sse_event_append_only BEFORE UPDATE OR DELETE ON opspilot.sse_event
FOR EACH ROW EXECUTE FUNCTION opspilot.reject_append_only_change();
