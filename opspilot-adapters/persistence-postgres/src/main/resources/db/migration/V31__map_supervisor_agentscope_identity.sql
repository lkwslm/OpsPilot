CREATE OR REPLACE FUNCTION opspilot.current_server_agent_id() RETURNS text
LANGUAGE sql STABLE PARALLEL SAFE AS $$
SELECT CASE current_user
    WHEN 'opspilot_app_role' THEN 'supervisor'
    WHEN 'opspilot_app_login' THEN 'supervisor'
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
