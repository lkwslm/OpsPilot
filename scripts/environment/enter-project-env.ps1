param(
    [switch]$Quiet
)

$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$projectTemp = Join-Path $projectRoot '.tmp'

$directories = @{
    Temp = Join-Path $projectTemp 'temp'
    Home = Join-Path $projectTemp 'home'
    MavenRepository = Join-Path $projectTemp 'maven-repository'
    MavenUserHome = Join-Path $projectTemp 'maven-user-home'
    NpmCache = Join-Path $projectTemp 'npm-cache'
    NpmPrefix = Join-Path $projectTemp 'npm-prefix'
    PipCache = Join-Path $projectTemp 'pip-cache'
    HuggingFaceHome = Join-Path $projectTemp 'huggingface'
    TorchHome = Join-Path $projectTemp 'torch'
    DockerConfig = Join-Path $projectTemp 'docker-config'
    Secrets = Join-Path $projectTemp 'secrets'
}

$directories.Values | ForEach-Object {
    New-Item -ItemType Directory -Force $_ | Out-Null
}

$env:TEMP = $directories.Temp
$env:TMP = $directories.Temp
$env:MAVEN_USER_HOME = $directories.MavenUserHome
$env:MAVEN_OPTS = "-Djava.io.tmpdir=$($directories.Temp) -Duser.home=$($directories.Home) -Dmaven.repo.local=$($directories.MavenRepository)"
$env:npm_config_cache = $directories.NpmCache
$env:npm_config_prefix = $directories.NpmPrefix
$env:npm_config_update_notifier = 'false'
$env:PIP_CACHE_DIR = $directories.PipCache
$env:PYTHONPYCACHEPREFIX = Join-Path $projectTemp 'python-cache'
$env:HF_HOME = $directories.HuggingFaceHome
$env:HUGGINGFACE_HUB_CACHE = Join-Path $directories.HuggingFaceHome 'hub'
$env:TORCH_HOME = $directories.TorchHome
$env:DOCKER_CONFIG = $directories.DockerConfig

$deepSeekKeyFile = Join-Path $directories.Secrets 'deepseek-api-key.txt'
if (Test-Path -LiteralPath $deepSeekKeyFile) {
    $deepSeekKey = (Get-Content -LiteralPath $deepSeekKeyFile -Raw).Trim()
    if (-not [string]::IsNullOrWhiteSpace($deepSeekKey)) {
        $env:DEEPSEEK_API_KEY = $deepSeekKey
    }
}

$projectJdk = Get-ChildItem (Join-Path $projectTemp 'toolchains') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    ForEach-Object {
        if (Test-Path (Join-Path $_.FullName 'bin\java.exe')) {
            $_.FullName
        } else {
            Get-ChildItem $_.FullName -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
                Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
                Select-Object -ExpandProperty FullName
        }
    } |
    Select-Object -First 1
if ($projectJdk) {
    $env:JAVA_HOME = $projectJdk
    $env:Path = "$projectJdk\bin;$env:Path"
}

if (-not $Quiet) {
    [pscustomobject]@{
        ProjectRoot = $projectRoot
        ProjectTemp = $projectTemp
        JavaHome = $env:JAVA_HOME
        MavenRepository = $directories.MavenRepository
        NpmCache = $directories.NpmCache
        HuggingFaceHome = $directories.HuggingFaceHome
        DockerConfig = $directories.DockerConfig
    } | Format-List
}
