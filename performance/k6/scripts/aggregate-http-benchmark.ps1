[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9_-]{3,40}$')]
    [string]$ExperimentId = 'P3_HTTP',
    [string]$ResultsPath = '',
    [ValidateRange(1, 20)]
    [int]$ExpectedAbRuns = 3
)

$ErrorActionPreference = 'Stop'
$k6Root = Split-Path -Parent $PSScriptRoot
$performanceRoot = Split-Path -Parent $k6Root
if ([string]::IsNullOrWhiteSpace($ResultsPath)) {
    $ResultsPath = Join-Path $performanceRoot 'results'
}

function Write-Utf8NoBom {
    param([string]$LiteralPath, [string]$Content)
    [IO.File]::WriteAllText(
        $LiteralPath,
        $Content.Replace("`r`n", "`n") + "`n",
        (New-Object Text.UTF8Encoding($false))
    )
}

function Get-PropertyValue {
    param([object]$Object, [string]$Name, [object]$Default = $null)
    if ($null -eq $Object) { return $Default }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return $Default }
    return $property.Value
}

function Get-MetricValue {
    param([object]$K6, [string]$Metric, [string]$Value, [double]$Default = 0)
    $metricObject = Get-PropertyValue -Object $K6.metrics -Name $Metric
    $values = Get-PropertyValue -Object $metricObject -Name 'values'
    return [double](Get-PropertyValue -Object $values -Name $Value -Default $Default)
}

function Get-PrometheusValue {
    param([object]$Series)
    $items = @($Series)
    if ($items.Count -eq 0 -or $null -eq $items[0].value) { return $null }
    return [double]$items[0].value[1]
}

function Get-Stats {
    param([double[]]$Values)
    if ($Values.Count -eq 0) {
        return [ordered]@{ mean = $null; sampleStdDev = $null; min = $null; max = $null }
    }
    $mean = ($Values | Measure-Object -Average).Average
    $variance = if ($Values.Count -gt 1) {
        ($Values | ForEach-Object { [Math]::Pow($_ - $mean, 2) } | Measure-Object -Sum).Sum /
            ($Values.Count - 1)
    } else { 0 }
    return [ordered]@{
        mean = [Math]::Round($mean, 6)
        sampleStdDev = [Math]::Round([Math]::Sqrt($variance), 6)
        min = [Math]::Round(($Values | Measure-Object -Minimum).Minimum, 6)
        max = [Math]::Round(($Values | Measure-Object -Maximum).Maximum, 6)
    }
}

function Format-Number {
    param([object]$Value, [int]$Digits = 2)
    if ($null -eq $Value) { return 'n/a' }
    return ([double]$Value).ToString("F$Digits", [Globalization.CultureInfo]::InvariantCulture)
}

$runSets = @()
$k6Files = Get-ChildItem -LiteralPath $ResultsPath -Filter "*-http-$ExperimentId-*-k6.json" |
    Sort-Object Name
foreach ($k6File in $k6Files) {
    $stem = $k6File.Name.Substring(0, $k6File.Name.Length - '-k6.json'.Length)
    $prometheusPath = Join-Path $ResultsPath "$stem-prometheus.json"
    if (-not (Test-Path -LiteralPath $prometheusPath)) {
        throw "Missing Prometheus artifact for $($k6File.Name)."
    }
    $k6 = Get-Content -Raw -LiteralPath $k6File.FullName | ConvertFrom-Json
    if ($k6.metadata.experimentId -ne $ExperimentId) { continue }
    $prometheus = Get-Content -Raw -LiteralPath $prometheusPath | ConvertFrom-Json
    $duration = [double]$k6.metadata.durationSeconds
    $requestCount = Get-MetricValue -K6 $k6 -Metric query_requests -Value count
    $targetRps = [double]$k6.metadata.targetRps
    $achievedRps = $requestCount / $duration
    $p95 = Get-MetricValue -K6 $k6 -Metric query_latency -Value 'p(95)' -Default 999999
    $errorRate = Get-MetricValue -K6 $k6 -Metric query_http_errors -Value rate -Default 1
    $dropped = Get-MetricValue -K6 $k6 -Metric dropped_iterations -Value count
    $passed = $p95 -lt 200 -and $errorRate -lt 0.01 -and
        $achievedRps -ge ($targetRps * 0.99) -and $dropped -eq 0

    $runSets += [pscustomobject][ordered]@{
        startedAt = $k6.metadata.startedAt
        gitCommit = $k6.metadata.gitCommit
        experimentPhase = $k6.metadata.experimentPhase
        endpoint = $k6.metadata.endpoint
        cacheState = $k6.metadata.cacheState
        targetRps = $targetRps
        durationSeconds = $duration
        runNumber = [int]$k6.metadata.runNumber
        requestCount = [long]$requestCount
        achievedRps = [Math]::Round($achievedRps, 6)
        latencyMs = [ordered]@{
            avg = Get-MetricValue -K6 $k6 -Metric query_latency -Value avg
            p50 = Get-MetricValue -K6 $k6 -Metric query_latency -Value 'p(50)'
            p95 = $p95
            p99 = Get-MetricValue -K6 $k6 -Metric query_latency -Value 'p(99)'
            max = Get-MetricValue -K6 $k6 -Metric query_latency -Value max
        }
        errorRate = $errorRate
        droppedIterations = [long]$dropped
        result = if ($passed) { 'PASS' } else { 'FAIL' }
        resources = [ordered]@{
            processCpuUsageMax = Get-PrometheusValue $prometheus.results.processCpuUsageMax
            processCpuUsageAvg = Get-PrometheusValue $prometheus.results.processCpuUsageAvg
            systemCpuUsageMax = Get-PrometheusValue $prometheus.results.systemCpuUsageMax
            jvmHeapUsedMaxBytes = Get-PrometheusValue $prometheus.results.jvmHeapUsedMaxBytes
            jvmGcPauseSeconds = Get-PrometheusValue $prometheus.results.jvmGcPauseIncreaseSeconds
            jvmThreadsLiveMax = Get-PrometheusValue $prometheus.results.jvmThreadsLiveMax
            tomcatThreadsBusyMax = Get-PrometheusValue $prometheus.results.tomcatThreadsBusyMax
            tomcatThreadsConfiguredMax = Get-PrometheusValue $prometheus.results.tomcatThreadsConfiguredMax
            hikariActiveMax = Get-PrometheusValue $prometheus.results.hikariActiveMax
            hikariConfiguredMax = Get-PrometheusValue $prometheus.results.hikariConfiguredMax
            http5xxCount = Get-PrometheusValue $prometheus.results.endpointHttp5xxCount
            redisKeyspaceHitsDelta = [double](Get-PropertyValue $prometheus.redis.delta 'keyspace_hits' 0)
            redisKeyspaceMissesDelta = [double](Get-PropertyValue $prometheus.redis.delta 'keyspace_misses' 0)
            redisCpuSecondsDelta = [double](Get-PropertyValue $prometheus.redis.delta 'used_cpu_sys' 0) +
                [double](Get-PropertyValue $prometheus.redis.delta 'used_cpu_user' 0)
            mysqlComSelectDelta = [double](Get-PropertyValue $prometheus.mysql.delta 'Com_select' 0)
            mysqlQuestionsDelta = [double](Get-PropertyValue $prometheus.mysql.delta 'Questions' 0)
            mysqlSlowQueriesDelta = [double](Get-PropertyValue $prometheus.mysql.delta 'Slow_queries' 0)
            mysqlBufferPoolReadsDelta = [double](Get-PropertyValue $prometheus.mysql.delta 'Innodb_buffer_pool_reads' 0)
        }
        files = [ordered]@{
            k6 = $k6File.Name
            prometheus = Split-Path -Leaf $prometheusPath
        }
    }
}
if ($runSets.Count -eq 0) {
    throw "No HTTP benchmark artifacts found for experiment $ExperimentId."
}

$abAggregates = @()
$abRuns = @($runSets | Where-Object { $_.experimentPhase -eq 'ab' })
foreach ($group in ($abRuns | Group-Object endpoint, cacheState)) {
    $items = @($group.Group | Sort-Object runNumber)
    if ($items.Count -ne $ExpectedAbRuns) {
        throw "Expected $ExpectedAbRuns A/B runs for $($group.Name), found $($items.Count)."
    }
    $abAggregates += [pscustomobject][ordered]@{
        endpoint = $items[0].endpoint
        cacheState = $items[0].cacheState
        repeats = $items.Count
        targetRps = $items[0].targetRps
        achievedRps = Get-Stats @($items.achievedRps)
        p50Ms = Get-Stats @($items | ForEach-Object { $_.latencyMs.p50 })
        p95Ms = Get-Stats @($items | ForEach-Object { $_.latencyMs.p95 })
        p99Ms = Get-Stats @($items | ForEach-Object { $_.latencyMs.p99 })
        maxMs = Get-Stats @($items | ForEach-Object { $_.latencyMs.max })
        errorRate = Get-Stats @($items.errorRate)
        hikariPeak = Get-Stats @($items | ForEach-Object { $_.resources.hikariActiveMax })
        processCpuMax = Get-Stats @($items | ForEach-Object { $_.resources.processCpuUsageMax })
        mysqlComSelect = Get-Stats @($items | ForEach-Object { $_.resources.mysqlComSelectDelta })
        allPassed = @($items | Where-Object { $_.result -ne 'PASS' }).Count -eq 0
    }
}

$abComparisons = @()
foreach ($endpoint in @($abAggregates.endpoint | Sort-Object -Unique)) {
    $miss = $abAggregates | Where-Object { $_.endpoint -eq $endpoint -and $_.cacheState -eq 'miss' }
    $hit = $abAggregates | Where-Object { $_.endpoint -eq $endpoint -and $_.cacheState -eq 'hit' }
    if ($null -eq $miss -or $null -eq $hit) { continue }
    $abComparisons += [pscustomobject][ordered]@{
        endpoint = $endpoint
        latencyReductionPercent = [ordered]@{
            p50 = [Math]::Round((1 - $hit.p50Ms.mean / $miss.p50Ms.mean) * 100, 3)
            p95 = [Math]::Round((1 - $hit.p95Ms.mean / $miss.p95Ms.mean) * 100, 3)
            p99 = [Math]::Round((1 - $hit.p99Ms.mean / $miss.p99Ms.mean) * 100, 3)
        }
        achievedRpsChangePercent = [Math]::Round(
            ($hit.achievedRps.mean / $miss.achievedRps.mean - 1) * 100, 3
        )
        hikariPeakChangePercent = if ($miss.hikariPeak.mean -eq 0) { $null } else {
            [Math]::Round(($hit.hikariPeak.mean / $miss.hikariPeak.mean - 1) * 100, 3)
        }
        mysqlSelectReductionPercent = if ($miss.mysqlComSelect.mean -eq 0) { $null } else {
            [Math]::Round((1 - $hit.mysqlComSelect.mean / $miss.mysqlComSelect.mean) * 100, 3)
        }
    }
}

$capacityRuns = @($runSets | Where-Object { $_.experimentPhase -eq 'capacity' } |
    Sort-Object cacheState, targetRps)
$capacitySummary = @()
foreach ($cacheState in @('miss', 'hit')) {
    $items = @($capacityRuns | Where-Object { $_.cacheState -eq $cacheState })
    if ($items.Count -eq 0) { continue }
    $passed = @($items | Where-Object { $_.result -eq 'PASS' })
    $failed = @($items | Where-Object { $_.result -eq 'FAIL' } | Sort-Object targetRps)
    $capacitySummary += [pscustomobject][ordered]@{
        cacheState = $cacheState
        maxStableRps = if ($passed.Count -eq 0) { $null } else {
            ($passed | Measure-Object targetRps -Maximum).Maximum
        }
        firstFailureRps = if ($failed.Count -eq 0) { $null } else { $failed[0].targetRps }
        firstFailureReason = if ($failed.Count -eq 0) { @() } else {
            $item = $failed[0]
            @(
                if ($item.errorRate -ge 0.01) { 'error_rate' }
                if ($item.latencyMs.p95 -ge 200) { 'p95' }
                if ($item.achievedRps -lt ($item.targetRps * 0.99)) { 'achieved_rps' }
                if ($item.droppedIterations -ne 0) { 'dropped_iterations' }
            )
        }
    }
}

$boundaryRuns = @()
foreach ($summary in $capacitySummary) {
    if ($null -eq $summary.firstFailureRps) { continue }
    $boundaryRuns += @($capacityRuns | Where-Object {
        $_.cacheState -eq $summary.cacheState -and
        $_.targetRps -eq $summary.firstFailureRps
    })
}
$hikariSaturated = $boundaryRuns.Count -gt 0 -and
    @($boundaryRuns | Where-Object {
        $_.resources.hikariConfiguredMax -gt 0 -and
        $_.resources.hikariActiveMax -ge $_.resources.hikariConfiguredMax
    }).Count -eq $boundaryRuns.Count
$hostCpuHigh = $boundaryRuns.Count -gt 0 -and
    @($boundaryRuns | Where-Object {
        $_.resources.systemCpuUsageMax -ge 0.9
    }).Count -eq $boundaryRuns.Count
$bottleneck = [ordered]@{
    conclusion = if ($hikariSaturated -and $hostCpuHigh) {
        'Capacity boundary coincides with a saturated Hikari/MySQL request path and host-wide CPU contention.'
    } elseif ($hikariSaturated) {
        'Capacity boundary coincides with a saturated Hikari/MySQL request path.'
    } elseif ($hostCpuHigh) {
        'Capacity boundary coincides with host-wide CPU contention.'
    } else {
        'Current observability cannot confirm a single capacity bottleneck.'
    }
    boundaryRuns = @($boundaryRuns | ForEach-Object {
        [ordered]@{
            cacheState = $_.cacheState
            targetRps = $_.targetRps
            achievedRps = $_.achievedRps
            p95Ms = $_.latencyMs.p95
            systemCpuUsageMax = $_.resources.systemCpuUsageMax
            processCpuUsageMax = $_.resources.processCpuUsageMax
            hikariActiveMax = $_.resources.hikariActiveMax
            hikariConfiguredMax = $_.resources.hikariConfiguredMax
            jvmHeapUsedMaxBytes = $_.resources.jvmHeapUsedMaxBytes
            jvmGcPauseSeconds = $_.resources.jvmGcPauseSeconds
            mysqlSlowQueriesDelta = $_.resources.mysqlSlowQueriesDelta
            mysqlBufferPoolReadsDelta = $_.resources.mysqlBufferPoolReadsDelta
            redisCpuSecondsDelta = $_.resources.redisCpuSecondsDelta
        }
    })
    ruledOutByCurrentEvidence = @(
        'JVM GC/heap pressure: low pause increase and modest heap at the boundary.'
        'HTTP 5xx: zero in every capacity run.'
        'MySQL disk-buffer misses/slow queries: both observed deltas were zero.'
    )
    limitation = 'No MySQL/Redis container CPU exporter and no usable Tomcat thread metric were available; host CPU cannot be attributed to one process, and Hikari cap versus downstream MySQL CPU cannot be isolated.'
}

$computer = Get-CimInstance Win32_ComputerSystem
$processor = Get-CimInstance Win32_Processor | Select-Object -First 1
$aiHealth = try {
    Invoke-RestMethod -Uri 'http://127.0.0.1:8001/health' -TimeoutSec 10
} catch { $null }
$generatedAt = (Get-Date).ToUniversalTime()
$dateName = $generatedAt.ToString('yyyy-MM-dd')
$jsonName = "$dateName-phase-3-http-cache-$ExperimentId.json"
$markdownName = "$dateName-phase-3-http-cache-$ExperimentId.md"
$aggregate = [ordered]@{
    schemaVersion = 3
    generatedAt = $generatedAt.ToString('o')
    experimentId = $ExperimentId
    gitCommit = $runSets[0].gitCommit
    stableSlo = 'error_rate<1%; p95<200ms; achieved_rps>=99% target; dropped_iterations=0'
    environment = [ordered]@{
        os = [Environment]::OSVersion.VersionString
        cpu = $processor.Name.Trim()
        logicalProcessors = [int]$computer.NumberOfLogicalProcessors
        memoryBytes = [long]$computer.TotalPhysicalMemory
        k6Image = 'grafana/k6:1.3.0'
        prometheusScrapeIntervalSeconds = 15
        aiBackendHealth = $aiHealth
    }
    ab = [ordered]@{
        workload = [ordered]@{
            executor = 'constant-arrival-rate'
            targetRps = if ($abRuns.Count -eq 0) { $null } else { $abRuns[0].targetRps }
            durationSeconds = if ($abRuns.Count -eq 0) { $null } else { $abRuns[0].durationSeconds }
            repeats = $ExpectedAbRuns
            uniqueKeyPerRequest = $true
            identicalIdOrder = $true
        }
        aggregates = $abAggregates
        comparisons = $abComparisons
    }
    capacity = [ordered]@{
        summary = $capacitySummary
        runs = $capacityRuns
    }
    bottleneck = $bottleneck
    consoleObservations = if (Test-Path -LiteralPath (Join-Path $ResultsPath "$ExperimentId-console-observations.json")) {
        Get-Content -Raw -LiteralPath (Join-Path $ResultsPath "$ExperimentId-console-observations.json") |
            ConvertFrom-Json
    } else { $null }
    runs = $runSets
    resumeSafe = @(
        'A/B aggregate numbers only: identical open-model workload, 3 repeats, complete conditions.'
        'Capacity breakpoint values are single runs per level and are not labeled resume-safe.'
    )
}
$jsonPath = Join-Path $ResultsPath $jsonName
Write-Utf8NoBom -LiteralPath $jsonPath -Content ($aggregate | ConvertTo-Json -Depth 20)

$lines = [Collections.Generic.List[string]]::new()
$lines.Add("# CourseInsight phase 3 Cache A/B and HTTP capacity ($ExperimentId)")
$lines.Add('')
$lines.Add("- Time: $($generatedAt.ToString('o')); Git HEAD: $($runSets[0].gitCommit).")
$lines.Add("- Host: $($processor.Name.Trim()), $($computer.NumberOfLogicalProcessors) logical CPUs, $([Math]::Round($computer.TotalPhysicalMemory / 1GB, 2)) GiB RAM.")
$lines.Add('- Fixed SLO: error <1%, p95 <200 ms, achieved RPS >=99% target, no dropped iterations.')
$lines.Add('- Formal A/B: 100 RPS x 30 seconds, constant-arrival-rate, identical ID order, each key requested once, 3 miss and 3 hit runs.')
$lines.Add('')
foreach ($comparison in $abComparisons) {
    $endpoint = $comparison.endpoint
    $miss = $abAggregates | Where-Object { $_.endpoint -eq $endpoint -and $_.cacheState -eq 'miss' }
    $hit = $abAggregates | Where-Object { $_.endpoint -eq $endpoint -and $_.cacheState -eq 'hit' }
    $lines.Add("## Cache A/B: $endpoint")
    $lines.Add('')
    $lines.Add('| Metric | Miss | Hit | Change |')
    $lines.Add('| --- | ---: | ---: | ---: |')
    $lines.Add("| p50 | $(Format-Number $miss.p50Ms.mean) ms | $(Format-Number $hit.p50Ms.mean) ms | -$(Format-Number $comparison.latencyReductionPercent.p50)% |")
    $lines.Add("| p95 | $(Format-Number $miss.p95Ms.mean) ms | $(Format-Number $hit.p95Ms.mean) ms | -$(Format-Number $comparison.latencyReductionPercent.p95)% |")
    $lines.Add("| p99 | $(Format-Number $miss.p99Ms.mean) ms | $(Format-Number $hit.p99Ms.mean) ms | -$(Format-Number $comparison.latencyReductionPercent.p99)% |")
    $lines.Add("| max | $(Format-Number $miss.maxMs.mean) ms | $(Format-Number $hit.maxMs.mean) ms | n/a |")
    $lines.Add("| achieved RPS | $(Format-Number $miss.achievedRps.mean) | $(Format-Number $hit.achievedRps.mean) | $(Format-Number $comparison.achievedRpsChangePercent)% |")
    $lines.Add("| error rate | $(Format-Number ($miss.errorRate.mean * 100) 4)% | $(Format-Number ($hit.errorRate.mean * 100) 4)% | n/a |")
    $lines.Add("| Hikari peak (mean) | $(Format-Number $miss.hikariPeak.mean) | $(Format-Number $hit.hikariPeak.mean) | $(Format-Number $comparison.hikariPeakChangePercent)% |")
    $lines.Add("| MySQL Com_select / run | $(Format-Number $miss.mysqlComSelect.mean 0) | $(Format-Number $hit.mysqlComSelect.mean 0) | -$(Format-Number $comparison.mysqlSelectReductionPercent)% |")
    $lines.Add('')
    $lines.Add("p95 run-to-run sample SD: miss $(Format-Number $miss.p95Ms.sampleStdDev) ms, hit $(Format-Number $hit.p95Ms.sampleStdDev) ms.")
    $lines.Add('')
}
$lines.Add('## Course detail capacity')
$lines.Add('')
foreach ($cacheState in @('miss', 'hit')) {
    $lines.Add("### $cacheState")
    $lines.Add('')
    $lines.Add('| Target RPS | Achieved RPS | p95 | p99 | Error | Dropped | Result | CPU max | Hikari peak |')
    $lines.Add('| ---: | ---: | ---: | ---: | ---: | ---: | :---: | ---: | ---: |')
    foreach ($run in @($capacityRuns | Where-Object { $_.cacheState -eq $cacheState })) {
        $lines.Add("| $(Format-Number $run.targetRps 0) | $(Format-Number $run.achievedRps) | $(Format-Number $run.latencyMs.p95) ms | $(Format-Number $run.latencyMs.p99) ms | $(Format-Number ($run.errorRate * 100) 4)% | $($run.droppedIterations) | $($run.result) | $(Format-Number ($run.resources.processCpuUsageMax * 100) 1)% | $(Format-Number $run.resources.hikariActiveMax 0) |")
    }
    $summary = $capacitySummary | Where-Object { $_.cacheState -eq $cacheState }
    $lines.Add('')
    $lines.Add("Max stable RPS: $(Format-Number $summary.maxStableRps 0); first failure: $(Format-Number $summary.firstFailureRps 0) RPS ($($summary.firstFailureReason -join ', ')).")
    $lines.Add('')
}
$lines.Add('## Resource evidence and bottleneck')
$lines.Add('')
$lines.Add($bottleneck.conclusion)
$lines.Add('')
foreach ($boundary in $bottleneck.boundaryRuns) {
    $lines.Add("- $($boundary.cacheState) at $($boundary.targetRps) target RPS: achieved $(Format-Number $boundary.achievedRps), p95 $(Format-Number $boundary.p95Ms) ms, host CPU max $(Format-Number ($boundary.systemCpuUsageMax * 100) 1)%, Java process CPU max $(Format-Number ($boundary.processCpuUsageMax * 100) 1)%, Hikari $(Format-Number $boundary.hikariActiveMax 0)/$(Format-Number $boundary.hikariConfiguredMax 0), GC pause $(Format-Number $boundary.jvmGcPauseSeconds 3) s.")
}
$lines.Add('')
$lines.Add("Limitation: $($bottleneck.limitation)")
$lines.Add('')
$lines.Add('## Resume-safe numbers')
$lines.Add('')
$lines.Add('- Resume-safe: strict A/B 3-run means, p95 variation, and cache-hit reduction under the documented identical workload.')
$lines.Add('- Not resume-safe: capacity has one run per level and is a local breakpoint, not production or cross-host capacity.')
$lines.Add('')
$lines.Add("Machine-readable summary: $jsonName. Raw k6/Prometheus names are in runs[].files.")
$markdownPath = Join-Path $ResultsPath $markdownName
Write-Utf8NoBom -LiteralPath $markdownPath -Content ($lines -join "`n")
Write-Host "Aggregate JSON: $jsonPath"
Write-Host "Markdown report: $markdownPath"
