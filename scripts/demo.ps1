param(
    [ValidateSet("start", "ready", "smoke", "stop", "clean")]
    [string]$Action = "start",
    [switch]$ConfirmDeleteData,
    [switch]$Rebuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepositoryRoot = Split-Path -Parent $PSScriptRoot
$DemoEnv = Join-Path $RepositoryRoot "demo.env"
$DemoCompose = Join-Path $RepositoryRoot "compose.demo.yaml"
$SmokeSource = Join-Path $PSScriptRoot "DemoSmoke.java"
$DemoAiImage = "courseinsight-demo-ai-service:latest"
$ComposePrefix = @("compose", "--env-file", $DemoEnv, "-f", $DemoCompose)
$HealthyContainers = @(
    "courseinsight-demo-mysql",
    "courseinsight-demo-redis",
    "courseinsight-demo-rocketmq-nameserver",
    "courseinsight-demo-rocketmq-broker",
    "courseinsight-demo-ai-stub"
)

function Require-Command([string]$Name, [string]$InstallHint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name is required. $InstallHint"
    }
}

function Require-Docker {
    Require-Command "docker" "Install Docker Desktop or Docker Engine."
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker is installed but the Docker Engine is unavailable. Start Docker and retry."
    }
    & docker compose version *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose v2 is required."
    }
}

function Require-Java {
    Require-Command "java" "Install JDK 17 and make java available on PATH."
    $versionOutput = (& java --version | Out-String)
    if ($LASTEXITCODE -ne 0 -or $versionOutput -notmatch '(?m)^(?:openjdk|java) 17(?:\.|\s)') {
        throw "JDK 17 is required. Detected: $($versionOutput.Trim())"
    }
}

function Invoke-Compose([string[]]$Arguments) {
    & docker @ComposePrefix @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose command failed: $($Arguments -join ' ')"
    }
}

function Invoke-DemoJava([string[]]$Arguments) {
    & java $SmokeSource @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Demo verification failed."
    }
}

function Test-DemoAiImageExists {
    & docker image inspect $DemoAiImage *> $null
    return $LASTEXITCODE -eq 0
}

function Start-DemoDependencies {
    if (-not $Rebuild -and (Test-DemoAiImageExists)) {
        Write-Host "[INFO] Reusing local deterministic AI Stub image (offline-safe)"
        Invoke-Compose @("up", "--detach", "--no-build", "--wait", "--wait-timeout", "180")
        return
    }

    Write-Host "[INFO] Building deterministic AI Stub; first build requires Docker Hub access"
    try {
        Invoke-Compose @("up", "--detach", "--build", "--wait", "--wait-timeout", "180")
    } catch {
        throw "AI Stub image is unavailable or rebuild failed. Check Docker access to auth.docker.io and registry-1.docker.io, then retry. $($_.Exception.Message)"
    }
}

function Assert-DependenciesHealthy {
    foreach ($container in $HealthyContainers) {
        $state = (& docker inspect --format "{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}" $container 2>$null | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) {
            throw "Required demo container '$container' is not running. Run scripts/demo.ps1 start first."
        }
        $parts = $state -split '\|', 2
        if ($parts[0] -ne "running") {
            throw "Demo container '$container' is not running (status=$($parts[0])). Run scripts/demo.ps1 start first."
        }
        if ($parts[1] -ne "healthy") {
            throw "Demo container '$container' is not healthy (health=$($parts[1]))."
        }
    }
    Write-Host "[PASS] MySQL, Redis, RocketMQ and deterministic AI Stub are healthy" -ForegroundColor Green
}

try {
    Set-Location $RepositoryRoot
    if (-not (Test-Path -LiteralPath $DemoEnv)) {
        throw "Missing demo settings file: $DemoEnv"
    }

    switch ($Action) {
        "start" {
            Require-Docker
            Require-Java
            $runningContainers = (& docker @ComposePrefix ps -q 2>$null | Out-String).Trim()
            if ([string]::IsNullOrWhiteSpace($runningContainers)) {
                Invoke-DemoJava @("--check-demo-ports")
            }
            Start-DemoDependencies
            Assert-DependenciesHealthy
            Write-Host "[PASS] Demo dependencies are ready" -ForegroundColor Green
            Write-Host "Start the application in another terminal:"
            Write-Host "  Set-Location courseinsight-server"
            Write-Host "  .\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=demo'"
        }
        "ready" {
            Require-Docker
            Require-Java
            Assert-DependenciesHealthy
            Invoke-DemoJava @("--health-only")
        }
        "smoke" {
            Require-Docker
            Require-Java
            Assert-DependenciesHealthy
            Invoke-DemoJava @()
        }
        "stop" {
            Require-Docker
            Invoke-Compose @("stop")
            Write-Host "[PASS] Demo containers stopped; demo data volumes were preserved" -ForegroundColor Green
        }
        "clean" {
            Require-Docker
            if (-not $ConfirmDeleteData) {
                throw "clean deletes the demo-only MySQL/Redis/RocketMQ volumes. Re-run with -ConfirmDeleteData."
            }
            Invoke-Compose @("down", "--volumes", "--remove-orphans")
            Write-Host "[PASS] Demo containers and demo-only data volumes removed" -ForegroundColor Green
        }
    }
} catch {
    Write-Host "[FAIL] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    Set-Location $RepositoryRoot
}
