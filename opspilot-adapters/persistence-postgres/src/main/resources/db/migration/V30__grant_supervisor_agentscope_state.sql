GRANT USAGE ON SCHEMA opspilot_a2a TO opspilot_app_role;

GRANT SELECT, INSERT, UPDATE, DELETE ON opspilot_a2a.agent_scope_state
TO opspilot_app_role;

COMMENT ON TABLE opspilot_a2a.agent_scope_state IS
    'RLS-isolated AgentScope checkpoints shared by professional agents and the Supervisor runtime';
