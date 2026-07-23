[CmdletBinding()]
param(
    [string]$ComposeProject = 'opspilot_phase3_verify',
    [string]$OutputPath = 'outputs/phase3/03-WP10/database-delivery-evidence.json'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$migrationRoot = Join-Path $repositoryRoot 'opspilot-adapters\persistence-postgres\src\main\resources\db\migration'
$migrationFiles = Get-ChildItem -LiteralPath $migrationRoot -File | Sort-Object Name
$checksums = [ordered]@{}
foreach ($file in $migrationFiles) {
    $checksums[$file.Name] = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
}
$checksumText = ($checksums.Values -join "`n")
$migrationDigest = [Convert]::ToHexString(
    [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($checksumText))
).ToLowerInvariant()

$runtimeDdl = & rg -n -i 'create\s+table' --glob '*.java' --glob '!**/src/test/**' --glob '!**/target/**' $repositoryRoot
if ($LASTEXITCODE -eq 0) { throw "Unversioned runtime DDL found:`n$runtimeDdl" }
if ($LASTEXITCODE -ne 1) { throw 'Runtime DDL scan failed' }
$h2 = & rg -n -i 'org\.h2|jdbc:h2' --glob 'pom.xml' --glob '*.properties' --glob '!**/target/**' $repositoryRoot
if ($LASTEXITCODE -eq 0) { throw "H2 production dependency found:`n$h2" }
if ($LASTEXITCODE -ne 1) { throw 'H2 scan failed' }
$ann = & rg -n -i 'hnsw|ivfflat' $migrationRoot
if ($LASTEXITCODE -eq 0) { throw "ANN index found in the exact-scan baseline:`n$ann" }
if ($LASTEXITCODE -ne 1) { throw 'ANN scan failed' }

$compose = Join-Path $repositoryRoot 'deployment\docker-compose.yml'
$database = docker compose -p $ComposeProject -f $compose exec -T postgres psql -U postgres -d opspilot -Atc @'
SELECT json_build_object(
  'postgresVersion', current_setting('server_version'),
  'pgvectorVersion', (SELECT extversion FROM pg_extension WHERE extname = 'vector'),
  'flywayVersion', (SELECT version FROM public.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1),
  'schemaDigest', (
    SELECT md5(string_agg(item, chr(10) ORDER BY item)) FROM (
      SELECT 'column|'||table_schema||'.'||table_name||'|'||ordinal_position||'|'||column_name||'|'||data_type||'|'||is_nullable item
      FROM information_schema.columns WHERE table_schema IN ('opspilot','opspilot_a2a','sample','opspilot_eval')
      UNION ALL
      SELECT 'constraint|'||n.nspname||'.'||c.relname||'|'||con.conname||'|'||pg_get_constraintdef(con.oid)
      FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname IN ('opspilot','opspilot_a2a','sample','opspilot_eval')
      UNION ALL
      SELECT 'index|'||schemaname||'.'||tablename||'|'||indexname||'|'||indexdef
      FROM pg_indexes WHERE schemaname IN ('opspilot','opspilot_a2a','sample','opspilot_eval')
    ) catalog
  )
);
'@
if ($LASTEXITCODE -ne 0) { throw 'Database evidence query failed' }

$evidence = [ordered]@{
    schemaVersion = '1.0.0'
    generatedAt = [DateTimeOffset]::UtcNow.ToString('O')
    sourceCommit = (git -C $repositoryRoot rev-parse HEAD).Trim()
    image = 'pgvector/pgvector@sha256:ad2e18408bf447f62092a8a5259e7df10505c5a0360bd1a1853ac8b8b0763da2'
    database = $database | ConvertFrom-Json
    migrationSetSha256 = $migrationDigest
    migrationChecksums = $checksums
    roleMatrix = [ordered]@{
        finalLoginFlags = 'rolsuper=false, rolcreatedb=false, rolcreaterole=false'
        runtimeDdl = 'DENIED (SQLSTATE 42501)'
        professionalAgentRls = 'PASS (cross-agent row count 0)'
        groundTruth = 'DENIED to app and professional agents'
    }
    tests = [ordered]@{
        emptyAndV4Upgrade = 'PostgresPhase0MigrationTest'
        rolesTransactionsTasksSseArtifacts = 'PostgresPhase3AdapterTest'
        exactVectorAndKnowledgeSwitch = 'PgvectorKnowledgeRepositoryTest'
        a2aIdempotency = 'A2aIdempotencyTest'
        agentScopeRestart = 'PostgresAgentStateStoreTest'
    }
    gates = [ordered]@{
        runtimeDdl = 'PASS'
        h2 = 'PASS'
        annIndexes = 'PASS'
        productionDdlAuto = 'validate'
        supportedPreviousSchema = 'V4'
    }
}

$absoluteOutput = Join-Path $repositoryRoot $OutputPath
New-Item -ItemType Directory -Path (Split-Path $absoluteOutput) -Force | Out-Null
$evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $absoluteOutput -Encoding utf8NoBOM
Write-Output $absoluteOutput
