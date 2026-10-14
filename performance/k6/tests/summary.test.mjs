import assert from 'node:assert/strict';
import test from 'node:test';
import { performanceSummary } from '../lib/summary.js';

test('HTTP cache summary excludes setup login and uses the configured query window', () => {
  globalThis.__ENV = { DURATION_SECONDS: '30', SCENARIO: 'http-cache-open' };
  const metrics = {
    http_reqs: { values: { count: 3001, rate: 75 } },
    http_req_duration: { values: { 'p(95)': 999 } },
    http_req_failed: { values: { rate: 0.1 } },
    query_requests: { values: { count: 3000, rate: 74.9 } },
    query_latency: { values: { 'p(95)': 12.34 } },
    query_http_errors: { values: { rate: 0 } },
  };
  const output = performanceSummary({ metrics }).stdout;
  assert.match(output, /metric scope:  query only/);
  assert.match(output, /request count: 3000/);
  assert.match(output, /request rate:  100\.00 req\/s/);
  assert.match(output, /HTTP p95:      12\.34 ms/);
  assert.match(output, /HTTP errors:   0\.0000%/);
});

test('other scenarios keep all-HTTP metrics and missing errors remain unavailable', () => {
  globalThis.__ENV = {};
  const output = performanceSummary({ metrics: {
    http_reqs: { values: { count: 3, rate: 1.5 } },
    http_req_duration: { values: { 'p(95)': 40 } },
  } }).stdout;
  assert.match(output, /metric scope:  all HTTP requests/);
  assert.match(output, /request rate:  1\.50 req\/s/);
  assert.match(output, /HTTP errors:   n\/a%/);
});

test('query RPS is unavailable when the measurement window is missing', () => {
  globalThis.__ENV = {};
  const output = performanceSummary({ metrics: {
    query_requests: { values: { count: 3000, rate: 75 } },
  } }).stdout;
  assert.match(output, /request rate:  n\/a req\/s/);
});
