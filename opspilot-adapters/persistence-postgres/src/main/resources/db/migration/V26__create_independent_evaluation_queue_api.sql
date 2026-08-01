CREATE OR REPLACE FUNCTION opspilot.claim_evaluation_task(p_worker text, p_lease_seconds integer)
RETURNS TABLE(task_id uuid, run_id uuid, payload_json jsonb)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    IF p_worker IS NULL OR btrim(p_worker) = '' OR p_lease_seconds IS NULL
       OR p_lease_seconds < 5 OR p_lease_seconds > 600 THEN
        RAISE EXCEPTION 'EVALUATION_CLAIM_ARGUMENT_INVALID' USING ERRCODE = '22023';
    END IF;
    UPDATE opspilot.task task
    SET status=CASE WHEN task.attempt_count < task.max_attempts THEN 'RECOVERING' ELSE 'FAILED' END,
        lease_owner=NULL, lease_until=NULL,
        available_at=CASE WHEN task.attempt_count < task.max_attempts THEN now() ELSE task.available_at END
    WHERE task.task_type='EVALUATION' AND task.status='RUNNING'
      AND task.lease_until IS NOT NULL AND task.lease_until <= now();
    RETURN QUERY
    WITH candidate AS (
        SELECT task.task_id
        FROM opspilot.task task
        WHERE task.task_type = 'EVALUATION'
          AND task.status IN ('PENDING','RECOVERING')
          AND task.available_at <= now()
          AND task.attempt_count < task.max_attempts
        ORDER BY task.priority DESC, task.available_at, task.task_id
        FOR UPDATE SKIP LOCKED
        LIMIT 1
    ), claimed AS (
        UPDATE opspilot.task task
        SET status='RUNNING', lease_owner=p_worker,
            lease_until=now() + make_interval(secs => p_lease_seconds),
            attempt_count=task.attempt_count+1
        FROM candidate
        WHERE task.task_id=candidate.task_id
        RETURNING task.task_id, task.run_id, task.payload_json
    )
    SELECT claimed.task_id, claimed.run_id, claimed.payload_json FROM claimed;
END $$;

CREATE OR REPLACE FUNCTION opspilot.complete_evaluation_task(p_task_id uuid, p_worker text)
RETURNS boolean
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
    UPDATE opspilot.task
    SET status='COMPLETED', lease_owner=NULL, lease_until=NULL,
        side_effect_committed_at=now()
    WHERE task_id=p_task_id AND task_type='EVALUATION'
      AND status='RUNNING' AND lease_owner=p_worker
    RETURNING true
$$;

CREATE OR REPLACE FUNCTION opspilot.fail_evaluation_task(
    p_task_id uuid, p_worker text, p_retry_delay_seconds integer)
RETURNS boolean
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
    UPDATE opspilot.task
    SET status=CASE WHEN attempt_count < max_attempts THEN 'RECOVERING' ELSE 'FAILED' END,
        available_at=CASE WHEN attempt_count < max_attempts
            THEN now() + make_interval(secs => greatest(1,least(p_retry_delay_seconds,300)))
            ELSE available_at END,
        lease_owner=NULL, lease_until=NULL
    WHERE task_id=p_task_id AND task_type='EVALUATION'
      AND status='RUNNING' AND lease_owner=p_worker
    RETURNING true
$$;

REVOKE ALL ON FUNCTION opspilot.claim_evaluation_task(text,integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION opspilot.complete_evaluation_task(uuid,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION opspilot.fail_evaluation_task(uuid,text,integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION opspilot.claim_evaluation_task(text,integer) TO evaluation_role;
GRANT EXECUTE ON FUNCTION opspilot.complete_evaluation_task(uuid,text) TO evaluation_role;
GRANT EXECUTE ON FUNCTION opspilot.fail_evaluation_task(uuid,text,integer) TO evaluation_role;
