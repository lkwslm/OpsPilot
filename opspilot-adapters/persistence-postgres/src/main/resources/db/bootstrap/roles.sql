DO $$
DECLARE
    role_name text;
BEGIN
    FOREACH role_name IN ARRAY ARRAY[
        'opspilot_migrator',
        'opspilot_app_role',
        'sample_app_role',
        'fault_lab_role',
        'evaluation_role',
        'professional_agent_role',
        'evidence_agent_role',
        'code_agent_role',
        'knowledge_agent_role',
        'knowledge_control_role',
        'diagnosis_agent_role',
        'remediation_agent_role'
    ]
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
            EXECUTE format('CREATE ROLE %I NOLOGIN', role_name);
        END IF;
    END LOOP;
END
$$;

GRANT professional_agent_role TO
    evidence_agent_role,
    code_agent_role,
    knowledge_agent_role,
    diagnosis_agent_role,
    remediation_agent_role;
