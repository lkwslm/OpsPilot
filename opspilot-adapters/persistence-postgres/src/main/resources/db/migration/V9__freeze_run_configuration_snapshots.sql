CREATE OR REPLACE FUNCTION opspilot.jsonb_has_sensitive_key(candidate jsonb)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
DECLARE
    entry record;
    normalized_key text;
BEGIN
    IF jsonb_typeof(candidate) = 'object' THEN
        FOR entry IN SELECT key, value FROM jsonb_each(candidate)
        LOOP
            normalized_key := regexp_replace(lower(entry.key), '[_-]', '', 'g');
            IF normalized_key IN (
                'apikey', 'secret', 'secretvalue', 'password', 'accesstoken',
                'bearertoken', 'credential', 'credentials', 'privatekey'
            ) OR opspilot.jsonb_has_sensitive_key(entry.value) THEN
                RETURN true;
            END IF;
        END LOOP;
    ELSIF jsonb_typeof(candidate) = 'array' THEN
        FOR entry IN SELECT value FROM jsonb_array_elements(candidate)
        LOOP
            IF opspilot.jsonb_has_sensitive_key(entry.value) THEN
                RETURN true;
            END IF;
        END LOOP;
    END IF;
    RETURN false;
END;
$$;

ALTER TABLE opspilot.incident_run
    ADD COLUMN model_configuration_version text NOT NULL DEFAULT 'legacy',
    ADD COLUMN knowledge_configuration_version text NOT NULL DEFAULT 'legacy',
    ADD COLUMN effective_model_configuration_json jsonb NOT NULL
        DEFAULT '{"schemaVersion":"1.0.0","legacy":true}'::jsonb,
    ADD COLUMN effective_knowledge_configuration_json jsonb NOT NULL
        DEFAULT '{"schemaVersion":"1.0.0","legacy":true}'::jsonb,
    ADD CONSTRAINT ck_run_model_configuration_version_nonblank
        CHECK (btrim(model_configuration_version) <> ''),
    ADD CONSTRAINT ck_run_knowledge_configuration_version_nonblank
        CHECK (btrim(knowledge_configuration_version) <> ''),
    ADD CONSTRAINT ck_run_model_snapshot_schema
        CHECK (effective_model_configuration_json ? 'schemaVersion'),
    ADD CONSTRAINT ck_run_knowledge_snapshot_schema
        CHECK (effective_knowledge_configuration_json ? 'schemaVersion'),
    ADD CONSTRAINT ck_run_model_snapshot_non_sensitive
        CHECK (NOT opspilot.jsonb_has_sensitive_key(effective_model_configuration_json)),
    ADD CONSTRAINT ck_run_knowledge_snapshot_non_sensitive
        CHECK (NOT opspilot.jsonb_has_sensitive_key(effective_knowledge_configuration_json));
