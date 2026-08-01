DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'knowledge_control_role') THEN
        CREATE ROLE knowledge_control_role NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
    END IF;
END
$$;

ALTER TABLE opspilot.knowledge_revision
    ADD COLUMN revision_key text,
    ADD COLUMN manifest_sha256 char(64),
    ADD COLUMN expires_at timestamptz,
    ADD CONSTRAINT ck_knowledge_revision_control_identity CHECK (
        (revision_key IS NULL AND manifest_sha256 IS NULL AND expires_at IS NULL)
        OR (btrim(revision_key) <> ''
            AND manifest_sha256 ~ '^[0-9a-f]{64}$'
            AND expires_at IS NOT NULL));

CREATE UNIQUE INDEX uq_knowledge_revision_control_key
    ON opspilot.knowledge_revision (collection_id, revision_key)
    WHERE revision_key IS NOT NULL;

CREATE TABLE opspilot.knowledge_activation_receipt (
    receipt_id uuid PRIMARY KEY,
    collection_id uuid NOT NULL REFERENCES opspilot.knowledge_collection (collection_id) ON DELETE RESTRICT,
    previous_revision_id uuid REFERENCES opspilot.knowledge_revision (knowledge_revision_id) ON DELETE RESTRICT,
    activated_revision_id uuid NOT NULL REFERENCES opspilot.knowledge_revision (knowledge_revision_id) ON DELETE RESTRICT,
    activate_operation_id text NOT NULL UNIQUE CHECK (btrim(activate_operation_id) <> ''),
    activated_by text NOT NULL CHECK (btrim(activated_by) <> ''),
    expires_at timestamptz NOT NULL,
    activated_at timestamptz NOT NULL,
    restore_operation_id text UNIQUE,
    restored_by text,
    restored_at timestamptz,
    CHECK ((restore_operation_id IS NULL AND restored_by IS NULL AND restored_at IS NULL)
        OR (restore_operation_id IS NOT NULL AND restored_by IS NOT NULL AND restored_at IS NOT NULL))
);

CREATE TABLE opspilot.knowledge_revision_control_audit (
    audit_id uuid PRIMARY KEY,
    operation_id text NOT NULL,
    principal_id text NOT NULL,
    action text NOT NULL CHECK (action IN ('PREPARE','ACTIVATE','RESTORE')),
    collection_id uuid NOT NULL REFERENCES opspilot.knowledge_collection (collection_id) ON DELETE RESTRICT,
    revision_id uuid NOT NULL REFERENCES opspilot.knowledge_revision (knowledge_revision_id) ON DELETE RESTRICT,
    receipt_id uuid REFERENCES opspilot.knowledge_activation_receipt (receipt_id) ON DELETE RESTRICT,
    occurred_at timestamptz NOT NULL,
    UNIQUE (operation_id, action)
);

REVOKE ALL ON
    opspilot.knowledge_activation_receipt,
    opspilot.knowledge_revision_control_audit
FROM PUBLIC, opspilot_app_role, fault_lab_role, evaluation_role, professional_agent_role;

GRANT USAGE ON SCHEMA opspilot TO knowledge_control_role;
GRANT SELECT ON
    opspilot.target_system,
    opspilot.model_provider,
    opspilot.model_profile,
    opspilot.model_revision,
    opspilot.knowledge_collection,
    opspilot.knowledge_revision,
    opspilot.knowledge_document,
    opspilot.knowledge_document_version,
    opspilot.knowledge_chunk,
    opspilot.knowledge_embedding,
    opspilot.knowledge_activation_receipt,
    opspilot.knowledge_revision_control_audit
TO knowledge_control_role;
GRANT INSERT, UPDATE ON
    opspilot.knowledge_collection,
    opspilot.knowledge_revision,
    opspilot.knowledge_document,
    opspilot.knowledge_document_version,
    opspilot.knowledge_chunk,
    opspilot.knowledge_embedding,
    opspilot.knowledge_activation_receipt,
    opspilot.knowledge_revision_control_audit
TO knowledge_control_role;
GRANT SELECT, INSERT ON
    opspilot.target_system,
    opspilot.incident,
    opspilot.incident_run,
    opspilot.artifact
TO knowledge_control_role;
