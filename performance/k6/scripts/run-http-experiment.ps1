[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Ab', 'Capacity')]
    [string]$Mode,
    [ValidatePattern('^[A-Za-z0-9_-]{3,40}$')]
    [string]$ExperimentId = 'P3_HTTP',
    [string]$ContextPath = '',
    [ValidateRange(10, 300)]
    [int]$DurationSeconds = 30,
    [ValidateRange(1, 5000)]
    [int]$AbTargetRps = 100,
    [int[]]$CapacityRates = @(100, 200, 400, 800, 1200, 1600, 2000),
    [ValidateRange(0, 120)]
    [int]$BetweenRunCooldownSeconds = 10
)

$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'run-http-benchmark.ps1'
$resultsRoot = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'results'
if ([string]::IsNullOrWhiteSpace($ContextPath)) {
    $ContextPath = Join-Path $resultsRoot 'phase-3-runtime-context.json'
}

function Invoke-OneRun {
    param(
        [string]$Endpoint,
        [string]$CacheState,
        [int]$TargetRps,
        [int]$RunNumber,
        [string]$Phase
    )
    & $runner `
        -Endpoint $Endpoint `
        -CacheState $CacheState `
        -TargetRps $TargetRps `
        -DurationSeconds $DurationSeconds `
        -RunNumber $RunNumber `
        -ExperimentPhase $Phase `
        -ExperimentId $ExperimentId `
        -ContextPath $ContextPath
    if ($LASTEXITCODE -ne 0) {
        throw "$Endpoint $CacheState ${TargetRps}RPS run $RunNumber failed to execute."
    }
    if ($BetweenRunCooldownSeconds -gt 0) {
        Start-Sleep -Seconds $BetweenRunCooldownSeconds
    }
}

function Test-LatestRunPassed {
    param([string]$CacheState, [int]$TargetRps)
    $pattern = "*-http-$ExperimentId-capacity-course-detail-$CacheState-rps$TargetRps-r1-k6.json"
    $path = Get-ChildItem -LiteralPath $resultsRoot -Filter $pattern |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $path) {
        throw "Cannot locate result for capacity $CacheState ${TargetRps}RPS."
    }
    $result = Get-Content -Raw -LiteralPath $path.FullName | ConvertFrom-Json
    $count = if ($null -eq $result.metrics.query_requests.values.count) {
        0
    } else { [double]$result.metrics.query_requests.values.count }
    $p95 = if ($null -eq $result.metrics.query_latency.values.'p(95)') {
        [double]::PositiveInfinity
    } else { [double]$result.metrics.query_latency.values.'p(95)' }
    $errorRate = if ($null -eq $result.metrics.query_http_errors.values.rate) {
        1
    } else { [double]$result.metrics.query_http_errors.values.rate }
    $dropped = if ($null -eq $result.metrics.dropped_iterations.values.count) {
        0
    } else { [double]$result.metrics.dropped_iterations.values.count }
    $achieved = $count / $DurationSeconds
    return $p95 -lt 200 -and $errorRate -lt 0.01 -and
        $achieved -ge ($TargetRps * 0.99) -and $dropped -eq 0
}

if ($Mode -eq 'Ab') {
    foreach ($endpoint in @('course-detail', 'course-analytics')) {
        for ($run = 1; $run -le 3; $run++) {
            # Alternate adjacent miss/hit runs to reduce time-of-day drift while preserving
            # identical ID order and arrival-rate settings within each pair.
            Invoke-OneRun -Endpoint $endpoint -CacheState miss `
                -TargetRps $AbTargetRps -RunNumber $run -Phase ab
            Invoke-OneRun -Endpoint $endpoint -CacheState hit `
                -TargetRps $AbTargetRps -RunNumber $run -Phase ab
        }
    }
    return
}

$failed = @{ miss = $false; hit = $false }
foreach ($rate in $CapacityRates) {
    foreach ($cacheState in @('miss', 'hit')) {
        if ($failed[$cacheState]) {
            continue
        }
        Invoke-OneRun -Endpoint 'course-detail' -CacheState $cacheState `
            -TargetRps $rate -RunNumber 1 -Phase capacity
        if (-not (Test-LatestRunPassed -CacheState $cacheState -TargetRps $rate)) {
            $failed[$cacheState] = $true
            Write-Host "$cacheState first failed the fixed SLO at ${rate}RPS."
        }
    }
    if ($failed.miss -and $failed.hit) {
        break
    }
}

if (-not $failed.miss -or -not $failed.hit) {
    Write-Warning 'At least one cache state did not reach its first failing capacity level.'
}
