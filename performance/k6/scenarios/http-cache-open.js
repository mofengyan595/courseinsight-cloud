import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

import { BASE_URL, authParams, login, readApiData, requiredEnv } from '../lib/api.js';
import { performanceSummary } from '../lib/summary.js';

const endpoint = (requiredEnv('ENDPOINT') || '').toLowerCase();
if (!['course-detail', 'course-analytics'].includes(endpoint)) {
  fail('ENDPOINT must be course-detail or course-analytics');
}

const contextPath = requiredEnv('COURSE_CONTEXT_FILE');
const context = JSON.parse(open(contextPath));
const ids = new SharedArray('http benchmark course IDs', () =>
  context.courseIds.map((value) => Number(value)),
);
const targetRps = Number(requiredEnv('TARGET_RPS'));
const duration = requiredEnv('DURATION');
const preAllocatedVUs = Number(__ENV.PRE_ALLOCATED_VUS || '100');
const maxVUs = Number(__ENV.MAX_VUS || '500');

const queryLatency = new Trend('query_latency', true);
const queryErrors = new Rate('query_http_errors');
const queryRequests = new Counter('query_requests');

export const options = {
  scenarios: {
    http_query: {
      executor: 'constant-arrival-rate',
      rate: targetRps,
      timeUnit: '1s',
      duration,
      preAllocatedVUs,
      maxVUs,
      gracefulStop: '10s',
    },
  },
  thresholds: {
    query_http_errors: ['rate<0.01'],
    query_latency: ['p(95)<200'],
    dropped_iterations: ['count==0'],
  },
  summaryTrendStats: ['avg', 'min', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function queryUrl(courseId) {
  if (endpoint === 'course-analytics') {
    return `${BASE_URL}/api/courses/${courseId}/analytics/summary`;
  }
  return `${BASE_URL}/api/courses/${courseId}`;
}

export function setup() {
  return { token: login() };
}

export default function (data) {
  const index = exec.scenario.iterationInTest;
  if (index >= ids.length) {
    fail(`Course ID fixture exhausted at iteration ${index}; available=${ids.length}`);
  }

  const response = http.get(
    queryUrl(ids[index]),
    authParams(data.token, endpoint),
  );
  const responseData = readApiData(response);
  const expectedId = endpoint === 'course-analytics'
    ? responseData?.courseId
    : responseData?.id;
  const failed = response.status !== 200 || Number(expectedId) !== ids[index];

  queryRequests.add(1);
  queryLatency.add(response.timings.duration);
  queryErrors.add(failed);
  check(response, {
    'query returns 200': (item) => item.status === 200,
    'query returns the requested course': () => !failed,
  });
}

export function handleSummary(data) {
  return performanceSummary(data);
}
