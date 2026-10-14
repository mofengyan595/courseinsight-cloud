function metricValues(data, name) {
  return data.metrics[name]?.values || {};
}

function value(values, name) {
  const current = values[name];
  return typeof current === 'number' ? current : null;
}

function formatNumber(current, digits = 2) {
  return current === null || current === undefined
    ? 'n/a'
    : Number(current).toFixed(digits);
}

function buildConsoleSummary(data, metadata) {
  const queryOnly = Boolean(data.metrics.query_requests);
  const requests = metricValues(data, queryOnly ? 'query_requests' : 'http_reqs');
  const duration = metricValues(data, queryOnly ? 'query_latency' : 'http_req_duration');
  const failed = metricValues(data, queryOnly ? 'query_http_errors' : 'http_req_failed');
  const requestCount = value(requests, 'count');
  const requestRate = queryOnly
    ? (requestCount !== null && metadata.durationSeconds > 0
      ? requestCount / metadata.durationSeconds : null)
    : value(requests, 'rate');
  const errorRate = value(failed, 'rate');
  const lines = [
    '',
    `CourseInsight performance result: ${metadata.scenario}`,
    `  metric scope:  ${queryOnly ? 'query only; count / configured duration' : 'all HTTP requests'}`,
    `  request count: ${formatNumber(requestCount, 0)}`,
    `  request rate:  ${formatNumber(requestRate)} req/s`,
    `  HTTP p50:      ${formatNumber(value(duration, 'p(50)'))} ms`,
    `  HTTP p95:      ${formatNumber(value(duration, 'p(95)'))} ms`,
    `  HTTP p99:      ${formatNumber(value(duration, 'p(99)'))} ms`,
    `  HTTP errors:   ${formatNumber(errorRate === null ? null : errorRate * 100, 4)}%`,
    `  result file:   ${__ENV.RESULT_FILE || 'not configured'}`,
    '',
  ];
  return lines.join('\n');
}

export function performanceSummary(data) {
  const metadata = {
    schemaVersion: 1,
    startedAt: __ENV.TEST_STARTED_AT || null,
    finishedAt: new Date().toISOString(),
    gitCommit: __ENV.GIT_COMMIT || null,
    scenario: __ENV.SCENARIO || null,
    cacheState: __ENV.CACHE_STATE || null,
    k6Image: __ENV.K6_IMAGE || null,
    k6ImageDigest: __ENV.K6_IMAGE_DIGEST || null,
    baseUrl: __ENV.BASE_URL || null,
    consumerConcurrency: Number(__ENV.CONSUMER_CONCURRENCY || '1'),
    aiBackendState: __ENV.AI_BACKEND_STATE || null,
    virtualUserModel: __ENV.VU_MODEL || null,
    experimentId: __ENV.EXPERIMENT_ID || null,
    experimentPhase: __ENV.EXPERIMENT_PHASE || null,
    endpoint: __ENV.ENDPOINT || null,
    targetRps: Number(__ENV.TARGET_RPS || '0') || null,
    durationSeconds: Number(__ENV.DURATION_SECONDS || '0') || null,
    runNumber: Number(__ENV.RUN_NUMBER || '0') || null,
    stableSlo: __ENV.STABLE_SLO || null,
    taskCount: Number(__ENV.TASK_COUNT || '0') || null,
    batchCount: Number(__ENV.BATCH_COUNT || '0') || null,
    aiStubDelayMs: Number(__ENV.AI_STUB_DELAY_MS || '0'),
    configurationLabel: __ENV.CONFIGURATION_LABEL || null,
    outboxBatchSize: Number(__ENV.OUTBOX_BATCH_SIZE || '0') || null,
    outboxPublishIntervalMs: Number(__ENV.OUTBOX_PUBLISH_INTERVAL_MS || '0') || null,
    outboxPublishConcurrency: Number(__ENV.OUTBOX_PUBLISH_CONCURRENCY || '0') || null,
  };
  const result = {
    metadata,
    metrics: data.metrics,
    thresholds: Object.fromEntries(
      Object.entries(data.metrics)
        .filter(([, metric]) => metric.thresholds)
        .map(([name, metric]) => [name, metric.thresholds]),
    ),
  };
  const outputs = {
    stdout: buildConsoleSummary(data, metadata),
  };
  if (__ENV.RESULT_FILE) {
    outputs[__ENV.RESULT_FILE] = JSON.stringify(result, null, 2);
  }
  return outputs;
}
