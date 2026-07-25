CREATE TABLE sample.orders (
    order_id uuid PRIMARY KEY,
    sku text NOT NULL CHECK (sku ~ '^[A-Za-z0-9._-]{1,64}$'),
    quantity integer NOT NULL CHECK (quantity > 0),
    status text NOT NULL CHECK (status IN ('RESERVED','CANCELLED','COMPLETED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);

CREATE TABLE sample.inventory (
    sku text PRIMARY KEY CHECK (sku ~ '^[A-Za-z0-9._-]{1,64}$'),
    available_quantity integer NOT NULL CHECK (available_quantity >= 0),
    reserved_quantity integer NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);

INSERT INTO sample.inventory(sku, available_quantity, reserved_quantity)
VALUES ('SKU-001', 1000, 0), ('SKU-002', 1000, 0)
ON CONFLICT DO NOTHING;

GRANT SELECT, INSERT, UPDATE ON sample.orders TO sample_app_role;
GRANT SELECT, INSERT, UPDATE ON sample.inventory TO sample_app_role;

CREATE TABLE opspilot.topology_snapshot (
    topology_snapshot_id uuid PRIMARY KEY,
    target_system_id text NOT NULL REFERENCES opspilot.target_system(target_system_id) ON DELETE RESTRICT,
    topology_version text NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    snapshot_json jsonb NOT NULL CHECK (snapshot_json ? 'version'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (target_system_id, topology_version),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX ix_topology_snapshot_effective
    ON opspilot.topology_snapshot(target_system_id, effective_from DESC);

GRANT SELECT, INSERT ON opspilot.topology_snapshot TO opspilot_app_role;
