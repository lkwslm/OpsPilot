ALTER TABLE opspilot.knowledge_collection
    ADD COLUMN active_knowledge_revision_id uuid
        REFERENCES opspilot.knowledge_revision (knowledge_revision_id) ON DELETE RESTRICT;

ALTER TABLE opspilot.incident_run
    ADD COLUMN effective_knowledge_revision_id uuid
        REFERENCES opspilot.knowledge_revision (knowledge_revision_id) ON DELETE RESTRICT;

ALTER TABLE opspilot.knowledge_revision
    ADD COLUMN searchable boolean NOT NULL DEFAULT false;

CREATE TABLE opspilot.knowledge_reference (
    knowledge_reference_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES opspilot.incident_run (run_id) ON DELETE RESTRICT,
    collection_id uuid NOT NULL REFERENCES opspilot.knowledge_collection (collection_id) ON DELETE RESTRICT,
    document_id uuid NOT NULL REFERENCES opspilot.knowledge_document (document_id) ON DELETE RESTRICT,
    document_version_id uuid NOT NULL REFERENCES opspilot.knowledge_document_version (document_version_id) ON DELETE RESTRICT,
    chunk_id uuid NOT NULL REFERENCES opspilot.knowledge_chunk (chunk_id) ON DELETE RESTRICT,
    knowledge_revision_id uuid NOT NULL REFERENCES opspilot.knowledge_revision (knowledge_revision_id) ON DELETE RESTRICT,
    model_revision_id uuid NOT NULL REFERENCES opspilot.model_revision (model_revision_id) ON DELETE RESTRICT,
    artifact_id uuid NOT NULL REFERENCES opspilot.artifact (artifact_id) ON DELETE RESTRICT,
    source_location text NOT NULL CHECK (btrim(source_location) <> ''),
    content_sha256 char(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    filter_summary_sha256 char(64) NOT NULL CHECK (filter_summary_sha256 ~ '^[0-9a-f]{64}$'),
    vector_distance double precision NOT NULL CHECK (
        vector_distance BETWEEN '-1.7976931348623157e308'::float8 AND '1.7976931348623157e308'::float8),
    rerank_score double precision NOT NULL CHECK (
        rerank_score BETWEEN '-1.7976931348623157e308'::float8 AND '1.7976931348623157e308'::float8),
    rerank_rank integer NOT NULL CHECK (rerank_rank > 0),
    rerank_provider_id text NOT NULL CHECK (btrim(rerank_provider_id) <> ''),
    rerank_model_id text NOT NULL CHECK (btrim(rerank_model_id) <> ''),
    rerank_revision text NOT NULL CHECK (btrim(rerank_revision) <> ''),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, knowledge_reference_id)
);

CREATE INDEX ix_knowledge_reference_history
    ON opspilot.knowledge_reference (run_id, knowledge_revision_id, document_version_id);
