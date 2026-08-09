import http from 'k6/http';
import { check, fail } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';

export function requiredEnv(name) {
  const value = __ENV[name];
  if (!value) {
    fail(`Missing required environment variable: ${name}`);
  }
  return value;
}

export function login() {
  const response = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({
      username: requiredEnv('PERF_USERNAME'),
      password: requiredEnv('PERF_PASSWORD'),
    }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'auth_login' },
    },
  );

  const valid = check(response, {
    'login returns 200': (item) => item.status === 200,
    'login returns a JWT': (item) => Boolean(readApiData(item)?.accessToken),
  });
  if (!valid) {
    fail(`Login failed with HTTP ${response.status}`);
  }
  return readApiData(response).accessToken;
}

export function authParams(token, name) {
  return {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name },
  };
}

export function readApiData(response) {
  try {
    const body = response.json();
    return body && body.code === 0 ? body.data : null;
  } catch (error) {
    return null;
  }
}

export function courseIds() {
  const ids = requiredEnv('COURSE_IDS')
    .split(',')
    .map((value) => Number(value.trim()))
    .filter((value) => Number.isSafeInteger(value) && value > 0);
  if (ids.length === 0) {
    fail('COURSE_IDS must contain at least one positive integer');
  }
  return ids;
}
