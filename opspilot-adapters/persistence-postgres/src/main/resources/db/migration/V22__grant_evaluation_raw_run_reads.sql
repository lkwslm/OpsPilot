GRANT SELECT ON
    opspilot.incident,
    opspilot.incident_run,
    opspilot.evidence,
    opspilot.rca_report,
    opspilot.tool_call,
    opspilot.model_call,
    opspilot.call_audit,
    opspilot.a2a_binding
TO evaluation_role;

COMMENT ON TABLE opspilot.evaluation_result IS
    'Evaluation 可从只读原始 Run/RCA/Evidence/调用事实独立复算，Ground Truth 仍仅存在于隔离评测边界';
