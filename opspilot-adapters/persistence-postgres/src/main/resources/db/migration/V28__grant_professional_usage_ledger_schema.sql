GRANT USAGE ON SCHEMA opspilot TO
    evidence_agent_role,
    code_agent_role,
    knowledge_agent_role,
    diagnosis_agent_role,
    remediation_agent_role;

COMMENT ON SCHEMA opspilot IS
    'OpsPilot application schema; professional roles require USAGE before their explicit table grants are effective';
