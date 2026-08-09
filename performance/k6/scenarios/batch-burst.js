import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Gauge, Rate, Trend } from 'k6/metrics';

import { BASE_URL, authParams, readApiData, requiredEnv } from '../lib/api.js';
import { performanceSummary } from '../lib/summary.js';

const fixture = open('../data/batch-200.csv', 'b');
const context = JSON.parse(open(__ENV.PHASE4_CONTEXT_FILE || '/results/phase-4-runtime-context.json'));
const pollInterval = Number(__ENV.POLL_INTERVAL_SECONDS || '0.5');
const timeoutSeconds = Number(__ENV.BATCH_TIMEOUT_SECONDS || '1200');
const expectedBatches = Number(__ENV.BATCH_COUNT || '5');
const expectedTasks = Number(__ENV.TASK_COUNT || '1000');

const createLatency = new Trend('burst_batch_create_latency', true);
const productionDuration = new Trend('burst_production_duration', true);
const completionTime = new Trend('burst_total_completion_time', true);
const drainTime = new Trend('burst_backlog_drain_time', true);
const pollLatency = new Trend('burst_progress_latency', true);
const createErrors = new Rate('burst_create_http_errors');
const pollErrors = new Rate('burst_progress_http_errors');
const peakBacklog = new Gauge('burst_peak_proxy_backlog');
const successCount = new Gauge('burst_success_count');
const terminalFailureCount = new Gauge('burst_terminal_failure_count');
const maxRetryingCount = new Gauge('burst_max_retrying_count');

export const options = {
  scenarios: {
    batch_burst: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: `${timeoutSeconds + 90}s`,
    },
  },
  thresholds: {
    burst_create_http_errors: ['rate<0.01'],
    burst_progress_http_errors: ['rate<0.01'],
    burst_batch_create_latency: ['p(95)<15000'],
    burst_total_completion_time: ['p(95)<1200000'],
  },
  summaryTrendStats: ['avg', 'min', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function login(principal) {
  const response = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ username: principal.username, password: requiredEnv('PERF_PASSWORD') }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'auth_login' } },
  );
  const token = readApiData(response)?.accessToken;
  const valid = check(response, {
    'login returns 200': (item) => item.status === 200,
    'login returns JWT': () => Boolean(token),
  });
  if (!valid) {
    fail(`Login failed for isolated principal with HTTP ${response.status}`);
  }
  return token;
}

export function setup() {
  const principals = context.principals || [];
  if (principals.length !== expectedBatches) {
    fail(`Expected ${expectedBatches} principals, got ${principals.length}`);
  }
  return {
    principals: principals.map((principal) => ({
      username: principal.username,
      courseId: Number(principal.courseId),
      token: login(principal),
    })),
  };
}

export default function (data) {
  const startedAtMs = Date.now();
  const createRequests = data.principals.map((principal) => ({
    method: 'POST',
    url: `${BASE_URL}/api/courses/${principal.courseId}/analysis-batches`,
    body: { file: http.file(fixture, 'batch-200.csv', 'text/csv') },
    params: authParams(principal.token, 'batch_create'),
  }));
  const createResponses = http.batch(createRequests);
  const productionFinishedAtMs = Date.now();
  productionDuration.add(productionFinishedAtMs - startedAtMs);

  const batches = [];
  for (let index = 0; index < createResponses.length; index += 1) {
    const response = createResponses[index];
    const created = response.status === 201 ? readApiData(response) : null;
    createLatency.add(response.timings.duration);
    createErrors.add(!created || created.totalCount !== 200);
    check(response, {
      'batch create returns 201': (item) => item.status === 201,
      'batch contains 200 rows': () => created?.totalCount === 200,
    });
    if (!created || created.totalCount !== 200) {
      fail(`Burst batch ${index + 1} creation failed with HTTP ${response.status}`);
    }
    batches.push({
      batchId: Number(created.batchId),
      batchNo: created.batchNo,
      courseId: data.principals[index].courseId,
      token: data.principals[index].token,
      createLatencyMs: response.timings.duration,
    });
  }

  const deadline = startedAtMs + timeoutSeconds * 1000;
  let finalProgress = null;
  let observedPeakBacklog = 0;
  let maxRetrying = 0;
  while (Date.now() < deadline) {
    sleep(pollInterval);
    const responses = http.batch(batches.map((batch) => ({
      method: 'GET',
      url: `${BASE_URL}/api/analysis-batches/${batch.batchId}`,
      params: authParams(batch.token, 'batch_progress'),
    })));
    const progressItems = [];
    for (const response of responses) {
      pollLatency.add(response.timings.duration);
      const progress = response.status === 200 ? readApiData(response) : null;
      pollErrors.add(!progress);
      check(response, { 'batch progress returns 200': (item) => item.status === 200 });
      if (progress) progressItems.push(progress);
    }
    if (progressItems.length !== batches.length) continue;

    const aggregate = progressItems.reduce((result, item) => ({
      waiting: result.waiting + Number(item.waitingCount || 0),
      processing: result.processing + Number(item.processingCount || 0),
      retrying: result.retrying + Number(item.retryingCount || 0),
      success: result.success + Number(item.successCount || 0),
      failed: result.failed + Number(item.failedCount || 0),
      terminal: result.terminal + (['COMPLETED', 'PARTIAL_FAILED', 'FAILED'].includes(item.status) ? 1 : 0),
    }), { waiting: 0, processing: 0, retrying: 0, success: 0, failed: 0, terminal: 0 });
    observedPeakBacklog = Math.max(observedPeakBacklog, aggregate.waiting + aggregate.processing);
    maxRetrying = Math.max(maxRetrying, aggregate.retrying);
    if (aggregate.terminal === batches.length) {
      finalProgress = aggregate;
      break;
    }
  }

  if (!finalProgress) {
    terminalFailureCount.add(expectedTasks);
    fail(`Burst did not finish within ${timeoutSeconds}s`);
  }
  const finishedAtMs = Date.now();
  const totalCompletionMs = finishedAtMs - startedAtMs;
  const drainMs = finishedAtMs - productionFinishedAtMs;
  completionTime.add(totalCompletionMs);
  drainTime.add(drainMs);
  peakBacklog.add(observedPeakBacklog);
  successCount.add(finalProgress.success);
  terminalFailureCount.add(finalProgress.failed);
  maxRetryingCount.add(maxRetrying);
  check(finalProgress, {
    'all burst tasks terminal': (progress) => progress.success + progress.failed === expectedTasks,
  });

  console.log(`COURSEINSIGHT_PHASE4_RESULT=${JSON.stringify({
    batchIds: batches.map((item) => item.batchId),
    courseIds: batches.map((item) => item.courseId),
    taskCount: expectedTasks,
    productionDurationMs: productionFinishedAtMs - startedAtMs,
    createLatencyMs: batches.map((item) => item.createLatencyMs),
    totalCompletionMs,
    drainTimeMs: drainMs,
    peakProxyBacklogObservedByApi: observedPeakBacklog,
    successCount: finalProgress.success,
    terminalFailureCount: finalProgress.failed,
    maxRetryingCountObserved: maxRetrying,
  })}`);
}

export function handleSummary(data) {
  return performanceSummary(data);
}
