CREATE POLICY task_release_identity_reader ON opspilot_a2a.task
    FOR SELECT TO opspilot_migrator
    USING (true);

CREATE POLICY agent_scope_release_identity_reader ON opspilot_a2a.agent_scope_state
    FOR SELECT TO opspilot_migrator
    USING (true);

CREATE OR REPLACE FUNCTION opspilot.read_release_run_identity(
    p_incident_id uuid,
    p_run_id uuid
)
RETURNS TABLE (
    run_status text,
    execution_started_at timestamptz,
    execution_ended_at timestamptz,
    product_task_id text,
    agent_id text,
    a2a_task_id text,
    a2a_context_id text,
    message_id text,
    professional_session_id text,
    supervisor_session_id text
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
    SELECT DISTINCT
           run.status,
           run.execution_started_at,
           run.execution_ended_at,
           product_task.task_id::text,
           a2a.server_agent_id,
           a2a.task_id,
           a2a.context_id,
           a2a.message_id,
           professional.session_id,
           supervisor.session_id
    FROM opspilot.incident_run run
    JOIN opspilot.task product_task
      ON product_task.run_id = run.run_id
     AND product_task.task_type = 'PRODUCT_RUN'
    JOIN opspilot_a2a.task a2a
      ON a2a.run_id = run.run_id
     AND a2a.state = 'COMPLETED'
    LEFT JOIN opspilot_a2a.agent_scope_state professional
      ON professional.server_agent_id = a2a.server_agent_id
     AND professional.session_id = a2a.server_agent_id || ':' || a2a.task_id
    LEFT JOIN opspilot_a2a.agent_scope_state supervisor
      ON supervisor.server_agent_id = 'supervisor'
     AND supervisor.session_id = 'supervisor:' || product_task.task_id::text
    WHERE run.incident_id = p_incident_id
      AND run.run_id = p_run_id
    ORDER BY a2a.server_agent_id
$$;

REVOKE ALL ON FUNCTION opspilot.read_release_run_identity(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION opspilot.read_release_run_identity(uuid, uuid) TO fault_lab_role;

COMMENT ON FUNCTION opspilot.read_release_run_identity(uuid, uuid) IS
    'Read-only, Run-scoped release identity view for Fault Lab; does not grant direct A2A or AgentScope table access';
