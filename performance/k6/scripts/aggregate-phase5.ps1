[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{1,40}$')]
    [string]$ExperimentId,
    [ValidateRange(1, 10)]
    [int]$ExpectedRunsPerGroup = 3,
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
    param([string]$LiteralPath, [string]$Content)
    [IO.File]::WriteAllText(
        $LiteralPath,
        $Content.Replace("`r`n", "`n").TrimEnd() + "`n",
        (New-Object Text.UTF8Encoding($false))
    )
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
    $average = [double]($Values | Measure-Object -Average).Average
    $variance = if ($Values.Count -gt 1) {
        [double](($Values | ForEach-Object { [Math]::Pow($_ - $average, 2) } |
            Measure-Object -Sum).Sum / ($Values.Count - 1))
    } else { 0.0 }
    [ordered]@{
        count = $Values.Count
        average = $average
        standardDeviationSample = [Math]::Sqrt($variance)
        coefficientOfVariation = if ($average -eq 0) { 0 } else { [Math]::Sqrt($variance) / $average }
        min = [double]($Values | Measure-Object -Minimum).Minimum
        p50 = Get-Percentile $Values 0.50
        p95 = Get-Percentile $Values 0.95
        p99 = Get-Percentile $Values 0.99
        max = [double]($Values | Measure-Object -Maximum).Maximum
    }
}

function Get-PrometheusValue {
    param([object[]]$Series, [string]$Outcome = '')
    $entries = @($Series)
    if (-not [string]::IsNullOrWhiteSpace($Outcome)) {
        $entries = @($entries | Where-Object { $_.metric.outcome -eq $Outcome })
    }
    if ($entries.Count -eq 0 -or $null -eq $entries[0].value) { return 0.0 }
    $raw = [string]$entries[0].value[1]
    if ($raw -in @('NaN', '+Inf', '-Inf')) { return $null }
    [double]::Parse($raw, $culture)
}

function Get-NativeValues {
    param([object]$Series)
    if ($null -eq $Series -or $null -eq $Series.values) { return [double[]]@() }
    [double[]]@($Series.values | ForEach-Object { [double]::Parse([string]$_[1], $culture) })
}

function Get-NativeActiveAverage {
    param([object]$Series)
    $values = @(Get-NativeValues $Series | Where-Object { $_ -gt 0 })
    if ($values.Count -eq 0) { return $null }
    [double]($values | Measure-Object -Average).Average
}

function Get-NativePeak {
    param([object]$Series)
    $values = @(Get-NativeValues $Series)
    if ($values.Count -eq 0) { return $null }
    [double]($values | Measure-Object -Maximum).Maximum
}

function Get-NativePositivePercent {
    param([object]$Series)
    $values = @(Get-NativeValues $Series)
    if ($values.Count -eq 0) { return $null }
    100.0 * @($values | Where-Object { $_ -gt 0 }).Count / $values.Count
}

function Get-DrainObservation {
    param([object[]]$Samples, [string]$Property)
    $available = @($Samples | Where-Object { $null -ne $_.$Property })
    if ($available.Count -eq 0) { return $null }
    $peak = $available | Sort-Object $Property -Descending | Select-Object -First 1
    $positive = @($available | Where-Object { [double]$_.$Property -gt 0 })
    $lastPositive = $positive | Select-Object -Last 1
    $durationSeconds = if ($null -ne $lastPositive -and
        $lastPositive.timestampEpochMs -gt $peak.timestampEpochMs) {
        ($lastPositive.timestampEpochMs - $peak.timestampEpochMs) / 1000.0
    } else { $null }
    [ordered]@{
        peak = [double]$peak.$Property
        peakTimestampEpochMs = [long]$peak.timestampEpochMs
        lastPositiveTimestampEpochMs = if ($null -ne $lastPositive) { [long]$lastPositive.timestampEpochMs } else { $null }
        drainDurationSeconds = $durationSeconds
        drainRatePerSecond = if ($null -ne $durationSeconds -and $durationSeconds -gt 0) {
            [double]$peak.$Property / $durationSeconds
        } else { $null }
    }
}

function Format-Number {
    param([object]$Value, [string]$Format = '0.00')
    if ($null -eq $Value) { return 'n/a' }
    ([double]$Value).ToString($Format, $culture)
}

$outcomeFiles = @(Get-ChildItem -LiteralPath $ResultsPath -File `
    -Filter "*-phase5-$ExperimentId-*-outcome.json" |
    Where-Object { $_.Name -notmatch '-pilot-' } | Sort-Object Name)
if ($outcomeFiles.Count -ne (3 * $ExpectedRunsPerGroup)) {
    throw "Expected $(3 * $ExpectedRunsPerGroup) formal Phase 5 runs, found $($outcomeFiles.Count)."
}

$sets = @()
foreach ($file in $outcomeFiles) {
    $outcome = Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json
    $stem = $file.Name.Substring(0, $file.Name.Length - '-outcome.json'.Length)
    $paths = [ordered]@{
        k6 = Join-Path $ResultsPath "$stem-k6.json"
        prometheus = Join-Path $ResultsPath "$stem-prometheus.json"
        backlog = Join-Path $ResultsPath "$stem-backlog.json"
        rocketMqNative = Join-Path $ResultsPath "$stem-rocketmq-native.json"
    }
    foreach ($path in $paths.Values) {
        if (-not (Test-Path -LiteralPath $path)) { throw "Missing artifact: $path" }
    }
    $sets += [pscustomobject]@{
        outcome = $outcome
        k6 = Get-Content -Raw -LiteralPath $paths.k6 | ConvertFrom-Json
        prometheus = Get-Content -Raw -LiteralPath $paths.prometheus | ConvertFrom-Json
        backlog = Get-Content -Raw -LiteralPath $paths.backlog | ConvertFrom-Json
        native = Get-Content -Raw -LiteralPath $paths.rocketMqNative | ConvertFrom-Json
        files = [ordered]@{
            outcome = $file.Name
            k6 = Split-Path -Leaf $paths.k6
            prometheus = Split-Path -Leaf $paths.prometheus
            backlog = Split-Path -Leaf $paths.backlog
            rocketMqNative = Split-Path -Leaf $paths.rocketMqNative
        }
    }
}

$runs = @()
foreach ($set in $sets) {
    $outcome = $set.outcome
    $prom = $set.prometheus.results
    $native = $set.native.results
    $aiSuccess = Get-PrometheusValue $prom.aiRequestCountByOutcome success
    $aiTotalSeconds = Get-PrometheusValue $prom.aiRequestTotalSecondsByOutcome success
    $publishSuccess = Get-PrometheusValue $prom.outboxPublishCountByOutcome success
    $publishFailure = Get-PrometheusValue $prom.outboxPublishCountByOutcome failure
    $publishAttempts = $publishSuccess + $publishFailure
    $outboxDrain = Get-DrainObservation @($set.backlog.samples) 'outboxPending'
    $proxyDrain = Get-DrainObservation @($set.backlog.samples) 'proxyBacklog'
    $runs += [ordered]@{
        configurationLabel = [string]$outcome.configurationLabel
        scenario = [string]$outcome.scenario
        runNumber = [int]$outcome.runNumber
        consumerConcurrency = [int]$outcome.consumerConcurrency
        outboxBatchSize = [int]$outcome.outboxBatchSize
        outboxPublishIntervalMs = [int]$outcome.outboxPublishIntervalMs
        aiStubDelayMs = [int]$outcome.aiStubDelayMs
        taskCount = [int]$outcome.taskCount
        productionDurationMs = [double]$outcome.productionDurationMs
        createLatencyMs = Get-Statistics ([double[]]@($outcome.createLatencyMs))
        completionTimeMs = [double]$outcome.totalCompletionMs
        drainTimeMs = [double]$outcome.drainTimeMs
        throughputTasksPerSecond = [double]$outcome.throughputTasksPerSecond
        steadyCompletionThroughputTasksPerSecond = [double]$outcome.steadyCompletionThroughputTasksPerSecond
        successCount = [long]$outcome.successCount
        terminalFailureCount = [long]$outcome.terminalFailureCount
        taskRetryCount = [long]$outcome.taskRetryCount
        outboxRetryCount = [long]$outcome.outboxRetryCount
        outboxFailureCount = [long]$outcome.outboxFailureCount
        peakProxyBacklog = [int]$outcome.peakProxyBacklog
        peakOutboxPending = [int]$outcome.peakOutboxPending
        outboxPending = $outboxDrain
        proxyBacklog = $proxyDrain
        outboxPublish = [ordered]@{
            successCount = $publishSuccess
            failedAttemptCount = $publishFailure
            successRate = if ($publishAttempts -gt 0) { $publishSuccess / $publishAttempts } else { $null }
        }
        ai = [ordered]@{
            requestCount = $aiSuccess
            requestRatePerSecond = $aiSuccess / ([double]$outcome.totalCompletionMs / 1000)
            averageMs = if ($aiSuccess -gt 0) { 1000 * $aiTotalSeconds / $aiSuccess } else { $null }
            p50Ms = 1000 * (Get-PrometheusValue $prom.aiP50SecondsByOutcome success)
            p95Ms = 1000 * (Get-PrometheusValue $prom.aiP95SecondsByOutcome success)
            p99Ms = 1000 * (Get-PrometheusValue $prom.aiP99SecondsByOutcome success)
        }
        rocketMqNative = [ordered]@{
            readyPeak = Get-NativePeak $native.readyMessages
            inflightPeak = Get-NativePeak $native.inflightMessages
            lagPeak = Get-NativePeak $native.lagMessages
            readyPositiveSamplePercent = Get-NativePositivePercent $native.readyMessages
            inflightPositiveSamplePercent = Get-NativePositivePercent $native.inflightMessages
            lagPositiveSamplePercent = Get-NativePositivePercent $native.lagMessages
            messagesInActiveAveragePerSecond = Get-NativeActiveAverage $native.messagesInPerSecond
            messagesInPeakPerSecond = Get-NativePeak $native.messagesInPerSecond
            messagesOutActiveAveragePerSecond = Get-NativeActiveAverage $native.messagesOutPerSecond
            messagesOutPeakPerSecond = Get-NativePeak $native.messagesOutPerSecond
            queueingLatencyAvailable = (@(Get-NativeValues $native.queueingLatencyMs).Count -gt 0)
            queueingLatencyPeakMs = Get-NativePeak $native.queueingLatencyMs
            lagLatencyAvailable = (@(Get-NativeValues $native.lagLatencyMs).Count -gt 0)
            lagLatencyPeakMs = Get-NativePeak $native.lagLatencyMs
            retryReadyAvailable = (@(Get-NativeValues $native.retryReadyMessages).Count -gt 0)
            retryReadyPeak = Get-NativePeak $native.retryReadyMessages
            dlqAvailable = (@(Get-NativeValues $native.dlqMessagesTotal).Count -gt 0)
            dlqPeak = Get-NativePeak $native.dlqMessagesTotal
        }
        resources = [ordered]@{
            processCpuUsagePeak = Get-PrometheusValue $prom.processCpuUsageMax
            systemCpuUsagePeak = Get-PrometheusValue $prom.systemCpuUsageMax
            jvmHeapPeakBytes = Get-PrometheusValue $prom.jvmHeapUsedMaxBytes
            jvmHeapMaxBytes = Get-PrometheusValue $prom.jvmHeapMaxBytes
            jvmGcPauseSeconds = Get-PrometheusValue $prom.jvmGcPauseIncreaseSeconds
            jvmThreadsLivePeak = Get-PrometheusValue $prom.jvmThreadsLiveMax
            hikariActivePeak = Get-PrometheusValue $prom.hikariActiveMax
            hikariConfiguredMax = Get-PrometheusValue $prom.hikariConfiguredMax
            http5xxCount = Get-PrometheusValue $prom.http5xxCount
        }
        artifacts = $set.files
    }
}

$expectedGroups = @(
    [ordered]@{ configurationLabel = 'current-b20'; consumerConcurrency = 2 },
    [ordered]@{ configurationLabel = 'current-b20'; consumerConcurrency = 4 },
    [ordered]@{ configurationLabel = 'candidate-b100'; consumerConcurrency = 2 }
)
$aggregates = @()
foreach ($expected in $expectedGroups) {
    $items = @($runs | Where-Object {
        $_.configurationLabel -eq $expected.configurationLabel -and
        $_.consumerConcurrency -eq $expected.consumerConcurrency
    })
    if ($items.Count -ne $ExpectedRunsPerGroup) {
        throw "Expected $ExpectedRunsPerGroup runs for $($expected.configurationLabel)/c$($expected.consumerConcurrency), found $($items.Count)."
    }
    $publishSuccess = [double]($items | ForEach-Object { $_.outboxPublish.successCount } | Measure-Object -Sum).Sum
    $publishFailure = [double]($items | ForEach-Object { $_.outboxPublish.failedAttemptCount } | Measure-Object -Sum).Sum
    $aggregates += [ordered]@{
        configurationLabel = $expected.configurationLabel
        consumerConcurrency = $expected.consumerConcurrency
        outboxBatchSize = [int]$items[0].outboxBatchSize
        outboxPublishIntervalMs = [int]$items[0].outboxPublishIntervalMs
        runCount = $items.Count
        completionTimeMs = Get-Statistics ([double[]]@($items | ForEach-Object { $_.completionTimeMs }))
        throughputTasksPerSecond = Get-Statistics ([double[]]@($items | ForEach-Object { $_.throughputTasksPerSecond }))
        steadyCompletionThroughputTasksPerSecond = Get-Statistics ([double[]]@($items | ForEach-Object { $_.steadyCompletionThroughputTasksPerSecond }))
        outboxPendingDrainRatePerSecond = Get-Statistics ([double[]]@($items | ForEach-Object { $_.outboxPending.drainRatePerSecond }))
        outboxPendingDrainDurationSeconds = Get-Statistics ([double[]]@($items | ForEach-Object { $_.outboxPending.drainDurationSeconds }))
        peakOutboxPending = Get-Statistics ([double[]]@($items | ForEach-Object { $_.peakOutboxPending }))
        peakProxyBacklog = Get-Statistics ([double[]]@($items | ForEach-Object { $_.peakProxyBacklog }))
        rocketMqReadyPeak = Get-Statistics ([double[]]@($items | ForEach-Object { $_.rocketMqNative.readyPeak }))
        rocketMqInflightPeak = Get-Statistics ([double[]]@($items | ForEach-Object { $_.rocketMqNative.inflightPeak }))
        rocketMqLagPeak = Get-Statistics ([double[]]@($items | ForEach-Object { $_.rocketMqNative.lagPeak }))
        rocketMqMessagesInActiveAveragePerSecond = Get-Statistics ([double[]]@($items | ForEach-Object { $_.rocketMqNative.messagesInActiveAveragePerSecond }))
        rocketMqMessagesOutActiveAveragePerSecond = Get-Statistics ([double[]]@($items | ForEach-Object { $_.rocketMqNative.messagesOutActiveAveragePerSecond }))
        aiAverageMs = Get-Statistics ([double[]]@($items | ForEach-Object { $_.ai.averageMs }))
        aiP95Ms = Get-Statistics ([double[]]@($items | ForEach-Object { $_.ai.p95Ms }))
        aiP99Ms = Get-Statistics ([double[]]@($items | ForEach-Object { $_.ai.p99Ms }))
        taskRetryCount = [long]($items | ForEach-Object { $_.taskRetryCount } | Measure-Object -Sum).Sum
        terminalFailureCount = [long]($items | ForEach-Object { $_.terminalFailureCount } | Measure-Object -Sum).Sum
        outboxRetryCount = [long]($items | ForEach-Object { $_.outboxRetryCount } | Measure-Object -Sum).Sum
        outboxTerminalFailureCount = [long]($items | ForEach-Object { $_.outboxFailureCount } | Measure-Object -Sum).Sum
        outboxPublishSuccessCount = $publishSuccess
        outboxPublishFailedAttemptCount = $publishFailure
        outboxPublishSuccessRate = if (($publishSuccess + $publishFailure) -gt 0) {
            $publishSuccess / ($publishSuccess + $publishFailure)
        } else { $null }
        http5xxCount = [double]($items | ForEach-Object { $_.resources.http5xxCount } | Measure-Object -Sum).Sum
        processCpuUsagePeak = [double]($items | ForEach-Object { $_.resources.processCpuUsagePeak } | Measure-Object -Maximum).Maximum
        jvmHeapPeakBytes = [double]($items | ForEach-Object { $_.resources.jvmHeapPeakBytes } | Measure-Object -Maximum).Maximum
        jvmGcPauseSeconds = [double]($items | ForEach-Object { $_.resources.jvmGcPauseSeconds } | Measure-Object -Sum).Sum
        hikariActivePeak = [double]($items | ForEach-Object { $_.resources.hikariActivePeak } | Measure-Object -Maximum).Maximum
        hikariConfiguredMax = [double]($items | ForEach-Object { $_.resources.hikariConfiguredMax } | Measure-Object -Maximum).Maximum
        queueingLatencyRunCount = @($items | Where-Object { $_.rocketMqNative.queueingLatencyAvailable }).Count
        lagLatencyRunCount = @($items | Where-Object { $_.rocketMqNative.lagLatencyAvailable }).Count
    }
}

$currentC2 = $aggregates | Where-Object { $_.configurationLabel -eq 'current-b20' -and $_.consumerConcurrency -eq 2 }
$currentC4 = $aggregates | Where-Object { $_.configurationLabel -eq 'current-b20' -and $_.consumerConcurrency -eq 4 }
$candidateC2 = $aggregates | Where-Object { $_.configurationLabel -eq 'candidate-b100' -and $_.consumerConcurrency -eq 2 }
$ab = [ordered]@{
    current = 'OUTBOX_BATCH_SIZE=20, publish interval=1000ms, consumer=2'
    candidate = 'OUTBOX_BATCH_SIZE=100, publish interval=1000ms, consumer=2'
    completionTimeChangePercent = 100 * ($candidateC2.completionTimeMs.average / $currentC2.completionTimeMs.average - 1)
    throughputChangePercent = 100 * ($candidateC2.throughputTasksPerSecond.average / $currentC2.throughputTasksPerSecond.average - 1)
    outboxDrainRateChangePercent = 100 * ($candidateC2.outboxPendingDrainRatePerSecond.average / $currentC2.outboxPendingDrainRatePerSecond.average - 1)
    rocketMqInflightPeakChange = $candidateC2.rocketMqInflightPeak.average - $currentC2.rocketMqInflightPeak.average
    aiP95ChangePercent = 100 * ($candidateC2.aiP95Ms.average / $currentC2.aiP95Ms.average - 1)
    taskRetryIncrease = $candidateC2.taskRetryCount - $currentC2.taskRetryCount
    outboxRetryIncrease = $candidateC2.outboxRetryCount - $currentC2.outboxRetryCount
    terminalFailureIncrease = $candidateC2.terminalFailureCount - $currentC2.terminalFailureCount
}
$consumer = [ordered]@{
    twoToFourCompletionChangePercent = 100 * ($currentC4.completionTimeMs.average / $currentC2.completionTimeMs.average - 1)
    twoToFourThroughputChangePercent = 100 * ($currentC4.throughputTasksPerSecond.average / $currentC2.throughputTasksPerSecond.average - 1)
    twoToFourOutboxDrainRateChangePercent = 100 * ($currentC4.outboxPendingDrainRatePerSecond.average / $currentC2.outboxPendingDrainRatePerSecond.average - 1)
}

$phaseOnePath = Join-Path $ResultsPath '2026-08-09-phase-1-baseline.json'
$phaseOneSetup = if (Test-Path -LiteralPath $phaseOnePath) {
    (Get-Content -Raw -LiteralPath $phaseOnePath | ConvertFrom-Json).setup
} else { $null }
$first = $sets | Select-Object -First 1
$result = [ordered]@{
    schemaVersion = 1
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    experimentId = $ExperimentId
    gitCommit = [string]$first.outcome.gitCommit
    fixture = 'performance/k6/data/batch-200.csv'
    workload = [ordered]@{ batches = 5; rowsPerBatch = 200; taskCount = 1000; repetitionsPerGroup = $ExpectedRunsPerGroup }
    aiStub = [ordered]@{ delayMs = 100; bertEnabled = $false; llmEnabled = $false; transport = 'real HTTP' }
    rocketMq = [ordered]@{
        version = '5.3.2'
        exporter = 'built-in PROM exporter'
        scrapeIntervalSeconds = 1
        topic = 'courseinsight-analysis'
        consumerGroup = 'courseinsight-analysis-consumer'
    }
    setup = [ordered]@{
        host = $phaseOneSetup.host
        docker = $phaseOneSetup.docker
        java = $phaseOneSetup.java
        springBoot = $phaseOneSetup.springBoot
        k6Image = [string]$first.k6.metadata.k6Image
        k6ImageDigest = [string]$first.k6.metadata.k6ImageDigest
    }
    definitions = [ordered]@{
        databaseProxy = 'analysis_task WAITING + PROCESSING sampled every 0.5s; not RocketMQ queue depth'
        outboxDrainRate = 'peak PENDING divided by elapsed time from peak sample to the final positive PENDING sample'
        rocketMqThroughput = 'mean of positive 5s-rate samples for broker messages_in/messages_out; broker delivery, not task completion'
        standardDeviation = 'sample standard deviation (n-1)'
        percentiles = 'linear interpolation over sorted samples'
    }
    outboxImplementation = [ordered]@{
        scheduler = 'Spring @Scheduled fixedDelay; the delay starts after the previous publishPending invocation completes'
        currentIntervalMs = 1000
        currentBatchSize = 20
        selection = 'status PENDING/FAILED/PUBLISHING, next_retry_at due, order by id, LIMIT batchSize'
        skipLocked = $false
        claim = 'conditional single-row update to PUBLISHING with recovery timeout'
        send = 'sequential rocketMQTemplate.syncSend per selected event'
        completion = 'conditional single-row update from PUBLISHING to SENT after SEND_OK'
        retry = 'on exception mark FAILED, increment retry_count, delay next attempt by 30s'
        transactionBoundary = 'publisher method has no @Transactional boundary; select, claim, send and mark operations are not one database transaction'
    }
    runs = $runs
    aggregates = $aggregates
    comparisons = [ordered]@{ currentConsumer2To4 = $consumer; batchSize20To100AtConsumer2 = $ab }
    conclusion = [ordered]@{
        currentBottleneck = 'Outbox publisher supply is the first limiter with batch size 20; ready stays empty and c4 receives no more work than c2.'
        candidateResult = 'Batch size 100 moves backlog from database Outbox into RocketMQ inflight/lag, but does not improve c2 end-to-end throughput.'
        recoveredRetryEvidence = 'candidate-b100 run 1 logged one syncSend timeout (RemotingTooMuchRequestException: sendDefaultImpl call timeout); the 30s Outbox retry recovered and all 1000 tasks succeeded.'
        productionDefaultRecommendation = 'Do not retain batch size 100 as the production default from this experiment.'
        queueingLatencyLimitation = 'The 5.3.2 exporter exposes metric descriptors, but no queueing_latency or lag_latency samples were emitted for this PushConsumer in any formal run.'
    }
    resumeSafe = [ordered]@{
        eligible = ($ExpectedRunsPerGroup -ge 3)
        groups = @('current-b20/c2', 'current-b20/c4', 'candidate-b100/c2')
        caveat = 'single-machine local environment with deterministic 100ms HTTP AI Stub; native broker rate is not task-completion rate'
    }
}

$outputStem = "$(Get-Date -Format 'yyyy-MM-dd')-phase-5-rocketmq-outbox-$ExperimentId"
$jsonPath = Join-Path $ResultsPath "$outputStem.json"
$markdownPath = Join-Path $ResultsPath "$outputStem.md"
Write-Utf8NoBom $jsonPath ($result | ConvertTo-Json -Depth 30)

$md = [Collections.Generic.List[string]]::new()
$md.Add('# CourseInsight Phase 5 - RocketMQ native backlog and Outbox capacity')
$md.Add('')
$md.Add("- Experiment: $ExperimentId")
$md.Add("- Git HEAD: $($result.gitCommit)")
$md.Add("- Host: $($phaseOneSetup.host.cpu); $([Math]::Round($phaseOneSetup.host.totalVisibleMemoryBytes/1GB,2)) GiB visible RAM; Docker $($phaseOneSetup.docker.cpus) CPUs / $([Math]::Round($phaseOneSetup.docker.memoryBytes/1GB,2)) GiB")
$md.Add('- Workload: five API uploads x 200 rows = 1000 tasks per run')
$md.Add('- AI backend: deterministic FastAPI Stub, real HTTP, 100 ms delay, no BERT/LLM')
$md.Add("- Repetitions: $ExpectedRunsPerGroup per group")
$md.Add('- RocketMQ: 5.3.2 built-in Prometheus exporter, 1 s scrape')
$md.Add('')
$md.Add('## Current Outbox publisher implementation')
$md.Add('')
$md.Add('- The scheduler uses fixed delay: the 1000 ms wait starts only after the previous `publishPending` call and all selected sends finish.')
$md.Add('- The current query selects due PENDING/FAILED/PUBLISHING rows in ID order with `LIMIT 20`. It does not use `SKIP LOCKED`.')
$md.Add('- Each row is conditionally claimed as PUBLISHING, synchronously sent with `rocketMQTemplate.syncSend`, then conditionally marked SENT. Sends are sequential within the scheduler invocation.')
$md.Add('- A send exception marks the row FAILED, increments `retry_count`, and schedules a retry after 30 seconds. The publisher method has no encompassing `@Transactional` boundary.')
$md.Add('- Therefore one cycle is 20 sequential claim/send/mark operations plus the following 1000 ms fixed delay. The repeated 15.2-15.4 PENDING events/s drain rate is consistent with this cadence.')
$md.Add('')
$md.Add('## Repeated results')
$md.Add('')
$md.Add('| Config | Consumer | Completion mean +/- SD (s) | Throughput (tasks/s) | Outbox drain (events/s) | MQ ready peak | MQ inflight/lag peak | Retry | Failure |')
$md.Add('| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($aggregate in $aggregates) {
    $md.Add("| $($aggregate.configurationLabel) | $($aggregate.consumerConcurrency) | $(Format-Number ($aggregate.completionTimeMs.average/1000) '0.000') +/- $(Format-Number ($aggregate.completionTimeMs.standardDeviationSample/1000) '0.000') | $(Format-Number $aggregate.throughputTasksPerSecond.average '0.000') | $(Format-Number $aggregate.outboxPendingDrainRatePerSecond.average '0.000') | $(Format-Number $aggregate.rocketMqReadyPeak.max '0') | $(Format-Number $aggregate.rocketMqInflightPeak.average '0.0')/$(Format-Number $aggregate.rocketMqLagPeak.average '0.0') | $($aggregate.taskRetryCount) task / $($aggregate.outboxRetryCount) Outbox | $($aggregate.terminalFailureCount) |")
}
$md.Add('')
$md.Add('## Native backlog versus database state')
$md.Add('')
$md.Add('- Current batch=20: Outbox PENDING reaches 1000 and drains at the same approximately 15 events/s rate as completion. Broker ready remains zero; inflight/lag only reaches 16-18. Most queued work is therefore still on the database publishing side.')
$md.Add('- Candidate batch=100: Outbox drain rate rises, while broker inflight/lag rises above 600 and ready remains approximately zero. The PushConsumer has pulled the published messages into inflight state, so ready alone would under-report backlog.')
$md.Add('- WAITING + PROCESSING is retained only as an end-to-end database proxy. It is not reported as broker queue depth.')
$md.Add('- Queueing-latency and lag-latency time series were unavailable in all formal runs even though the exporter exposed their metric descriptors.')
$md.Add('')
$md.Add('## Single-variable A/B: Outbox batch 20 -> 100 at consumer=2')
$md.Add('')
$md.Add("- Completion time change: $(Format-Number $ab.completionTimeChangePercent)%")
$md.Add("- End-to-end throughput change: $(Format-Number $ab.throughputChangePercent)%")
$md.Add("- Outbox PENDING drain-rate change: +$(Format-Number $ab.outboxDrainRateChangePercent)%")
$md.Add("- Mean MQ inflight peak change: +$(Format-Number $ab.rocketMqInflightPeakChange '0.0') messages")
$md.Add("- AI p95 change: $(Format-Number $ab.aiP95ChangePercent)%")
$md.Add("- Retry/failure delta: $($ab.taskRetryIncrease) task retry, $($ab.outboxRetryIncrease) Outbox retry, $($ab.terminalFailureIncrease) terminal failure")
$md.Add('- The single candidate Outbox retry was a `syncSend` timeout (`RemotingTooMuchRequestException: sendDefaultImpl call timeout`) in run 1. The configured retry recovered; all 1000 tasks in that run succeeded.')
$md.Add('')
$md.Add('## Why consumer=4 did not beat consumer=2')
$md.Add('')
$md.Add("- With batch=20, c2 -> c4 changes completion by $(Format-Number $consumer.twoToFourCompletionChangePercent)% and throughput by $(Format-Number $consumer.twoToFourThroughputChangePercent)%.")
$md.Add('- The publisher supplies only approximately 15 events/s, broker ready is continuously zero, and native lag stays small. Additional consumer threads are starved rather than saturated.')
$md.Add('- Batch=100 proves that the publisher can be made faster, but the backlog then relocates to broker inflight at c2. This experiment does not isolate the next downstream limiter within consumer dispatch, AI HTTP, and result persistence.')
$md.Add('')
$md.Add('## Resource and reliability observations')
$md.Add('')
$md.Add('| Config | Consumer | AI avg/p95/p99 (ms) | CPU peak | Heap peak (MiB) | GC pause sum (s) | Hikari peak/max | HTTP 5xx | Outbox publish success |')
$md.Add('| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($aggregate in $aggregates) {
    $md.Add("| $($aggregate.configurationLabel) | $($aggregate.consumerConcurrency) | $(Format-Number $aggregate.aiAverageMs.average)/$(Format-Number $aggregate.aiP95Ms.average)/$(Format-Number $aggregate.aiP99Ms.average) | $(Format-Number (100*$aggregate.processCpuUsagePeak) '0.0')% | $(Format-Number ($aggregate.jvmHeapPeakBytes/1MB)) | $(Format-Number $aggregate.jvmGcPauseSeconds '0.000') | $(Format-Number $aggregate.hikariActivePeak '0')/$(Format-Number $aggregate.hikariConfiguredMax '0') | $(Format-Number $aggregate.http5xxCount '0') | $(Format-Number (100*$aggregate.outboxPublishSuccessRate) '0.000')% |")
}
$md.Add('')
$md.Add('## Recommendation')
$md.Add('')
$md.Add('- Do not promote OUTBOX_BATCH_SIZE=100 to the production default from this experiment. It materially accelerates publication but does not improve total completion or throughput at c2 and introduces one recovered Outbox retry across 3000 tasks.')
$md.Add('- The next discriminating experiment is batch=100 with consumer=2 versus 4, keeping everything else fixed. It tests whether the new broker inflight backlog can be drained by extra consumers.')
$md.Add('')
$md.Add('## Resume-safe numbers')
$md.Add('')
$md.Add('- Eligible: the three aggregate rows above, the batch 20 -> 100 A/B percentages, and zero terminal/task failures. Each is based on three 1000-task runs with the same machine, 100 ms deterministic HTTP Stub, and unchanged 1000 ms publisher interval.')
$md.Add('- Not eligible as a numeric claim: RocketMQ queueing latency/lag latency, because this client/version combination emitted no samples.')
Write-Utf8NoBom $markdownPath ($md -join "`n")

Write-Host "Phase 5 JSON: $jsonPath"
Write-Host "Phase 5 Markdown: $markdownPath"
