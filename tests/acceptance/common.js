import http from 'k6/http';
import { check, fail } from 'k6';

export const ORIGIN = 'http://frontend:8080';
export const API = `${ORIGIN}/api/v1`;
export const MOVIE = '00000000-0000-0000-0000-000000000001';
export const PASSWORD = 'LocalAcceptanceOnly9X';

export function requireThat(value, label) {
  if (!check(value, { [label]: (v) => Boolean(v) })) fail(label);
}

export function request(method, path, expected, body = null, token = null, extra = {}) {
  const response = http.request(method, `${API}${path}`, body === null ? null : JSON.stringify(body), {
    redirects: 0, timeout: '10s',
    ...extra,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...extra.headers },
    responseCallback: http.expectedStatuses(expected),
    tags: { name: `${method} ${path.replace(MOVIE, ':movieId')}`, ...extra.tags },
  });
  requireThat(response.status === expected, `${method} ${path}: expected ${expected}`);
  return response;
}

export function summary(data, file, profile) {
  // k6 summary contains aggregate metrics/check names, never response bodies or credentials.
  const report = { profile, metrics: data.metrics, checks: data.root_group, durationMs: data.state.testRunDurationMs };
  return { [`/reports/${file}.json`]: JSON.stringify(report, null, 2), stdout: `Completed ${profile}; aggregate metrics written to ${file}.json\n` };
}
