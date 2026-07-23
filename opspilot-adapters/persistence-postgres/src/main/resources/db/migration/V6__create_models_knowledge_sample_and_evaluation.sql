CREATE TABLE sample.customer_order (
    order_id uuid PRIMARY KEY,
    external_order_id text NOT NULL UNIQUE,
    status text NOT NULL CHECK (status IN ('CREATED','RESERVED','PAID','FULFILLED','CANCELLED','FAILED')),
    total_amount numeric(19,4) NOT NULL CHECK (total_amount >= 0),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE sample.inventory_item (
    sku text PRIMARY KEY,
    available_quantity bigint NOT NULL CHECK (available_quantity >= 0),
    reserved_quantity bigint NOT NULL CHECK (reserved_quantity >= 0),
    version bigint NOT NULL DEFAULT 0,
    CHECK (reserved_quantity <= available_quantity + reserved_quantity)
);
CREATE TABLE sample.order_line (
    order_id uuid NOT NULL REFERENCES sample.customer_order (order_id) ON DELETE CASCADE,
    line_number integer NOT NULL CHECK (line_number > 0),
    sku text NOT NULL REFERENCES sample.inventory_item (sku) ON DELETE RESTRICT,
    quantity bigint NOT NULL CHECK (quantity > 0),
    unit_price numeric(19,4) NOT NULL CHECK (unit_price >= 0),
    PRIMARY KEY (order_id, line_number)
);

CREATE TABLE opspilot.model_provider (
    provider_id uuid PRIMARY KEY,
    provider_key text NOT NULL UNIQUE,
    display_name text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE opspilot.model_profile (
    profile_id uuid PRIMARY KEY,
    provider_id uuid NOT NULL REFERENCES opspilot.model_provider (provider_id) ON DELETE RESTRICT,
    profile_key text NOT NULL,
    purpose text NOT NULL CHECK (purpose IN ('CHAT','EMBEDDING','RERANK')),
    config_json jsonb NOT NULL CHECK (config_json ? 'schemaVersion'),
    UNIQUE (provider_id, profile_key)
);
CREATE TABLE opspilot.model_revision (
    model_revision_id uuid PRIMARY KEY,
    profile_id uuid NOT NULL REFERENCES opspilot.model_profile (profile_id) ON DELETE RESTRICT,
    revision_key text NOT NULL,
    embedding_dimension integer NOT NULL CHECK (embedding_dimension > 0),
    distance_metric text NOT NULL CHECK (distance_metric IN ('COSINE','INNER_PRODUCT','L2')),
    normalization text NOT NULL CHECK (normalization IN ('NONE','L2')),
    active boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (profile_id, revision_key),
    UNIQUE (model_revision_id, embedding_dimension)
);
ALTER TABLE opspilot.model_call
    ADD CONSTRAINT fk_model_call_revision FOREIGN KEY (model_revision_id)
        REFERENCES opspilot.model_revision (model_revision_id) ON DELETE RESTRICT;
CREATE TABLE opspilot.prompt_template (
    prompt_id uuid PRIMARY KEY,
    prompt_key text NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    template_artifact_id uuid NOT NULL REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    input_schema_json jsonb NOT NULL CHECK (input_schema_json ? 'schemaVersion'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (prompt_key, version)
);
CREATE TABLE opspilot.model_pricing (
    pricing_id uuid PRIMARY KEY,
    model_revision_id uuid NOT NULL REFERENCES opspilot.model_revision (model_revision_id) ON DELETE RESTRICT,
    valid_from timestamptz NOT NULL,
    valid_to timestamptz,
    input_micros_per_token numeric(20,8) NOT NULL CHECK (input_micros_per_token >= 0),
    output_micros_per_token numeric(20,8) NOT NULL CHECK (output_micros_per_token >= 0),
    CHECK (valid_to IS NULL OR valid_to > valid_from),
    UNIQUE (model_revision_id, valid_from)
);
CREATE TABLE opspilot.model_usage (
    usage_id uuid PRIMARY KEY,
    model_call_id uuid NOT NULL UNIQUE REFERENCES opspilot.model_call (model_call_id) ON DELETE RESTRICT,
    input_tokens bigint NOT NULL CHECK (input_tokens >= 0),
    output_tokens bigint NOT NULL CHECK (output_tokens >= 0),
    cost_micros numeric(20,4) NOT NULL CHECK (cost_micros >= 0),
    recorded_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE opspilot.knowledge_collection (
    collection_id uuid PRIMARY KEY,
    collection_key text NOT NULL UNIQUE,
    active_model_revision_id uuid REFERENCES opspilot.model_revision (model_revision_id) ON DELETE RESTRICT,
    metadata_json jsonb NOT NULL DEFAULT '{"schemaVersion":"1.0.0"}'::jsonb CHECK (metadata_json ? 'schemaVersion'),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE opspilot.knowledge_document (
    document_id uuid PRIMARY KEY,
    collection_id uuid NOT NULL REFERENCES opspilot.knowledge_collection (collection_id) ON DELETE RESTRICT,
    external_key text NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE','INACTIVE','DELETED')),
    active_version_id uuid,
    deleted_at timestamptz,
    metadata_json jsonb NOT NULL DEFAULT '{"schemaVersion":"1.0.0"}'::jsonb CHECK (metadata_json ? 'schemaVersion'),
    UNIQUE (collection_id, external_key)
);
CREATE TABLE opspilot.knowledge_document_version (
    document_version_id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES opspilot.knowledge_document (document_id) ON DELETE RESTRICT,
    version_number integer NOT NULL CHECK (version_number > 0),
    status text NOT NULL CHECK (status IN ('BUILDING','ACTIVE','RETAINED','FAILED','DELETED')),
    content_sha256 char(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    source_artifact_id uuid NOT NULL REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    coverage_status text NOT NULL CHECK (coverage_status IN ('PENDING','PARTIAL','COMPLETE','FAILED')),
    expected_chunk_count integer NOT NULL CHECK (expected_chunk_count >= 0),
    completed_chunk_count integer NOT NULL DEFAULT 0 CHECK (completed_chunk_count >= 0),
    retain_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_id, version_number),
    CHECK (completed_chunk_count <= expected_chunk_count)
);
ALTER TABLE opspilot.knowledge_document
    ADD CONSTRAINT fk_knowledge_document_active_version FOREIGN KEY (active_version_id)
        REFERENCES opspilot.knowledge_document_version (document_version_id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX uq_document_one_active_version
    ON opspilot.knowledge_document_version (document_id) WHERE status = 'ACTIVE';

CREATE TABLE opspilot.knowledge_chunk (
    chunk_id uuid PRIMARY KEY,
    document_version_id uuid NOT NULL REFERENCES opspilot.knowledge_document_version (document_version_id) ON DELETE RESTRICT,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    content_sha256 char(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    content_artifact_id uuid NOT NULL REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    searchable boolean NOT NULL DEFAULT false,
    deleted_at timestamptz,
    metadata_json jsonb NOT NULL DEFAULT '{"schemaVersion":"1.0.0"}'::jsonb CHECK (metadata_json ? 'schemaVersion'),
    UNIQUE (document_version_id, ordinal)
);
CREATE TABLE opspilot.knowledge_embedding (
    chunk_id uuid NOT NULL REFERENCES opspilot.knowledge_chunk (chunk_id) ON DELETE RESTRICT,
    model_revision_id uuid NOT NULL,
    embedding_dimension integer NOT NULL CHECK (embedding_dimension > 0),
    embedding vector NOT NULL,
    content_sha256 char(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (chunk_id, model_revision_id),
    CONSTRAINT fk_embedding_revision_dimension FOREIGN KEY (model_revision_id, embedding_dimension)
        REFERENCES opspilot.model_revision (model_revision_id, embedding_dimension) ON DELETE RESTRICT,
    CONSTRAINT ck_embedding_actual_dimension CHECK (vector_dims(embedding) = embedding_dimension)
);
CREATE TABLE opspilot.knowledge_ingestion_job (
    job_id uuid PRIMARY KEY,
    document_version_id uuid NOT NULL REFERENCES opspilot.knowledge_document_version (document_version_id) ON DELETE RESTRICT,
    model_revision_id uuid NOT NULL REFERENCES opspilot.model_revision (model_revision_id) ON DELETE RESTRICT,
    status text NOT NULL CHECK (status IN ('PENDING','RUNNING','RECOVERING','COMPLETED','FAILED')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    checkpoint_ordinal integer NOT NULL DEFAULT 0 CHECK (checkpoint_ordinal >= 0),
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_version_id, model_revision_id)
);
CREATE TABLE opspilot.knowledge_ingestion_failure (
    job_id uuid NOT NULL REFERENCES opspilot.knowledge_ingestion_job (job_id) ON DELETE CASCADE,
    chunk_id uuid NOT NULL REFERENCES opspilot.knowledge_chunk (chunk_id) ON DELETE RESTRICT,
    attempt integer NOT NULL CHECK (attempt > 0),
    error_code text NOT NULL,
    error_summary text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, chunk_id, attempt)
);

ALTER TABLE opspilot_eval.ground_truth
    ADD COLUMN scenario_version integer NOT NULL DEFAULT 1 CHECK (scenario_version > 0),
    ADD COLUMN lifecycle_status text NOT NULL DEFAULT 'ACTIVE' CHECK (lifecycle_status IN ('ACTIVE','DEPRECATED')),
    ADD COLUMN retired_at timestamptz;
UPDATE opspilot_eval.ground_truth
SET expected_evidence = jsonb_set(expected_evidence, '{schemaVersion}', '"1.0.0"'::jsonb, true),
    expected_actions = jsonb_set(expected_actions, '{schemaVersion}', '"1.0.0"'::jsonb, true)
WHERE NOT expected_evidence ? 'schemaVersion' OR NOT expected_actions ? 'schemaVersion';
ALTER TABLE opspilot_eval.ground_truth
    ADD CONSTRAINT ck_ground_truth_evidence_schema CHECK (expected_evidence ? 'schemaVersion'),
    ADD CONSTRAINT ck_ground_truth_actions_schema CHECK (expected_actions ? 'schemaVersion');

CREATE OR REPLACE FUNCTION opspilot.reject_model_revision_identity_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.embedding_dimension <> NEW.embedding_dimension
       OR OLD.distance_metric <> NEW.distance_metric
       OR OLD.normalization <> NEW.normalization THEN
        RAISE EXCEPTION 'MODEL_REVISION_IMMUTABLE' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER model_revision_identity_immutable
BEFORE UPDATE ON opspilot.model_revision FOR EACH ROW
EXECUTE FUNCTION opspilot.reject_model_revision_identity_change();

CREATE OR REPLACE FUNCTION opspilot.validate_embedding() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE metric text;
BEGIN
    SELECT distance_metric INTO STRICT metric
    FROM opspilot.model_revision WHERE model_revision_id = NEW.model_revision_id;
    IF NEW.embedding::text ~* 'nan|infinity' THEN
        RAISE EXCEPTION 'VECTOR_NON_FINITE' USING ERRCODE = '22003';
    END IF;
    IF metric = 'COSINE' AND vector_norm(NEW.embedding) = 0 THEN
        RAISE EXCEPTION 'VECTOR_ZERO_NORM' USING ERRCODE = '22023';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER knowledge_embedding_validate
BEFORE INSERT OR UPDATE ON opspilot.knowledge_embedding FOR EACH ROW
EXECUTE FUNCTION opspilot.validate_embedding();

CREATE OR REPLACE FUNCTION opspilot.reject_sealed_analysis_write() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE candidate_run uuid;
BEGIN
    IF TG_TABLE_NAME = 'evidence' THEN
        candidate_run := COALESCE(NEW.run_id, OLD.run_id);
    ELSIF TG_TABLE_NAME = 'hypothesis' THEN
        candidate_run := COALESCE(NEW.run_id, OLD.run_id);
    ELSE
        SELECT run_id INTO candidate_run FROM opspilot.hypothesis
        WHERE hypothesis_id = COALESCE(NEW.hypothesis_id, OLD.hypothesis_id);
    END IF;
    IF EXISTS (SELECT 1 FROM opspilot.incident_run WHERE run_id = candidate_run AND analysis_sealed_at IS NOT NULL) THEN
        RAISE EXCEPTION 'ANALYSIS_SEALED' USING ERRCODE = '55000';
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$;
CREATE TRIGGER evidence_sealed_guard BEFORE INSERT OR UPDATE OR DELETE ON opspilot.evidence
FOR EACH ROW EXECUTE FUNCTION opspilot.reject_sealed_analysis_write();
CREATE TRIGGER hypothesis_sealed_guard BEFORE INSERT OR UPDATE OR DELETE ON opspilot.hypothesis
FOR EACH ROW EXECUTE FUNCTION opspilot.reject_sealed_analysis_write();
CREATE TRIGGER hypothesis_evidence_sealed_guard BEFORE INSERT OR UPDATE OR DELETE ON opspilot.hypothesis_evidence
FOR EACH ROW EXECUTE FUNCTION opspilot.reject_sealed_analysis_write();
CREATE TRIGGER hypothesis_verification_sealed_guard BEFORE INSERT OR UPDATE OR DELETE ON opspilot.hypothesis_verification
FOR EACH ROW EXECUTE FUNCTION opspilot.reject_sealed_analysis_write();
