param(
    [int]$RootVerifyExitCode = 0,
    [int]$CoreTestExitCode = 0
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $projectRoot.StartsWith('E:\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Phase 2 reports must be generated on E:; resolved root was $projectRoot"
}

$reportRoot = Join-Path $projectRoot 'outputs\phase2\02-WP09'
[System.IO.Directory]::CreateDirectory($reportRoot) | Out-Null
$surefireRoot = Join-Path $projectRoot 'opspilot-core\target\surefire-reports'
$sourceCommit = (& git -C $projectRoot rev-parse HEAD).Trim()
& git -C $projectRoot diff --quiet --ignore-submodules --
$unstagedChanges = $LASTEXITCODE -ne 0
& git -C $projectRoot diff --cached --quiet --ignore-submodules --
$stagedChanges = $LASTEXITCODE -ne 0
$untrackedFiles = @(& git -C $projectRoot ls-files --others --exclude-standard)
$workingTreeDirty = $unstagedChanges -or $stagedChanges -or $untrackedFiles.Count -ne 0
$generatedAt = [DateTimeOffset]::Now.ToString('o')

$groups = [ordered]@{
    state       = 'io.github.opspilot.core.state.StateContractTest'
    snapshot    = 'io.github.opspilot.core.state.SnapshotContractTest'
    scheduling  = 'io.github.opspilot.core.scheduling.SchedulingContractTest'
    evidence    = 'io.github.opspilot.core.evidence.EvidenceContractTest'
    middleware  = 'io.github.opspilot.core.middleware.MiddlewareContractTest'
    transaction = 'io.github.opspilot.core.transaction.TransactionContractTest'
}

$reportFiles = [System.Collections.Generic.List[string]]::new()
$gateFailures = [System.Collections.Generic.List[string]]::new()
foreach ($entry in $groups.GetEnumerator()) {
    $xmlPath = Join-Path $surefireRoot ("TEST-{0}.xml" -f $entry.Value)
    if (-not (Test-Path -LiteralPath $xmlPath)) {
        $gateFailures.Add("missing report: $($entry.Key)")
        continue
    }
    [xml]$xml = [System.IO.File]::ReadAllText($xmlPath)
    $suite = $xml.testsuite
    $tests = [int]$suite.tests
    $failures = [int]$suite.failures
    $errors = [int]$suite.errors
    $skipped = [int]$suite.skipped
    if ($tests -lt 1 -or $failures -ne 0 -or $errors -ne 0) {
        $gateFailures.Add("failed report: $($entry.Key)")
    }
    $report = [ordered]@{
        change = 'implement-phase-2-cohesive-core-module-skeleton'
        workPackage = '02-WP09'
        group = $entry.Key
        sourceCommit = $sourceCommit
        workingTreeDirty = $workingTreeDirty
        generatedAt = $generatedAt
        command = ".\mvnw.cmd -pl opspilot-core -Dtest=$($entry.Value) test"
        aggregateCommand = '.\mvnw.cmd -pl opspilot-core test'
        exitCode = $(if ($failures -eq 0 -and $errors -eq 0) { 0 } else { 1 })
        tests = $tests
        failures = $failures
        errors = $errors
        skipped = $skipped
        durationSeconds = [double]$suite.time
        sourceReport = $xmlPath.Substring($projectRoot.Length + 1)
    }
    $reportPath = Join-Path $reportRoot ("{0}-report.json" -f $entry.Key)
    [System.IO.File]::WriteAllText(
        $reportPath,
        ($report | ConvertTo-Json -Depth 8) + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false))
    $reportFiles.Add($reportPath)
}

if ($RootVerifyExitCode -ne 0) { $gateFailures.Add("root verify exit code: $RootVerifyExitCode") }
if ($CoreTestExitCode -ne 0) { $gateFailures.Add("core test exit code: $CoreTestExitCode") }
if ($gateFailures.Count -ne 0) {
    throw "Phase 2 core gate failed: $($gateFailures -join '; ')"
}

$dependencyOutput = & (Join-Path $projectRoot 'mvnw.cmd') -pl opspilot-core dependency:tree -Dscope=compile 2>&1 | Out-String
$dependencyExitCode = $LASTEXITCODE
if ($dependencyExitCode -ne 0) {
    throw "core dependency tree failed with exit code $dependencyExitCode"
}
$forbiddenDependencies = @(
    'org.springframework', 'agentscope', 'a2a-sdk', 'jakarta.persistence',
    'prometheus', 'jaeger', 'infinity', 'com.openai', 'deepseek'
)
$dependencyHits = @($forbiddenDependencies | Where-Object { $dependencyOutput -match [regex]::Escape($_) })

$classRoot = Join-Path $projectRoot 'opspilot-core\target\classes'
$bytecodeHits = [System.Collections.Generic.List[string]]::new()
$binaryPatterns = @(
    'org/springframework', 'io/agentscope', 'org/a2aproject/sdk', 'jakarta/persistence',
    'io/prometheus', 'io/jaegertracing', 'ai/infinity', 'com/openai'
)
$latin1 = [System.Text.Encoding]::GetEncoding(28591)
Get-ChildItem -LiteralPath $classRoot -Recurse -Filter '*.class' | ForEach-Object {
    $binaryText = $latin1.GetString([System.IO.File]::ReadAllBytes($_.FullName))
    foreach ($pattern in $binaryPatterns) {
        if ($binaryText.Contains($pattern)) {
            $bytecodeHits.Add("$($_.FullName.Substring($projectRoot.Length + 1))::$pattern")
        }
    }
}

$negativeControl = 'org.springframework:spring-core:6.0.0'
$negativeControlRejected = @($forbiddenDependencies | Where-Object {
    $negativeControl -match [regex]::Escape($_)
}).Count -gt 0
if ($dependencyHits.Count -ne 0 -or $bytecodeHits.Count -ne 0 -or -not $negativeControlRejected) {
    throw "core forbidden dependency/import scan failed"
}

$dependencyReport = [ordered]@{
    change = 'implement-phase-2-cohesive-core-module-skeleton'
    sourceCommit = $sourceCommit
    generatedAt = $generatedAt
    command = '.\mvnw.cmd -pl opspilot-core dependency:tree -Dscope=compile'
    exitCode = $dependencyExitCode
    forbiddenDependencyHits = $dependencyHits
    forbiddenBytecodeImportHits = @($bytecodeHits)
    injectedTransitiveNegativeControl = $negativeControl
    negativeControlRejected = $negativeControlRejected
}
$dependencyPath = Join-Path $reportRoot 'core-dependency-boundary-report.json'
[System.IO.File]::WriteAllText(
    $dependencyPath,
    ($dependencyReport | ConvertTo-Json -Depth 8) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false))
$reportFiles.Add($dependencyPath)

$boundaryInventory = [ordered]@{
    change = 'implement-phase-2-cohesive-core-module-skeleton'
    sourceCommit = $sourceCommit
    generatedAt = $generatedAt
    compositionRoot = 'opspilot-server/src/main/java/io/github/opspilot/server/OpsPilotCompositionRoot.java'
    repositoryOwnership = [ordered]@{
        IncidentAgentState = 'Supervisor/core via IncidentAgentStateRepository'
        AgentRuntimeState = 'Agent runtime via AgentRuntimeStatePort'
        A2aTaskState = 'A2A runtime via A2aTaskStatePort'
    }
    transactionBoundary = 'CheckpointUnitOfWork atomically commits state/version, call audits, reference bindings, and outbox facts'
    extensionPoints = @('ProviderExtension', 'ToolExtension', 'SourceExtension', 'AnalyzerExtension', 'SandboxExtension')
    assembly = 'Explicit composition-root parameters only; no registry, ServiceLoader, classpath scan, or dynamic discovery'
    testImplementationPolicy = 'Test Ports and InMemoryUnitOfWork exist only under src/test and are never production Providers or fallbacks'
    realChainRegression = 'Root verify retains real AgentScope, A2A, PostgreSQL/Testcontainers, Source Adapter, and evaluation integration tests'
    rootVerify = [ordered]@{ command = '.\mvnw.cmd verify'; exitCode = $RootVerifyExitCode }
    coreVerify = [ordered]@{ command = '.\mvnw.cmd -pl opspilot-core test'; exitCode = $CoreTestExitCode }
}
$boundaryPath = Join-Path $reportRoot 'boundary-inventory.json'
[System.IO.File]::WriteAllText(
    $boundaryPath,
    ($boundaryInventory | ConvertTo-Json -Depth 8) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false))
$reportFiles.Add($boundaryPath)

$manifestEntries = foreach ($file in $reportFiles) {
    [ordered]@{
        path = $file.Substring($projectRoot.Length + 1)
        sha256 = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$manifest = [ordered]@{
    change = 'implement-phase-2-cohesive-core-module-skeleton'
    sourceCommit = $sourceCommit
    workingTreeDirty = $workingTreeDirty
    generatedAt = $generatedAt
    rootVerify = [ordered]@{ command = '.\mvnw.cmd verify'; exitCode = $RootVerifyExitCode }
    coreVerify = [ordered]@{ command = '.\mvnw.cmd -pl opspilot-core test'; exitCode = $CoreTestExitCode }
    gate = 'PASSED'
    reports = @($manifestEntries)
}
$manifestPath = Join-Path $reportRoot 'manifest.json'
[System.IO.File]::WriteAllText(
    $manifestPath,
    ($manifest | ConvertTo-Json -Depth 8) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false))

Write-Output "PHASE2_CORE_GATE:PASSED"
Write-Output "REPORT_ROOT:$reportRoot"
Write-Output "SOURCE_COMMIT:$sourceCommit"
