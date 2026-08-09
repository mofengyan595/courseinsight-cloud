[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('course-detail', 'course-analytics')]
    [string]$Endpoint,
    [Parameter(Mandatory = $true)]
    [ValidateSet('miss', 'hit')]
    [string]$CacheState,
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 5000)]
    [int]$TargetRps,
    [ValidateRange(10, 300)]
    [int]$DurationSeconds = 30,
    [ValidateRange(1, 5000)]
    [int]$PreAllocatedVUs = 100,
    [ValidateRange(1, 10000)]
    [int]$MaxVUs = 1000,
    [ValidateRange(1, 100)]
    [int]$PrewarmVUs = 10,
    [ValidateRange(1, 5)]
    [int]$PrewarmAttempts = 3,
    [ValidateRange(1, 100)]
    [int]$RunNumber = 1,
    [ValidateSet('pilot', 'ab', 'capacity')]
    [string]$ExperimentPhase = 'ab',
    [ValidatePattern('^[A-Za-z0-9_-]{3,40}$')]
    [string]$ExperimentId = 'P3_HTTP',
    [string]$ContextPath = '',
    [string]$BaseUrl = 'http://host.docker.internal:8080',
    [string]$PrometheusUrl = 'http://127.0.0.1:9090',
    [ValidateRange(0, 60)]
    [int]$PreconditionCooldownSeconds = 20
)

$ErrorActionPreference = 'Stop'
$stableSlo = 'error_rate<1%;p95<200ms;achieved_rps>=99%_target;dropped_iterations=0'
$k6Image = 'grafana/k6:1.3.0'
$k6Root = Split-Path -Parent $PSScriptRoot
$performanceRoot = Split-Path -Parent $k6Root
$resultsRoot = Join-Path $performanceRoot 'results'
if ([string]::IsNullOrWhiteSpace($ContextPath)) {
    $ContextPath = Join-Path $resultsRoot 'phase-3-runtime-context.json'
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$LiteralPath,
        [Parameter(Mandatory = $true)][string]$Content
    )
    [IO.File]::WriteAllText(
        $LiteralPath,
        $Content.Replace("`r`n", "`n") + "`n",
        (New-Object Text.UTF8Encoding($false))
    )
}

function Invoke-PrometheusQuery {
    param([Parameter(Mandatory = $true)][string]$Query)
    $encoded = [Uri]::EscapeDataString($Query)
    $response = Invoke-RestMethod -Uri "$PrometheusUrl/api/v1/query?query=$encoded" -TimeoutSec 30
    if ($response.status -ne 'success') {
        throw "Prometheus query failed: $Query"
    }
    return @($response.data.result)
}

function Get-RedisInfo {
    $values = [ordered]@{}
    foreach ($section in @('stats', 'memory', 'cpu')) {
        $lines = & docker exec courseinsight-redis redis-cli INFO $section
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to read Redis INFO $section."
        }
        foreach ($line in $lines) {
            $normalized = $line.Trim()
            if ($normalized -match '^([^#:]+):(.+)$') {
                $name = $Matches[1]
                $raw = $Matches[2]
                if ($raw -match '^-?\d+$') {
                    $values[$name] = [long]$raw
                } elseif ($raw -match '^-?\d+\.\d+$') {
                    $values[$name] = [double]$raw
                }
            }
        }
    }
    return $values
}

function Get-MySqlStatus {
    $names = @(
        'Bytes_received', 'Bytes_sent', 'Com_select', 'Connections',
        'Created_tmp_disk_tables', 'Created_tmp_tables', 'Handler_read_key',
        'Handler_read_next', 'Innodb_buffer_pool_read_requests',
        'Innodb_buffer_pool_reads', 'Questions', 'Slow_queries',
        'Threads_connected', 'Threads_running'
    )
    $quotedNames = ($names | ForEach-Object { "'$_'" }) -join ','
    $sql = "SHOW GLOBAL STATUS WHERE Variable_name IN ($quotedNames);"
    $sqlBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sql))
    $lines = & docker exec -e "PERF_SQL_B64=$sqlBase64" courseinsight-mysql `
        sh -c 'echo ${PERF_SQL_B64}|base64 -d|MYSQL_PWD=${MYSQL_PASSWORD} mysql -N -B -u${MYSQL_USER} ${MYSQL_DATABASE}'
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to read MySQL GLOBAL STATUS.'
    }
    $values = [ordered]@{}
    foreach ($line in $lines) {
        $parts = $line -split "`t", 2
        if ($parts.Count -eq 2 -and $parts[1] -match '^\d+$') {
            $values[$parts[0]] = [long]$parts[1]
        }
    }
    return $values
}

function Get-NumericDelta {
    param([Collections.IDictionary]$Before, [Collections.IDictionary]$After)
    $delta = [ordered]@{}
    foreach ($key in $Before.Keys) {
        if ($After.Contains($key) -and $Before[$key] -is [ValueType] -and
            $After[$key] -is [ValueType]) {
            $delta[$key] = $After[$key] - $Before[$key]
        }
    }
    return $delta
}

function Invoke-RedisKeyCommand {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('UNLINK', 'EXISTS')][string]$Command,
        [Parameter(Mandatory = $true)][string[]]$Keys
    )
    [long]$sum = 0
    $batchSize = 500
    for ($offset = 0; $offset -lt $Keys.Count; $offset += $batchSize) {
        $last = [Math]::Min($offset + $batchSize - 1, $Keys.Count - 1)
        $batch = @($Keys[$offset..$last])
        $output = & docker exec courseinsight-redis redis-cli $Command @batch
        if ($LASTEXITCODE -ne 0) {
            throw "Redis $Command failed for isolated benchmark keys."
        }
        if ($output -match '^\d+$') {
            $sum += [long]$output
        }
    }
    return $sum
}

if (-not (Test-Path -LiteralPath $ContextPath)) {
    throw "HTTP benchmark context does not exist: $ContextPath"
}
if ([string]::IsNullOrWhiteSpace($env:PERF_USERNAME) -or
    [string]::IsNullOrWhiteSpace($env:PERF_PASSWORD)) {
    throw 'PERF_USERNAME and PERF_PASSWORD must be set in the current process.'
}
$context = Get-Content -Raw -LiteralPath $ContextPath | ConvertFrom-Json
if ($context.username -ne $env:PERF_USERNAME) {
    throw 'PERF_USERNAME does not match the prepared HTTP benchmark context.'
}
if ($context.dataPresent -eq $false) {
    throw 'The HTTP benchmark context has already been cleaned.'
}

# k6 can schedule one final arrival on the duration boundary. Ten guard keys keep
# every measured iteration unique and, for hit runs, prewarmed as well.
$requiredCourseCount = $TargetRps * $DurationSeconds + 10
$courseIds = @($context.courseIds | Select-Object -First $requiredCourseCount | ForEach-Object { [long]$_ })
if ($courseIds.Count -lt $requiredCourseCount) {
    throw "Need $requiredCourseCount unique course IDs, but context has $($courseIds.Count)."
}
$cachePrefix = if ($Endpoint -eq 'course-detail') {
    'course:detail:'
} else {
    'course:analytics:summary:'
}
$cacheKeys = @($courseIds | ForEach-Object { "$cachePrefix$_" })

$appHealth = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 10
if ($appHealth.status -ne 'UP') {
    throw 'CourseInsight application health is not UP.'
}
$prometheusReady = Invoke-WebRequest -UseBasicParsing -Uri "$PrometheusUrl/-/ready" -TimeoutSec 10
if ($prometheusReady.StatusCode -ne 200) {
    throw 'Prometheus is not ready.'
}
$targetResponse = Invoke-RestMethod -Uri "$PrometheusUrl/api/v1/targets" -TimeoutSec 20
$appTarget = @($targetResponse.data.activeTargets | Where-Object {
    $_.labels.job -eq 'courseinsight-server'
}) | Select-Object -First 1
if ($null -eq $appTarget -or $appTarget.health -ne 'up') {
    throw 'Prometheus courseinsight-server target is not UP.'
}

Invoke-RedisKeyCommand -Command UNLINK -Keys $cacheKeys | Out-Null

$contextLeaf = Split-Path -Leaf $ContextPath
$contextDockerPath = "/results/$contextLeaf"
$commonDockerArguments = @(
    'run', '--rm', '--add-host=host.docker.internal:host-gateway',
    '-v', "${k6Root}:/scripts:ro", '-v', "${resultsRoot}:/results",
    '-e', 'BASE_URL', '-e', 'PERF_USERNAME', '-e', 'PERF_PASSWORD',
    '-e', 'ENDPOINT', '-e', 'COURSE_CONTEXT_FILE'
)
$env:BASE_URL = $BaseUrl
$env:ENDPOINT = $Endpoint
$env:COURSE_CONTEXT_FILE = $contextDockerPath

if ($CacheState -eq 'hit') {
    $env:PREWARM_ITERATIONS = [string]$requiredCourseCount
    $env:PREWARM_VUS = [string]$PrewarmVUs
    $prewarmArguments = $commonDockerArguments + @(
        '-e', 'PREWARM_ITERATIONS', '-e', 'PREWARM_VUS',
        $k6Image, 'run', '--quiet', '/scripts/scenarios/http-cache-prewarm.js'
    )
    for ($attempt = 1; $attempt -le $PrewarmAttempts; $attempt++) {
        Write-Host "Prewarming $requiredCourseCount $Endpoint cache keys through authenticated HTTP (attempt $attempt/$PrewarmAttempts)..."
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $prewarmOutput = @(& docker @prewarmArguments 2>&1)
            $prewarmExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousPreference
        }
        if ($prewarmExitCode -ne 0 -and $prewarmExitCode -ne 99) {
            $prewarmOutput | Select-Object -Last 30 | Write-Host
            throw "Cache prewarm failed with unexpected k6 exit code $prewarmExitCode."
        }
        $prewarmedKeyCount = Invoke-RedisKeyCommand -Command EXISTS -Keys $cacheKeys
        if ($prewarmedKeyCount -eq $requiredCourseCount) {
            break
        }
        Write-Warning "Prewarm attempt $attempt produced $prewarmedKeyCount/$requiredCourseCount keys."
        if ($attempt -eq $PrewarmAttempts) {
            $prewarmOutput | Select-Object -Last 30 | Write-Host
            throw "Cache prewarm did not populate every selected key after $PrewarmAttempts attempts."
        }
    }
}

if ($PreconditionCooldownSeconds -gt 0) {
    Write-Host "Waiting $PreconditionCooldownSeconds seconds after cache preconditioning..."
    Start-Sleep -Seconds $PreconditionCooldownSeconds
}
$keysPresent = Invoke-RedisKeyCommand -Command EXISTS -Keys $cacheKeys
if ($CacheState -eq 'miss' -and $keysPresent -ne 0) {
    throw "Miss precondition failed: $keysPresent selected cache keys exist."
}
if ($CacheState -eq 'hit' -and $keysPresent -ne $requiredCourseCount) {
    throw "Hit precondition failed: expected $requiredCourseCount keys, found $keysPresent."
}

$gitCommit = (& git rev-parse HEAD).Trim()
$imageDigest = (& docker image inspect $k6Image --format '{{index .RepoDigests 0}}').Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect $k6Image."
}
$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$stem = "$stamp-http-$ExperimentId-$ExperimentPhase-$Endpoint-$CacheState-rps$TargetRps-r$RunNumber"
$k6Name = "$stem-k6.json"
$prometheusName = "$stem-prometheus.json"
$startedAt = (Get-Date).ToUniversalTime()
$redisBefore = Get-RedisInfo
$mySqlBefore = Get-MySqlStatus

$env:RESULT_FILE = "/results/$k6Name"
$env:TEST_STARTED_AT = $startedAt.ToString('o')
$env:GIT_COMMIT = $gitCommit
$env:SCENARIO = 'http-cache-open'
$env:CACHE_STATE = $CacheState
$env:K6_IMAGE = $k6Image
$env:K6_IMAGE_DIGEST = $imageDigest
$env:VU_MODEL = "constant-arrival-rate ${TargetRps}/s for ${DurationSeconds}s; preAllocatedVUs=$PreAllocatedVUs; maxVUs=$MaxVUs"
$env:EXPERIMENT_ID = $ExperimentId
$env:EXPERIMENT_PHASE = $ExperimentPhase
$env:TARGET_RPS = [string]$TargetRps
$env:DURATION = "${DurationSeconds}s"
$env:DURATION_SECONDS = [string]$DurationSeconds
$env:PRE_ALLOCATED_VUS = [string]$PreAllocatedVUs
$env:MAX_VUS = [string]$MaxVUs
$env:RUN_NUMBER = [string]$RunNumber
$env:STABLE_SLO = $stableSlo

$environmentNames = @(
    'RESULT_FILE', 'TEST_STARTED_AT', 'GIT_COMMIT', 'SCENARIO', 'CACHE_STATE',
    'K6_IMAGE', 'K6_IMAGE_DIGEST', 'VU_MODEL', 'EXPERIMENT_ID', 'EXPERIMENT_PHASE',
    'TARGET_RPS', 'DURATION', 'DURATION_SECONDS', 'PRE_ALLOCATED_VUS', 'MAX_VUS',
    'RUN_NUMBER', 'STABLE_SLO'
)
$runArguments = $commonDockerArguments
foreach ($name in $environmentNames) {
    $runArguments += @('-e', $name)
}
$runArguments += @($k6Image, 'run', '/scripts/scenarios/http-cache-open.js')

Write-Host "Running $Endpoint $CacheState at ${TargetRps} RPS for ${DurationSeconds}s..."
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $capturedOutput = @(& docker @runArguments 2>&1)
    $k6ExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
}
$finishedAt = (Get-Date).ToUniversalTime()
$redisAfter = Get-RedisInfo
$mySqlAfter = Get-MySqlStatus

Write-Host 'Waiting for the final Prometheus scrape...'
Start-Sleep -Seconds 20
$observedAt = (Get-Date).ToUniversalTime()
$startEpoch = [Math]::Floor(([DateTimeOffset]$startedAt).ToUnixTimeMilliseconds() / 1000)
$endEpoch = [Math]::Floor(([DateTimeOffset]$observedAt).ToUnixTimeMilliseconds() / 1000)
$rangeSeconds = [Math]::Max(30, $endEpoch - $startEpoch + 5)
$uri = if ($Endpoint -eq 'course-detail') {
    '/api/courses/{id}'
} else {
    '/api/courses/{courseId}/analytics/summary'
}
$queries = [ordered]@{
    applicationUp = 'max(up{job="courseinsight-server"})'
    processCpuUsageMax = "max(max_over_time(process_cpu_usage[${rangeSeconds}s] @ $endEpoch))"
    processCpuUsageAvg = "avg(avg_over_time(process_cpu_usage[${rangeSeconds}s] @ $endEpoch))"
    systemCpuUsageMax = "max(max_over_time(system_cpu_usage[${rangeSeconds}s] @ $endEpoch))"
    jvmHeapUsedMaxBytes = "max_over_time(sum(jvm_memory_used_bytes{area=`"heap`"})[${rangeSeconds}s:15s] @ $endEpoch)"
    jvmHeapMaxBytes = 'sum(jvm_memory_max_bytes{area="heap"})'
    jvmGcPauseIncreaseSeconds = "sum(jvm_gc_pause_seconds_sum @ $endEpoch - jvm_gc_pause_seconds_sum @ $startEpoch)"
    jvmThreadsLiveMax = "max(max_over_time(jvm_threads_live_threads[${rangeSeconds}s] @ $endEpoch))"
    tomcatThreadsBusyMax = "max(max_over_time(tomcat_threads_busy_threads[${rangeSeconds}s] @ $endEpoch))"
    tomcatThreadsConfiguredMax = 'max(tomcat_threads_config_max_threads)'
    hikariActiveMax = "max(max_over_time(hikaricp_connections_active[${rangeSeconds}s] @ $endEpoch))"
    hikariConfiguredMax = 'max(hikaricp_connections_max)'
    endpointHttpP95Seconds = "histogram_quantile(0.95, sum by (le) (http_server_requests_seconds_bucket{uri=`"$uri`"} @ $endEpoch - http_server_requests_seconds_bucket{uri=`"$uri`"} @ $startEpoch))"
    endpointHttpCount = "sum(http_server_requests_seconds_count{uri=`"$uri`"} @ $endEpoch - http_server_requests_seconds_count{uri=`"$uri`"} @ $startEpoch)"
    endpointHttp5xxCount = "sum(http_server_requests_seconds_count{uri=`"$uri`",status=~`"5..`"} @ $endEpoch - http_server_requests_seconds_count{uri=`"$uri`",status=~`"5..`"} @ $startEpoch)"
}
$queryResults = [ordered]@{}
foreach ($entry in $queries.GetEnumerator()) {
    $queryResults[$entry.Key] = Invoke-PrometheusQuery -Query $entry.Value
}

$prometheusArtifact = [ordered]@{
    schemaVersion = 3
    gitCommit = $gitCommit
    experimentId = $ExperimentId
    experimentPhase = $ExperimentPhase
    runNumber = $RunNumber
    endpoint = $Endpoint
    cacheState = $CacheState
    targetRps = $TargetRps
    durationSeconds = $DurationSeconds
    requiredUniqueCourses = $requiredCourseCount
    cacheKeysPresentBeforeRun = $keysPresent
    stableSlo = $stableSlo
    k6ExitCode = $k6ExitCode
    testStartedAt = $startedAt.ToString('o')
    testFinishedAt = $finishedAt.ToString('o')
    observedAt = $observedAt.ToString('o')
    prometheusTarget = $appTarget.health
    prometheusScrapeIntervalSeconds = 15
    queries = $queries
    results = $queryResults
    redis = [ordered]@{
        before = $redisBefore
        after = $redisAfter
        delta = Get-NumericDelta -Before $redisBefore -After $redisAfter
    }
    mysql = [ordered]@{
        before = $mySqlBefore
        after = $mySqlAfter
        delta = Get-NumericDelta -Before $mySqlBefore -After $mySqlAfter
    }
}
$prometheusPath = Join-Path $resultsRoot $prometheusName
Write-Utf8NoBom -LiteralPath $prometheusPath -Content ($prometheusArtifact | ConvertTo-Json -Depth 20)

$k6Path = Join-Path $resultsRoot $k6Name
if (-not (Test-Path -LiteralPath $k6Path)) {
    $capturedOutput | Select-Object -Last 50 | Write-Host
    throw 'k6 did not produce the expected structured result.'
}
$capturedOutput | Select-Object -Last 24 | Write-Host
Write-Host "k6 result: $k6Path"
Write-Host "Prometheus result: $prometheusPath"
if ($k6ExitCode -ne 0 -and $k6ExitCode -ne 99) {
    throw "k6 failed with unexpected exit code $k6ExitCode."
}
if ($k6ExitCode -eq 99) {
    Write-Warning 'One or more fixed benchmark thresholds failed; artifacts were preserved.'
}
