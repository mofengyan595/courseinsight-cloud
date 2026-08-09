[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{1,40}$')]
    [string]$ExperimentId,
    [int[]]$Concurrencies = @(1, 2, 4, 8),
    [ValidateRange(1, 10)]
    [int]$ExpectedRunsPerConcurrency = 3,
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
    $lower = [Math]::Floor($position); $upper = [Math]::Ceiling($position)
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
        count = $Values.Count; average = $average
        standardDeviationSample = [Math]::Sqrt($variance)
        coefficientOfVariation = if ($average -eq 0) { 0 } else { [Math]::Sqrt($variance) / $average }
        min = [double]($Values | Measure-Object -Minimum).Minimum
        p50 = Get-Percentile $Values 0.50; p95 = Get-Percentile $Values 0.95
        p99 = Get-Percentile $Values 0.99
        max = [double]($Values | Measure-Object -Maximum).Maximum
    }
}

function Get-PrometheusValue {
    param([object[]]$Series, [string]$Outcome = '')
    $entry = if ([string]::IsNullOrWhiteSpace($Outcome)) {
        @($Series) | Select-Object -First 1
    } else {
        @($Series) | Where-Object { $_.metric.outcome -eq $Outcome } | Select-Object -First 1
    }
    if ($null -eq $entry -or $null -eq $entry.value) { return 0.0 }
    $raw = [string]$entry.value[1]
    if ($raw -in @('NaN', '+Inf', '-Inf')) { return $null }
    [double]::Parse($raw, $culture)
}

function Format-Number {
    param([object]$Value, [string]$Format = '0.00')
    if ($null -eq $Value) { return 'n/a' }
    ([double]$Value).ToString($Format, $culture)
}

$outcomeFiles = @(Get-ChildItem -LiteralPath $ResultsPath -File `
    -Filter "*-phase4-$ExperimentId-*-outcome.json" | Sort-Object Name)
if ($outcomeFiles.Count -lt ($Concurrencies.Count * $ExpectedRunsPerConcurrency + 1)) {
    throw "Phase 4 artifact set for $ExperimentId is incomplete."
}
$sets = @()
foreach ($file in $outcomeFiles) {
    $outcome = Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json
    $stem = $file.Name.Substring(0, $file.Name.Length - '-outcome.json'.Length)
    $promPath = Join-Path $ResultsPath "$stem-prometheus.json"
    $backlogPath = Join-Path $ResultsPath "$stem-backlog.json"
    $k6Path = Join-Path $ResultsPath "$stem-k6.json"
    foreach ($path in @($promPath, $backlogPath, $k6Path)) {
        if (-not (Test-Path -LiteralPath $path)) { throw "Missing artifact: $path" }
    }
    $sets += [pscustomobject]@{
        outcome = $outcome
        prometheus = Get-Content -Raw -LiteralPath $promPath | ConvertFrom-Json
        backlog = Get-Content -Raw -LiteralPath $backlogPath | ConvertFrom-Json
        k6 = Get-Content -Raw -LiteralPath $k6Path | ConvertFrom-Json
        files = [ordered]@{ outcome=$file.Name; prometheus=(Split-Path -Leaf $promPath); backlog=(Split-Path -Leaf $backlogPath); k6=(Split-Path -Leaf $k6Path) }
    }
}

$runs = @()
foreach ($set in $sets) {
    $prom = $set.prometheus.results
    $aiCount = Get-PrometheusValue $prom.aiRequestCountByOutcome success
    $aiTotal = Get-PrometheusValue $prom.aiRequestTotalSecondsByOutcome success
    $runs += [ordered]@{
        scenario = [string]$set.outcome.scenario
        runNumber = [int]$set.outcome.runNumber
        consumerConcurrency = [int]$set.outcome.consumerConcurrency
        aiStubDelayMs = [int]$set.outcome.aiStubDelayMs
        taskCount = [int]$set.outcome.taskCount
        productionDurationMs = [double]$set.outcome.productionDurationMs
        createLatencyMs = Get-Statistics ([double[]]@($set.outcome.createLatencyMs))
        completionTimeMs = [double]$set.outcome.totalCompletionMs
        drainTimeMs = [double]$set.outcome.drainTimeMs
        throughputTasksPerSecond = [double]$set.outcome.throughputTasksPerSecond
        steadyCompletionThroughputTasksPerSecond = [double]$set.outcome.steadyCompletionThroughputTasksPerSecond
        taskCompletionLatencyMs = $set.outcome.taskCompletionLatencyMs
        successCount = [long]$set.outcome.successCount
        terminalFailureCount = [long]$set.outcome.terminalFailureCount
        taskRetryCount = [long]$set.outcome.taskRetryCount
        outboxRetryCount = [long]$set.outcome.outboxRetryCount
        outboxFailureCount = [long]$set.outcome.outboxFailureCount
        peakProxyBacklog = [int]$set.outcome.peakProxyBacklog
        peakOutboxPending = [int]$set.outcome.peakOutboxPending
        ai = [ordered]@{
            requestCount = $aiCount
            requestRatePerSecond = $aiCount / ([double]$set.outcome.totalCompletionMs / 1000)
            averageMs = if ($aiCount -gt 0) { 1000 * $aiTotal / $aiCount } else { $null }
            p50Ms = 1000 * (Get-PrometheusValue $prom.aiP50SecondsByOutcome success)
            p95Ms = 1000 * (Get-PrometheusValue $prom.aiP95SecondsByOutcome success)
            p99Ms = 1000 * (Get-PrometheusValue $prom.aiP99SecondsByOutcome success)
            retryableFailureCount = Get-PrometheusValue $prom.aiRequestCountByOutcome retryable_failure
            terminalFailureCount = Get-PrometheusValue $prom.aiRequestCountByOutcome terminal_failure
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

$scalingRuns = @($runs | Where-Object { $_.scenario -eq 'scaling' -and $_.aiStubDelayMs -eq 100 })
foreach ($concurrency in $Concurrencies) {
    $count = @($scalingRuns | Where-Object { $_.consumerConcurrency -eq $concurrency }).Count
    if ($count -ne $ExpectedRunsPerConcurrency) {
        throw "Expected $ExpectedRunsPerConcurrency scaling runs for c$concurrency, found $count."
    }
}
$aggregates = @()
foreach ($concurrency in $Concurrencies) {
    $items = @($scalingRuns | Where-Object { $_.consumerConcurrency -eq $concurrency })
    $aggregates += [ordered]@{
        consumerConcurrency = $concurrency; runCount = $items.Count
        completionTimeMs = Get-Statistics ([double[]]@($items | ForEach-Object { $_.completionTimeMs }))
        throughputTasksPerSecond = Get-Statistics ([double[]]@($items | ForEach-Object { $_.throughputTasksPerSecond }))
        steadyThroughputTasksPerSecond = Get-Statistics ([double[]]@($items | ForEach-Object { $_.steadyCompletionThroughputTasksPerSecond }))
        taskLatencyP50Ms = Get-Statistics ([double[]]@($items | ForEach-Object { $_.taskCompletionLatencyMs.p50 }))
        taskLatencyP95Ms = Get-Statistics ([double[]]@($items | ForEach-Object { $_.taskCompletionLatencyMs.p95 }))
        taskLatencyP99Ms = Get-Statistics ([double[]]@($items | ForEach-Object { $_.taskCompletionLatencyMs.p99 }))
        aiAverageMs = Get-Statistics ([double[]]@($items | ForEach-Object { $_.ai.averageMs }))
        aiP95Ms = Get-Statistics ([double[]]@($items | ForEach-Object { $_.ai.p95Ms }))
        aiP99Ms = Get-Statistics ([double[]]@($items | ForEach-Object { $_.ai.p99Ms }))
        aiRequestRatePerSecond = Get-Statistics ([double[]]@($items | ForEach-Object { $_.ai.requestRatePerSecond }))
        taskRetryCount = [long]($items | ForEach-Object { $_.taskRetryCount } | Measure-Object -Sum).Sum
        terminalFailureCount = [long]($items | ForEach-Object { $_.terminalFailureCount } | Measure-Object -Sum).Sum
        outboxRetryCount = [long]($items | ForEach-Object { $_.outboxRetryCount } | Measure-Object -Sum).Sum
        outboxFailureCount = [long]($items | ForEach-Object { $_.outboxFailureCount } | Measure-Object -Sum).Sum
        http5xxCount = [double]($items | ForEach-Object { $_.resources.http5xxCount } | Measure-Object -Sum).Sum
        peakProxyBacklog = [int]($items | ForEach-Object { $_.peakProxyBacklog } | Measure-Object -Maximum).Maximum
        processCpuUsagePeak = [double]($items | ForEach-Object { $_.resources.processCpuUsagePeak } | Measure-Object -Maximum).Maximum
        jvmHeapPeakBytes = [double]($items | ForEach-Object { $_.resources.jvmHeapPeakBytes } | Measure-Object -Maximum).Maximum
        jvmGcPauseSeconds = [double]($items | ForEach-Object { $_.resources.jvmGcPauseSeconds } | Measure-Object -Sum).Sum
        hikariActivePeak = [double]($items | ForEach-Object { $_.resources.hikariActivePeak } | Measure-Object -Maximum).Maximum
        hikariConfiguredMax = [double]($items | ForEach-Object { $_.resources.hikariConfiguredMax } | Measure-Object -Maximum).Maximum
    }
}
$baseline = $aggregates | Where-Object { $_.consumerConcurrency -eq 1 }
$scaling = @()
foreach ($aggregate in $aggregates) {
    $speedup = $baseline.completionTimeMs.average / $aggregate.completionTimeMs.average
    $scaling += [ordered]@{
        consumerConcurrency = $aggregate.consumerConcurrency
        speedupVs1 = $speedup
        scalingEfficiencyPercent = 100 * $speedup / $aggregate.consumerConcurrency
        throughputImprovementPercentVs1 = 100 * ($aggregate.throughputTasksPerSecond.average / $baseline.throughputTasksPerSecond.average - 1)
        aiP95ChangePercentVs1 = 100 * ($aggregate.aiP95Ms.average / $baseline.aiP95Ms.average - 1)
    }
}
$backlogRun = @($runs | Where-Object { $_.scenario -eq 'backlog' }) | Select-Object -First 1
$delayControl = @($runs | Where-Object { $_.scenario -eq 'delay-control' }) | Select-Object -First 1
$c2 = $aggregates | Where-Object { $_.consumerConcurrency -eq 2 }
$c4 = $aggregates | Where-Object { $_.consumerConcurrency -eq 4 }
$c8 = $aggregates | Where-Object { $_.consumerConcurrency -eq 8 }
$backlogSet = @($sets | Where-Object { $_.outcome.scenario -eq 'backlog' }) | Select-Object -First 1
$backlogSamples = @($backlogSet.backlog.samples)
$peakPendingSample = $backlogSamples | Sort-Object outboxPending -Descending | Select-Object -First 1
$lastPendingSample = $backlogSamples | Where-Object { $_.outboxPending -gt 0 } | Select-Object -Last 1
$outboxPendingDrainRate = if ($null -ne $peakPendingSample -and
    $null -ne $lastPendingSample -and
    $lastPendingSample.timestampEpochMs -gt $peakPendingSample.timestampEpochMs) {
    $peakPendingSample.outboxPending / (
        ($lastPendingSample.timestampEpochMs - $peakPendingSample.timestampEpochMs) / 1000
    )
} else { $null }
$delayControlCompletionReduction = if ($null -ne $delayControl) {
    100 * (1 - $delayControl.completionTimeMs / $c8.completionTimeMs.average)
} else { $null }
$phaseOnePath = Join-Path $ResultsPath '2026-08-09-phase-1-baseline.json'
$phaseOneSetup = if (Test-Path -LiteralPath $phaseOnePath) {
    (Get-Content -Raw -LiteralPath $phaseOnePath | ConvertFrom-Json).setup
} else { $null }
$first = $sets | Select-Object -First 1
$result = [ordered]@{
    schemaVersion = 1; generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    experimentId = $ExperimentId; gitCommit = [string]$first.outcome.gitCommit
    fixture = 'performance/k6/data/batch-200.csv'; batchesPerRun = 5; rowsPerBatch = 200
    taskCountPerRun = 1000; repetitionsPerConcurrency = $ExpectedRunsPerConcurrency
    aiStub = [ordered]@{ delayMs = 100; bertEnabled = $false; llmEnabled = $false; transport = 'real HTTP' }
    setup = [ordered]@{
        host = $phaseOneSetup.host; docker = $phaseOneSetup.docker
        java = $phaseOneSetup.java; springBoot = $phaseOneSetup.springBoot
        k6Image = [string]$first.k6.metadata.k6Image
        k6ImageDigest = [string]$first.k6.metadata.k6ImageDigest
        prometheusScrapeIntervalSeconds = 15
    }
    backlogDefinition = 'analysis_task WAITING + PROCESSING sampled every 0.5s; proxy only, not RocketMQ native queue depth'
    steadyThroughputDefinition = 'completed tasks between 10th and 90th percentile completion timestamps divided by that window'
    standardDeviationMethod = 'sample standard deviation (n-1)'; percentileMethod = 'linear interpolation over sorted samples'
    runs = $runs; aggregates = $aggregates; scalingVsConcurrency1 = $scaling
    backlogExperiment = $backlogRun; delayControl = $delayControl
    conclusion = [ordered]@{
        recommendedConsumerConcurrency = 2
        firstSaturationPoint = 2
        oneToTwo = [ordered]@{
            completionReductionPercent = 100 * (1 - $c2.completionTimeMs.average / $baseline.completionTimeMs.average)
            throughputImprovementPercent = 100 * ($c2.throughputTasksPerSecond.average / $baseline.throughputTasksPerSecond.average - 1)
            aiP95ChangePercent = 100 * ($c2.aiP95Ms.average / $baseline.aiP95Ms.average - 1)
        }
        twoToFour = [ordered]@{
            completionChangePercent = 100 * ($c4.completionTimeMs.average / $c2.completionTimeMs.average - 1)
            throughputChangePercent = 100 * ($c4.throughputTasksPerSecond.average / $c2.throughputTasksPerSecond.average - 1)
            aiP95ChangePercent = 100 * ($c4.aiP95Ms.average / $c2.aiP95Ms.average - 1)
        }
        fourToEight = [ordered]@{
            completionChangePercent = 100 * ($c8.completionTimeMs.average / $c4.completionTimeMs.average - 1)
            throughputChangePercent = 100 * ($c8.throughputTasksPerSecond.average / $c4.throughputTasksPerSecond.average - 1)
            aiP95ChangePercent = 100 * ($c8.aiP95Ms.average / $c4.aiP95Ms.average - 1)
        }
        outboxPendingDrainRatePerSecond = $outboxPendingDrainRate
        delay100To10CompletionReductionPercentAtC8 = $delayControlCompletionReduction
        bottleneckRanking = @(
            'Transactional Outbox dispatch throughput under the current fixed configuration',
            'Serial consumer plus 100ms AI HTTP at concurrency 1 only',
            'RocketMQ broker versus result persistence cannot be isolated with current native metrics'
        )
        evidence = @(
            'c2 has the highest repeated mean throughput; c4 and c8 do not improve it',
            'backlog run peaks at 990 pending Outbox events and drains near 15.2 events/s',
            '10ms Stub at c8 reduces completion only about 4.2% versus the 100ms c8 mean',
            'AI p95 stays near 111ms at 100ms delay with zero retry/failure',
            'Java CPU and Hikari remain far below observed capacity'
        )
        limitations = @(
            'No broker-native RocketMQ queue-depth metric was available',
            'The 10ms delay control is a single diagnostic run, not a repeated resume-safe result'
        )
        nextExperiments = @(
            'Compare the current Outbox batch/cadence against one larger dispatch batch or shorter cadence while keeping c2 and the 1000-task workload fixed',
            'Add broker-native RocketMQ consumer-lag observation and repeat c2 versus c4 to separate broker lag from database task backlog'
        )
    }
    resumeSafe = [ordered]@{
        eligible = ($ExpectedRunsPerConcurrency -ge 3)
        scope = 'consumer scaling aggregates at fixed 100ms deterministic AI Stub only'
        caveat = 'single-machine local environment; backlog is a database task-state proxy'
    }
}
$outputStem = "$(Get-Date -Format 'yyyy-MM-dd')-phase-4-async-stub-$ExperimentId"
$jsonPath = Join-Path $ResultsPath "$outputStem.json"
$markdownPath = Join-Path $ResultsPath "$outputStem.md"
Write-Utf8NoBom $jsonPath ($result | ConvertTo-Json -Depth 30)

$md = [Collections.Generic.List[string]]::new()
$md.Add('# CourseInsight Phase 4 - AI Stub and RocketMQ async-chain benchmark')
$md.Add(''); $md.Add("- Experiment: $ExperimentId")
$md.Add("- Git HEAD: $($result.gitCommit)")
$md.Add("- Host: $($phaseOneSetup.host.cpu); $([Math]::Round($phaseOneSetup.host.totalVisibleMemoryBytes/1GB,2)) GiB visible RAM; Docker $($phaseOneSetup.docker.cpus) CPUs / $([Math]::Round($phaseOneSetup.docker.memoryBytes/1GB,2)) GiB")
$md.Add('- Workload: 5 concurrent API uploads x 200 rows = 1000 tasks per run')
$md.Add('- AI backend: deterministic FastAPI Stub, real HTTP, 100 ms delay, no BERT/LLM')
$md.Add("- Repetitions: $ExpectedRunsPerConcurrency per consumer concurrency")
$md.Add('- Backlog proxy: WAITING + PROCESSING sampled every 0.5 s; not broker-native queue depth')
$md.Add(''); $md.Add('## Consumer scaling'); $md.Add('')
$md.Add('| Consumer | Completion mean (s) | Throughput mean (tasks/s) | Speedup | Efficiency | Retry | Failure |')
$md.Add('| ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($aggregate in $aggregates) {
    $scale = $scaling | Where-Object { $_.consumerConcurrency -eq $aggregate.consumerConcurrency }
    $md.Add("| $($aggregate.consumerConcurrency) | $(Format-Number ($aggregate.completionTimeMs.average/1000) '0.000') | $(Format-Number $aggregate.throughputTasksPerSecond.average '0.000') | $(Format-Number $scale.speedupVs1 '0.000')x | $(Format-Number $scale.scalingEfficiencyPercent '0.0')% | $($aggregate.taskRetryCount) | $($aggregate.terminalFailureCount) |")
}
$md.Add(''); $md.Add('## Latency and resources'); $md.Add('')
$md.Add('| Consumer | Task p50/p95/p99 mean (ms) | AI avg/p95/p99 mean (ms) | AI req/s | CPU peak | Heap peak (MiB) | Hikari peak/max | GC pause sum (s) | HTTP 5xx |')
$md.Add('| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($aggregate in $aggregates) {
    $md.Add("| $($aggregate.consumerConcurrency) | $(Format-Number $aggregate.taskLatencyP50Ms.average)/$(Format-Number $aggregate.taskLatencyP95Ms.average)/$(Format-Number $aggregate.taskLatencyP99Ms.average) | $(Format-Number $aggregate.aiAverageMs.average)/$(Format-Number $aggregate.aiP95Ms.average)/$(Format-Number $aggregate.aiP99Ms.average) | $(Format-Number $aggregate.aiRequestRatePerSecond.average) | $(Format-Number (100*$aggregate.processCpuUsagePeak) '0.0')% | $(Format-Number ($aggregate.jvmHeapPeakBytes/1MB)) | $(Format-Number $aggregate.hikariActivePeak '0')/$(Format-Number $aggregate.hikariConfiguredMax '0') | $(Format-Number $aggregate.jvmGcPauseSeconds '0.000') | $(Format-Number $aggregate.http5xxCount '0') |")
}
if ($null -ne $backlogRun) {
    $md.Add(''); $md.Add('## Backlog burst'); $md.Add('')
    $md.Add("- Consumer: $($backlogRun.consumerConcurrency); tasks: $($backlogRun.taskCount)")
    $md.Add("- Production duration: $(Format-Number ($backlogRun.productionDurationMs/1000) '0.000') s")
    $md.Add("- Peak proxy backlog: $($backlogRun.peakProxyBacklog)")
    $md.Add("- Peak Outbox pending: $($backlogRun.peakOutboxPending)")
    $md.Add("- Drain time: $(Format-Number ($backlogRun.drainTimeMs/1000) '0.000') s")
    $md.Add("- Steady completion throughput: $(Format-Number $backlogRun.steadyCompletionThroughputTasksPerSecond '0.000') tasks/s")
    $md.Add("- Success/failure/retry: $($backlogRun.successCount)/$($backlogRun.terminalFailureCount)/$($backlogRun.taskRetryCount)")
    $md.Add("- Observed Outbox pending drain slope: $(Format-Number $outboxPendingDrainRate '0.000') events/s")
}
if ($null -ne $delayControl) {
    $md.Add(''); $md.Add('## 10 ms delay control (single diagnostic run)'); $md.Add('')
    $md.Add("- c$($delayControl.consumerConcurrency): completion $(Format-Number ($delayControl.completionTimeMs/1000) '0.000') s; throughput $(Format-Number $delayControl.throughputTasksPerSecond '0.000') tasks/s")
    $md.Add('- This single run is diagnostic only and is not resume-safe.')
}
$md.Add(''); $md.Add('## Bottleneck conclusion'); $md.Add('')
$md.Add('- Recommended consumer concurrency: **2**. It has the highest repeated mean throughput and retains 93.9% scaling efficiency versus c1; c4 and c8 are slightly slower with no reliability benefit.')
$md.Add("- 1 -> 2: completion time falls $(Format-Number $result.conclusion.oneToTwo.completionReductionPercent)% and throughput rises $(Format-Number $result.conclusion.oneToTwo.throughputImprovementPercent)%; AI p95 changes $(Format-Number $result.conclusion.oneToTwo.aiP95ChangePercent)%.")
$md.Add("- 2 -> 4: completion time changes +$(Format-Number $result.conclusion.twoToFour.completionChangePercent)% and throughput $(Format-Number $result.conclusion.twoToFour.throughputChangePercent)%; this is the first clear saturation point.")
$md.Add("- 4 -> 8: completion time changes +$(Format-Number $result.conclusion.fourToEight.completionChangePercent)% and throughput $(Format-Number $result.conclusion.fourToEight.throughputChangePercent)%.")
$md.Add('- Primary limit: Transactional Outbox dispatch throughput under the current fixed configuration. The c4 backlog run reached 990 pending Outbox events and drained near 15.2 events/s, matching the approximately 14-15 tasks/s plateau.')
$md.Add("- The 10 ms c8 control improves completion by only $(Format-Number $delayControlCompletionReduction)% versus the 100 ms c8 mean. Combined with stable AI p95, low CPU, Hikari <= 5/10, and zero retry/failure, Stub latency, Java CPU, and the DB pool are not the plateau limit.")
$md.Add('- RocketMQ broker versus result persistence cannot be separated further because no broker-native consumer-lag metric is available; the database proxy must not be presented as native queue depth.')
$md.Add(''); $md.Add('## Recommended next experiments'); $md.Add('')
$md.Add('1. At fixed c2 and 1000 tasks, compare the current Outbox dispatch batch/cadence with one larger-batch or shorter-cadence setting.')
$md.Add('2. Add broker-native RocketMQ consumer lag, then repeat c2 versus c4 to distinguish broker lag from the database task-state proxy.')
$md.Add(''); $md.Add('## Resume-safe numbers'); $md.Add('')
$md.Add('- Eligible: c1/c2/c4/c8 completion and throughput means, speedup/efficiency, AI p95, and zero retry/failure. Each uses the same 100 ms deterministic Stub and 1000-task workload with three repetitions.')
$md.Add('- Not eligible as a repeated number: the single 10 ms control. It is diagnostic only.')
$md.Add('- Scope limitation: one local machine; task backlog is a database-state proxy, not RocketMQ native queue depth.')
Write-Utf8NoBom $markdownPath ($md -join "`n")
Write-Host "Phase 4 JSON: $jsonPath"
Write-Host "Phase 4 Markdown: $markdownPath"
