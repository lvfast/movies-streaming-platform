import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { API, MOVIE, PASSWORD, summary } from './common.js';

const quick = __ENV.PROFILE === 'quick';
const users = quick ? 2 : 20;
const failures = new Rate('api_errors');
const reads = new Trend('api_read_ms', true);
const operations = new Counter('api_operations');
export const options = {
  setupTimeout: '5m',
  scenarios: { api: { executor: 'constant-vus', vus: users, duration: quick ? '15s' : '10m', gracefulStop: '15s' } },
  thresholds: { api_errors: ['rate<0.01'], api_read_ms: ['p(95)<500'], api_operations: [`count>${quick ? 10 : 1000}`], checks: ['rate>0.99'] },
};

export function setup() {
  const tokens = [];
  const prefix = `load_${Date.now().toString(36)}`;
  for (let i = 0; i < users; i++) {
    // No rate limiter bypass: retry only setup throttling, never measured traffic.
    let response;
    for (let retry = 0; retry < 12; retry++) {
      response = http.post(`${API}/auth/register`, JSON.stringify({ username: `${prefix}_${i}`, password: PASSWORD }), {
        headers: { 'Content-Type': 'application/json' }, timeout: '10s', redirects: 0,
        tags: { phase: 'setup', name: 'register' },
      });
      if (response.status !== 429) break;
      sleep(7);
    }
    if (response.status !== 201) fail(`User preparation failed: status ${response.status}`);
    tokens.push(response.json().accessToken);
    if (i < users - 1) sleep(7);
  }
  return tokens;
}

export default function (tokens) {
  const headers = { Authorization: `Bearer ${tokens[__VU - 1]}`, 'Content-Type': 'application/json' };
  const tasks = [
    ['GET', '/catalog/home', null, 200],
    ['GET', '/search?q=archive&page=0&size=10', null, 200],
    ['GET', '/movies/starlight-archive', null, 200],
    ['GET', '/me/watchlist', null, 200],
    ['GET', `/movies/${MOVIE}/playback`, null, 200],
  ];
  if (__ITER % 5 === 0) {
    tasks.push(['PUT', `/me/watchlist/${MOVIE}`, null, 204]);
    tasks.push(['PUT', `/me/progress/${MOVIE}`, JSON.stringify({ positionSeconds: 1, durationSeconds: 2, clientUpdatedAt: new Date().toISOString() }), 200]);
  }
  for (const [method, path, body, expected] of tasks) {
    const response = http.request(method, `${API}${path}`, body, { headers, timeout: '10s', redirects: 0, tags: { phase: 'load', name: `${method} ${path.replace(MOVIE, ':movieId')}` } });
    const ok = check(response, { 'API status matches contract': r => r.status === expected });
    failures.add(!ok);
    operations.add(1);
    if (method === 'GET') reads.add(response.timings.duration);
  }
  sleep(1);
}

export function handleSummary(data) { return summary(data, 'load', quick ? 'quick-2vu-15s-not-acceptance' : 'acceptance-20vu-10m'); }
