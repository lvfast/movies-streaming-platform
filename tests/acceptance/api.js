import http from 'k6/http';
import { API, ORIGIN, MOVIE, PASSWORD, request, requireThat, summary } from './common.js';

export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'], http_req_failed: ['rate==0'], iterations: ['count==1'] } };

export default function () {
  const suffix = Date.now().toString(36);
  const credentials = { username: `e2e_${suffix}`, password: PASSWORD };
  const cookie = 'refresh_token';
  const jar = http.cookieJar();
  const home = request('GET', '/catalog/home', 200).json();
  requireThat(home.rails.length > 0, 'catalog has rails');
  request('GET', '/search?q=&page=0&size=20', 400);
  requireThat(request('GET', '/search?q=starlight&page=0&size=5', 200).json().items.some(m => m.id === MOVIE), 'search finds movie');
  requireThat(request('GET', '/movies/starlight-archive', 200).json().id === MOVIE, 'details resolve slug');
  request('GET', '/me/watchlist', 401);
  const invalid = request('GET', '/search?page=bad', 400, null, null, { headers: { 'X-Request-Id': 'acceptance-invalid-page' } });
  requireThat(invalid.headers['Content-Type'].includes('application/problem+json') && invalid.json().requestId === 'acceptance-invalid-page', 'Problem Details preserves request ID');

  const registered = request('POST', '/auth/register', 201, credentials);
  requireThat(Boolean(registered.cookies[cookie]?.[0]?.http_only), 'refresh cookie is HttpOnly');
  requireThat(registered.headers['Set-Cookie'].includes('SameSite=Strict'), 'refresh cookie is SameSite Strict');
  let token = registered.json().accessToken;
  requireThat(request('GET', '/auth/me', 200, null, token).json().username === credentials.username, 'registered user identity');
  request('POST', '/auth/logout', 204);
  jar.clear(ORIGIN);
  request('POST', '/auth/login', 401, { ...credentials, password: 'WrongPassword9X' });
  token = request('POST', '/auth/login', 200, credentials).json().accessToken;
  const oldCookie = jar.cookiesForURL(API)[cookie][0];
  token = request('POST', '/auth/refresh', 200).json().accessToken;
  const rotatedCookie = jar.cookiesForURL(API)[cookie][0];
  requireThat(oldCookie !== rotatedCookie, 'refresh rotates cookie');

  for (let i = 0; i < 2; i++) request('PUT', `/me/watchlist/${MOVIE}`, 204, null, token);
  const list = request('GET', '/me/watchlist', 200, null, token).json();
  requireThat(list.total === 1 && list.items[0].id === MOVIE, 'watchlist addition is idempotent');
  const initialPlayback = request('GET', `/movies/${MOVIE}/playback`, 200, null, token).json();
  requireThat(initialPlayback.resumePositionSeconds === 0, 'initial resume is zero');
  requireThat(initialPlayback.manifestUrl === '/media/fixtures/starlight-archive/index.m3u8', 'playback uses same-origin local fixture');
  const time = Date.now();
  const progress = { positionSeconds: 1, durationSeconds: 2, clientUpdatedAt: new Date(time).toISOString() };
  requireThat(!request('PUT', `/me/progress/${MOVIE}`, 200, progress, token).json().completed, 'partial viewing not completed');
  requireThat(request('GET', `/movies/${MOVIE}/playback`, 200, null, token).json().resumePositionSeconds === 1, 'resume persists');
  request('PUT', `/me/progress/${MOVIE}`, 200, { ...progress, positionSeconds: 0, clientUpdatedAt: new Date(time - 1000).toISOString() }, token);
  requireThat(request('GET', `/movies/${MOVIE}/playback`, 200, null, token).json().resumePositionSeconds === 1, 'stale progress cannot overwrite');
  const badProgress = request('PUT', `/me/progress/${MOVIE}`, 400, { ...progress, positionSeconds: 3 }, token).json();
  requireThat(badProgress.code === 'VALIDATION_FAILED', 'invalid progress rejected');
  // A second account must not see the first account's library or progress.
  const second = request('POST', '/auth/register', 201, { username: `other_${suffix}`, password: PASSWORD }).json().accessToken;
  requireThat(request('GET', '/me/watchlist', 200, null, second).json().total === 0, 'watchlist isolation');
  requireThat(request('GET', `/movies/${MOVIE}/playback`, 200, null, second).json().resumePositionSeconds === 0, 'progress isolation');
  requireThat(request('GET', `/movies/${MOVIE}/playback`, 200, null, token).json().resumePositionSeconds === 1, 'first user progress remains isolated');
  requireThat(request('PUT', `/me/progress/${MOVIE}`, 200, { ...progress, positionSeconds: 2, clientUpdatedAt: new Date(time + 1000).toISOString() }, token).json().completed, 'viewing completion stored');
  requireThat(request('GET', `/movies/${MOVIE}/playback`, 200, null, token).json().resumePositionSeconds === 0, 'completed movie restarts');
  for (let i = 0; i < 2; i++) request('DELETE', `/me/watchlist/${MOVIE}`, 204, null, token);
  requireThat(request('GET', '/me/watchlist', 200, null, token).json().total === 0, 'watchlist removal is idempotent');

  jar.set(ORIGIN, cookie, oldCookie, { path: '/' });
  requireThat(request('POST', '/auth/refresh', 401).json().code === 'REFRESH_TOKEN_REUSE', 'refresh reuse rejected');
  jar.set(ORIGIN, cookie, rotatedCookie, { path: '/' });
  request('POST', '/auth/refresh', 401);
  jar.clear(ORIGIN);
  request('POST', '/auth/login', 200, credentials);
  const beforeLogout = jar.cookiesForURL(API)[cookie][0];
  request('POST', '/auth/logout', 204);
  jar.set(ORIGIN, cookie, beforeLogout, { path: '/' });
  request('POST', '/auth/refresh', 401);

  // Media checks are local fixture transport acceptance, not CDN or browser decoding.
  for (const slug of ['starlight-archive', 'paper-moons', 'quiet-current']) {
    const playlist = http.get(`${ORIGIN}/media/fixtures/${slug}/index.m3u8`, { redirects: 0 });
    requireThat(playlist.status === 200 && playlist.body.startsWith('#EXTM3U'), `HLS playlist ${slug}`);
    const segment = http.get(`${ORIGIN}/media/fixtures/${slug}/segment-000.ts`, { headers: { Range: 'bytes=0-0' }, responseType: 'binary', redirects: 0 });
    requireThat(segment.status === 206 && segment.body.byteLength === 1 && segment.headers['Content-Range'].startsWith('bytes 0-0/'), `HLS byte range ${slug}`);
  }
}

export function handleSummary(data) { return summary(data, 'api', 'http-e2e'); }
