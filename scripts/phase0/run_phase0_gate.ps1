param(
    [switch]$CleanMavenCache
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $projectRoot 'scripts\environment\enter-project-env.ps1')

$python = Join-Path $projectRoot '.tmp\venvs\contracts-py39\Scripts\python.exe'
$maven = Join-Path $projectRoot '.tmp\toolchains\apache-maven-3.9.11\bin\mvn.cmd'
$wp10 = Join-Path $projectRoot 'outputs\phase0\01-WP10'
New-Item -ItemType Directory -Force -Path $wp10 | Out-Null

if ($CleanMavenCache) {
    $cleanRoot = Join-Path $projectRoot ('.tmp\phase0-clean\' + [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss'))
    $cleanRepository = Join-Path $cleanRoot 'maven-repository'
    New-Item -ItemType Directory -Force -Path $cleanRepository | Out-Null
    $env:MAVEN_OPTS = "-Dmaven.repo.local=$cleanRepository"
}

Push-Location $projectRoot
try {
    & $maven -B -ntp verify
    if ($LASTEXITCODE -ne 0) { throw 'BUILD_TEST_FAILED' }

    $buildReport = [ordered]@{
        schemaVersion = '1.0.0'
        status = 'PASS'
        cleanMavenCache = [bool]$CleanMavenCache
        mavenRepository = $env:MAVEN_OPTS
        jdk = '21.0.11+10'
        maven = '3.9.11'
        completedAt = [DateTimeOffset]::UtcNow.ToString('o')
    }
    $buildReport | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $wp10 '01-WP10.T05-clean-build-report.json')

    & $python .\scripts\versions\validate_versions_lock.py --lock .\deployment\versions.lock.yaml --schema .\deployment\schemas\versions-lock.schema.json --report .\outputs\phase0\01-WP10\01-WP10.T05-versions-lock.json
    if ($LASTEXITCODE -ne 0) { throw 'VERSIONS_LOCK_FAILED' }
    & $python .\scripts\contracts\run_contracts.py --project-root . --report-dir .\outputs\phase0\01-WP02\phase0-final --summary .\outputs\phase0\01-WP02\phase0-final\contracts-summary.json
    if ($LASTEXITCODE -ne 0) { throw 'CONTRACTS_FAILED' }
    & $python .\scripts\deployment\validate_compose.py --compose .\deployment\docker-compose.yml --report .\outputs\phase0\01-WP09\compose-validation-report.json
    if ($LASTEXITCODE -ne 0) { throw 'COMPOSE_FAILED' }
    & $python .\scripts\vertical-slice\validate_outputs.py --root . --report .\outputs\phase0\01-WP10\01-WP10.T01-T03-vertical-slice-report.json
    if ($LASTEXITCODE -ne 0) { throw 'VERTICAL_SLICE_EVIDENCE_FAILED' }
    & $python .\scripts\security\scan_repository.py --root . --report .\outputs\phase0\01-WP10\01-WP10.T05-security-report.json
    if ($LASTEXITCODE -ne 0) { throw 'SECURITY_FAILED' }

    $unfinished = Select-String -Path .\openspec\changes\implement-phase-0-spikes-and-gates\tasks.md -Pattern '^- \[ \]' | Where-Object { $_.Line -notmatch '10\.5' }
    if ($unfinished) { throw 'OPENSPEC_TASKS_BLOCKED' }

    & $python .\scripts\phase0\generate_release_manifest.py --root . --output .\outputs\phase0\01-WP10\release-manifest.json
    if ($LASTEXITCODE -ne 0) { throw 'RELEASE_MANIFEST_FAILED' }

    [ordered]@{
        schemaVersion = '1.0.0'
        status = 'PASS'
        missingEvidenceCount = 0
        blockedTaskCount = 0
        automaticDeployment = $false
        completedAt = [DateTimeOffset]::UtcNow.ToString('o')
    } | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $wp10 '01-WP10.T05-phase0-gate-summary.json')
    Write-Output 'PHASE0_GATE_PASS automaticDeployment=false'
} finally {
    Pop-Location
}
