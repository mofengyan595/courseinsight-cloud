[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{1,40}$')]
    [string]$ExperimentId,
    [ValidateRange(1, 10)]
    [int]$RunsPerConcurrency = 3,
    [int[]]$Concurrencies = @(1, 2, 4, 8),
    [ValidateRange(0, 60000)]
    [int]$MainStubDelayMs = 100,
    [ValidateRange(0, 60000)]
    [int]$ControlStubDelayMs = 10,
    [ValidateRange(1, 64)]
    [int]$BacklogConcurrency = 4,
    [switch]$SkipDelayControl,
    [string]$ContextPath = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$serverRoot = Join-Path $repoRoot 'courseinsight-server'
$stubCompose = Join-Path $repoRoot 'performance\ai-stub\compose.yaml'
$runScript = Join-Path $PSScriptRoot 'run-phase4-burst.ps1'
$prepareScript = Join-Path $PSScriptRoot 'prepare-phase4-data.ps1'
$resultsRoot = Join-Path $repoRoot 'performance\results'
if ([string]::IsNullOrWhiteSpace($ContextPath)) {
    $ContextPath = Join-Path $resultsRoot 'phase-4-runtime-context.json'
}
if ([string]::IsNullOrWhiteSpace($env:PERF_PASSWORD)) {
    throw 'PERF_PASSWORD must be set. It is passed to k6 at runtime and is never written to results.'
}

function Get-CourseInsightProcess {
    $listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $listener) { return $null }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if ($null -eq $process -or $process.CommandLine -notmatch 'courseinsight') {
        throw "Port 8080 is owned by an unrelated process (PID $($listener.OwningProcess))."
    }
    $process
}

function Stop-CourseInsightServer {
    $process = Get-CourseInsightProcess
    if ($null -ne $process) {
        Write-Host "Stopping CourseInsight server PID $($process.ProcessId)."
        Stop-Process -Id $process.ProcessId -Force
        $deadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $deadline -and
            $null -ne (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) {
            Start-Sleep -Milliseconds 500
        }
        if ($null -ne (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) {
            throw 'CourseInsight server did not release port 8080.'
        }
    }
}

function Start-CourseInsightServer {
    param([int]$Concurrency)
    Stop-CourseInsightServer
    [Environment]::SetEnvironmentVariable(
        'ROCKETMQ_ANALYSIS_CONSUME_THREAD_NUMBER', [string]$Concurrency, 'Process')
    [Environment]::SetEnvironmentVariable(
        'AI_SERVICE_BASE_URL', 'http://127.0.0.1:8002', 'Process')
    $stdout = Join-Path $env:TEMP "courseinsight-phase4-$ExperimentId-c$Concurrency-stdout.log"
    $stderr = Join-Path $env:TEMP "courseinsight-phase4-$ExperimentId-c$Concurrency-stderr.log"
    $process = Start-Process -FilePath (Join-Path $serverRoot 'mvnw.cmd') `
        -ArgumentList @('spring-boot:run', '-Dspring-boot.run.profiles=local') `
        -WorkingDirectory $serverRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    Write-Host "Starting CourseInsight with consumer concurrency $Concurrency (launcher PID $($process.Id))."
    $deadline = (Get-Date).AddMinutes(4)
    do {
        Start-Sleep -Seconds 2
        if ($process.HasExited) {
            $tail = if (Test-Path -LiteralPath $stderr) {
                (Get-Content -Tail 30 -LiteralPath $stderr) -join "`n"
            } else { '' }
            throw "CourseInsight launcher exited during startup.`n$tail"
        }
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 3
        } catch { $health = $null }
    } while (($null -eq $health -or $health.status -ne 'UP') -and (Get-Date) -lt $deadline)
    if ($null -eq $health -or $health.status -ne 'UP') {
        throw "CourseInsight did not become healthy. Runtime log: $stdout"
    }
    Start-Sleep -Seconds 16
    $targets = Invoke-RestMethod -Uri 'http://127.0.0.1:9090/api/v1/targets' -TimeoutSec 20
    $up = @($targets.data.activeTargets | Where-Object {
        $_.labels.job -eq 'courseinsight-server' -and $_.health -eq 'up'
    })
    if ($up.Count -eq 0) { throw 'Prometheus target did not become UP after server restart.' }
}

function Set-AiStubDelay {
    param([int]$DelayMs)
    [Environment]::SetEnvironmentVariable('AI_STUB_DELAY_MS', [string]$DelayMs, 'Process')
    & docker compose -f $stubCompose up -d --build --force-recreate
    if ($LASTEXITCODE -ne 0) { throw 'Unable to start the performance AI Stub.' }
    $deadline = (Get-Date).AddMinutes(3)
    do {
        Start-Sleep -Seconds 2
        try { $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8002/health' -TimeoutSec 3 }
        catch { $health = $null }
    } while (($null -eq $health -or [int]$health.delayMs -ne $DelayMs) -and (Get-Date) -lt $deadline)
    if ($null -eq $health -or [int]$health.delayMs -ne $DelayMs) {
        throw "AI Stub did not become healthy with delay ${DelayMs}ms."
    }
}

& docker compose --profile observability up -d
if ($LASTEXITCODE -ne 0) { throw 'Unable to start local dependencies and observability stack.' }
Set-AiStubDelay -DelayMs $MainStubDelayMs

try {
    Start-CourseInsightServer -Concurrency $Concurrencies[0]
    & $prepareScript -OutputPath $ContextPath

    foreach ($concurrency in $Concurrencies) {
        Start-CourseInsightServer -Concurrency $concurrency
        for ($run = 1; $run -le $RunsPerConcurrency; $run++) {
            & $runScript -ConsumerConcurrency $concurrency -ExperimentId $ExperimentId `
                -RunNumber $run -Scenario scaling -AiStubDelayMs $MainStubDelayMs `
                -ContextPath $ContextPath
        }
        if ($concurrency -eq $BacklogConcurrency) {
            & $runScript -ConsumerConcurrency $concurrency -ExperimentId $ExperimentId `
                -RunNumber 1 -Scenario backlog -AiStubDelayMs $MainStubDelayMs `
                -ContextPath $ContextPath
        }
    }

    if (-not $SkipDelayControl) {
        if ($Concurrencies -notcontains 8) { Start-CourseInsightServer -Concurrency 8 }
        Set-AiStubDelay -DelayMs $ControlStubDelayMs
        & $runScript -ConsumerConcurrency 8 -ExperimentId $ExperimentId `
            -RunNumber 1 -Scenario delay-control -AiStubDelayMs $ControlStubDelayMs `
            -ContextPath $ContextPath
    }
} finally {
    Stop-CourseInsightServer
}

Write-Host "Phase 4 experiment $ExperimentId completed. The shared Docker services remain running."
