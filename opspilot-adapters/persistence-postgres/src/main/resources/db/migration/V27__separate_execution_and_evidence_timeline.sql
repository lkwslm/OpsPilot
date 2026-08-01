ALTER TABLE opspilot.incident_run
    ADD COLUMN execution_started_at timestamptz,
    ADD COLUMN execution_ended_at timestamptz;

UPDATE opspilot.incident_run
SET execution_started_at = started_at
WHERE execution_started_at IS NULL;

ALTER TABLE opspilot.incident_run
    ALTER COLUMN execution_started_at SET DEFAULT now(),
    ALTER COLUMN execution_started_at SET NOT NULL;

CREATE INDEX ix_incident_run_execution_deadline
    ON opspilot.incident_run (execution_started_at, deadline_seconds)
    WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED');

COMMENT ON COLUMN opspilot.incident_run.started_at IS
    'Beginning of the evidence/incident window used by citation validation';
COMMENT ON COLUMN opspilot.incident_run.execution_started_at IS
    'Beginning of product execution used for deadline and efficiency accounting';
COMMENT ON COLUMN opspilot.incident_run.execution_ended_at IS
    'End of product execution used for efficiency accounting';
