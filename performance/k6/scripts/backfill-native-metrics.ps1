[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(5, 20)]
    [int]$ExperimentPhase,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{1,40}$')]
    [string]$ExperimentId,
    [string]$ResultsPath = '',
    [string]$PrometheusUrl = 'http://127.0.0.1:9090'
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
        $Content.Replace("`r`n", "`n").TrimEnd() + "`n",
        (New-Object Text.UTF8Encoding($false))
    )
}

function Invoke-PrometheusRangeQuery {
    param([string]$Query, [long]$Start, [long]$End)
    $encoded = [Uri]::EscapeDataString($Query)
    $response = Invoke-RestMethod `
        -Uri "$PrometheusUrl/api/v1/query_range?query=$encoded&start=$Start&end=$End&step=1" `
        -TimeoutSec 30
    if ($response.status -ne 'success') {
        throw "Prometheus range query failed: $Query"
    }
    @($response.data.result)
}

$targetResponse = Invoke-RestMethod -Uri "$PrometheusUrl/api/v1/targets" -TimeoutSec 20
$rocketMqTarget = @($targetResponse.data.activeTargets | Where-Object {
    $_.labels.job -eq 'rocketmq-broker' -and $_.health -eq 'up'
})
if ($rocketMqTarget.Count -eq 0) {
    throw 'Prometheus rocketmq-broker target is not UP.'
}

$nativeQueries = [ordered]@{
    readyMessages = 'sum(rocketmq_consumer_ready_messages{topic="courseinsight-analysis",consumer_group="courseinsight-analysis-consumer"})'
    inflightMessages = 'sum(rocketmq_consumer_inflight_messages{topic="courseinsight-analysis",consumer_group="courseinsight-analysis-consumer"})'
    lagMessages = 'sum(rocketmq_consumer_lag_messages{topic="courseinsight-analysis",consumer_group="courseinsight-analysis-consumer"})'
    queueingLatencyMs = 'max(rocketmq_consumer_queueing_latency{topic="courseinsight-analysis",consumer_group="courseinsight-analysis-consumer"})'
    lagLatencyMs = 'max(rocketmq_consumer_lag_latency{topic="courseinsight-analysis",consumer_group="courseinsight-analysis-consumer"})'
    messagesInPerSecond = 'sum(rate(rocketmq_messages_in_total{topic="courseinsight-analysis"}[5s]))'
    messagesOutPerSecond = 'sum(rate(rocketmq_messages_out_total{topic="courseinsight-analysis",consumer_group="courseinsight-analysis-consumer"}[5s]))'
    retryReadyMessages = 'sum(rocketmq_consumer_ready_messages{consumer_group="courseinsight-analysis-consumer",is_retry="true"})'
    dlqMessagesTotal = 'sum(rocketmq_send_to_dlq_messages_total{consumer_group="courseinsight-analysis-consumer"})'
}

$outcomeFiles = @(Get-ChildItem -LiteralPath $ResultsPath -File `
    -Filter "*-phase$ExperimentPhase-$ExperimentId-*-outcome.json" | Sort-Object Name)
if ($outcomeFiles.Count -eq 0) {
    throw "No Phase $ExperimentPhase outcomes found for $ExperimentId."
}

foreach ($outcomeFile in $outcomeFiles) {
    $stem = $outcomeFile.Name.Substring(0, $outcomeFile.Name.Length - '-outcome.json'.Length)
    $prometheusPath = Join-Path $ResultsPath "$stem-prometheus.json"
    if (-not (Test-Path -LiteralPath $prometheusPath)) {
        throw "Missing Prometheus artifact: $prometheusPath"
    }
    $prometheus = Get-Content -Raw -LiteralPath $prometheusPath | ConvertFrom-Json
    $outcome = Get-Content -Raw -LiteralPath $outcomeFile.FullName | ConvertFrom-Json
    $start = [DateTimeOffset]::Parse([string]$prometheus.testStartedAt)
    $end = [DateTimeOffset]::Parse([string]$prometheus.observedAt)
    $startEpoch = [Math]::Floor($start.ToUnixTimeMilliseconds() / 1000)
    $endEpoch = [Math]::Floor($end.ToUnixTimeMilliseconds() / 1000)
    $nativeResults = [ordered]@{}
    foreach ($entry in $nativeQueries.GetEnumerator()) {
        $nativeResults[$entry.Key] = Invoke-PrometheusRangeQuery `
            -Query $entry.Value -Start $startEpoch -End $endEpoch
    }
    $nativeName = "$stem-rocketmq-native.json"
    $nativeArtifact = [ordered]@{
        schemaVersion = 1
        gitCommit = [string]$outcome.gitCommit
        experimentId = [string]$outcome.experimentId
        configurationLabel = [string]$outcome.configurationLabel
        scenario = [string]$outcome.scenario
        runNumber = [int]$outcome.runNumber
        consumerConcurrency = [int]$outcome.consumerConcurrency
        outboxBatchSize = [int]$outcome.outboxBatchSize
        outboxPublishIntervalMs = [int]$outcome.outboxPublishIntervalMs
        scrapeIntervalSeconds = 1
        startEpochSeconds = $startEpoch
        endEpochSeconds = $endEpoch
        source = 'RocketMQ 5.3.2 built-in PROM exporter on broker port 5557'
        collection = 'backfilled from retained Prometheus samples using the original run window'
        queries = $nativeQueries
        results = $nativeResults
    }
    Write-Utf8NoBom (Join-Path $ResultsPath $nativeName) `
        ($nativeArtifact | ConvertTo-Json -Depth 30)
    if ($null -eq $outcome.artifacts) {
        $outcome | Add-Member -NotePropertyName artifacts -NotePropertyValue ([pscustomobject]@{})
    }
    $outcome.artifacts | Add-Member -NotePropertyName rocketMqNative `
        -NotePropertyValue $nativeName -Force
    Write-Utf8NoBom $outcomeFile.FullName ($outcome | ConvertTo-Json -Depth 12)
    Write-Host "RocketMQ native: $(Join-Path $ResultsPath $nativeName)"
}
