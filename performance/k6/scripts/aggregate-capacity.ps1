[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{1,40}$')]
    [string]$ExperimentId,
    [int[]]$Concurrencies = @(1, 2, 4),
    [ValidateRange(1, 20)]
    [int]$ExpectedRunsPerConcurrency = 3,
    [ValidateRange(0, 300)]
    [int]$CooldownSeconds = 30,
    [string]$ResultsPath = ''
)

$ErrorActionPreference = 'Stop'
$culture = [Globalization.CultureInfo]::InvariantCulture
$k6Root = Split-Path -Parent $PSScriptRoot
$performanceRoot = Split-Path -Parent $k6Root
if ([string]::IsNullOrWhiteSpace($ResultsPath)) {
    $ResultsPath = Join-Path $performanceRoot 'results'
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$LiteralPath,
        [Parameter(Mandatory = $true)][string]$Content
    )
    $normalized = $Content.Replace("`r`n", "`n")
    [IO.File]::WriteAllText(
        $LiteralPath,
        $normalized.TrimEnd() + "`n",
        (New-Object Text.UTF8Encoding($false))
    )
}

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    if ($Values.Count -eq 0) {
        return $null
    }
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 1) {
        return [double]$sorted[0]
    }
    $position = ($sorted.Count - 1) * $Percentile
    $lower = [Math]::Floor($position)
    $upper = [Math]::Ceiling($position)
    if ($lower -eq $upper) {
        return [double]$sorted[$lower]
    }
    $weight = $position - $lower
    [double]$sorted[$lower] * (1 - $weight) + [double]$sorted[$upper] * $weight
}

function Get-Statistics {
    param([double[]]$Values)
    if ($Values.Count -eq 0) {
        return $null
    }
    $average = ($Values | Measure-Object -Average).Average
    $variance = if ($Values.Count -gt 1) {
        ($Values | ForEach-Object { [Math]::Pow($_ - $average, 2) } |
            Measure-Object -Sum).Sum / ($Values.Count - 1)
    } else {
        0
    }
    [ordered]@{
        count = $Values.Count
        average = [double]$average
        standardDeviationSample = [Math]::Sqrt($variance)
        coefficientOfVariation = if ($average -eq 0) { 0 } else {
            [Math]::Sqrt($variance) / $average
        }
        min = [double]($Values | Measure-Object -Minimum).Minimum
        p50 = Get-Percentile -Values $Values -Percentile 0.50
        p95 = Get-Percentile -Values $Values -Percentile 0.95
        p99 = Get-Percentile -Values $Values -Percentile 0.99
        max = [double]($Values | Measure-Object -Maximum).Maximum
    }
}

function Get-PrometheusValue {
    param(
        [object[]]$Series,
        [string]$Outcome = ''
    )
    $candidate = if ([string]::IsNullOrWhiteSpace($Outcome)) {
        @($Series) | Select-Object -First 1
    } else {
        @($Series) |
            Where-Object { $_.metric.outcome -eq $Outcome } |
            Select-Object -First 1
    }
    if ($null -eq $candidate -or $null -eq $candidate.value) {
        return 0.0
    }
    $raw = [string]$candidate.value[1]
    if ($raw -in @('NaN', '+Inf', '-Inf')) {
        return $null
    }
    [double]::Parse($raw, $culture)
}

function Invoke-MySqlLines {
    param([Parameter(Mandatory = $true)][string]$Sql)
    $sqlBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Sql))
    $lines = & docker exec `
        -e "PERF_SQL_B64=$sqlBase64" `
        courseinsight-mysql `
        sh -c 'echo ${PERF_SQL_B64}|base64 -d|MYSQL_PWD=${MYSQL_PASSWORD} mysql -N -B -u${MYSQL_USER} ${MYSQL_DATABASE}'
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to collect capacity evidence from MySQL.'
    }
    @($lines)
}

function Format-Number {
    param([object]$Value, [string]$Format = '0.000')
    if ($null -eq $Value) {
        return 'n/a'
    }
    ([double]$Value).ToString($Format, $culture)
}

$pattern = "*-batch-200-$ExperimentId-c*-r*-outcome.json"
$outcomeFiles = @(
    Get-ChildItem -LiteralPath $ResultsPath -Filter $pattern -File |
        Sort-Object Name
)
$expectedCount = $Concurrencies.Count * $ExpectedRunsPerConcurrency
if ($outcomeFiles.Count -ne $expectedCount) {
    throw "Expected $expectedCount outcome files for $ExperimentId, found $($outcomeFiles.Count)."
}

$artifactSets = @()
foreach ($outcomeFile in $outcomeFiles) {
    $stem = $outcomeFile.Name.Substring(
        0,
        $outcomeFile.Name.Length - '-outcome.json'.Length
    )
    $k6Path = Join-Path $ResultsPath "$stem-k6.json"
    $prometheusPath = Join-Path $ResultsPath "$stem-prometheus.json"
    if (-not (Test-Path -LiteralPath $k6Path) -or
        -not (Test-Path -LiteralPath $prometheusPath)) {
        throw "Incomplete artifact set: $stem"
    }
    $artifactSets += [pscustomobject]@{
        stem = $stem
        outcomePath = $outcomeFile.FullName
        k6Path = $k6Path
        prometheusPath = $prometheusPath
        outcome = Get-Content -Raw -LiteralPath $outcomeFile.FullName | ConvertFrom-Json
        k6 = Get-Content -Raw -LiteralPath $k6Path | ConvertFrom-Json
        prometheus = Get-Content -Raw -LiteralPath $prometheusPath | ConvertFrom-Json
    }
}

$keys = @{}
foreach ($set in $artifactSets) {
    $key = "c$($set.outcome.consumerConcurrency)-r$($set.outcome.runNumber)"
    if ($keys.ContainsKey($key)) {
        throw "Duplicate capacity run: $key"
    }
    $keys[$key] = $true
}
foreach ($concurrency in $Concurrencies) {
    for ($run = 1; $run -le $ExpectedRunsPerConcurrency; $run++) {
        if (-not $keys.ContainsKey("c$concurrency-r$run")) {
            throw "Missing capacity run c$concurrency-r$run."
        }
    }
}

$batchIds = @($artifactSets | ForEach-Object { [long]$_.outcome.batchId })
$batchIdsSql = $batchIds -join ','
$databaseLines = Invoke-MySqlLines -Sql @"
SELECT CONCAT_WS(CHAR(9), 'BATCH', batch_row.id,
    DATE_FORMAT(batch_row.created_at, '%Y-%m-%dT%H:%i:%s.%f'),
    DATE_FORMAT(MAX(task.completed_at), '%Y-%m-%dT%H:%i:%s.%f'),
    ROUND(TIMESTAMPDIFF(MICROSECOND, batch_row.created_at, MAX(task.completed_at)) / 1000, 3),
    SUM(task.retry_count), MAX(task.retry_count),
    SUM(task.status = 'SUCCESS'),
    SUM(task.status = 'FAILED' AND task.dead_lettered_at IS NOT NULL))
FROM analysis_batch batch_row
INNER JOIN analysis_task task ON task.batch_id = batch_row.id
WHERE batch_row.id IN ($batchIdsSql)
GROUP BY batch_row.id, batch_row.created_at
ORDER BY batch_row.id;
SELECT CONCAT_WS(CHAR(9), 'OUTBOX', task.batch_id,
    SUM(outbox.retry_count), SUM(outbox.status = 'SENT'),
    SUM(outbox.status = 'FAILED'))
FROM analysis_task task
INNER JOIN analysis_outbox_event outbox ON outbox.task_id = task.id
WHERE task.batch_id IN ($batchIdsSql)
GROUP BY task.batch_id
ORDER BY task.batch_id;
SELECT CONCAT_WS(CHAR(9), 'SOURCE', task.batch_id,
    result_row.sentiment_source, result_row.sentiment_device,
    COALESCE(result_row.advice_source, 'none'), COUNT(*))
FROM analysis_task task
INNER JOIN analysis_result result_row ON result_row.task_id = task.id
WHERE task.batch_id IN ($batchIdsSql)
GROUP BY task.batch_id, result_row.sentiment_source,
    result_row.sentiment_device, result_row.advice_source
ORDER BY task.batch_id, result_row.sentiment_source, result_row.advice_source;
SELECT CONCAT_WS(CHAR(9), 'TASK', task.batch_id, task.id)
FROM analysis_task task
WHERE task.batch_id IN ($batchIdsSql)
ORDER BY task.batch_id, task.id;
"@

$databaseByBatch = @{}
$taskToBatch = @{}
foreach ($batchId in $batchIds) {
    $databaseByBatch[[string]$batchId] = [ordered]@{
        sources = @()
    }
}
foreach ($line in $databaseLines) {
    $parts = [string]$line -split '\\t'
    switch ($parts[0]) {
        'BATCH' {
            $entry = $databaseByBatch[$parts[1]]
            $entry.createdAt = $parts[2]
            $entry.completedAt = $parts[3]
            $entry.terminalDurationMs = [double]::Parse($parts[4], $culture)
            $entry.taskRetryCount = [long]$parts[5]
            $entry.maxTaskRetryCount = [long]$parts[6]
            $entry.successCount = [long]$parts[7]
            $entry.terminalFailureCount = [long]$parts[8]
        }
        'OUTBOX' {
            $entry = $databaseByBatch[$parts[1]]
            $entry.outboxRetryCount = [long]$parts[2]
            $entry.outboxSentCount = [long]$parts[3]
            $entry.outboxFailureCount = [long]$parts[4]
        }
        'SOURCE' {
            $entry = $databaseByBatch[$parts[1]]
            $entry.sources += [ordered]@{
                sentimentSource = $parts[2]
                sentimentDevice = $parts[3]
                adviceSource = $parts[4]
                count = [long]$parts[5]
            }
        }
        'TASK' {
            $taskToBatch[$parts[2]] = $parts[1]
        }
    }
}

$startedAtValues = @($artifactSets | ForEach-Object {
    [DateTimeOffset]::Parse([string]$_.k6.metadata.startedAt)
})
$finishedAtValues = @($artifactSets | ForEach-Object {
    [DateTimeOffset]::Parse([string]$_.k6.metadata.finishedAt)
})
$logSince = ($startedAtValues | Sort-Object | Select-Object -First 1).AddSeconds(-2)
$logUntil = ($finishedAtValues | Sort-Object | Select-Object -Last 1).AddSeconds(2)
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $aiLogLines = @(& docker logs --timestamps `
        --since $logSince.ToString('o') `
        --until $logUntil.ToString('o') `
        courseinsight-ai-service 2>&1)
    $dockerLogExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
if ($dockerLogExitCode -ne 0) {
    throw 'Unable to read AI timing observations from the AI container.'
}

$phaseDurations = @{}
$phaseSources = @{}
foreach ($line in $aiLogLines) {
    $text = [string]$line
    if ($text -notmatch 'courseinsight_ai_phase_duration phase=(sentiment|advice) task_id=([0-9]+) duration_ms=([0-9]+(?:\.[0-9]+)?) source=([^\s]+)(?: device=([^\s]+))?') {
        continue
    }
    $phase = $Matches[1]
    $taskId = $Matches[2]
    if (-not $taskToBatch.ContainsKey($taskId)) {
        continue
    }
    $batchId = $taskToBatch[$taskId]
    $key = "$batchId|$phase"
    if (-not $phaseDurations.ContainsKey($key)) {
        $phaseDurations[$key] = @()
        $phaseSources[$key] = @{}
    }
    $phaseDurations[$key] += [double]::Parse($Matches[3], $culture)
    $source = $Matches[4]
    if (-not $phaseSources[$key].ContainsKey($source)) {
        $phaseSources[$key][$source] = 0
    }
    $phaseSources[$key][$source]++
}

$runs = @()
foreach ($set in ($artifactSets | Sort-Object {
    [int]$_.outcome.consumerConcurrency
}, {
    [int]$_.outcome.runNumber
})) {
    $batchId = [string]$set.outcome.batchId
    $prometheusResults = $set.prometheus.results
    $aiCount = Get-PrometheusValue `
        -Series $prometheusResults.aiRequestCountByOutcome `
        -Outcome success
    $aiTotalSeconds = Get-PrometheusValue `
        -Series $prometheusResults.aiRequestTotalSecondsByOutcome `
        -Outcome success
    $completionMs = [double]$set.outcome.totalCompletionMs
    $sentimentKey = "$batchId|sentiment"
    $adviceKey = "$batchId|advice"
    $runs += [ordered]@{
        consumerConcurrency = [int]$set.outcome.consumerConcurrency
        runNumber = [int]$set.outcome.runNumber
        batchId = [long]$set.outcome.batchId
        startedAt = [string]$set.k6.metadata.startedAt
        finishedAt = [string]$set.k6.metadata.finishedAt
        createLatencyMs = [double]$set.outcome.createLatencyMs
        completionTimeMs = $completionMs
        throughputTasksPerSecond = 200 / ($completionMs / 1000)
        httpErrorRate = [double]$set.k6.metrics.http_req_failed.values.rate
        http5xxCount = Get-PrometheusValue -Series $prometheusResults.http5xxCount
        successCount = [long]$databaseByBatch[$batchId].successCount
        terminalFailureCount = [long]$databaseByBatch[$batchId].terminalFailureCount
        taskRetryCount = [long]$databaseByBatch[$batchId].taskRetryCount
        maxTaskRetryCount = [long]$databaseByBatch[$batchId].maxTaskRetryCount
        outboxRetryCount = [long]$databaseByBatch[$batchId].outboxRetryCount
        outboxFailureCount = [long]$databaseByBatch[$batchId].outboxFailureCount
        ai = [ordered]@{
            requestCount = $aiCount
            requestRatePerSecond = $aiCount / ($completionMs / 1000)
            averageMs = if ($aiCount -eq 0) { $null } else {
                1000 * $aiTotalSeconds / $aiCount
            }
            p95Ms = 1000 * (Get-PrometheusValue `
                -Series $prometheusResults.aiP95SecondsByOutcome `
                -Outcome success)
            p99Ms = 1000 * (Get-PrometheusValue `
                -Series $prometheusResults.aiP99SecondsByOutcome `
                -Outcome success)
            retryableFailureCount = Get-PrometheusValue `
                -Series $prometheusResults.aiRequestCountByOutcome `
                -Outcome retryable_failure
            terminalFailureCount = Get-PrometheusValue `
                -Series $prometheusResults.aiRequestCountByOutcome `
                -Outcome terminal_failure
            sentimentPhase = if ($phaseDurations.ContainsKey($sentimentKey)) {
                [ordered]@{
                    statisticsMs = Get-Statistics `
                        -Values ([double[]]$phaseDurations[$sentimentKey])
                    sources = $phaseSources[$sentimentKey]
                }
            } else { $null }
            advicePhase = if ($phaseDurations.ContainsKey($adviceKey)) {
                [ordered]@{
                    statisticsMs = Get-Statistics `
                        -Values ([double[]]$phaseDurations[$adviceKey])
                    sources = $phaseSources[$adviceKey]
                }
            } else { $null }
        }
        resources = [ordered]@{
            jvmHeapPeakBytes = Get-PrometheusValue `
                -Series $prometheusResults.jvmHeapUsedMaxBytes
            jvmGcPauseSeconds = Get-PrometheusValue `
                -Series $prometheusResults.jvmGcPauseIncreaseSeconds
            jvmLiveThreadsPeak = Get-PrometheusValue `
                -Series $prometheusResults.jvmThreadsLiveMax
            hikariActivePeak = Get-PrometheusValue `
                -Series $prometheusResults.hikariActiveMax
            hikariConfiguredMax = Get-PrometheusValue `
                -Series $prometheusResults.hikariConfiguredMax
        }
        database = $databaseByBatch[$batchId]
        artifacts = [ordered]@{
            k6 = Split-Path -Leaf $set.k6Path
            prometheus = Split-Path -Leaf $set.prometheusPath
            outcome = Split-Path -Leaf $set.outcomePath
        }
    }
}

$aggregates = @()
foreach ($concurrency in $Concurrencies) {
    $items = @($runs | Where-Object {
        $_.consumerConcurrency -eq $concurrency
    })
    $aggregates += [ordered]@{
        consumerConcurrency = $concurrency
        runCount = $items.Count
        completionTimeMs = Get-Statistics `
            -Values ([double[]]@($items | ForEach-Object { $_.completionTimeMs }))
        throughputTasksPerSecond = Get-Statistics `
            -Values ([double[]]@($items | ForEach-Object { $_.throughputTasksPerSecond }))
        createLatencyMs = Get-Statistics `
            -Values ([double[]]@($items | ForEach-Object { $_.createLatencyMs }))
        aiAverageMs = Get-Statistics `
            -Values ([double[]]@($items | ForEach-Object { $_.ai.averageMs }))
        aiP95Ms = Get-Statistics `
            -Values ([double[]]@($items | ForEach-Object { $_.ai.p95Ms }))
        aiP99Ms = Get-Statistics `
            -Values ([double[]]@($items | ForEach-Object { $_.ai.p99Ms }))
        sentimentPhaseAverageMs = Get-Statistics `
            -Values ([double[]]@($items | ForEach-Object {
                $_.ai.sentimentPhase.statisticsMs.average
            }))
        sentimentPhaseP95Ms = Get-Statistics `
            -Values ([double[]]@($items | ForEach-Object {
                $_.ai.sentimentPhase.statisticsMs.p95
            }))
        advicePhaseAverageMs = Get-Statistics `
            -Values ([double[]]@($items | ForEach-Object {
                $_.ai.advicePhase.statisticsMs.average
            }))
        advicePhaseP95Ms = Get-Statistics `
            -Values ([double[]]@($items | ForEach-Object {
                $_.ai.advicePhase.statisticsMs.p95
            }))
        adviceShareOfAiAverage = ($items | ForEach-Object {
            $_.ai.advicePhase.statisticsMs.average / $_.ai.averageMs
        } | Measure-Object -Average).Average
        taskRetryCount = [long]($items |
            ForEach-Object { $_.taskRetryCount } |
            Measure-Object -Sum).Sum
        outboxRetryCount = [long]($items |
            ForEach-Object { $_.outboxRetryCount } |
            Measure-Object -Sum).Sum
        terminalFailureCount = [long]($items |
            ForEach-Object { $_.terminalFailureCount } |
            Measure-Object -Sum).Sum
        http5xxCount = [double]($items |
            ForEach-Object { $_.http5xxCount } |
            Measure-Object -Sum).Sum
        aiRetryableFailureCount = [double]($items |
            ForEach-Object { $_.ai.retryableFailureCount } |
            Measure-Object -Sum).Sum
        aiTerminalFailureCount = [double]($items |
            ForEach-Object { $_.ai.terminalFailureCount } |
            Measure-Object -Sum).Sum
        jvmHeapPeakBytes = [double]($items |
            ForEach-Object { $_.resources.jvmHeapPeakBytes } |
            Measure-Object -Maximum).Maximum
        jvmGcPauseSeconds = [double]($items |
            ForEach-Object { $_.resources.jvmGcPauseSeconds } |
            Measure-Object -Sum).Sum
        hikariActivePeak = [double]($items |
            ForEach-Object { $_.resources.hikariActivePeak } |
            Measure-Object -Maximum).Maximum
    }
}

$baseline = $aggregates | Where-Object { $_.consumerConcurrency -eq 1 }
if ($null -eq $baseline) {
    throw 'Concurrency 1 is required as the scaling baseline.'
}
$scaling = @()
foreach ($aggregate in $aggregates) {
    $scaling += [ordered]@{
        consumerConcurrency = $aggregate.consumerConcurrency
        completionSpeedupVs1 = $baseline.completionTimeMs.average /
            $aggregate.completionTimeMs.average
        completionReductionPercentVs1 = 100 * (
            1 - $aggregate.completionTimeMs.average /
            $baseline.completionTimeMs.average
        )
        throughputImprovementPercentVs1 = 100 * (
            $aggregate.throughputTasksPerSecond.average /
            $baseline.throughputTasksPerSecond.average - 1
        )
        aiP95DegradationPercentVs1 = 100 * (
            $aggregate.aiP95Ms.average / $baseline.aiP95Ms.average - 1
        )
        taskRetryIncreaseVs1 = $aggregate.taskRetryCount -
            $baseline.taskRetryCount
        terminalFailureIncreaseVs1 = $aggregate.terminalFailureCount -
            $baseline.terminalFailureCount
    }
}

$firstSet = $artifactSets | Select-Object -First 1
$aiHealth = $firstSet.prometheus.aiBackendState
$phaseOnePath = Join-Path $ResultsPath '2026-08-09-phase-1-baseline.json'
$phaseOneSetup = if (Test-Path -LiteralPath $phaseOnePath) {
    (Get-Content -Raw -LiteralPath $phaseOnePath | ConvertFrom-Json).setup
} else {
    $null
}
$aiContainerImageId = (& docker inspect courseinsight-ai-service `
    --format '{{.Image}}').Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to inspect the AI container image ID.'
}
$result = [ordered]@{
    schemaVersion = 1
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    experimentId = $ExperimentId
    gitCommit = [string]$firstSet.k6.metadata.gitCommit
    fixture = 'performance/k6/data/batch-200.csv'
    repetitionsPerConcurrency = $ExpectedRunsPerConcurrency
    setup = [ordered]@{
        host = $phaseOneSetup.host
        docker = $phaseOneSetup.docker
        java = $phaseOneSetup.java
        springBoot = $phaseOneSetup.springBoot
        k6Image = [string]$firstSet.k6.metadata.k6Image
        k6ImageDigest = [string]$firstSet.k6.metadata.k6ImageDigest
        aiContainerImageId = $aiContainerImageId
        consumerConcurrencies = $Concurrencies
        cooldownSecondsBetweenRuns = $CooldownSeconds
        prometheusScrapeIntervalSeconds = 15
    }
    percentileMethod = 'linear interpolation over sorted samples'
    standardDeviationMethod = 'sample standard deviation (n-1)'
    aiBackendState = $aiHealth
    aiObservation = [ordered]@{
        source = 'FastAPI structured phase timing logs joined to MySQL task IDs'
        logSince = $logSince.ToString('o')
        logUntil = $logUntil.ToString('o')
        contentLogged = $false
    }
    runs = $runs
    aggregates = $aggregates
    scalingVsConcurrency1 = $scaling
}

$outputStem = "$(Get-Date -Format 'yyyy-MM-dd')-phase-2-capacity-$ExperimentId"
$jsonPath = Join-Path $ResultsPath "$outputStem.json"
$markdownPath = Join-Path $ResultsPath "$outputStem.md"
Write-Utf8NoBom -LiteralPath $jsonPath -Content ($result | ConvertTo-Json -Depth 30)

$markdown = [Collections.Generic.List[string]]::new()
$markdown.Add('# CourseInsight Phase 2 Capacity Experiment')
$markdown.Add('')
$markdown.Add("- Experiment ID: $ExperimentId")
$markdown.Add("- Git HEAD: $($result.gitCommit)")
$markdown.Add("- Repetitions: $ExpectedRunsPerConcurrency per concurrency")
$markdown.Add("- AI backend: $($aiHealth.activeBackend); BERT ready = $($aiHealth.bertReady); LLM configured = $($aiHealth.llmConfigured)")
$markdown.Add('- AI phase timing: structured FastAPI logs; no review text, prompt, or credential is logged')
$markdown.Add('')
$markdown.Add('## Summary')
$markdown.Add('')
$markdown.Add('| Metric | 1 | 2 | 4 |')
$markdown.Add('| --- | ---: | ---: | ---: |')
function Add-AggregateRow {
    param([string]$Label, [scriptblock]$Value)
    $cells = @($aggregates | ForEach-Object { & $Value $_ })
    $markdown.Add("| $Label | $($cells -join ' | ') |")
}
Add-AggregateRow 'Batch completion mean (s)' {
    param($item) Format-Number ($item.completionTimeMs.average / 1000)
}
Add-AggregateRow 'Batch completion p95/min/max (s)' {
    param($item)
    "$(Format-Number ($item.completionTimeMs.p95 / 1000))/$(Format-Number ($item.completionTimeMs.min / 1000))/$(Format-Number ($item.completionTimeMs.max / 1000))"
}
Add-AggregateRow 'Completion CV' {
    param($item) Format-Number $item.completionTimeMs.coefficientOfVariation '0.0000'
}
Add-AggregateRow 'Throughput mean (tasks/s)' {
    param($item) Format-Number $item.throughputTasksPerSecond.average '0.0000'
}
Add-AggregateRow 'AI avg mean (ms)' {
    param($item) Format-Number $item.aiAverageMs.average
}
Add-AggregateRow 'AI p95 mean (ms)' {
    param($item) Format-Number $item.aiP95Ms.average
}
Add-AggregateRow 'AI p99 mean (ms)' {
    param($item) Format-Number $item.aiP99Ms.average
}
Add-AggregateRow 'Task retry' {
    param($item) [string]$item.taskRetryCount
}
Add-AggregateRow 'Terminal failure' {
    param($item) [string]$item.terminalFailureCount
}
Add-AggregateRow 'HTTP 5xx' {
    param($item) Format-Number $item.http5xxCount '0'
}
Add-AggregateRow 'JVM heap peak (MiB)' {
    param($item) Format-Number ($item.jvmHeapPeakBytes / 1MB)
}
Add-AggregateRow 'Hikari active/max' {
    param($item) "$(Format-Number $item.hikariActivePeak '0')/$(Format-Number (($runs | Select-Object -First 1).resources.hikariConfiguredMax) '0')"
}
$markdown.Add('')
$markdown.Add('## Scaling vs consumer=1')
$markdown.Add('')
$markdown.Add('| concurrency | completion speedup | completion reduction | throughput improvement | AI p95 degradation | retry increase | failure increase |')
$markdown.Add('| ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($item in $scaling) {
    $markdown.Add(
        "| $($item.consumerConcurrency) | $(Format-Number $item.completionSpeedupVs1 '0.000')x | " +
        "$(Format-Number $item.completionReductionPercentVs1 '0.00')% | " +
        "$(Format-Number $item.throughputImprovementPercentVs1 '0.00')% | " +
        "$(Format-Number $item.aiP95DegradationPercentVs1 '0.00')% | " +
        "$($item.taskRetryIncreaseVs1) | $($item.terminalFailureIncreaseVs1) |"
    )
}
$markdown.Add('')
$markdown.Add('## Individual runs')
$markdown.Add('')
$markdown.Add('| c | run | batch | create (ms) | completion (s) | AI avg/p95/p99 (ms) | success/failure | task/outbox retry | heap peak (MiB) | Hikari | HTTP 5xx |')
$markdown.Add('| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($run in $runs) {
    $markdown.Add(
        "| $($run.consumerConcurrency) | $($run.runNumber) | $($run.batchId) | " +
        "$(Format-Number $run.createLatencyMs) | $(Format-Number ($run.completionTimeMs / 1000)) | " +
        "$(Format-Number $run.ai.averageMs)/$(Format-Number $run.ai.p95Ms)/$(Format-Number $run.ai.p99Ms) | " +
        "$($run.successCount)/$($run.terminalFailureCount) | " +
        "$($run.taskRetryCount)/$($run.outboxRetryCount) | " +
        "$(Format-Number ($run.resources.jvmHeapPeakBytes / 1MB)) | " +
        "$(Format-Number $run.resources.hikariActivePeak '0')/$(Format-Number $run.resources.hikariConfiguredMax '0') | " +
        "$(Format-Number $run.http5xxCount '0') |"
    )
}
$markdown.Add('')
$markdown.Add('## Conclusion')
$markdown.Add('')
$markdown.Add('Complete this section from the measured data: recommendation, saturation, capacity limit, and next experiment.')
Write-Utf8NoBom -LiteralPath $markdownPath -Content ($markdown -join "`n")

Write-Host "Capacity JSON: $jsonPath"
Write-Host "Capacity Markdown: $markdownPath"
