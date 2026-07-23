DO $$
BEGIN
    IF to_regclass('opspilot.target_system') IS NOT NULL THEN
        INSERT INTO opspilot.target_system (target_system_id, display_name, metadata_json)
        VALUES ('sample-system', 'Sample System', '{"schemaVersion":"1.0.0","seed":"reference"}'::jsonb)
        ON CONFLICT (target_system_id) DO UPDATE
        SET display_name = EXCLUDED.display_name,
            metadata_json = EXCLUDED.metadata_json;
    END IF;
END $$;
