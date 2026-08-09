import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

import { BASE_URL, authParams, courseIds, login, readApiData } from '../lib/api.js';
import { performanceSummary } from '../lib/summary.js';

const cacheState = (__ENV.CACHE_STATE || 'warm').toLowerCase();
const ids = courseIds();
const miss = cacheState === 'miss';
const requestCount = Number(__ENV.MISS_REQUESTS || ids.length);
const latency = new Trend('course_analytics_latency', true);
const errors = new Rate('course_analytics_http_errors');

export const options = {
  scenarios: miss
    ? {
        course_analytics_miss: {
          executor: 'shared-iterations',
          vus: 1,
          iterations: requestCount,
          maxDuration: '3m',
        },
      }
    : {
        course_analytics_warm: {
          executor: 'ramping-vus',
          startVUs: 0,
          stages: [
            { duration: __ENV.WARMUP_DURATION || '5s', target: Number(__ENV.WARMUP_VUS || '2') },
            { duration: __ENV.STEADY_DURATION || '15s', target: Number(__ENV.STEADY_VUS || '4') },
            { duration: __ENV.HIGH_DURATION || '10s', target: Number(__ENV.HIGH_VUS || '8') },
            { duration: __ENV.COOLDOWN_DURATION || '5s', target: 0 },
          ],
          gracefulRampDown: '5s',
        },
      },
  thresholds: {
    course_analytics_http_errors: ['rate<0.01'],
    course_analytics_latency: [miss ? 'p(95)<1000' : 'p(95)<350'],
  },
  summaryTrendStats: ['avg', 'min', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function analyticsUrl(courseId) {
  return `${BASE_URL}/api/courses/${courseId}/analytics/summary`;
}

export function setup() {
  const token = login();
  if (!miss) {
    for (const id of ids) {
      const response = http.get(
        analyticsUrl(id),
        authParams(token, 'course_analytics_prewarm'),
      );
      if (!check(response, { 'analytics prewarm returns 200': (item) => item.status === 200 })) {
        throw new Error(`Unable to prewarm analytics for course ${id}: HTTP ${response.status}`);
      }
    }
  }
  return { token };
}

export default function (data) {
  const index = exec.scenario.iterationInTest % ids.length;
  const response = http.get(
    analyticsUrl(ids[index]),
    authParams(data.token, 'course_analytics'),
  );
  const failed = response.status !== 200 || !readApiData(response);
  latency.add(response.timings.duration);
  errors.add(failed);
  check(response, {
    'course analytics returns 200': (item) => item.status === 200,
    'course analytics has courseId': (item) => Boolean(readApiData(item)?.courseId),
  });
  if (!miss) {
    sleep(Number(__ENV.THINK_TIME_SECONDS || '0.1'));
  }
}

export function handleSummary(data) {
  return performanceSummary(data);
}
