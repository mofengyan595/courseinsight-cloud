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
  const requests = metricValues(data, 'http_reqs');
  const duration = metricValues(data, 'http_req_duration');
  const failed = metricValues(data, 'http_req_failed');
  const lines = [
    '',
    `CourseInsight performance result: ${metadata.scenario}`,
    `  request count: ${formatNumber(value(requests, 'count'), 0)}`,
    `  request rate:  ${formatNumber(value(requests, 'rate'))} req/s`,
    `  HTTP p50:      ${formatNumber(value(duration, 'p(50)'))} ms`,
    `  HTTP p95:      ${formatNumber(value(duration, 'p(95)'))} ms`,
    `  HTTP p99:      ${formatNumber(value(duration, 'p(99)'))} ms`,
    `  HTTP errors:   ${formatNumber((value(failed, 'rate') || 0) * 100, 4)}%`,
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
