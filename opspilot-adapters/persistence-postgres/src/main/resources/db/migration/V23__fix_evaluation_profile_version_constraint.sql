ALTER TABLE opspilot.evaluation_result
    DROP CONSTRAINT ck_evaluation_profile_version,
    ADD CONSTRAINT ck_evaluation_profile_version
        CHECK (profile_version IS NULL OR profile_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$');
