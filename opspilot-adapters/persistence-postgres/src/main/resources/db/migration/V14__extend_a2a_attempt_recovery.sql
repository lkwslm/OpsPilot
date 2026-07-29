ALTER TABLE opspilot.step_attempt
    ADD COLUMN message_id text,
    ADD COLUMN remote_agent_id text,
    ADD COLUMN remote_task_state text,
    ADD COLUMN artifact_cursor bigint NOT NULL DEFAULT 0 CHECK (artifact_cursor >= 0),
    ADD COLUMN session_id text,
    ADD COLUMN cancel_requested_at timestamptz,
    ADD COLUMN cancel_propagated_at timestamptz,
    ADD COLUMN recovery_owner text,
    ADD COLUMN recovery_lease_until timestamptz,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

UPDATE opspilot.step_attempt attempt
SET message_id = attempt.idempotency_key,
    session_id = 'legacy:' || attempt.attempt_id::text,
    remote_agent_id = (
        SELECT binding.server_agent_id
        FROM opspilot.a2a_binding binding
        WHERE binding.step_id = attempt.step_id
          AND binding.remote_task_id = attempt.remote_task_id
        LIMIT 1),
    remote_task_state = CASE WHEN attempt.remote_task_id IS NULL THEN NULL ELSE 'SUBMITTED' END;

ALTER TABLE opspilot.step_attempt
    ALTER COLUMN message_id SET NOT NULL,
    ALTER COLUMN session_id SET NOT NULL,
    ADD CONSTRAINT ck_step_attempt_remote_binding CHECK (
        (remote_task_id IS NULL AND remote_task_state IS NULL)
        OR (remote_task_id IS NOT NULL AND remote_agent_id IS NOT NULL AND remote_task_state IS NOT NULL)),
    ADD CONSTRAINT ck_step_attempt_remote_state CHECK (
        remote_task_state IS NULL OR remote_task_state IN (
            'SUBMITTED','WORKING','INPUT_REQUIRED','AUTH_REQUIRED',
            'COMPLETED','FAILED','CANCELED','REJECTED')),
    ADD CONSTRAINT ck_step_attempt_cancel_order CHECK (
        cancel_propagated_at IS NULL OR cancel_requested_at IS NOT NULL),
    ADD CONSTRAINT ck_step_attempt_recovery_lease CHECK (
        (recovery_owner IS NULL) = (recovery_lease_until IS NULL));

CREATE UNIQUE INDEX uq_step_attempt_remote_task
ON opspilot.step_attempt (remote_agent_id, remote_task_id)
WHERE remote_task_id IS NOT NULL;

CREATE UNIQUE INDEX uq_step_attempt_message
ON opspilot.step_attempt (remote_agent_id, message_id)
WHERE remote_agent_id IS NOT NULL;

CREATE INDEX ix_step_attempt_recovery
ON opspilot.step_attempt (status, recovery_lease_until, updated_at)
WHERE status IN ('DISPATCHING','RECONCILING','QUEUED','RUNNING','WAITING_INPUT',
                 'WAITING_AUTH','VALIDATING_RESULT','CANCEL_REQUESTED');
