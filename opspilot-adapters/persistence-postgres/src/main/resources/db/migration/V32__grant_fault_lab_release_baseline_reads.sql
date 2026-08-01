GRANT SELECT ON
    opspilot.incident_run,
    opspilot.model_call,
    opspilot.model_usage
TO fault_lab_role;

COMMENT ON TABLE opspilot.model_usage IS
    'Append-only Provider usage facts; fault_lab_role has read-only access for release baseline and reconciliation evidence';
