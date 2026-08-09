[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{1,40}$')]
    [string]$ExperimentId,
    [int[]]$Consumers = @(2, 4, 8),
    [ValidateRange(1, 10)]
    [int]$ExpectedRunsPerConsumer = 3,
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

function Get-SeriesPoints {
    param([object]$Series)
    if ($null -eq $Series -or $null -eq $Series.values) { return @() }
    @($Series.values | ForEach-Object {
        [pscustomobject]@{
            timestampEpochSeconds = [double]$_[0]
            value = [double]::Parse([string]$_[1], $culture)
        }
    })
}

function Get-SeriesPeak {
    param([object]$Series)
    $points = @(Get-SeriesPoints $Series)
    if ($points.Count -eq 0) { return $null }
    [double]($points | Measure-Object value -Maximum).Maximum
}

function Get-SeriesActiveAverage {
    param([object]$Series)
    $values = @(Get-SeriesPoints $Series | Where-Object { $_.value -gt 0 } |
        ForEach-Object { $_.value })
    if ($values.Count -eq 0) { return $null }
    [double]($values | Measure-Object -Average).Average
}

function Get-SeriesDrain {
    param([object]$Series)
    $points = @(Get-SeriesPoints $Series)
    if ($points.Count -eq 0) { return $null }
    $peakValue = [double]($points | Measure-Object value -Maximum).Maximum
    $peak = $points | Where-Object { $_.value -eq $peakValue } | Select-Object -First 1
    $lastPositive = $points | Where-Object { $_.value -gt 0 } | Select-Object -Last 1
    [ordered]@{
        peak = $peakValue
        peakTimestampEpochSeconds = [double]$peak.timestampEpochSeconds
        lastPositiveTimestampEpochSeconds = if ($null -ne $lastPositive) {
            [double]$lastPositive.timestampEpochSeconds
        } else { $null }
        peakToLastPositiveSeconds = if ($null -ne $lastPositive -and
            $lastPositive.timestampEpochSeconds -ge $peak.timestampEpochSeconds) {
            [double]($lastPositive.timestampEpochSeconds - $peak.timestampEpochSeconds)
        } else { $null }
    }
}

function Get-DatabaseDrain {
    param([object[]]$Samples, [string]$Property)
    $available = @($Samples | Where-Object { $null -ne $_.$Property })
    if ($available.Count -eq 0) { return $null }
    $peak = $available | Sort-Object $Property -Descending | Select-Object -First 1
    $lastPositive = $available | Where-Object { [double]$_.$Property -gt 0 } |
        Select-Object -Last 1
    $duration = if ($null -ne $lastPositive -and
        $lastPositive.timestampEpochMs -gt $peak.timestampEpochMs) {
        ($lastPositive.timestampEpochMs - $peak.timestampEpochMs) / 1000.0
    } else { $null }
    [ordered]@{
        peak = [double]$peak.$Property
        peakToLastPositiveSeconds = $duration
        ratePerSecond = if ($null -ne $duration -and $duration -gt 0) {
            [double]$peak.$Property / $duration
        } else { $null }
    }
}

function Format-Number {
    param([object]$Value, [string]$Format = '0.00')
    if ($null -eq $Value) { return 'n/a' }
    ([double]$Value).ToString($Format, $culture)
}

$outcomeFiles = @(Get-ChildItem -LiteralPath $ResultsPath -File `
    -Filter "*-phase6-$ExperimentId-*-outcome.json" | Sort-Object Name)
$expectedCount = $Consumers.Count * $ExpectedRunsPerConsumer
if ($outcomeFiles.Count -ne $expectedCount) {
    throw "Expected $expectedCount Phase 6 outcomes, found $($outcomeFiles.Count)."
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
        artifacts = [ordered]@{
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
    $aiTotal = Get-PrometheusValue $prom.aiRequestTotalSecondsByOutcome success
    $publishSuccess = Get-PrometheusValue $prom.outboxPublishCountByOutcome success
    $publishFailure = Get-PrometheusValue $prom.outboxPublishCountByOutcome failure
    $runs += [ordered]@{
        runNumber = [int]$outcome.runNumber
        consumerConcurrency = [int]$outcome.consumerConcurrency
        taskCount = [int]$outcome.taskCount
        productionDurationMs = [double]$outcome.productionDurationMs
        completionTimeMs = [double]$outcome.totalCompletionMs
        endToEndDrainTimeMs = [double]$outcome.drainTimeMs
        throughputTasksPerSecond = [double]$outcome.throughputTasksPerSecond
        resultPersistenceRateProxyPerSecond = [double]$outcome.steadyCompletionThroughputTasksPerSecond
        resultPersistenceRateDefinition = '10th-to-90th percentile task completed_at rate; SUCCESS update and result insert commit in one transaction'
        taskCompletionLatencyMs = $outcome.taskCompletionLatencyMs
        successCount = [long]$outcome.successCount
        terminalFailureCount = [long]$outcome.terminalFailureCount
        taskRetryCount = [long]$outcome.taskRetryCount
        outboxRetryCount = [long]$outcome.outboxRetryCount
        outboxFailureCount = [long]$outcome.outboxFailureCount
        outboxPending = Get-DatabaseDrain @($set.backlog.samples) 'outboxPending'
        proxyBacklog = Get-DatabaseDrain @($set.backlog.samples) 'proxyBacklog'
        rocketMq = [ordered]@{
            collection = if ($null -ne $set.native.collection) { [string]$set.native.collection } else { 'captured at run completion' }
            readyPeak = Get-SeriesPeak $native.readyMessages
            inflight = Get-SeriesDrain $native.inflightMessages
            lagPeak = Get-SeriesPeak $native.lagMessages
            messagesInActiveAveragePerSecond = Get-SeriesActiveAverage $native.messagesInPerSecond
            messagesInPeakPerSecond = Get-SeriesPeak $native.messagesInPerSecond
            messagesOutActiveAveragePerSecond = Get-SeriesActiveAverage $native.messagesOutPerSecond
            messagesOutPeakPerSecond = Get-SeriesPeak $native.messagesOutPerSecond
            queueingLatencyAvailable = (@(Get-SeriesPoints $native.queueingLatencyMs).Count -gt 0)
            lagLatencyAvailable = (@(Get-SeriesPoints $native.lagLatencyMs).Count -gt 0)
        }
        ai = [ordered]@{
            requestCount = $aiSuccess
            requestRatePerSecond = $aiSuccess / ([double]$outcome.totalCompletionMs / 1000)
            averageMs = if ($aiSuccess -gt 0) { 1000 * $aiTotal / $aiSuccess } else { $null }
            p95Ms = 1000 * (Get-PrometheusValue $prom.aiP95SecondsByOutcome success)
            p99Ms = 1000 * (Get-PrometheusValue $prom.aiP99SecondsByOutcome success)
        }
        outboxPublish = [ordered]@{
            successCount = $publishSuccess
            failedAttemptCount = $publishFailure
        }
        resources = [ordered]@{
            processCpuUsagePeak = Get-PrometheusValue $prom.processCpuUsageMax
            jvmHeapPeakBytes = Get-PrometheusValue $prom.jvmHeapUsedMaxBytes
            jvmGcPauseSeconds = Get-PrometheusValue $prom.jvmGcPauseIncreaseSeconds
            hikariActivePeak = Get-PrometheusValue $prom.hikariActiveMax
            hikariConfiguredMax = Get-PrometheusValue $prom.hikariConfiguredMax
            http5xxCount = Get-PrometheusValue $prom.http5xxCount
        }
        artifacts = $set.artifacts
    }
}

$aggregates = @()
foreach ($consumer in $Consumers) {
    $items = @($runs | Where-Object { $_.consumerConcurrency -eq $consumer })
    if ($items.Count -ne $ExpectedRunsPerConsumer) {
        throw "Expected $ExpectedRunsPerConsumer runs for c$consumer, found $($items.Count)."
    }
    $publishSuccess = [double]($items | ForEach-Object { $_.outboxPublish.successCount } | Measure-Object -Sum).Sum
    $publishFailure = [double]($items | ForEach-Object { $_.outboxPublish.failedAttemptCount } | Measure-Object -Sum).Sum
    $aggregates += [ordered]@{
        consumerConcurrency = $consumer
        runCount = $items.Count
        completionTimeMs = Get-Statistics ([double[]]@($items | ForEach-Object { $_.completionTimeMs }))
        throughputTasksPerSecond = Get-Statistics ([double[]]@($items | ForEach-Object { $_.throughputTasksPerSecond }))
        resultPersistenceRateProxyPerSecond = Get-Statistics ([double[]]@($items | ForEach-Object { $_.resultPersistenceRateProxyPerSecond }))
        endToEndDrainTimeMs = Get-Statistics ([double[]]@($items | ForEach-Object { $_.endToEndDrainTimeMs }))
        outboxPendingDrainRatePerSecond = Get-Statistics ([double[]]@($items | ForEach-Object { $_.outboxPending.ratePerSecond }))
        outboxPendingDrainSeconds = Get-Statistics ([double[]]@($items | ForEach-Object { $_.outboxPending.peakToLastPositiveSeconds }))
        rocketMqReadyPeak = Get-Statistics ([double[]]@($items | ForEach-Object { $_.rocketMq.readyPeak }))
        rocketMqInflightPeak = Get-Statistics ([double[]]@($items | ForEach-Object { $_.rocketMq.inflight.peak }))
        rocketMqInflightDrainSeconds = Get-Statistics ([double[]]@($items | ForEach-Object { $_.rocketMq.inflight.peakToLastPositiveSeconds }))
        rocketMqLagPeak = Get-Statistics ([double[]]@($items | ForEach-Object { $_.rocketMq.lagPeak }))
        rocketMqMessagesInActiveAveragePerSecond = Get-Statistics ([double[]]@($items | ForEach-Object { $_.rocketMq.messagesInActiveAveragePerSecond }))
        rocketMqMessagesOutActiveAveragePerSecond = Get-Statistics ([double[]]@($items | ForEach-Object { $_.rocketMq.messagesOutActiveAveragePerSecond }))
        aiAverageMs = Get-Statistics ([double[]]@($items | ForEach-Object { $_.ai.averageMs }))
        aiP95Ms = Get-Statistics ([double[]]@($items | ForEach-Object { $_.ai.p95Ms }))
        aiP99Ms = Get-Statistics ([double[]]@($items | ForEach-Object { $_.ai.p99Ms }))
        taskRetryCount = [long]($items | ForEach-Object { $_.taskRetryCount } | Measure-Object -Sum).Sum
        outboxRetryCount = [long]($items | ForEach-Object { $_.outboxRetryCount } | Measure-Object -Sum).Sum
        terminalFailureCount = [long]($items | ForEach-Object { $_.terminalFailureCount } | Measure-Object -Sum).Sum
        outboxFailureCount = [long]($items | ForEach-Object { $_.outboxFailureCount } | Measure-Object -Sum).Sum
        outboxPublishSuccessRate = if (($publishSuccess + $publishFailure) -gt 0) {
            $publishSuccess / ($publishSuccess + $publishFailure)
        } else { $null }
        http5xxCount = [double]($items | ForEach-Object { $_.resources.http5xxCount } | Measure-Object -Sum).Sum
        processCpuUsagePeak = [double]($items | ForEach-Object { $_.resources.processCpuUsagePeak } | Measure-Object -Maximum).Maximum
        jvmHeapPeakBytes = [double]($items | ForEach-Object { $_.resources.jvmHeapPeakBytes } | Measure-Object -Maximum).Maximum
        jvmGcPauseSeconds = [double]($items | ForEach-Object { $_.resources.jvmGcPauseSeconds } | Measure-Object -Sum).Sum
        hikariActivePeak = [double]($items | ForEach-Object { $_.resources.hikariActivePeak } | Measure-Object -Maximum).Maximum
        hikariConfiguredMax = [double]($items | ForEach-Object { $_.resources.hikariConfiguredMax } | Measure-Object -Maximum).Maximum
        nativeBackfilledRunCount = @($items | Where-Object { $_.rocketMq.collection -match 'backfilled' }).Count
    }
}

$comparisons = @()
for ($index = 1; $index -lt $aggregates.Count; $index++) {
    $from = $aggregates[$index - 1]
    $to = $aggregates[$index]
    $speedup = $from.completionTimeMs.average / $to.completionTimeMs.average
    $threadRatio = $to.consumerConcurrency / $from.consumerConcurrency
    $comparisons += [ordered]@{
        fromConsumer = $from.consumerConcurrency
        toConsumer = $to.consumerConcurrency
        completionReductionPercent = 100 * (1 - $to.completionTimeMs.average / $from.completionTimeMs.average)
        throughputImprovementPercent = 100 * ($to.throughputTasksPerSecond.average / $from.throughputTasksPerSecond.average - 1)
        completionSpeedup = $speedup
        scalingEfficiencyPercent = 100 * $speedup / $threadRatio
        inflightPeakReductionPercent = 100 * (1 - $to.rocketMqInflightPeak.average / $from.rocketMqInflightPeak.average)
        inflightDrainReductionPercent = 100 * (1 - $to.rocketMqInflightDrainSeconds.average / $from.rocketMqInflightDrainSeconds.average)
        aiP95ChangePercent = 100 * ($to.aiP95Ms.average / $from.aiP95Ms.average - 1)
    }
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
    workload = [ordered]@{
        fixture = 'performance/k6/data/batch-200.csv'
        batches = 5
        rowsPerBatch = 200
        taskCount = 1000
        repetitionsPerConsumer = $ExpectedRunsPerConsumer
    }
    fixedConfiguration = [ordered]@{
        aiStubDelayMs = 100
        outboxBatchSize = 100
        outboxPublishIntervalMs = 1000
        rocketMqNativeScrapeSeconds = 1
    }
    setup = [ordered]@{
        host = $phaseOneSetup.host
        docker = $phaseOneSetup.docker
        java = $phaseOneSetup.java
        springBoot = $phaseOneSetup.springBoot
        k6Image = [string]$first.k6.metadata.k6Image
        k6ImageDigest = [string]$first.k6.metadata.k6ImageDigest
    }
    callChain = @(
        'RocketMQ customizer sets consumeThreadNumber and consumeThreadMax to the configured value',
        'each consumer thread synchronously reads task/comment and conditionally claims execution ownership',
        'the same thread performs blocking RestClient AI HTTP using SimpleClientHttpRequestFactory',
        'saveSuccess transaction conditionally marks SUCCESS, inserts one analysis_result row, then commits',
        'no shared global lock or single result-persistence scheduler exists in this path'
    )
    definitions = [ordered]@{
        inflightDrain = 'seconds from the first native inflight peak sample to the final positive inflight sample'
        resultPersistenceRateProxy = '10th-to-90th percentile analysis_task completed_at rate; the SUCCESS update and result insert are committed in one transaction'
        nativeBackfill = 'c2/c4 native files were queried after the runs from retained 1s Prometheus samples using each original testStartedAt-to-observedAt window; c8 was captured by the corrected runner'
        standardDeviation = 'sample standard deviation (n-1)'
    }
    runs = $runs
    aggregates = $aggregates
    scaling = $comparisons
    conclusion = [ordered]@{
        c2ToC4 = 'Consumer concurrency is the limiting downstream factor at c2; c4 nearly doubles throughput and materially reduces inflight backlog.'
        c4ToC8 = 'c8 still improves throughput, but scaling efficiency drops and inflight nearly disappears.'
        newLimit = 'At c8, throughput and persistence rate converge on the roughly 35-40 events/s Outbox publishing supply, so the limiting boundary moves back to the publisher side.'
        batch100Value = 'Batch size 100 is useful only when consumer concurrency is high enough to use the additional supply; it did not help c2 in Phase 5 but unlocks c4/c8 scaling here.'
        nextOptimization = 'Optimize the sequential Outbox claim -> syncSend -> mark-sent dispatch path with bounded pipelining while preserving conditional state transitions and retry semantics.'
    }
    resumeSafe = [ordered]@{
        eligible = ($ExpectedRunsPerConsumer -ge 3)
        consumers = $Consumers
        caveat = 'single-machine local environment, deterministic 100ms HTTP AI Stub, batch size 100; no real BERT/LLM'
    }
}

$outputStem = "$(Get-Date -Format 'yyyy-MM-dd')-phase-6-consumer-capacity-$ExperimentId"
$jsonPath = Join-Path $ResultsPath "$outputStem.json"
$markdownPath = Join-Path $ResultsPath "$outputStem.md"
Write-Utf8NoBom $jsonPath ($result | ConvertTo-Json -Depth 30)

$md = [Collections.Generic.List[string]]::new()
$md.Add('# CourseInsight Phase 6 - downstream consumer capacity')
$md.Add('')
$md.Add("- Experiment: $ExperimentId")
$md.Add("- Git HEAD: $($result.gitCommit)")
$md.Add("- Host: $($phaseOneSetup.host.cpu); $([Math]::Round($phaseOneSetup.host.totalVisibleMemoryBytes/1GB,2)) GiB visible RAM; Docker $($phaseOneSetup.docker.cpus) CPUs / $([Math]::Round($phaseOneSetup.docker.memoryBytes/1GB,2)) GiB")
$md.Add('- Workload: 5 x 200-row API batches = 1000 tasks per run')
$md.Add('- Fixed: 100 ms deterministic HTTP AI Stub, Outbox batch 100, publisher interval 1000 ms')
$md.Add("- Repetitions: $ExpectedRunsPerConsumer per consumer concurrency")
$md.Add('- RocketMQ native metrics: 1 s scrape')
$md.Add('')
$md.Add('## Repeated results')
$md.Add('')
$md.Add('| Consumer | Completion mean +/- SD (s) | Throughput (tasks/s) | MQ inflight peak | MQ inflight drain (s) | Persistence-rate proxy (/s) | Retry | Failure |')
$md.Add('| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($aggregate in $aggregates) {
    $md.Add("| $($aggregate.consumerConcurrency) | $(Format-Number ($aggregate.completionTimeMs.average/1000) '0.000') +/- $(Format-Number ($aggregate.completionTimeMs.standardDeviationSample/1000) '0.000') | $(Format-Number $aggregate.throughputTasksPerSecond.average '0.000') | $(Format-Number $aggregate.rocketMqInflightPeak.average '0.0') | $(Format-Number $aggregate.rocketMqInflightDrainSeconds.average '0.000') | $(Format-Number $aggregate.resultPersistenceRateProxyPerSecond.average '0.000') | $($aggregate.taskRetryCount) task / $($aggregate.outboxRetryCount) Outbox | $($aggregate.terminalFailureCount) |")
}
$md.Add('')
$md.Add('## Scaling')
$md.Add('')
foreach ($comparison in $comparisons) {
    $md.Add("- c$($comparison.fromConsumer) -> c$($comparison.toConsumer): completion -$(Format-Number $comparison.completionReductionPercent)%; throughput +$(Format-Number $comparison.throughputImprovementPercent)%; speedup $(Format-Number $comparison.completionSpeedup '0.000')x; scaling efficiency $(Format-Number $comparison.scalingEfficiencyPercent '0.0')%; inflight peak -$(Format-Number $comparison.inflightPeakReductionPercent)%; inflight drain -$(Format-Number $comparison.inflightDrainReductionPercent)%; AI p95 $(Format-Number $comparison.aiP95ChangePercent)%.")
}
$md.Add('')
$md.Add('## Resource and broker observations')
$md.Add('')
$md.Add('| Consumer | AI avg/p95/p99 (ms) | Outbox drain (/s) | MQ in/out active avg (/s) | CPU peak | Heap peak (MiB) | GC sum (s) | Hikari peak/max | HTTP 5xx |')
$md.Add('| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($aggregate in $aggregates) {
    $md.Add("| $($aggregate.consumerConcurrency) | $(Format-Number $aggregate.aiAverageMs.average)/$(Format-Number $aggregate.aiP95Ms.average)/$(Format-Number $aggregate.aiP99Ms.average) | $(Format-Number $aggregate.outboxPendingDrainRatePerSecond.average) | $(Format-Number $aggregate.rocketMqMessagesInActiveAveragePerSecond.average)/$(Format-Number $aggregate.rocketMqMessagesOutActiveAveragePerSecond.average) | $(Format-Number (100*$aggregate.processCpuUsagePeak) '0.0')% | $(Format-Number ($aggregate.jvmHeapPeakBytes/1MB)) | $(Format-Number $aggregate.jvmGcPauseSeconds '0.000') | $(Format-Number $aggregate.hikariActivePeak '0')/$(Format-Number $aggregate.hikariConfiguredMax '0') | $(Format-Number $aggregate.http5xxCount '0') |")
}
$md.Add('')
$md.Add('## Call-chain interpretation')
$md.Add('')
$md.Add('- The listener customizer sets both RocketMQ consumer thread bounds to the requested c2/c4/c8 value.')
$md.Add('- Each message stays on one consumer thread through claim, blocking AI HTTP, and the transactional SUCCESS/result insert. No shared serial persistence scheduler exists.')
$md.Add('- c2 -> c4 nearly doubles completion throughput and sharply reduces inflight, proving that c2 was constrained by available consumer workers around the synchronous 100 ms HTTP call.')
$md.Add('- c8 still improves, but native inflight falls to only about 20-24 messages and task throughput converges on Outbox publish supply. CPU, Hikari, GC and AI p95 remain stable, so they are not the observed c8 boundary.')
$md.Add('')
$md.Add('## Recommendation')
$md.Add('')
$md.Add('- c8 was worth testing and remains faster than c4, but shows diminishing scaling. Testing above c8 is not justified until publisher supply is increased in a separate experiment.')
$md.Add('- Batch size 100 has value only together with higher consumer concurrency: it unlocks c4/c8 scaling, while Phase 5 showed no benefit at c2 alone.')
$md.Add('- If one implementation optimization is attempted next, target the sequential Outbox claim -> syncSend -> mark-sent path with bounded pipelining and unchanged correctness/retry fencing.')
$md.Add('')
$md.Add('## Native-metric collection note')
$md.Add('')
$md.Add('- c2/c4 native artifacts were backfilled from retained one-second Prometheus samples using each original run window after a phase-gating bug was found. c8 artifacts were written directly by the corrected runner. No metric value was synthesized.')
$md.Add('')
$md.Add('## Resume-safe numbers')
$md.Add('')
$md.Add('- Eligible: all three consumer aggregate rows and c2->c4/c4->c8 scaling comparisons. Each is based on three runs under the fixed Phase 6 conditions.')
$md.Add('- Scope caveat: single local machine and deterministic Stub; these are not real-LLM or production-cluster capacity numbers.')
Write-Utf8NoBom $markdownPath ($md -join "`n")

Write-Host "Phase 6 JSON: $jsonPath"
Write-Host "Phase 6 Markdown: $markdownPath"
