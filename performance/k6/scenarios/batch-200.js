import http from 'k6/http';
import { check, sleep } from 'k6';
import { Gauge, Rate, Trend } from 'k6/metrics';

import { BASE_URL, authParams, login, readApiData, requiredEnv } from '../lib/api.js';
import { performanceSummary } from '../lib/summary.js';

const fixture = open('../data/batch-200.csv', 'b');
const pollInterval = Number(__ENV.POLL_INTERVAL_SECONDS || '1');
const timeoutSeconds = Number(__ENV.BATCH_TIMEOUT_SECONDS || '1200');
const createLatency = new Trend('batch_create_latency', true);
const completionTime = new Trend('batch_total_completion_time', true);
const pollLatency = new Trend('batch_progress_latency', true);
const createErrors = new Rate('batch_create_http_errors');
const pollErrors = new Rate('batch_progress_http_errors');
const successCount = new Gauge('batch_success_count');
const terminalFailureCount = new Gauge('batch_terminal_failure_count');
const maxRetryingCount = new Gauge('batch_max_retrying_count');

export const options = {
  scenarios: {
    batch_200: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: `${timeoutSeconds + 60}s`,
    },
  },
  thresholds: {
    batch_create_http_errors: ['rate<0.01'],
    batch_progress_http_errors: ['rate<0.01'],
    batch_create_latency: ['p(95)<5000'],
    batch_total_completion_time: ['p(95)<1200000'],
  },
  summaryTrendStats: ['avg', 'min', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { token: login() };
}

export default function (data) {
  const courseId = Number(requiredEnv('PRIMARY_COURSE_ID'));
  const startedAt = Date.now();
  const createResponse = http.post(
    `${BASE_URL}/api/courses/${courseId}/analysis-batches`,
    { file: http.file(fixture, 'batch-200.csv', 'text/csv') },
    authParams(data.token, 'batch_create'),
  );
  createLatency.add(createResponse.timings.duration);
  const created = createResponse.status === 201 ? readApiData(createResponse) : null;
  createErrors.add(!created || created.totalCount !== 200);
  const createValid = check(createResponse, {
    'batch create returns 201': (item) => item.status === 201,
    'batch contains 200 rows': () => created?.totalCount === 200,
  });
  if (!createValid) {
    throw new Error(`Batch creation failed with HTTP ${createResponse.status}`);
  }

  const deadline = startedAt + timeoutSeconds * 1000;
  let maxRetrying = 0;
  let finalProgress = null;
  while (Date.now() < deadline) {
    sleep(pollInterval);
    const progressResponse = http.get(
      `${BASE_URL}/api/analysis-batches/${created.batchId}`,
      authParams(data.token, 'batch_progress'),
    );
    pollLatency.add(progressResponse.timings.duration);
    const progress = progressResponse.status === 200 ? readApiData(progressResponse) : null;
    pollErrors.add(!progress);
    check(progressResponse, {
      'batch progress returns 200': (item) => item.status === 200,
      'batch progress is readable': () => Boolean(progress),
    });
    if (!progress) {
      continue;
    }
    maxRetrying = Math.max(maxRetrying, Number(progress.retryingCount || 0));
    if (['COMPLETED', 'PARTIAL_FAILED', 'FAILED'].includes(progress.status)) {
      finalProgress = progress;
      break;
    }
  }

  if (!finalProgress) {
    terminalFailureCount.add(200);
    throw new Error(`Batch ${created.batchId} did not finish within ${timeoutSeconds}s`);
  }

  const elapsed = Date.now() - startedAt;
  completionTime.add(elapsed);
  successCount.add(finalProgress.successCount);
  terminalFailureCount.add(finalProgress.failedCount);
  maxRetryingCount.add(maxRetrying);
  check(finalProgress, {
    'all batch rows reached terminal state': (progress) =>
      progress.successCount + progress.failedCount === 200,
  });

  console.log(`COURSEINSIGHT_BATCH_RESULT=${JSON.stringify({
    batchId: created.batchId,
    batchNo: created.batchNo,
    courseId,
    createLatencyMs: createResponse.timings.duration,
    totalCompletionMs: elapsed,
    status: finalProgress.status,
    successCount: finalProgress.successCount,
    terminalFailureCount: finalProgress.failedCount,
    maxRetryingCountObserved: maxRetrying,
    completedAt: finalProgress.completedAt,
  })}`);
}

export function handleSummary(data) {
  return performanceSummary(data);
}
