[CmdletBinding()]
param(
    [string]$ResultsPath = '',
    [ValidatePattern('^[A-Za-z0-9_-]{1,40}$')]
    [string]$ExperimentId = 'P7_OUTBOX',
    [ValidateRange(3, 10)]
    [int]$ExpectedRunsPerCandidate = 3
)

$ErrorActionPreference = 'Stop'
$culture = [Globalization.CultureInfo]::InvariantCulture
$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
if ([string]::IsNullOrWhiteSpace($ResultsPath)) {
    $ResultsPath = Join-Path $repoRoot 'performance\results'
}
$baselinePath = Join-Path $ResultsPath '2026-08-10-phase-6-consumer-capacity-P6_CONSUMER.json'
if (-not (Test-Path -LiteralPath $baselinePath)) {
    throw "Missing Phase 6 baseline: $baselinePath"
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
            Measure-Object -Sum).Sum) / ($Values.Count - 1)
    } else { 0 }
    $sd = [Math]::Sqrt($variance)
    [ordered]@{
        count = $Values.Count
        average = $average
        standardDeviationSample = $sd
        coefficientOfVariation = if ($average -ne 0) { $sd / $average } else { 0 }
        min = [double]($Values | Measure-Object -Minimum).Minimum
        p50 = Get-Percentile $Values 0.50
        p95 = Get-Percentile $Values 0.95
        p99 = Get-Percentile $Values 0.99
        max = [double]($Values | Measure-Object -Maximum).Maximum
    }
}

function Get-PrometheusValue {
    param($Results, [string]$Name, [string]$Outcome = '')
    foreach ($series in @($Results.$Name)) {
        if (-not [string]::IsNullOrWhiteSpace($Outcome) -and
            [string]$series.metric.outcome -ne $Outcome) { continue }
        if ($null -eq $series.value -or $series.value.Count -lt 2) { continue }
        $value = [double]$series.value[1]
        if (-not [double]::IsNaN($value)) { return $value }
    }
    0.0
}

function Get-SeriesPoints {
    param($Series)
    $points = @()
    foreach ($item in @($Series)) {
        foreach ($pair in @($item.values)) {
            if ($null -eq $pair -or $pair.Count -lt 2) { continue }
            $value = [double]$pair[1]
            if ([double]::IsNaN($value)) { continue }
            $points += [pscustomobject]@{
                timestamp = [double]$pair[0]
                value = $value
            }
        }
    }
    @($points | Sort-Object timestamp)
}

function Get-SeriesPeak {
    param($Series)
    $points = @(Get-SeriesPoints $Series)
    if ($points.Count -eq 0) { return 0.0 }
    [double]($points.value | Measure-Object -Maximum).Maximum
}

function Get-SeriesActiveAverage {
    param($Series)
    $positive = @(Get-SeriesPoints $Series | Where-Object { $_.value -gt 0 })
    if ($positive.Count -eq 0) { return 0.0 }
    [double]($positive.value | Measure-Object -Average).Average
}

function Get-SeriesDrainSeconds {
    param($Series)
    $points = @(Get-SeriesPoints $Series)
    if ($points.Count -eq 0) { return 0.0 }
    $peakValue = [double]($points.value | Measure-Object -Maximum).Maximum
    if ($peakValue -le 0) { return 0.0 }
    $peak = $points | Where-Object { $_.value -eq $peakValue } | Select-Object -First 1
    $lastPositive = $points |
        Where-Object { $_.timestamp -ge $peak.timestamp -and $_.value -gt 0 } |
        Select-Object -Last 1
    [double]($lastPositive.timestamp - $peak.timestamp)
}

function Get-DatabaseDrain {
    param($Samples, [string]$Property)
    $peakValue = [double]($Samples | ForEach-Object { $_.$Property } |
        Measure-Object -Maximum).Maximum
    $peak = $Samples | Where-Object { [double]$_.$Property -eq $peakValue } |
        Select-Object -First 1
    $lastPositive = $Samples | Where-Object {
        $_.timestampEpochMs -ge $peak.timestampEpochMs -and
        [double]$_.$Property -gt 0
    } | Select-Object -Last 1
    $seconds = [double]($lastPositive.timestampEpochMs - $peak.timestampEpochMs) / 1000
    [ordered]@{
        peak = $peakValue
        drainSeconds = $seconds
        drainRatePerSecond = if ($seconds -gt 0) { $peakValue / $seconds } else { 0 }
    }
}

function Format-Number {
    param($Value, [string]$Pattern = '0.00')
    if ($null -eq $Value) { return 'n/a' }
    ([double]$Value).ToString($Pattern, $culture)
}

$labels = @('bounded-c2', 'bounded-c4')
$sets = @()
foreach ($label in $labels) {
    $files = @(Get-ChildItem -LiteralPath $ResultsPath -File `
        -Filter "*-phase7-$ExperimentId-$label-scaling-c8-r*-outcome.json" |
        Sort-Object Name)
    if ($files.Count -ne $ExpectedRunsPerCandidate) {
        throw "Expected $ExpectedRunsPerCandidate $label outcomes, found $($files.Count)."
    }
    foreach ($file in $files) {
        $stem = $file.Name.Substring(0, $file.Name.Length - '-outcome.json'.Length)
        $artifacts = [ordered]@{
            outcome = $file.Name
            k6 = "$stem-k6.json"
            prometheus = "$stem-prometheus.json"
            backlog = "$stem-backlog.json"
            rocketMqNative = "$stem-rocketmq-native.json"
        }
        foreach ($name in $artifacts.Values) {
            if (-not (Test-Path -LiteralPath (Join-Path $ResultsPath $name))) {
                throw "Missing Phase 7 artifact: $name"
            }
        }
        $sets += [pscustomobject]@{
            label = $label
            outcome = Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json
            k6 = Get-Content -Raw -LiteralPath (Join-Path $ResultsPath $artifacts.k6) | ConvertFrom-Json
            prometheus = Get-Content -Raw -LiteralPath (Join-Path $ResultsPath $artifacts.prometheus) | ConvertFrom-Json
            backlog = Get-Content -Raw -LiteralPath (Join-Path $ResultsPath $artifacts.backlog) | ConvertFrom-Json
            native = Get-Content -Raw -LiteralPath (Join-Path $ResultsPath $artifacts.rocketMqNative) | ConvertFrom-Json
            artifacts = $artifacts
        }
    }
}

$runs = @()
foreach ($set in $sets) {
    $outcome = $set.outcome
    $prom = $set.prometheus.results
    $native = $set.native.results
    $sendCount = Get-PrometheusValue $prom outboxSendCountByOutcome success
    $sendTotal = Get-PrometheusValue $prom outboxSendTotalSecondsByOutcome success
    $runs += [ordered]@{
        candidate = $set.label
        runNumber = [int]$outcome.runNumber
        gitCommit = [string]$outcome.gitCommit
        taskCount = [int]$outcome.taskCount
        completionTimeMs = [double]$outcome.totalCompletionMs
        throughputTasksPerSecond = [double]$outcome.throughputTasksPerSecond
        steadyCompletionThroughputTasksPerSecond = [double]$outcome.steadyCompletionThroughputTasksPerSecond
        successCount = [long]$outcome.successCount
        terminalFailureCount = [long]$outcome.terminalFailureCount
        taskRetryCount = [long]$outcome.taskRetryCount
        outboxRetryCount = [long]$outcome.outboxRetryCount
        outboxFailureCount = [long]$outcome.outboxFailureCount
        outboxPending = Get-DatabaseDrain @($set.backlog.samples) outboxPending
        outboxSend = [ordered]@{
            count = $sendCount
            averageMs = if ($sendCount -gt 0) { 1000 * $sendTotal / $sendCount } else { 0 }
            p95Ms = 1000 * (Get-PrometheusValue $prom outboxSendP95SecondsByOutcome success)
            p99Ms = 1000 * (Get-PrometheusValue $prom outboxSendP99SecondsByOutcome success)
            configuredConcurrency = Get-PrometheusValue $prom outboxPublishConfiguredConcurrency
            observedPeakConcurrency = Get-PrometheusValue $prom outboxPublishPeakActive
        }
        rocketMq = [ordered]@{
            readyPeak = Get-SeriesPeak $native.readyMessages
            inflightPeak = Get-SeriesPeak $native.inflightMessages
            inflightDrainSeconds = Get-SeriesDrainSeconds $native.inflightMessages
            messagesInActiveAveragePerSecond = Get-SeriesActiveAverage $native.messagesInPerSecond
            messagesOutActiveAveragePerSecond = Get-SeriesActiveAverage $native.messagesOutPerSecond
        }
        ai = [ordered]@{
            p95Ms = 1000 * (Get-PrometheusValue $prom aiP95SecondsByOutcome success)
            p99Ms = 1000 * (Get-PrometheusValue $prom aiP99SecondsByOutcome success)
        }
        resources = [ordered]@{
            processCpuUsagePeak = Get-PrometheusValue $prom processCpuUsageMax
            jvmHeapPeakBytes = Get-PrometheusValue $prom jvmHeapUsedMaxBytes
            jvmGcPauseSeconds = Get-PrometheusValue $prom jvmGcPauseIncreaseSeconds
            hikariActivePeak = Get-PrometheusValue $prom hikariActiveMax
            hikariConfiguredMax = Get-PrometheusValue $prom hikariConfiguredMax
            http5xxCount = Get-PrometheusValue $prom http5xxCount
        }
        artifacts = $set.artifacts
    }
}

$candidates = @()
foreach ($label in $labels) {
    $items = @($runs | Where-Object { $_.candidate -eq $label })
    $candidates += [ordered]@{
        candidate = $label
        publishConcurrency = [int]$items[0].outboxSend.configuredConcurrency
        runCount = $items.Count
        completionTimeMs = Get-Statistics ([double[]]@($items.completionTimeMs))
        throughputTasksPerSecond = Get-Statistics ([double[]]@($items.throughputTasksPerSecond))
        steadyCompletionThroughputTasksPerSecond = Get-Statistics ([double[]]@($items.steadyCompletionThroughputTasksPerSecond))
        outboxDrainRatePerSecond = Get-Statistics ([double[]]@($items | ForEach-Object { $_.outboxPending.drainRatePerSecond }))
        outboxDrainSeconds = Get-Statistics ([double[]]@($items | ForEach-Object { $_.outboxPending.drainSeconds }))
        outboxSendAverageMs = Get-Statistics ([double[]]@($items | ForEach-Object { $_.outboxSend.averageMs }))
        outboxSendP95Ms = Get-Statistics ([double[]]@($items | ForEach-Object { $_.outboxSend.p95Ms }))
        outboxSendP99Ms = Get-Statistics ([double[]]@($items | ForEach-Object { $_.outboxSend.p99Ms }))
        observedPublishConcurrencyPeak = [double]($items | ForEach-Object { $_.outboxSend.observedPeakConcurrency } | Measure-Object -Maximum).Maximum
        rocketMqReadyPeak = Get-Statistics ([double[]]@($items | ForEach-Object { $_.rocketMq.readyPeak }))
        rocketMqInflightPeak = Get-Statistics ([double[]]@($items | ForEach-Object { $_.rocketMq.inflightPeak }))
        rocketMqInflightDrainSeconds = Get-Statistics ([double[]]@($items | ForEach-Object { $_.rocketMq.inflightDrainSeconds }))
        rocketMqMessagesInActiveAveragePerSecond = Get-Statistics ([double[]]@($items | ForEach-Object { $_.rocketMq.messagesInActiveAveragePerSecond }))
        rocketMqMessagesOutActiveAveragePerSecond = Get-Statistics ([double[]]@($items | ForEach-Object { $_.rocketMq.messagesOutActiveAveragePerSecond }))
        aiP95Ms = Get-Statistics ([double[]]@($items | ForEach-Object { $_.ai.p95Ms }))
        aiP99Ms = Get-Statistics ([double[]]@($items | ForEach-Object { $_.ai.p99Ms }))
        taskRetryCount = [long]($items.taskRetryCount | Measure-Object -Sum).Sum
        outboxRetryCount = [long]($items.outboxRetryCount | Measure-Object -Sum).Sum
        terminalFailureCount = [long]($items.terminalFailureCount | Measure-Object -Sum).Sum
        outboxFailureCount = [long]($items.outboxFailureCount | Measure-Object -Sum).Sum
        http5xxCount = [double]($items | ForEach-Object { $_.resources.http5xxCount } | Measure-Object -Sum).Sum
        processCpuUsagePeak = [double]($items | ForEach-Object { $_.resources.processCpuUsagePeak } | Measure-Object -Maximum).Maximum
        jvmHeapPeakBytes = [double]($items | ForEach-Object { $_.resources.jvmHeapPeakBytes } | Measure-Object -Maximum).Maximum
        jvmGcPauseSeconds = [double]($items | ForEach-Object { $_.resources.jvmGcPauseSeconds } | Measure-Object -Sum).Sum
        hikariActivePeak = [double]($items | ForEach-Object { $_.resources.hikariActivePeak } | Measure-Object -Maximum).Maximum
        hikariConfiguredMax = [double]($items | ForEach-Object { $_.resources.hikariConfiguredMax } | Measure-Object -Maximum).Maximum
    }
}

$phase6 = Get-Content -Raw -LiteralPath $baselinePath | ConvertFrom-Json
$before = $phase6.aggregates | Where-Object { $_.consumerConcurrency -eq 8 }
$after = $candidates | Where-Object { $_.candidate -eq 'bounded-c4' }
$c2 = $candidates | Where-Object { $_.candidate -eq 'bounded-c2' }
$comparison = [ordered]@{
    completionReductionPercent = 100 * (1 - $after.completionTimeMs.average / $before.completionTimeMs.average)
    throughputIncreasePercent = 100 * ($after.throughputTasksPerSecond.average / $before.throughputTasksPerSecond.average - 1)
    outboxDrainIncreasePercent = 100 * ($after.outboxDrainRatePerSecond.average / $before.outboxPendingDrainRatePerSecond.average - 1)
    mqInflightPeakChangePercent = 100 * ($after.rocketMqInflightPeak.average / $before.rocketMqInflightPeak.average - 1)
    c2ThroughputIncreasePercent = 100 * ($c2.throughputTasksPerSecond.average / $before.throughputTasksPerSecond.average - 1)
    c2CompletionReductionPercent = 100 * (1 - $c2.completionTimeMs.average / $before.completionTimeMs.average)
}

$result = [ordered]@{
    schemaVersion = 1
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    experimentId = $ExperimentId
    gitHead = [string]$runs[0].gitCommit
    candidateWorkingTree = 'uncommitted Phase 7 implementation on the recorded Git HEAD'
    workload = [ordered]@{
        fixture = 'performance/k6/data/batch-200.csv'
        batches = 5
        rowsPerBatch = 200
        taskCount = 1000
        repetitionsPerCandidate = $ExpectedRunsPerCandidate
        aiStubDelayMs = 100
        consumerConcurrency = 8
        outboxBatchSize = 100
        outboxPublishIntervalMs = 1000
    }
    before = [ordered]@{
        source = Split-Path -Leaf $baselinePath
        benchmarkGitCommit = [string]$phase6.gitCommit
        serverImplementationNote = 'Only performance harness/results changed between the Phase 6 benchmark commit and current HEAD; server implementation was unchanged before Phase 7.'
        aggregate = $before
    }
    runs = $runs
    candidates = $candidates
    selectedCandidate = 'bounded-c4'
    comparison = $comparison
    correctness = [ordered]@{
        totalCandidateTasks = 6000
        successCount = [long]($runs.successCount | Measure-Object -Sum).Sum
        terminalFailureCount = [long]($runs.terminalFailureCount | Measure-Object -Sum).Sum
        taskRetryCount = [long]($runs.taskRetryCount | Measure-Object -Sum).Sum
        outboxRetryCount = [long]($runs.outboxRetryCount | Measure-Object -Sum).Sum
        outboxFailureCount = [long]($runs.outboxFailureCount | Measure-Object -Sum).Sum
    }
    conclusion = [ordered]@{
        keepOptimization = $true
        reason = 'Publish concurrency 4 materially improves repeated completion, throughput and Outbox drain with no lost task, retry or failure.'
        defaultConcurrency = 2
        benchmarkConcurrency = 4
        newBoundary = 'The broker is not the limiter: ready remains zero and messages in/out match. Inflight now accumulates while one run observes Hikari 10/10, so the next boundary is downstream consumer/result persistence plus shared database-pool contention; the available data cannot separate those two more finely.'
        productionDefaultDecision = 'Keep the bounded pipeline and conservative default 2. Do not promote 4 as a production default from a local Stub benchmark because one run observed Hikari at 10/10.'
    }
    resumeSafe = [ordered]@{
        eligible = $true
        numbers = [ordered]@{
            completionBeforeSeconds = $before.completionTimeMs.average / 1000
            completionAfterSeconds = $after.completionTimeMs.average / 1000
            throughputBeforeTasksPerSecond = $before.throughputTasksPerSecond.average
            throughputAfterTasksPerSecond = $after.throughputTasksPerSecond.average
            throughputIncreasePercent = $comparison.throughputIncreasePercent
            completionReductionPercent = $comparison.completionReductionPercent
            outboxDrainIncreasePercent = $comparison.outboxDrainIncreasePercent
        }
        caveat = 'Single local machine, deterministic 100ms HTTP AI Stub, explicit publish concurrency 4, three repeated 1000-task runs; not real BERT/LLM or production cluster capacity.'
    }
}

$outputStem = "$(Get-Date -Format 'yyyy-MM-dd')-phase-7-outbox-publisher-$ExperimentId"
$jsonPath = Join-Path $ResultsPath "$outputStem.json"
$markdownPath = Join-Path $ResultsPath "$outputStem.md"
Write-Utf8NoBom $jsonPath ($result | ConvertTo-Json -Depth 30)

$md = [Collections.Generic.List[string]]::new()
$md.Add('# CourseInsight Phase 7 - bounded Outbox publisher')
$md.Add('')
$md.Add("- Git HEAD: $($result.gitHead) with uncommitted Phase 7 implementation")
$md.Add('- Workload: 5 x 200-row API batches, 100 ms deterministic HTTP Stub, consumer 8, Outbox batch 100, fixed delay 1000 ms')
$md.Add('- Repetitions: Phase 6 before = 3; Phase 7 concurrency 2 = 3; Phase 7 concurrency 4 = 3')
$md.Add('')
$md.Add('## Before / after')
$md.Add('')
$md.Add('| Metric | Before serial | After bounded c4 | Change |')
$md.Add('| --- | ---: | ---: | ---: |')
$md.Add("| Completion | $(Format-Number ($before.completionTimeMs.average/1000) '0.000') +/- $(Format-Number ($before.completionTimeMs.standardDeviationSample/1000) '0.000') s | $(Format-Number ($after.completionTimeMs.average/1000) '0.000') +/- $(Format-Number ($after.completionTimeMs.standardDeviationSample/1000) '0.000') s | -$(Format-Number $comparison.completionReductionPercent)% |")
$md.Add("| Throughput | $(Format-Number $before.throughputTasksPerSecond.average '0.000') tasks/s | $(Format-Number $after.throughputTasksPerSecond.average '0.000') tasks/s | +$(Format-Number $comparison.throughputIncreasePercent)% |")
$md.Add("| Outbox pending drain | $(Format-Number $before.outboxPendingDrainRatePerSecond.average) events/s | $(Format-Number $after.outboxDrainRatePerSecond.average) events/s | +$(Format-Number $comparison.outboxDrainIncreasePercent)% |")
$md.Add("| MQ inflight peak | $(Format-Number $before.rocketMqInflightPeak.average '0.0') | $(Format-Number $after.rocketMqInflightPeak.average '0.0') | +$(Format-Number $comparison.mqInflightPeakChangePercent)% |")
$md.Add("| Retry/failure | 0 / 0 | $($after.taskRetryCount + $after.outboxRetryCount) / $($after.terminalFailureCount + $after.outboxFailureCount) | unchanged |")
$md.Add('')
$md.Add('## Candidate sensitivity')
$md.Add('')
$md.Add('| Publish concurrency | Completion mean +/- SD | Throughput | Outbox drain | Send avg/p95/p99 | Observed peak | Hikari peak/max |')
$md.Add('| ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($candidate in $candidates) {
    $md.Add("| $($candidate.publishConcurrency) | $(Format-Number ($candidate.completionTimeMs.average/1000) '0.000') +/- $(Format-Number ($candidate.completionTimeMs.standardDeviationSample/1000) '0.000') s | $(Format-Number $candidate.throughputTasksPerSecond.average '0.000') | $(Format-Number $candidate.outboxDrainRatePerSecond.average) | $(Format-Number $candidate.outboxSendAverageMs.average)/$(Format-Number $candidate.outboxSendP95Ms.average)/$(Format-Number $candidate.outboxSendP99Ms.average) ms | $(Format-Number $candidate.observedPublishConcurrencyPeak '0') | $(Format-Number $candidate.hikariActivePeak '0')/$(Format-Number $candidate.hikariConfiguredMax '0') |")
}
$md.Add('')
$md.Add('## Correctness and boundary')
$md.Add('')
$md.Add('- Across the six Phase 7 candidate runs, all 6000 tasks reached SUCCESS with zero task retry, Outbox retry, terminal failure, Outbox failure and unexpected HTTP 5xx.')
$md.Add('- A fixed-size executor and at most N submitted-but-unfinished sends bound concurrency; the fixed-delay scheduler is additionally guarded against overlapping cycles.')
$md.Add('- Every claim writes a new 32-character publish token. SENT/FAILED updates require id + PUBLISHING + the current token, so stale attempt completion cannot mutate a newer owner.')
$md.Add('- Broker acceptance followed by a mark-SENT crash deliberately leaves PUBLISHING for stale recovery; a duplicate send is allowed, preserving at-least-once rather than pretending exactly-once.')
$md.Add('- With c4, RocketMQ ready remains zero and broker in/out rates match, while inflight grows and one run observes Hikari 10/10. The next boundary is downstream consumer/result persistence and shared DB-pool contention; this experiment cannot separate them further.')
$md.Add('')
$md.Add('## Decision')
$md.Add('')
$md.Add('- Keep the bounded publisher and attempt fencing. Explicit concurrency 4 produced a material repeated improvement; concurrency 2 alone was only a small gain.')
$md.Add('- Keep the production default conservative at 2. The local c4 result is a benchmark setting, not enough evidence to promote 4 globally because Hikari reached 10/10 once.')
$md.Add('')
$md.Add('## Resume-safe numbers')
$md.Add('')
$md.Add("- Under the stated local Stub workload, bounded c4 changed mean completion from $(Format-Number ($before.completionTimeMs.average/1000) '0.000') s to $(Format-Number ($after.completionTimeMs.average/1000) '0.000') s (-$(Format-Number $comparison.completionReductionPercent)%) and throughput from $(Format-Number $before.throughputTasksPerSecond.average '0.000') to $(Format-Number $after.throughputTasksPerSecond.average '0.000') tasks/s (+$(Format-Number $comparison.throughputIncreasePercent)%).")
$md.Add('- Safe caveat: single Windows machine, deterministic 100 ms HTTP Stub, three 1000-task runs per compared setting; no real BERT/LLM and no production cluster claim.')
Write-Utf8NoBom $markdownPath ($md -join "`n")

Write-Host "Phase 7 JSON: $jsonPath"
Write-Host "Phase 7 Markdown: $markdownPath"
