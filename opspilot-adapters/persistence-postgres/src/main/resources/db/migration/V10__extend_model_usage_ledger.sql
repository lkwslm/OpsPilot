ALTER TABLE opspilot.model_usage
    ALTER COLUMN cost_micros DROP NOT NULL,
    ADD COLUMN cached_tokens bigint NOT NULL DEFAULT 0 CHECK (cached_tokens >= 0),
    ADD COLUMN cost_estimated boolean NOT NULL DEFAULT false,
    ADD COLUMN price_table_version text,
    ADD COLUMN usage_source text NOT NULL DEFAULT 'PROVIDER'
        CHECK (usage_source IN ('PROVIDER', 'CONSERVATIVE_ESTIMATE')),
    ADD COLUMN estimator_version text,
    ADD COLUMN attempt_outcome text NOT NULL DEFAULT 'SUCCEEDED'
        CHECK (attempt_outcome IN ('SUCCEEDED', 'FAILED', 'CANCELLED')),
    ADD COLUMN incident_key text NOT NULL DEFAULT 'legacy',
    ADD COLUMN task_key text NOT NULL DEFAULT 'legacy',
    ADD COLUMN agent_key text NOT NULL DEFAULT 'legacy',
    ADD CONSTRAINT ck_model_usage_estimator_source CHECK (
        (usage_source = 'PROVIDER' AND estimator_version IS NULL)
        OR (usage_source = 'CONSERVATIVE_ESTIMATE' AND estimator_version IS NOT NULL)),
    ADD CONSTRAINT ck_model_usage_estimated_cost CHECK (NOT cost_estimated OR cost_micros IS NOT NULL);

CREATE TRIGGER model_usage_append_only BEFORE UPDATE OR DELETE ON opspilot.model_usage
FOR EACH ROW EXECUTE FUNCTION opspilot.reject_append_only_change();
