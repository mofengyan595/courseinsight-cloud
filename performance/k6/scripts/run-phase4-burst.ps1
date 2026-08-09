[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 64)]
    [int]$ConsumerConcurrency,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{1,40}$')]
    [string]$ExperimentId,
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 100)]
    [int]$RunNumber,
    [ValidateSet('scaling', 'backlog', 'delay-control')]
    [string]$Scenario = 'scaling',
    [ValidateRange(0, 60000)]
    [int]$AiStubDelayMs = 100,
    [string]$ContextPath = '',
    [string]$BaseUrl = 'http://host.docker.internal:8080',
    [string]$PrometheusUrl = 'http://127.0.0.1:9090'
)

$ErrorActionPreference = 'Stop'
$culture = [Globalization.CultureInfo]::InvariantCulture
$k6Image = 'grafana/k6:1.3.0'
$k6Root = Split-Path -Parent $PSScriptRoot
$performanceRoot = Split-Path -Parent $k6Root
$resultsRoot = Join-Path $performanceRoot 'results'
if ([string]::IsNullOrWhiteSpace($ContextPath)) {
    $ContextPath = Join-Path $resultsRoot 'phase-4-runtime-context.json'
}
if (-not (Test-Path -LiteralPath $ContextPath)) {
    throw "Phase 4 context does not exist: $ContextPath"
}
if ([string]::IsNullOrWhiteSpace($env:PERF_PASSWORD)) {
    throw 'PERF_PASSWORD must be set in the current process.'
}
$context = Get-Content -Raw -LiteralPath $ContextPath | ConvertFrom-Json
$courseIds = @($context.principals | ForEach-Object { [long]$_.courseId })
if ($courseIds.Count -ne 5) {
    throw 'Phase 4 requires exactly five isolated courses.'
}
$courseIdsSql = $courseIds -join ','

function Write-Utf8NoBom {
    param([string]$LiteralPath, [string]$Content)
    [IO.File]::WriteAllText(
        $LiteralPath,
        $Content.Replace("`r`n", "`n").TrimEnd() + "`n",
        (New-Object Text.UTF8Encoding($false))
    )
}

function Invoke-MySqlLines {
    param([string]$Sql)
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Sql))
    $lines = & docker exec -e "PERF_SQL_B64=$encoded" courseinsight-mysql sh -c `
        'echo ${PERF_SQL_B64}|base64 -d|MYSQL_PWD=${MYSQL_PASSWORD} mysql --raw -N -B -u${MYSQL_USER} ${MYSQL_DATABASE}'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to query local MySQL for Phase 4.' }
    @($lines)
}

function Invoke-MySqlScalar {
    param([string]$Sql)
    [string](Invoke-MySqlLines -Sql $Sql | Select-Object -First 1)
}

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    if ($Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 1) { return [double]$sorted[0] }
    $position = ($sorted.Count - 1) * $Percentile
    $lower = [Math]::Floor($position)
    $upper = [Math]::Ceiling($position)
    if ($lower -eq $upper) { return [double]$sorted[$lower] }
    $weight = $position - $lower
    [double]$sorted[$lower] * (1 - $weight) + [double]$sorted[$upper] * $weight
}

function Get-Statistics {
    param([double[]]$Values)
    if ($Values.Count -eq 0) { return $null }
    [ordered]@{
        count = $Values.Count
        average = [double]($Values | Measure-Object -Average).Average
        min = [double]($Values | Measure-Object -Minimum).Minimum
        p50 = Get-Percentile $Values 0.50
        p95 = Get-Percentile $Values 0.95
        p99 = Get-Percentile $Values 0.99
        max = [double]($Values | Measure-Object -Maximum).Maximum
    }
}

function Invoke-PrometheusQuery {
    param([string]$Query)
    $encoded = [Uri]::EscapeDataString($Query)
    $response = Invoke-RestMethod -Uri "$PrometheusUrl/api/v1/query?query=$encoded" -TimeoutSec 20
    if ($response.status -ne 'success') { throw "Prometheus query failed: $Query" }
    @($response.data.result)
}

$localHealth = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 10
if ($localHealth.status -ne 'UP') { throw 'CourseInsight server is not healthy.' }
$stubHealth = Invoke-RestMethod -Uri 'http://127.0.0.1:8002/health' -TimeoutSec 10
if ($stubHealth.status -ne 'UP' -or [int]$stubHealth.delayMs -ne $AiStubDelayMs -or
    $stubHealth.bertEnabled -ne $false -or $stubHealth.llmEnabled -ne $false) {
    throw "AI Stub health does not match the requested ${AiStubDelayMs}ms configuration."
}
$target = Invoke-RestMethod -Uri "$PrometheusUrl/api/v1/targets" -TimeoutSec 20
$appTarget = @($target.data.activeTargets | Where-Object {
    $_.labels.job -eq 'courseinsight-server' -and $_.health -eq 'up'
})
if ($appTarget.Count -eq 0) { throw 'Prometheus courseinsight-server target is not UP.' }

$active = [long](Invoke-MySqlScalar @"
SELECT COUNT(*) FROM analysis_task
WHERE status IN ('WAITING','PROCESSING')
   OR (status='FAILED' AND dead_lettered_at IS NULL);
"@)
$pending = [long](Invoke-MySqlScalar "SELECT COUNT(*) FROM analysis_outbox_event WHERE status IN ('PENDING','PUBLISHING');")
if ($active -ne 0 -or $pending -ne 0) {
    throw "Run refused: activeTasks=$active, pendingOutbox=$pending."
}

$lastBatchAge = Invoke-MySqlScalar @"
SELECT COALESCE(TIMESTAMPDIFF(SECOND, MAX(created_at), CURRENT_TIMESTAMP(3)), 999999)
FROM analysis_batch WHERE course_id IN ($courseIdsSql);
"@
if ([int]$lastBatchAge -lt 65) {
    $waitSeconds = 65 - [int]$lastBatchAge
    Write-Host "Waiting $waitSeconds seconds for the real upload rate-limit window."
    Start-Sleep -Seconds $waitSeconds
}

$gitCommit = (& git rev-parse HEAD).Trim()
$imageDigest = (& docker image inspect $k6Image --format '{{index .RepoDigests 0}}').Trim()
if ($LASTEXITCODE -ne 0) { throw "Unable to inspect $k6Image." }
$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$stem = "$stamp-phase4-$ExperimentId-$Scenario-c$ConsumerConcurrency-r$RunNumber"
$k6Name = "$stem-k6.json"
$prometheusName = "$stem-prometheus.json"
$backlogName = "$stem-backlog.json"
$outcomeName = "$stem-outcome.json"
$startedAt = (Get-Date).ToUniversalTime()
$databaseStartedAt = Invoke-MySqlScalar "SELECT DATE_FORMAT(CURRENT_TIMESTAMP(3), '%Y-%m-%d %H:%i:%s.%f');"

$samplerLocal = Join-Path $PSScriptRoot 'sample-phase4-backlog.sh'
$samplerRemote = '/tmp/courseinsight-phase4-backlog-sampler.sh'
& docker cp $samplerLocal "courseinsight-mysql:$samplerRemote" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Unable to install the temporary backlog sampler.' }
$samplerArguments = @(
    'exec',
    '-e', "PERF_COURSE_IDS=$courseIdsSql",
    '-e', "PERF_STARTED_AT=$databaseStartedAt",
    '-e', 'PERF_EXPECTED_TASKS=1000',
    '-e', 'PERF_SAMPLE_SECONDS=0.5',
    'courseinsight-mysql', 'sh', $samplerRemote
)
$samplerJob = Start-Job -ScriptBlock {
    param([string[]]$DockerArguments)
    & docker @DockerArguments 2>&1
} -ArgumentList (,$samplerArguments)

$envValues = [ordered]@{
    BASE_URL = $BaseUrl
    PERF_PASSWORD = $env:PERF_PASSWORD
    PHASE4_CONTEXT_FILE = '/results/phase-4-runtime-context.json'
    RESULT_FILE = "/results/$k6Name"
    TEST_STARTED_AT = $startedAt.ToString('o')
    GIT_COMMIT = $gitCommit
    SCENARIO = "phase4-$Scenario"
    K6_IMAGE = $k6Image
    K6_IMAGE_DIGEST = $imageDigest
    CONSUMER_CONCURRENCY = [string]$ConsumerConcurrency
    AI_BACKEND_STATE = ($stubHealth | ConvertTo-Json -Compress)
    AI_STUB_DELAY_MS = [string]$AiStubDelayMs
    VU_MODEL = '1 VU x 1 iteration; 5 concurrent real API uploads; 0.5s progress polling'
    EXPERIMENT_ID = $ExperimentId
    EXPERIMENT_PHASE = '4'
    RUN_NUMBER = [string]$RunNumber
    TASK_COUNT = '1000'
    BATCH_COUNT = '5'
    BATCH_TIMEOUT_SECONDS = '1200'
    POLL_INTERVAL_SECONDS = '0.5'
}
$dockerArguments = @(
    'run', '--rm', '--add-host=host.docker.internal:host-gateway',
    '-v', "${k6Root}:/scripts:ro", '-v', "${resultsRoot}:/results"
)
foreach ($entry in $envValues.GetEnumerator()) {
    [Environment]::SetEnvironmentVariable(
        [string]$entry.Key,
        [string]$entry.Value,
        [EnvironmentVariableTarget]::Process
    )
    $dockerArguments += @('-e', $entry.Key)
}
$dockerArguments += @($k6Image, 'run', '/scripts/scenarios/batch-burst.js')

Write-Host "Running Phase 4 $Scenario c$ConsumerConcurrency r$RunNumber with ${AiStubDelayMs}ms Stub."
$oldPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $capturedOutput = @(& docker @dockerArguments 2>&1)
    $k6ExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $oldPreference
}
$finishedAt = (Get-Date).ToUniversalTime()

$samplerFinished = Wait-Job -Job $samplerJob -Timeout 30
if ($null -eq $samplerFinished) {
    Stop-Job -Job $samplerJob
    $samplerLines = @(Receive-Job -Job $samplerJob)
    Remove-Job -Job $samplerJob -Force
    throw 'Backlog sampler did not observe a fully drained run.'
}
$samplerLines = @(Receive-Job -Job $samplerJob)
Remove-Job -Job $samplerJob

$outcomeText = $null
foreach ($line in $capturedOutput) {
    $text = [string]$line
    $marker = 'COURSEINSIGHT_PHASE4_RESULT='
    $position = $text.IndexOf($marker)
    if ($position -ge 0) { $outcomeText = $text.Substring($position + $marker.Length) }
}
if ([string]::IsNullOrWhiteSpace($outcomeText)) {
    throw 'k6 did not emit the Phase 4 outcome line.'
}
$normalizedOutcome = $outcomeText.Replace('\"', '"')
$jsonStart = $normalizedOutcome.IndexOf('{')
$jsonEnd = $normalizedOutcome.LastIndexOf('}')
if ($jsonStart -lt 0 -or $jsonEnd -le $jsonStart) {
    throw 'The Phase 4 outcome line did not contain a complete JSON object.'
}
$k6Outcome = $normalizedOutcome.Substring(
    $jsonStart,
    $jsonEnd - $jsonStart + 1
) | ConvertFrom-Json
if (@($k6Outcome.batchIds).Count -ne 5) { throw 'k6 outcome does not contain five batches.' }
$batchIdsSql = @($k6Outcome.batchIds | ForEach-Object { [long]$_ }) -join ','

Write-Host 'Waiting for the final Prometheus scrape.'
Start-Sleep -Seconds 20
$observedAt = (Get-Date).ToUniversalTime()
$rangeSeconds = [Math]::Max(30, [Math]::Ceiling(($observedAt - $startedAt).TotalSeconds) + 15)
$range = "${rangeSeconds}s"
$startEpoch = [Math]::Floor(([DateTimeOffset]$startedAt).ToUnixTimeMilliseconds() / 1000)
$endEpoch = [Math]::Floor(([DateTimeOffset]$observedAt).ToUnixTimeMilliseconds() / 1000)
$queries = [ordered]@{
    applicationUp = 'max(up{job="courseinsight-server"})'
    processCpuUsageMax = "max(max_over_time(process_cpu_usage[${range}]))"
    systemCpuUsageMax = "max(max_over_time(system_cpu_usage[${range}]))"
    jvmHeapUsedMaxBytes = "max_over_time(sum(jvm_memory_used_bytes{area=`"heap`"})[${range}:15s])"
    jvmHeapMaxBytes = 'sum(jvm_memory_max_bytes{area="heap"})'
    jvmGcPauseIncreaseSeconds = "sum(jvm_gc_pause_seconds_sum @ $endEpoch - jvm_gc_pause_seconds_sum @ $startEpoch)"
    jvmThreadsLiveMax = "max(max_over_time(jvm_threads_live_threads[${range}]))"
    hikariActiveMax = "max(max_over_time(hikaricp_connections_active[${range}]))"
    hikariConfiguredMax = 'max(hikaricp_connections_max)'
    http5xxCount = "sum(http_server_requests_seconds_count{status=~`"5..`",uri!~`"/actuator.*`"} @ $endEpoch - http_server_requests_seconds_count{status=~`"5..`",uri!~`"/actuator.*`"} @ $startEpoch)"
    aiRequestCountByOutcome = "sum by (outcome) (courseinsight_ai_request_seconds_count @ $endEpoch - courseinsight_ai_request_seconds_count @ $startEpoch)"
    aiRequestTotalSecondsByOutcome = "sum by (outcome) (courseinsight_ai_request_seconds_sum @ $endEpoch - courseinsight_ai_request_seconds_sum @ $startEpoch)"
    aiP50SecondsByOutcome = "histogram_quantile(0.50, sum by (le,outcome) (courseinsight_ai_request_seconds_bucket @ $endEpoch - courseinsight_ai_request_seconds_bucket @ $startEpoch))"
    aiP95SecondsByOutcome = "histogram_quantile(0.95, sum by (le,outcome) (courseinsight_ai_request_seconds_bucket @ $endEpoch - courseinsight_ai_request_seconds_bucket @ $startEpoch))"
    aiP99SecondsByOutcome = "histogram_quantile(0.99, sum by (le,outcome) (courseinsight_ai_request_seconds_bucket @ $endEpoch - courseinsight_ai_request_seconds_bucket @ $startEpoch))"
    analysisTaskCountByOutcome = "sum by (outcome) (courseinsight_analysis_task_total @ $endEpoch - courseinsight_analysis_task_total @ $startEpoch)"
    outboxPublishCountByOutcome = "sum by (outcome) (courseinsight_outbox_publish_total @ $endEpoch - courseinsight_outbox_publish_total @ $startEpoch)"
}
$prometheusResults = [ordered]@{}
foreach ($entry in $queries.GetEnumerator()) {
    $prometheusResults[$entry.Key] = Invoke-PrometheusQuery $entry.Value
}
$prometheusArtifact = [ordered]@{
    schemaVersion = 1; gitCommit = $gitCommit; experimentId = $ExperimentId
    scenario = $Scenario; runNumber = $RunNumber; consumerConcurrency = $ConsumerConcurrency
    aiStub = $stubHealth; testStartedAt = $startedAt.ToString('o')
    testFinishedAt = $finishedAt.ToString('o'); observedAt = $observedAt.ToString('o')
    queryRangeSeconds = $rangeSeconds; queries = $queries; results = $prometheusResults
}
Write-Utf8NoBom (Join-Path $resultsRoot $prometheusName) ($prometheusArtifact | ConvertTo-Json -Depth 20)

$samples = @()
foreach ($line in $samplerLines) {
    $parts = [string]$line -split "`t"
    if ($parts.Count -eq 10 -and $parts[0] -match '^\d+$') {
        $samples += [ordered]@{
            timestampEpochMs = [long]$parts[0]; total = [int]$parts[1]
            waiting = [int]$parts[2]; processing = [int]$parts[3]
            retrying = [int]$parts[4]; success = [int]$parts[5]
            terminalFailure = [int]$parts[6]; outboxPending = [int]$parts[7]
            outboxPublishing = [int]$parts[8]; outboxFailed = [int]$parts[9]
            proxyBacklog = [int]$parts[2] + [int]$parts[3]
        }
    }
}
if ($samples.Count -eq 0) { throw 'Backlog sampler produced no parseable samples.' }
$backlogArtifact = [ordered]@{
    schemaVersion = 1; definition = 'proxy backlog = analysis_task WAITING + PROCESSING; not RocketMQ native queue depth'
    sampleIntervalSeconds = 0.5; experimentId = $ExperimentId; scenario = $Scenario
    runNumber = $RunNumber; consumerConcurrency = $ConsumerConcurrency
    aiStubDelayMs = $AiStubDelayMs
    peakProxyBacklog = [int]($samples | ForEach-Object { $_.proxyBacklog } | Measure-Object -Maximum).Maximum
    peakOutboxPending = [int]($samples | ForEach-Object { $_.outboxPending } | Measure-Object -Maximum).Maximum
    samples = $samples
}
Write-Utf8NoBom (Join-Path $resultsRoot $backlogName) ($backlogArtifact | ConvertTo-Json -Depth 10)

$databaseLines = Invoke-MySqlLines @"
SELECT CONCAT_WS(CHAR(9), 'TASK', task.id,
  ROUND(UNIX_TIMESTAMP(task.created_at)*1000,3),
  ROUND(UNIX_TIMESTAMP(task.completed_at)*1000,3),
  task.status, task.retry_count,
  IF(task.dead_lettered_at IS NULL,0,1))
FROM analysis_task task WHERE task.batch_id IN ($batchIdsSql) ORDER BY task.id;
SELECT CONCAT_WS(CHAR(9), 'OUTBOX',
  SUM(retry_count), SUM(status='SENT'), SUM(status='FAILED'))
FROM analysis_outbox_event WHERE task_id IN
  (SELECT id FROM analysis_task WHERE batch_id IN ($batchIdsSql));
SELECT CONCAT_WS(CHAR(9), 'BATCH',
  MIN(ROUND(UNIX_TIMESTAMP(created_at)*1000,3)), COUNT(*))
FROM analysis_batch WHERE id IN ($batchIdsSql);
"@
$taskLatencies = @()
$completionEpochs = @()
$taskRetry = 0L
$success = 0L
$failure = 0L
$outboxRetry = 0L
$outboxSent = 0L
$outboxFailure = 0L
foreach ($line in $databaseLines) {
    $parts = [string]$line -split "`t"
    switch ($parts[0]) {
        'TASK' {
            $created = [double]::Parse($parts[2], $culture)
            $completed = [double]::Parse($parts[3], $culture)
            $taskLatencies += $completed - $created
            $completionEpochs += $completed
            $taskRetry += [long]$parts[5]
            if ($parts[4] -eq 'SUCCESS') { $success++ }
            elseif ($parts[4] -eq 'FAILED' -and $parts[6] -eq '1') { $failure++ }
        }
        'OUTBOX' {
            $outboxRetry = [long]$parts[1]; $outboxSent = [long]$parts[2]
            $outboxFailure = [long]$parts[3]
        }
    }
}
if ($taskLatencies.Count -ne 1000) { throw "Expected 1000 terminal task rows, got $($taskLatencies.Count)." }
$orderedCompletion = @($completionEpochs | Sort-Object)
$lowIndex = [Math]::Floor(($orderedCompletion.Count - 1) * 0.10)
$highIndex = [Math]::Ceiling(($orderedCompletion.Count - 1) * 0.90)
$steadyWindowSeconds = ($orderedCompletion[$highIndex] - $orderedCompletion[$lowIndex]) / 1000
$steadyThroughput = if ($steadyWindowSeconds -gt 0) {
    ($highIndex - $lowIndex) / $steadyWindowSeconds
} else { $null }
$outcome = [ordered]@{
    schemaVersion = 1; gitCommit = $gitCommit; experimentId = $ExperimentId
    scenario = $Scenario; runNumber = $RunNumber; consumerConcurrency = $ConsumerConcurrency
    aiStubDelayMs = $AiStubDelayMs; taskCount = 1000; batchCount = 5
    batchIds = @($k6Outcome.batchIds); productionDurationMs = [double]$k6Outcome.productionDurationMs
    createLatencyMs = @($k6Outcome.createLatencyMs)
    totalCompletionMs = [double]$k6Outcome.totalCompletionMs
    drainTimeMs = [double]$k6Outcome.drainTimeMs
    throughputTasksPerSecond = 1000 / ([double]$k6Outcome.totalCompletionMs / 1000)
    steadyCompletionThroughputTasksPerSecond = $steadyThroughput
    steadyThroughputDefinition = 'completed tasks between the 10th and 90th percentile completion timestamps divided by that window'
    taskCompletionLatencyMs = Get-Statistics ([double[]]$taskLatencies)
    successCount = $success; terminalFailureCount = $failure; taskRetryCount = $taskRetry
    outboxRetryCount = $outboxRetry; outboxSentCount = $outboxSent
    outboxFailureCount = $outboxFailure
    peakProxyBacklog = $backlogArtifact.peakProxyBacklog
    peakOutboxPending = $backlogArtifact.peakOutboxPending
    http5xxSource = 'Prometheus artifact'
    aiStub = $stubHealth
    artifacts = [ordered]@{ k6 = $k6Name; prometheus = $prometheusName; backlog = $backlogName }
}
Write-Utf8NoBom (Join-Path $resultsRoot $outcomeName) ($outcome | ConvertTo-Json -Depth 12)

Write-Host "k6: $(Join-Path $resultsRoot $k6Name)"
Write-Host "Prometheus: $(Join-Path $resultsRoot $prometheusName)"
Write-Host "Backlog: $(Join-Path $resultsRoot $backlogName)"
Write-Host "Outcome: $(Join-Path $resultsRoot $outcomeName)"
if ($k6ExitCode -ne 0) { throw "k6 exited with code $k6ExitCode after writing artifacts." }
