[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('course-detail', 'course-analytics', 'batch-200')]
    [string]$Scenario,
    [ValidateSet('cold', 'warm', 'miss', 'none')]
    [string]$CacheState = 'none',
    [string]$ContextPath = '',
    [string]$BaseUrl = 'http://host.docker.internal:8080',
    [string]$PrometheusUrl = 'http://127.0.0.1:9090'
)

$ErrorActionPreference = 'Stop'
$k6Image = 'grafana/k6:1.3.0'
$k6Root = Split-Path -Parent $PSScriptRoot
$performanceRoot = Split-Path -Parent $k6Root
$resultsRoot = Join-Path $performanceRoot 'results'
if ([string]::IsNullOrWhiteSpace($ContextPath)) {
    $ContextPath = Join-Path $resultsRoot 'runtime-context.json'
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)]
        [string]$LiteralPath,
        [Parameter(Mandatory = $true)]
        [string]$Content
    )
    $normalized = $Content.Replace("`r`n", "`n")
    [IO.File]::WriteAllText(
        $LiteralPath,
        $normalized + "`n",
        (New-Object Text.UTF8Encoding($false))
    )
}
if (-not (Test-Path -LiteralPath $ContextPath)) {
    throw "Performance context does not exist: $ContextPath"
}
if ([string]::IsNullOrWhiteSpace($env:PERF_USERNAME) -or
    [string]::IsNullOrWhiteSpace($env:PERF_PASSWORD)) {
    throw 'PERF_USERNAME and PERF_PASSWORD must be set in the current process.'
}
if ($Scenario -eq 'course-detail' -and $CacheState -notin @('cold', 'warm')) {
    throw 'course-detail requires -CacheState cold or warm.'
}
if ($Scenario -eq 'course-analytics' -and $CacheState -notin @('miss', 'warm')) {
    throw 'course-analytics requires -CacheState miss or warm.'
}
if ($Scenario -eq 'batch-200') {
    $CacheState = 'none'
}

$context = Get-Content -Raw -LiteralPath $ContextPath | ConvertFrom-Json
$courseIds = @($context.courseIds | ForEach-Object { [long]$_ })
if ($courseIds.Count -eq 0) {
    throw 'The performance context contains no course IDs.'
}
if ($context.username -ne $env:PERF_USERNAME) {
    throw 'PERF_USERNAME does not match the prepared runtime context.'
}

if ($CacheState -eq 'cold') {
    $keys = @($courseIds | ForEach-Object { "course:detail:$_" })
    & docker exec courseinsight-redis redis-cli DEL @keys | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to evict the selected course-detail cache keys.'
    }
}
if ($CacheState -eq 'miss') {
    $keys = @($courseIds | ForEach-Object { "course:analytics:summary:$_" })
    & docker exec courseinsight-redis redis-cli DEL @keys | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to evict the selected analytics cache keys.'
    }
}

$health = Invoke-RestMethod -Uri 'http://127.0.0.1:8001/health' -TimeoutSec 10
$prometheusReady = Invoke-WebRequest `
    -UseBasicParsing `
    -Uri "$PrometheusUrl/-/ready" `
    -TimeoutSec 10
if ($prometheusReady.StatusCode -ne 200 -or
    $prometheusReady.Content.Trim() -ne 'Prometheus Server is Ready.') {
    throw 'Prometheus is not ready.'
}

$gitCommit = (& git rev-parse HEAD).Trim()
$imageDigest = (& docker image inspect $k6Image --format '{{index .RepoDigests 0}}').Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect $k6Image."
}
$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$suffix = if ($CacheState -eq 'none') { $Scenario } else { "$Scenario-$CacheState" }
$resultName = "$stamp-$suffix-k6.json"
$prometheusName = "$stamp-$suffix-prometheus.json"
$batchResultName = "$stamp-$suffix-outcome.json"
$startedAt = (Get-Date).ToUniversalTime()

$env:BASE_URL = $BaseUrl
$env:COURSE_IDS = $courseIds -join ','
$env:PRIMARY_COURSE_ID = [string]$context.primaryCourseId
$env:RESULT_FILE = "/results/$resultName"
$env:TEST_STARTED_AT = $startedAt.ToString('o')
$env:GIT_COMMIT = $gitCommit
$env:SCENARIO = $Scenario
$env:CACHE_STATE = $CacheState
$env:K6_IMAGE = $k6Image
$env:K6_IMAGE_DIGEST = $imageDigest
$env:CONSUMER_CONCURRENCY = '1'
$env:AI_BACKEND_STATE = $health | ConvertTo-Json -Compress
$env:VU_MODEL = if ($Scenario -eq 'batch-200') {
    '1 VU x 1 iteration; asynchronous polling every 1s'
} elseif ($CacheState -in @('cold', 'miss')) {
    "1 VU x $($courseIds.Count) unique-cache iterations"
} else {
    'ramping VUs: 0->2 (5s), 2->4 (15s), 4->8 (10s), 8->0 (5s); 0.1s think time'
}

$environmentNames = @(
    'BASE_URL',
    'PERF_USERNAME',
    'PERF_PASSWORD',
    'COURSE_IDS',
    'PRIMARY_COURSE_ID',
    'RESULT_FILE',
    'TEST_STARTED_AT',
    'GIT_COMMIT',
    'SCENARIO',
    'CACHE_STATE',
    'K6_IMAGE',
    'K6_IMAGE_DIGEST',
    'CONSUMER_CONCURRENCY',
    'AI_BACKEND_STATE',
    'VU_MODEL'
)
$dockerArguments = @(
    'run',
    '--rm',
    '--add-host=host.docker.internal:host-gateway',
    '-v', "${k6Root}:/scripts:ro",
    '-v', "${resultsRoot}:/results"
)
foreach ($name in $environmentNames) {
    $dockerArguments += @('-e', $name)
}
$dockerArguments += @($k6Image, 'run', "/scripts/scenarios/$Scenario.js")

Write-Host "Running $Scenario ($CacheState) with $k6Image"
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $capturedOutput = @(& docker @dockerArguments 2>&1)
    $k6ExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
$finishedAt = (Get-Date).ToUniversalTime()

Write-Host 'Waiting for the final Prometheus scrape...'
Start-Sleep -Seconds 20
$observedAt = (Get-Date).ToUniversalTime()
$rangeSeconds = [Math]::Max(30, [Math]::Ceiling(($observedAt - $startedAt).TotalSeconds) + 15)
$range = "${rangeSeconds}s"
$startEpoch = [Math]::Floor(
    ([DateTimeOffset]$startedAt).ToUnixTimeMilliseconds() / 1000
)
$endEpoch = [Math]::Floor(
    ([DateTimeOffset]$observedAt).ToUnixTimeMilliseconds() / 1000
)

function Invoke-PrometheusQuery {
    param([Parameter(Mandatory = $true)][string]$Query)
    $encoded = [Uri]::EscapeDataString($Query)
    $response = Invoke-RestMethod `
        -Uri "$PrometheusUrl/api/v1/query?query=$encoded" `
        -TimeoutSec 20
    if ($response.status -ne 'success') {
        throw "Prometheus query failed: $Query"
    }
    @($response.data.result)
}

$queries = [ordered]@{
    applicationUp = 'max(up{job="courseinsight-server"})'
    jvmHeapUsedMaxBytes = "max_over_time(sum(jvm_memory_used_bytes{area=`"heap`"})[${range}:15s])"
    jvmHeapMaxBytes = 'sum(jvm_memory_max_bytes{area="heap"})'
    jvmGcPauseIncreaseSeconds = "sum(jvm_gc_pause_seconds_sum @ $endEpoch - jvm_gc_pause_seconds_sum @ $startEpoch)"
    jvmThreadsLiveMax = "max(max_over_time(jvm_threads_live_threads[${range}]))"
    jvmThreadsPeakMax = "max(max_over_time(jvm_threads_peak_threads[${range}]))"
    hikariActiveMax = "max(max_over_time(hikaricp_connections_active[${range}]))"
    hikariConfiguredMax = 'max(hikaricp_connections_max)'
    httpP95Seconds = "histogram_quantile(0.95, sum by (le) (http_server_requests_seconds_bucket{uri!~`"/actuator.*`"} @ $endEpoch - http_server_requests_seconds_bucket{uri!~`"/actuator.*`"} @ $startEpoch))"
    http5xxCount = "sum(http_server_requests_seconds_count{status=~`"5..`",uri!~`"/actuator.*`"} @ $endEpoch - http_server_requests_seconds_count{status=~`"5..`",uri!~`"/actuator.*`"} @ $startEpoch)"
    aiRequestCountByOutcome = "sum by (outcome) (courseinsight_ai_request_seconds_count @ $endEpoch - courseinsight_ai_request_seconds_count @ $startEpoch)"
    aiP95SecondsByOutcome = "histogram_quantile(0.95, sum by (le, outcome) (courseinsight_ai_request_seconds_bucket @ $endEpoch - courseinsight_ai_request_seconds_bucket @ $startEpoch))"
    analysisTaskCountByOutcome = "sum by (outcome) (courseinsight_analysis_task_total @ $endEpoch - courseinsight_analysis_task_total @ $startEpoch)"
    outboxPublishCountByOutcome = "sum by (outcome) (courseinsight_outbox_publish_total @ $endEpoch - courseinsight_outbox_publish_total @ $startEpoch)"
}
$queryResults = [ordered]@{}
foreach ($entry in $queries.GetEnumerator()) {
    $queryResults[$entry.Key] = Invoke-PrometheusQuery -Query $entry.Value
}

$prometheusArtifact = [ordered]@{
    schemaVersion = 1
    gitCommit = $gitCommit
    scenario = $Scenario
    cacheState = $CacheState
    testStartedAt = $startedAt.ToString('o')
    testFinishedAt = $finishedAt.ToString('o')
    observedAt = $observedAt.ToString('o')
    testDurationSeconds = [Math]::Round(($finishedAt - $startedAt).TotalSeconds, 3)
    queryRangeSeconds = $rangeSeconds
    consumerConcurrency = 1
    aiBackendState = $health
    queries = $queries
    results = $queryResults
}
$prometheusPath = Join-Path $resultsRoot $prometheusName
Write-Utf8NoBom `
    -LiteralPath $prometheusPath `
    -Content ($prometheusArtifact | ConvertTo-Json -Depth 20)

if ($Scenario -eq 'batch-200') {
    $outcomeLine = $capturedOutput |
        Where-Object { $_ -match 'COURSEINSIGHT_BATCH_RESULT=(\{[^}]+\})' } |
        Select-Object -Last 1
    if ($null -ne $outcomeLine -and $outcomeLine -match 'COURSEINSIGHT_BATCH_RESULT=(\{[^}]+\})') {
        $outcome = $Matches[1] | ConvertFrom-Json
        $outcome | Add-Member -NotePropertyName gitCommit -NotePropertyValue $gitCommit
        $outcome | Add-Member -NotePropertyName consumerConcurrency -NotePropertyValue 1
        Write-Utf8NoBom `
            -LiteralPath (Join-Path $resultsRoot $batchResultName) `
            -Content ($outcome | ConvertTo-Json -Depth 5)
    } else {
        Write-Warning 'The k6 batch outcome line was not found.'
    }
}

Write-Host "k6 result: $(Join-Path $resultsRoot $resultName)"
Write-Host "Prometheus result: $prometheusPath"
if ($k6ExitCode -ne 0) {
    throw "k6 exited with code $k6ExitCode. Results were still collected."
}
