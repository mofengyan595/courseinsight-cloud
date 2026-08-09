[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 64)]
    [int]$ConsumerConcurrency,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{1,40}$')]
    [string]$ExperimentId,
    [ValidateRange(1, 20)]
    [int]$Runs = 3,
    [ValidateRange(1, 100)]
    [int]$StartRunNumber = 1,
    [ValidateRange(0, 300)]
    [int]$CooldownSeconds = 30,
    [string]$ContextPath = ''
)

$ErrorActionPreference = 'Stop'
$runScript = Join-Path $PSScriptRoot 'run.ps1'
$k6Root = Split-Path -Parent $PSScriptRoot
$performanceRoot = Split-Path -Parent $k6Root
if ([string]::IsNullOrWhiteSpace($ContextPath)) {
    $ContextPath = Join-Path $performanceRoot 'results\runtime-context.json'
}

function Invoke-MySqlScalar {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $sqlBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Sql))
    $value = & docker exec `
        -e "PERF_SQL_B64=$sqlBase64" `
        courseinsight-mysql `
        sh -c 'echo ${PERF_SQL_B64}|base64 -d|MYSQL_PWD=${MYSQL_PASSWORD} mysql -N -B -u${MYSQL_USER} ${MYSQL_DATABASE}'
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect active analysis work in MySQL.'
    }
    [long]$value
}

if (-not (Test-Path -LiteralPath $ContextPath)) {
    throw "Performance context does not exist: $ContextPath"
}
if ([string]::IsNullOrWhiteSpace($env:PERF_USERNAME) -or
    [string]::IsNullOrWhiteSpace($env:PERF_PASSWORD)) {
    throw 'PERF_USERNAME and PERF_PASSWORD must be set in the current process.'
}

$health = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 10
if ($health.status -ne 'UP') {
    throw 'CourseInsight server is not healthy.'
}

for ($offset = 0; $offset -lt $Runs; $offset++) {
    $run = $StartRunNumber + $offset
    $activeTaskCount = Invoke-MySqlScalar -Sql @"
SELECT COUNT(*)
FROM analysis_task
WHERE status IN ('WAITING', 'PROCESSING')
   OR (status = 'FAILED' AND dead_lettered_at IS NULL);
"@
    $pendingOutboxCount = Invoke-MySqlScalar -Sql @"
SELECT COUNT(*)
FROM analysis_outbox_event
WHERE status IN ('PENDING', 'PUBLISHING');
"@
    if ($activeTaskCount -ne 0 -or $pendingOutboxCount -ne 0) {
        throw "Run $run refused: activeTasks=$activeTaskCount, pendingOutbox=$pendingOutboxCount."
    }

    Write-Host "Starting capacity run c$ConsumerConcurrency r$run/$Runs"
    & $runScript `
        -Scenario batch-200 `
        -ContextPath $ContextPath `
        -ConsumerConcurrency $ConsumerConcurrency `
        -RunNumber $run `
        -ExperimentId $ExperimentId

    $remainingTaskCount = Invoke-MySqlScalar -Sql @"
SELECT COUNT(*)
FROM analysis_task
WHERE status IN ('WAITING', 'PROCESSING')
   OR (status = 'FAILED' AND dead_lettered_at IS NULL);
"@
    if ($remainingTaskCount -ne 0) {
        throw "Run $run returned before all analysis tasks were terminal."
    }

    if ($offset -lt ($Runs - 1) -and $CooldownSeconds -gt 0) {
        Write-Host "Cooling down for $CooldownSeconds seconds"
        Start-Sleep -Seconds $CooldownSeconds
    }
}

Write-Host "Completed $Runs capacity runs at consumer concurrency $ConsumerConcurrency."
