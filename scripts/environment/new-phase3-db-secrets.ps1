[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$secretDirectory = Join-Path $repositoryRoot '.tmp\secrets'
$secretNames = @(
    'postgres-bootstrap-password.txt',
    'migrator-db-password.txt',
    'runtime-db-password.txt',
    'fault-lab-db-password.txt',
    'evaluation-db-password.txt'
)

New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
foreach ($secretName in $secretNames) {
    $path = Join-Path $secretDirectory $secretName
    if (Test-Path -LiteralPath $path) {
        continue
    }
    $bytes = [byte[]]::new(32)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $value = [Convert]::ToHexString($bytes).ToLowerInvariant()
    [System.IO.File]::WriteAllText($path, $value, [System.Text.UTF8Encoding]::new($false))
}

Write-Output "Phase 3 database secrets are present in $secretDirectory"
