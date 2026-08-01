CREATE OR REPLACE FUNCTION opspilot.read_release_run_evidence(p_run_id uuid)
RETURNS TABLE (
    rca_json jsonb,
    rca_markdown text,
    evaluation_json jsonb,
    provider_calls jsonb,
    tool_calls jsonb,
    a2a_calls jsonb,
    usage_ledger jsonb,
    state_events jsonb,
    citations jsonb,
    artifact_index jsonb
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
    WITH latest_rca AS (
        SELECT report_json, report_markdown
        FROM opspilot.rca_report
        WHERE run_id = p_run_id
        ORDER BY run_version DESC
        LIMIT 1
    ), latest_evaluation AS (
        SELECT status, profile_id, profile_version, scenario_id,
               profile_snapshot_sha256, result_digest,
               COALESCE(report_json, metrics_json) AS report_json
        FROM opspilot.evaluation_result
        WHERE run_id = p_run_id
        ORDER BY created_at DESC
        LIMIT 1
    )
    SELECT
        rca.report_json,
        rca.report_markdown,
        jsonb_build_object(
            'status', evaluation.status,
            'profileId', evaluation.profile_id,
            'profileVersion', evaluation.profile_version,
            'scenarioId', evaluation.scenario_id,
            'profileSnapshotSha256', evaluation.profile_snapshot_sha256,
            'resultDigest', evaluation.result_digest,
            'report', evaluation.report_json
        ),
        COALESCE((
            SELECT jsonb_agg(to_jsonb(model_call) ORDER BY model_call.created_at)
            FROM opspilot.model_call
            WHERE run_id = p_run_id
        ), '[]'::jsonb),
        COALESCE((
            SELECT jsonb_agg(to_jsonb(tool_call) ORDER BY tool_call.created_at)
            FROM opspilot.tool_call
            WHERE run_id = p_run_id
        ), '[]'::jsonb),
        jsonb_build_object(
            'audit', COALESCE((
                SELECT jsonb_agg(to_jsonb(call_audit) ORDER BY call_audit.occurred_at)
                FROM opspilot.call_audit
                WHERE run_id = p_run_id AND call_kind = 'A2A'
            ), '[]'::jsonb),
            'tasks', COALESCE((
                SELECT jsonb_agg(
                    jsonb_build_object(
                        'agentId', task.server_agent_id,
                        'taskId', task.task_id,
                        'messageId', task.message_id,
                        'contextId', task.context_id,
                        'state', task.state,
                        'artifact', task.artifact_payload::jsonb
                    ) ORDER BY task.server_agent_id
                )
                FROM opspilot_a2a.task
                WHERE run_id = p_run_id
            ), '[]'::jsonb)
        ),
        COALESCE((
            SELECT jsonb_agg(to_jsonb(model_usage) ORDER BY model_usage.recorded_at)
            FROM opspilot.model_usage
            JOIN opspilot.model_call
              ON model_call.model_call_id = model_usage.model_call_id
            WHERE model_call.run_id = p_run_id
        ), '[]'::jsonb),
        COALESCE((
            SELECT jsonb_agg(to_jsonb(sse_event) ORDER BY sse_event.sequence_no)
            FROM opspilot.sse_event
            WHERE run_id = p_run_id
        ), '[]'::jsonb),
        COALESCE(rca.report_json->'citations', '[]'::jsonb),
        COALESCE((
            SELECT jsonb_agg(to_jsonb(artifact) ORDER BY artifact.created_at, artifact.artifact_id)
            FROM opspilot.artifact
            WHERE run_id = p_run_id
        ), '[]'::jsonb)
    FROM latest_rca rca
    JOIN latest_evaluation evaluation ON true
$$;

REVOKE ALL ON FUNCTION opspilot.read_release_run_evidence(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION opspilot.read_release_run_evidence(uuid) TO fault_lab_role;

COMMENT ON FUNCTION opspilot.read_release_run_evidence(uuid) IS
    'Run-scoped immutable release evidence projection for Fault Lab; exposes no arbitrary table reads';
