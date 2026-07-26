CREATE TABLE opspilot.knowledge_revision (
    knowledge_revision_id uuid PRIMARY KEY,
    collection_id uuid NOT NULL REFERENCES opspilot.knowledge_collection (collection_id) ON DELETE RESTRICT,
    status text NOT NULL CHECK (status IN ('BUILDING','READY','ACTIVE','RETAINED','FAILED','DELETED')),
    normalization_version text NOT NULL CHECK (btrim(normalization_version) <> ''),
    chunk_strategy_version text NOT NULL CHECK (btrim(chunk_strategy_version) <> ''),
    model_revision_id uuid NOT NULL REFERENCES opspilot.model_revision (model_revision_id) ON DELETE RESTRICT,
    embedding_dimension integer NOT NULL CHECK (embedding_dimension > 0),
    coverage_status text NOT NULL CHECK (coverage_status IN ('PENDING','PARTIAL','COMPLETE','FAILED')),
    expected_chunk_count integer NOT NULL DEFAULT 0 CHECK (expected_chunk_count >= 0),
    completed_chunk_count integer NOT NULL DEFAULT 0 CHECK (completed_chunk_count >= 0),
    retain_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    activated_at timestamptz,
    CHECK (completed_chunk_count <= expected_chunk_count)
);
CREATE UNIQUE INDEX uq_knowledge_collection_active_revision
    ON opspilot.knowledge_revision (collection_id) WHERE status = 'ACTIVE';

CREATE TABLE opspilot.knowledge_ingestion_request (
    idempotency_key text PRIMARY KEY CHECK (btrim(idempotency_key) <> ''),
    request_sha256 char(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    document_id uuid NOT NULL REFERENCES opspilot.knowledge_document (document_id) ON DELETE RESTRICT,
    document_version_id uuid NOT NULL REFERENCES opspilot.knowledge_document_version (document_version_id) ON DELETE RESTRICT,
    job_id uuid NOT NULL REFERENCES opspilot.knowledge_ingestion_job (job_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE opspilot.knowledge_document_version
    ADD COLUMN knowledge_revision_id uuid REFERENCES opspilot.knowledge_revision (knowledge_revision_id) ON DELETE RESTRICT,
    ADD COLUMN normalization_version text NOT NULL DEFAULT 'legacy',
    ADD COLUMN chunk_strategy_version text NOT NULL DEFAULT 'legacy',
    ADD COLUMN acl_json jsonb NOT NULL DEFAULT '{"principals":[]}'::jsonb,
    ADD CONSTRAINT ck_knowledge_version_normalization_nonblank CHECK (btrim(normalization_version) <> ''),
    ADD CONSTRAINT ck_knowledge_version_chunk_strategy_nonblank CHECK (btrim(chunk_strategy_version) <> ''),
    ADD CONSTRAINT ck_knowledge_version_acl_principals CHECK (acl_json ? 'principals');

ALTER TABLE opspilot.knowledge_chunk
    ADD COLUMN source_location text NOT NULL DEFAULT 'legacy',
    ADD COLUMN acl_json jsonb NOT NULL DEFAULT '{"principals":[]}'::jsonb,
    ADD CONSTRAINT ck_knowledge_chunk_location_nonblank CHECK (btrim(source_location) <> ''),
    ADD CONSTRAINT ck_knowledge_chunk_acl_principals CHECK (acl_json ? 'principals');

CREATE INDEX ix_knowledge_revision_recovery
    ON opspilot.knowledge_revision (status, coverage_status, created_at);
