#!/usr/bin/env bash
set -euo pipefail
# Docker secrets are consumed without copying their values into the image or logs.

# The official PostgreSQL image creates POSTGRES_DB and the bootstrap superuser.
# This script only creates login identities and memberships. Flyway owns every
# extension, schema, table, policy, grant, and other DDL object.
load_secret() {
  local name="$1"
  local file_name="${name}_FILE"
  if [[ -z "${!name:-}" && -n "${!file_name:-}" ]]; then
    printf -v "$name" '%s' "$(<"${!file_name}")"
    export "$name"
  fi
}

for secret_name in \
  OPSPILOT_MIGRATOR_PASSWORD OPSPILOT_APP_PASSWORD SAMPLE_APP_PASSWORD \
  FAULT_LAB_PASSWORD EVALUATION_PASSWORD EVIDENCE_AGENT_PASSWORD \
  CODE_AGENT_PASSWORD KNOWLEDGE_AGENT_PASSWORD DIAGNOSIS_AGENT_PASSWORD \
  REMEDIATION_AGENT_PASSWORD KNOWLEDGE_CONTROL_PASSWORD; do
  load_secret "$secret_name"
done

: "${OPSPILOT_MIGRATOR_PASSWORD:?OPSPILOT_MIGRATOR_PASSWORD is required}"
: "${OPSPILOT_APP_PASSWORD:?OPSPILOT_APP_PASSWORD is required}"
: "${SAMPLE_APP_PASSWORD:?SAMPLE_APP_PASSWORD is required}"
: "${FAULT_LAB_PASSWORD:?FAULT_LAB_PASSWORD is required}"
: "${EVALUATION_PASSWORD:?EVALUATION_PASSWORD is required}"
: "${EVIDENCE_AGENT_PASSWORD:?EVIDENCE_AGENT_PASSWORD is required}"
: "${CODE_AGENT_PASSWORD:?CODE_AGENT_PASSWORD is required}"
: "${KNOWLEDGE_AGENT_PASSWORD:?KNOWLEDGE_AGENT_PASSWORD is required}"
: "${DIAGNOSIS_AGENT_PASSWORD:?DIAGNOSIS_AGENT_PASSWORD is required}"
: "${REMEDIATION_AGENT_PASSWORD:?REMEDIATION_AGENT_PASSWORD is required}"
: "${KNOWLEDGE_CONTROL_PASSWORD:?KNOWLEDGE_CONTROL_PASSWORD is required}"

psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=migrator_password="$OPSPILOT_MIGRATOR_PASSWORD" \
  --set=app_password="$OPSPILOT_APP_PASSWORD" \
  --set=sample_password="$SAMPLE_APP_PASSWORD" \
  --set=fault_password="$FAULT_LAB_PASSWORD" \
  --set=evaluation_password="$EVALUATION_PASSWORD" \
  --set=evidence_password="$EVIDENCE_AGENT_PASSWORD" \
  --set=code_password="$CODE_AGENT_PASSWORD" \
  --set=knowledge_password="$KNOWLEDGE_AGENT_PASSWORD" \
  --set=diagnosis_password="$DIAGNOSIS_AGENT_PASSWORD" \
  --set=remediation_password="$REMEDIATION_AGENT_PASSWORD" \
  --set=knowledge_control_password="$KNOWLEDGE_CONTROL_PASSWORD" <<'SQL'
CREATE ROLE opspilot_app_role NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE sample_app_role NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE fault_lab_role NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE evaluation_role NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE professional_agent_role NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE evidence_agent_role NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE code_agent_role NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE knowledge_agent_role NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE diagnosis_agent_role NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE remediation_agent_role NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE knowledge_control_role NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
-- pgvector 0.8.4 is not a trusted extension. The migration command uses this
-- bootstrap privilege for V1 only and immediately demotes the role permanently.
CREATE ROLE opspilot_migrator LOGIN SUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD :'migrator_password';
CREATE ROLE opspilot_app_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT PASSWORD :'app_password';
CREATE ROLE sample_app_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT PASSWORD :'sample_password';
CREATE ROLE fault_lab_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT PASSWORD :'fault_password';
CREATE ROLE evaluation_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT PASSWORD :'evaluation_password';
CREATE ROLE evidence_agent_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT PASSWORD :'evidence_password';
CREATE ROLE code_agent_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT PASSWORD :'code_password';
CREATE ROLE knowledge_agent_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT PASSWORD :'knowledge_password';
CREATE ROLE diagnosis_agent_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT PASSWORD :'diagnosis_password';
CREATE ROLE remediation_agent_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT PASSWORD :'remediation_password';
CREATE ROLE knowledge_control_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT PASSWORD :'knowledge_control_password';
GRANT CREATE ON DATABASE :"DBNAME" TO opspilot_migrator;
GRANT USAGE, CREATE ON SCHEMA public TO opspilot_migrator;
GRANT opspilot_app_role TO opspilot_app_login;
GRANT sample_app_role TO sample_app_login;
GRANT fault_lab_role TO fault_lab_login;
GRANT evaluation_role TO evaluation_login;
GRANT professional_agent_role TO evidence_agent_role, code_agent_role, knowledge_agent_role, diagnosis_agent_role, remediation_agent_role;
GRANT evidence_agent_role TO evidence_agent_login;
GRANT code_agent_role TO code_agent_login;
GRANT knowledge_agent_role TO knowledge_agent_login;
GRANT diagnosis_agent_role TO diagnosis_agent_login;
GRANT remediation_agent_role TO remediation_agent_login;
GRANT knowledge_control_role TO knowledge_control_login;
SQL
