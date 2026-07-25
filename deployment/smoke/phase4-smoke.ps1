param(
    [string]$ProjectName = "opspilot-phase4-smoke",
    [string]$ComposeFile = "",
    [ValidateSet("v1", "v2", "dual", "matrix")]
    [string]$JaegerMode = "matrix"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if (-not $ComposeFile) { $ComposeFile = Join-Path $repoRoot "deployment\docker-compose.yml" }
$evidenceRoot = Join-Path $repoRoot ".tmp\phase4-smoke"
$secretDir = Join-Path $repoRoot ".tmp\secrets"
New-Item -ItemType Directory -Force -Path $evidenceRoot, $secretDir | Out-Null

$secretNames = @(
    "postgres-bootstrap-password", "migrator-db-password", "runtime-db-password",
    "supervisor-service-token", "evidence-agent-token", "code-agent-token",
    "knowledge-agent-token", "diagnosis-agent-token", "remediation-agent-token"
)
foreach ($name in $secretNames) {
    $path = Join-Path $secretDir "$name.txt"
    if (-not (Test-Path -LiteralPath $path)) {
        [Guid]::NewGuid().ToString("N") | Set-Content -NoNewline -Encoding utf8 -LiteralPath $path
    }
}

function Invoke-JaegerSmokeMode {
    param([ValidateSet("v1", "v2", "dual")][string]$Mode)

    $modeEvidenceDir = Join-Path $evidenceRoot $Mode
    New-Item -ItemType Directory -Force -Path $modeEvidenceDir | Out-Null
    $env:JAEGER_MODE = $Mode
    $env:PHASE4_EVIDENCE_HOST_DIR = $modeEvidenceDir
    $env:OTEL_COLLECTOR_CONFIG = if ($Mode -eq "dual") {
        "./otel-collector/config.yaml"
    } else {
        "./otel-collector/config-$Mode.yaml"
    }

    $compose = @("compose", "-p", "$ProjectName-$Mode", "-f", $ComposeFile)
    if ($Mode -ne "v1") { $compose += @("--profile", "jaeger-v2") }
    $jaegerServices = @(switch ($Mode) {
        "v1" { @("jaeger-v1") }
        "v2" { @("jaeger-v2") }
        "dual" { @("jaeger-v1", "jaeger-v2") }
    })

    $exitCode = 1
    try {
        & docker @compose config | Set-Content -Encoding utf8 (Join-Path $modeEvidenceDir "compose.rendered.yaml")
        if ($LASTEXITCODE -ne 0) { throw "docker compose config failed for Jaeger $Mode" }

        & docker @compose --profile phase4-smoke build sample-gateway order-service inventory-service observability-smoke
        if ($LASTEXITCODE -ne 0) { throw "Phase 4 images failed to build for Jaeger $Mode" }

        $upArguments = $compose + @("up", "-d", "--wait", "--wait-timeout", "240", "sample-gateway") + $jaegerServices
        & docker @upArguments
        if ($LASTEXITCODE -ne 0) { throw "Jaeger $Mode sample stack did not become healthy" }

        $headers = @{
            "X-Request-Id" = "phase4-smoke-request-$Mode"
            "X-Trace-Id" = "phase4-smoke-trace-$Mode"
            "X-Run-Id" = "phase4-smoke-run-$Mode"
        }
        $body = '{"sku":"SKU-001","quantity":1}'
        Invoke-RestMethod -NoProxy -Method Post -Uri "http://127.0.0.1:8090/api/orders" `
            -Headers $headers -ContentType "application/json" -Body $body |
            ConvertTo-Json -Depth 20 | Set-Content -Encoding utf8 (Join-Path $modeEvidenceDir "business-response.json")

        Start-Sleep -Seconds 8
        $smokeOutput = & docker @compose --profile phase4-smoke run --rm --no-deps observability-smoke 2>&1
        $smokeOutput | Set-Content -Encoding utf8 (Join-Path $modeEvidenceDir "observability-smoke.log")
        if ($LASTEXITCODE -ne 0) { throw "Source to Evidence smoke failed for Jaeger $Mode" }

        $report = Join-Path $modeEvidenceDir "phase4-observability-report-$Mode.json"
        if (-not (Test-Path -LiteralPath $report)) { throw "smoke report was not written for Jaeger $Mode" }
        $reportHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $report).Hash.ToLowerInvariant()
        [ordered]@{
            schemaVersion = "1.0.0"
            jaegerMode = $Mode
            command = "docker compose --profile phase4-smoke run --rm --no-deps observability-smoke"
            exitCode = 0
            reportUri = (Resolve-Path -LiteralPath $report).Path
            reportSha256 = "sha256:$reportHash"
        } | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $modeEvidenceDir "smoke-summary.json")
        $exitCode = 0
    }
    catch {
        $_ | Out-String | Set-Content -Encoding utf8 (Join-Path $modeEvidenceDir "failure.txt")
        & docker @compose ps --all | Set-Content -Encoding utf8 (Join-Path $modeEvidenceDir "compose-ps.txt")
        & docker @compose logs --no-color | Set-Content -Encoding utf8 (Join-Path $modeEvidenceDir "compose.log")
        throw
    }
    finally {
        & docker @compose down --volumes --remove-orphans | Out-Null
        if ($LASTEXITCODE -ne 0 -and $exitCode -eq 0) { throw "Compose cleanup failed for Jaeger $Mode" }
    }
}

$modes = if ($JaegerMode -eq "matrix") { @("v1", "v2", "dual") } else { @($JaegerMode) }
foreach ($mode in $modes) { Invoke-JaegerSmokeMode -Mode $mode }

[ordered]@{
    schemaVersion = "1.0.0"
    modes = $modes
    status = "PASS"
    completedAt = [DateTimeOffset]::UtcNow.ToString("o")
} | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $evidenceRoot "jaeger-compatibility-matrix.json")
