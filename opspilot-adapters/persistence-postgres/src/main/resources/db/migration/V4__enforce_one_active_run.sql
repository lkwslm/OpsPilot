CREATE UNIQUE INDEX uq_incident_one_active_run
    ON opspilot.incident_run (incident_id)
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED');
