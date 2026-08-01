GRANT SELECT ON opspilot.model_revision TO knowledge_agent_role;

COMMENT ON TABLE opspilot.model_revision IS
    'Immutable model identity metadata readable by the Knowledge Agent for frozen pgvector search validation';
