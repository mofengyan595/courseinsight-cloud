import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { SharedArray } from 'k6/data';

import { BASE_URL, authParams, login, readApiData, requiredEnv } from '../lib/api.js';

const endpoint = (requiredEnv('ENDPOINT') || '').toLowerCase();
if (!['course-detail', 'course-analytics'].includes(endpoint)) {
  fail('ENDPOINT must be course-detail or course-analytics');
}
const context = JSON.parse(open(requiredEnv('COURSE_CONTEXT_FILE')));
const ids = new SharedArray('http benchmark prewarm IDs', () =>
  context.courseIds.map((value) => Number(value)),
);
const iterations = Number(requiredEnv('PREWARM_ITERATIONS'));

export const options = {
  scenarios: {
    cache_prewarm: {
      executor: 'shared-iterations',
      vus: Number(__ENV.PREWARM_VUS || '20'),
      iterations,
      maxDuration: __ENV.PREWARM_MAX_DURATION || '10m',
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
  },
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
    fail(`Prewarm fixture exhausted at iteration ${index}`);
  }
  const response = http.get(
    queryUrl(ids[index]),
    authParams(data.token, `${endpoint}_prewarm`),
  );
  const responseData = readApiData(response);
  const expectedId = endpoint === 'course-analytics'
    ? responseData?.courseId
    : responseData?.id;
  check(response, {
    'prewarm returns 200': (item) => item.status === 200,
    'prewarm returns requested course': () => Number(expectedId) === ids[index],
  });
}
