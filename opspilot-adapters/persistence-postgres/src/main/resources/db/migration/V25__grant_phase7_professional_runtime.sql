GRANT INSERT ON opspilot.model_call, opspilot.model_usage
TO evidence_agent_role, code_agent_role, knowledge_agent_role,
   diagnosis_agent_role, remediation_agent_role;

GRANT SELECT ON
    opspilot.knowledge_collection,
    opspilot.knowledge_revision,
    opspilot.knowledge_document,
    opspilot.knowledge_document_version,
    opspilot.knowledge_chunk,
    opspilot.knowledge_embedding,
    opspilot.artifact,
    opspilot.incident_run
TO knowledge_agent_role;

GRANT INSERT ON opspilot.knowledge_reference TO knowledge_agent_role;

COMMENT ON TABLE opspilot.model_usage IS
    'Append-only provider usage written by the process-owned professional AgentScope identity';
