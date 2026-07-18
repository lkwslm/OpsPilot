CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS opspilot;
CREATE SCHEMA IF NOT EXISTS opspilot_a2a;
CREATE SCHEMA IF NOT EXISTS sample;
CREATE SCHEMA IF NOT EXISTS opspilot_eval;

REVOKE ALL ON SCHEMA opspilot, opspilot_a2a, sample, opspilot_eval FROM PUBLIC;

GRANT ALL ON SCHEMA opspilot, opspilot_a2a, sample, opspilot_eval TO opspilot_migrator;
GRANT USAGE ON SCHEMA opspilot TO opspilot_app_role, evaluation_role;
GRANT USAGE ON SCHEMA opspilot_a2a TO professional_agent_role;
GRANT USAGE ON SCHEMA sample TO sample_app_role;
GRANT USAGE ON SCHEMA opspilot_eval TO fault_lab_role, evaluation_role;
