/**
 * Acceptance tests for the media gateway.
 *
 * Tokens are signed with a test-only RSA key generated in this process, and `env.DELIVERY` is
 * a recording fake whose `get(key)` records every requested key. That lets each rejection case
 * assert the hard requirement: no cache lookup and no bucket read happens for a request that
 * is refused.
 */

import { SignJWT, exportJWK, generateKeyPair, type JWK, type JWTPayload } from 'jose';
import { beforeAll, describe, expect, it } from 'vitest';

import type { DeliveryBucket, DeliveryObject, DeliveryRangeOption } from '../src/delivery';
import { createGateway, type Env, type GatewayCache, type GatewayExecutionContext } from '../src/index';
import { contentTypeFor, parseRangeHeader, parseRoute } from '../src/media-path';

const ISSUER = 'lvfast-media-backend';
const AUDIENCE = 'lvfast-media';
const KID = 'test-key-1';
const ALLOWED_ORIGIN = 'https://app.example.test';
const FOREIGN_ORIGIN = 'https://foreign.example.test';

const MOVIE_ID = '11111111-1111-4111-8111-111111111111';
const VERSION_ID = '22222222-2222-4222-8222-222222222222';
const ATTEMPT_ID = '33333333-3333-4333-8333-333333333333';
const OTHER_VERSION_ID = '44444444-4444-4444-8444-444444444444';
const OTHER_ATTEMPT_ID = '55555555-5555-4555-8555-555555555555';
const MIXED_CASE_MOVIE_ID = '1111AAAA-1111-4111-8111-111111111111';
const ASSET_ID = '66666666-6666-4666-8666-666666666666';

const MANIFEST_PATH = `/hls/${MOVIE_ID}/${VERSION_ID}/${ATTEMPT_ID}/index.m3u8`;
const SEGMENT_PATH = `/hls/${MOVIE_ID}/${VERSION_ID}/${ATTEMPT_ID}/segment_00001.ts`;
const MANIFEST_KEY = `hls/${MOVIE_ID}/${VERSION_ID}/${ATTEMPT_ID}/index.m3u8`;
const SEGMENT_KEY = `hls/${MOVIE_ID}/${VERSION_ID}/${ATTEMPT_ID}/segment_00001.ts`;
const ARTWORK_PATH = `/public-artwork/${ASSET_ID}/image.jpg`;
const ARTWORK_KEY = `public-artwork/${ASSET_ID}/image.jpg`;

const CANONICAL_PREFIX = `/hls/${MOVIE_ID}/${VERSION_ID}/${ATTEMPT_ID}/`;

const OBJECT_BODY = '0123456789abcdefghijklmnopqrstuvwxyz';
const OBJECT_SIZE = OBJECT_BODY.length;

type SigningKey = Awaited<ReturnType<typeof generateKeyPair>>['privateKey'];

let signingKey: SigningKey;
let otherSigningKey: SigningKey;
let publicJwk: JWK;

beforeAll(async () => {
  const pair = await generateKeyPair('RS256', { extractable: true });
  const otherPair = await generateKeyPair('RS256', { extractable: true });
  signingKey = pair.privateKey;
  otherSigningKey = otherPair.privateKey;
  publicJwk = { ...(await exportJWK(pair.publicKey)), kid: KID, alg: 'RS256', use: 'sig' };
});

function nowSeconds(): number {
  return Math.floor(Date.now() / 1000);
}

async function signToken(
  overrides: JWTPayload = {},
  options: { kid?: string; alg?: 'RS256' | 'HS256'; key?: SigningKey | Uint8Array } = {},
): Promise<string> {
  const issuedAt = nowSeconds();
  const claims: JWTPayload = {
    iss: ISSUER,
    aud: AUDIENCE,
    sub: crypto.randomUUID(),
    sid: crypto.randomUUID(),
    movieId: MOVIE_ID,
    versionId: VERSION_ID,
    prefix: CANONICAL_PREFIX,
    purpose: 'VIEWER',
    iat: issuedAt,
    nbf: issuedAt - 5,
    exp: issuedAt + 300,
    jti: crypto.randomUUID(),
    ...overrides,
  };
  const alg = options.alg ?? 'RS256';
  const key: SigningKey | Uint8Array = options.key ?? signingKey;
  return new SignJWT(claims)
    .setProtectedHeader({ alg, typ: 'JWT', kid: options.kid ?? KID })
    .sign(key);
}

function bearer(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

function streamOf(bytes: Uint8Array): ReadableStream<Uint8Array> {
  const copy = new Uint8Array(bytes);
  return new Response(copy.buffer).body as ReadableStream<Uint8Array>;
}

interface RecordingBucket {
  readonly bucket: DeliveryBucket;
  readonly requestedKeys: string[];
  readonly requestedRanges: Array<DeliveryRangeOption | undefined>;
}

/** Fake delivery bucket: records reads and serves a synthetic body/range response. */
function createRecordingBucket(body = OBJECT_BODY, options: { missing?: boolean } = {}): RecordingBucket {
  const requestedKeys: string[] = [];
  const requestedRanges: Array<DeliveryRangeOption | undefined> = [];
  const bytes = new TextEncoder().encode(body);

  const bucket: DeliveryBucket = {
    async get(key: string, getOptions?: { range?: DeliveryRangeOption }): Promise<DeliveryObject | null> {
      requestedKeys.push(key);
      requestedRanges.push(getOptions?.range);
      if (options.missing === true) {
        return null;
      }
      const range = getOptions?.range;
      if (range === undefined) {
        return { body: streamOf(bytes), size: bytes.byteLength };
      }
      let offset: number;
      let length: number;
      if (range.suffix !== undefined) {
        length = Math.min(range.suffix, bytes.byteLength);
        offset = bytes.byteLength - length;
      } else {
        offset = range.offset ?? 0;
        length =
          range.length === undefined
            ? bytes.byteLength - offset
            : Math.min(range.length, bytes.byteLength - offset);
      }
      if (offset < 0 || length <= 0 || offset >= bytes.byteLength) {
        return null;
      }
      return {
        body: streamOf(bytes.slice(offset, offset + length)),
        size: bytes.byteLength,
        range: { offset, length },
      };
    },
  };

  return { bucket, requestedKeys, requestedRanges };
}

interface TestEnv {
  readonly env: Env;
  readonly bucket: RecordingBucket;
}

function createEnv(overrides: Partial<Env> = {}, bucket = createRecordingBucket()): TestEnv {
  const env: Env = {
    DELIVERY: bucket.bucket,
    JWT_PUBLIC_JWKS: JSON.stringify({ keys: [publicJwk] }),
    JWT_ISSUER: ISSUER,
    JWT_AUDIENCE: AUDIENCE,
    ALLOWED_ORIGINS: ALLOWED_ORIGIN,
    ...overrides,
  };
  return { env, bucket };
}

const gateway = createGateway();

function makeRequest(
  path: string,
  init: { method?: string; headers?: Record<string, string>; query?: string } = {},
): Request {
  return new Request(`https://media.example.test${path}${init.query ?? ''}`, {
    method: init.method ?? 'GET',
    headers: init.headers ?? {},
  });
}

async function handle(request: Request, env: Env, ctx?: GatewayExecutionContext): Promise<Response> {
  return gateway.fetch(request, env, ctx);
}

describe('rule 1 - bearer header is the only authentication source', () => {
  it('rejects a request with no Authorization header before any read', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH), env);
    expect(response.status).toBe(401);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects a query-string token before any read', async () => {
    const token = await signToken();
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { query: `?token=${token}` }), env);
    expect(response.status).toBe(401);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects a cookie token before any read', async () => {
    const token = await signToken();
    const { env, bucket } = createEnv();
    const response = await handle(
      makeRequest(MANIFEST_PATH, { headers: { Cookie: `media_token=${token}` } }),
      env,
    );
    expect(response.status).toBe(401);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects a non-bearer scheme that carries a valid token before any read', async () => {
    const token = await signToken();
    const { env, bucket } = createEnv();
    const basic = await handle(
      makeRequest(MANIFEST_PATH, { headers: { Authorization: `Basic ${token}` } }),
      env,
    );
    expect(basic.status).toBe(401);
    const bare = await handle(makeRequest(MANIFEST_PATH, { headers: { Authorization: token } }), env);
    expect(bare.status).toBe(401);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('accepts a case-insensitive Bearer scheme', async () => {
    const token = await signToken();
    const { env, bucket } = createEnv();
    const response = await handle(
      makeRequest(MANIFEST_PATH, { headers: { Authorization: `bearer   ${token}` } }),
      env,
    );
    expect(response.status).toBe(200);
    expect(bucket.requestedKeys).toEqual([MANIFEST_KEY]);
  });
});

describe('rule 2 - signature, key, algorithm and standard claims', () => {
  it('accepts a valid token and returns the manifest', async () => {
    const token = await signToken();
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(200);
    expect(await response.text()).toBe(OBJECT_BODY);
    expect(bucket.requestedKeys).toEqual([MANIFEST_KEY]);
  });

  it('accepts an audience array that contains the configured audience', async () => {
    const token = await signToken({ aud: ['another-audience', AUDIENCE] });
    const { env } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(200);
  });

  it('rejects a token signed by a different key', async () => {
    const token = await signToken({}, { key: otherSigningKey });
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(401);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects an unknown kid', async () => {
    const token = await signToken({}, { kid: 'rotated-away-key' });
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(401);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects a non-RS256 algorithm', async () => {
    const token = await signToken({}, { alg: 'HS256', key: new TextEncoder().encode('test-only-shared-secret-value') });
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(401);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects an expired token', async () => {
    const issuedAt = nowSeconds();
    const token = await signToken({ iat: issuedAt - 600, nbf: issuedAt - 600, exp: issuedAt - 300 });
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(401);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects a token whose nbf is in the future', async () => {
    const issuedAt = nowSeconds();
    const token = await signToken({ iat: issuedAt, nbf: issuedAt + 300, exp: issuedAt + 600 });
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(401);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects a wrong issuer', async () => {
    const token = await signToken({ iss: 'some-other-issuer' });
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(401);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects an audience that does not contain the configured audience', async () => {
    const token = await signToken({ aud: 'some-other-audience' });
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(401);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects a malformed token', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer('not-a-jwt') }), env);
    expect(response.status).toBe(401);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('honours a custom issuer and audience configuration', async () => {
    const token = await signToken({ iss: 'custom-issuer', aud: 'custom-audience' });
    const { env } = createEnv({ JWT_ISSUER: 'custom-issuer', JWT_AUDIENCE: 'custom-audience' });
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(200);
  });

  it('fails closed when no verification key is configured', async () => {
    const token = await signToken();
    const { env, bucket } = createEnv({ JWT_PUBLIC_JWKS: undefined });
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(500);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('verifies with a single JWT_PUBLIC_JWK configuration', async () => {
    const token = await signToken();
    const { env } = createEnv({ JWT_PUBLIC_JWKS: undefined, JWT_PUBLIC_JWK: JSON.stringify(publicJwk) });
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(200);
  });
});

describe('rule 3 - canonical path binding', () => {
  it('rejects a token issued for a different attempt (prefix mismatch)', async () => {
    const token = await signToken({
      prefix: `/hls/${MOVIE_ID}/${VERSION_ID}/${OTHER_ATTEMPT_ID}/`,
    });
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(403);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects a token whose versionId claim disagrees with the path', async () => {
    const token = await signToken({ versionId: OTHER_VERSION_ID });
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(403);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects a token whose movieId claim disagrees with the path', async () => {
    const token = await signToken({ movieId: OTHER_VERSION_ID });
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(403);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects a prefix that is only a partial path match', async () => {
    const token = await signToken({ prefix: `/hls/${MOVIE_ID}/` });
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(403);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects a file name outside the HLS allowlist', async () => {
    const path = `/hls/${MOVIE_ID}/${VERSION_ID}/${ATTEMPT_ID}/playlist.m3u8`;
    const token = await signToken({ prefix: `/hls/${MOVIE_ID}/${VERSION_ID}/${ATTEMPT_ID}/` });
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(path, { headers: bearer(token) }), env);
    expect(response.status).toBe(403);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects extra path depth under the attempt prefix', async () => {
    const path = `/hls/${MOVIE_ID}/${VERSION_ID}/${ATTEMPT_ID}/1080p/index.m3u8`;
    const token = await signToken();
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(path, { headers: bearer(token) }), env);
    expect(response.status).toBe(403);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects non-canonical (mixed-case) UUID segments even when the token agrees', async () => {
    const path = `/hls/${MIXED_CASE_MOVIE_ID}/${VERSION_ID}/${ATTEMPT_ID}/index.m3u8`;
    const token = await signToken({
      movieId: MIXED_CASE_MOVIE_ID,
      prefix: `/hls/${MIXED_CASE_MOVIE_ID}/${VERSION_ID}/${ATTEMPT_ID}/`,
    });
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(path, { headers: bearer(token) }), env);
    expect(response.status).toBe(403);
    expect(bucket.requestedKeys).toEqual([]);
  });
});

describe('rule 4 - allowlist, traversal and route refusal', () => {
  it('rejects a dot-segment traversal', async () => {
    // The URL parser already removes `../` segments before the Worker sees the path; the
    // rewritten path no longer matches the canonical allowlist, so it is still refused.
    const { env, bucket } = createEnv();
    const response = await handle(
      makeRequest(`/hls/${MOVIE_ID}/${VERSION_ID}/../${ATTEMPT_ID}/index.m3u8`),
      env,
    );
    expect([400, 403]).toContain(response.status);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects an encoded %2e%2e traversal', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(`/hls/%2e%2e/${VERSION_ID}/${ATTEMPT_ID}/index.m3u8`), env);
    expect([400, 403]).toContain(response.status);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects an encoded backslash', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(
      makeRequest(`/hls/${MOVIE_ID}%5c${VERSION_ID}/${ATTEMPT_ID}/index.m3u8`),
      env,
    );
    expect(response.status).toBe(400);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects a backslash traversal that the URL parser normalizes into a foreign path', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(`/hls\\..\\..\\..\\etc\\passwd`), env);
    expect([400, 403]).toContain(response.status);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects a double-slash trick', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(`/hls//${MOVIE_ID}/${VERSION_ID}/${ATTEMPT_ID}/index.m3u8`), env);
    expect(response.status).toBe(400);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('refuses the raw worker artwork namespace', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(`/artwork/${ASSET_ID}/image.jpg`), env);
    expect(response.status).toBe(403);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('refuses a bare source-style key', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(`/media-source/uploads/${ASSET_ID}.mp4`), env);
    expect(response.status).toBe(403);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('refuses an unknown route', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest('/unknown/route'), env);
    expect(response.status).toBe(403);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('refuses the bare /hls namespace', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest('/hls'), env);
    expect(response.status).toBe(403);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('never echoes the request path or a storage key in an error body', async () => {
    const token = await signToken();
    const { env, bucket } = createEnv();
    const mismatch = await handle(
      makeRequest(SEGMENT_PATH, { headers: bearer(await signToken({ versionId: OTHER_VERSION_ID })) }),
      env,
    );
    expect(mismatch.status).toBe(403);
    const body = await mismatch.text();
    expect(body).toBe(JSON.stringify({ error: 'forbidden' }));
    expect(body).not.toContain(VERSION_ID);
    expect(body).not.toContain('hls/');
    expect(body).not.toContain(token);

    const traversal = await handle(makeRequest(`/hls/${MOVIE_ID}/../${ATTEMPT_ID}/index.m3u8`), env);
    const traversalBody = await traversal.text();
    expect(traversalBody).not.toContain(MOVIE_ID);
    expect(traversalBody).not.toContain('hls/');
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('parses raw hostile path strings directly', () => {
    expect(parseRoute('/hls\\..\\index.m3u8')).toEqual({ kind: 'rejected', status: 400 });
    expect(parseRoute(`/hls/../${ATTEMPT_ID}/index.m3u8`)).toEqual({ kind: 'rejected', status: 400 });
    expect(parseRoute(`/hls/${MOVIE_ID}/%2e%2e/${ATTEMPT_ID}/index.m3u8`)).toEqual({
      kind: 'rejected',
      status: 400,
    });
    expect(parseRoute(`/hls/${MOVIE_ID}%5c${VERSION_ID}/${ATTEMPT_ID}/index.m3u8`)).toEqual({
      kind: 'rejected',
      status: 400,
    });
    expect(parseRoute(`/hls/${MOVIE_ID}/${VERSION_ID}/..%2f${ATTEMPT_ID}/index.m3u8`)).toEqual({
      kind: 'rejected',
      status: 400,
    });
    expect(parseRoute(`/hls/${MOVIE_ID}/${VERSION_ID}//${ATTEMPT_ID}/index.m3u8`)).toEqual({
      kind: 'rejected',
      status: 400,
    });
    expect(parseRoute('/hls/a/b/c/index.m3u8')).toEqual({ kind: 'rejected', status: 403 });
    expect(parseRoute('/public-artwork/not-a-uuid/image.jpg')).toEqual({ kind: 'rejected', status: 403 });
    expect(parseRoute(`/hls/${MOVIE_ID}/${VERSION_ID}/${ATTEMPT_ID}/index.m3u8`)).toEqual({
      kind: 'hls',
      key: MANIFEST_KEY,
      movieId: MOVIE_ID,
      versionId: VERSION_ID,
      attemptId: ATTEMPT_ID,
      fileName: 'index.m3u8',
      contentType: 'application/vnd.apple.mpegurl',
    });
    expect(parseRoute(ARTWORK_PATH)).toEqual({
      kind: 'public-artwork',
      key: ARTWORK_KEY,
      assetId: ASSET_ID,
      contentType: 'image/jpeg',
    });
  });
});

describe('rule 5 - methods', () => {
  it('rejects POST with 405 before any read', async () => {
    const token = await signToken();
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { method: 'POST', headers: bearer(token) }), env);
    expect(response.status).toBe(405);
    expect(response.headers.get('allow')).toBe('GET, HEAD, OPTIONS');
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects DELETE with 405 before any read', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { method: 'DELETE' }), env);
    expect(response.status).toBe(405);
    expect(bucket.requestedKeys).toEqual([]);
  });
});

describe('rule 6 - byte ranges', () => {
  it('serves one closed range as 206 with Content-Range', async () => {
    const token = await signToken();
    const { env, bucket } = createEnv();
    const response = await handle(
      makeRequest(MANIFEST_PATH, { headers: { ...bearer(token), Range: 'bytes=0-9' } }),
      env,
    );
    expect(response.status).toBe(206);
    expect(response.headers.get('content-range')).toBe(`bytes 0-9/${OBJECT_SIZE}`);
    expect(response.headers.get('content-length')).toBe('10');
    expect(response.headers.get('accept-ranges')).toBe('bytes');
    expect(await response.text()).toBe(OBJECT_BODY.slice(0, 10));
    expect(bucket.requestedKeys).toEqual([MANIFEST_KEY]);
    expect(bucket.requestedRanges).toEqual([{ offset: 0, length: 10 }]);
  });

  it('serves an open-ended range', async () => {
    const token = await signToken();
    const { env } = createEnv();
    const response = await handle(
      makeRequest(SEGMENT_PATH, { headers: { ...bearer(token), Range: 'bytes=10-' } }),
      env,
    );
    expect(response.status).toBe(206);
    expect(response.headers.get('content-range')).toBe(`bytes 10-${OBJECT_SIZE - 1}/${OBJECT_SIZE}`);
    expect(await response.text()).toBe(OBJECT_BODY.slice(10));
  });

  it('serves a suffix range', async () => {
    const token = await signToken();
    const { env } = createEnv();
    const response = await handle(
      makeRequest(SEGMENT_PATH, { headers: { ...bearer(token), Range: 'bytes=-5' } }),
      env,
    );
    expect(response.status).toBe(206);
    expect(response.headers.get('content-range')).toBe(`bytes ${OBJECT_SIZE - 5}-${OBJECT_SIZE - 1}/${OBJECT_SIZE}`);
    expect(await response.text()).toBe(OBJECT_BODY.slice(OBJECT_SIZE - 5));
  });

  it('answers HEAD with a range as 206 without a body', async () => {
    const token = await signToken();
    const { env } = createEnv();
    const response = await handle(
      makeRequest(SEGMENT_PATH, { method: 'HEAD', headers: { ...bearer(token), Range: 'bytes=0-9' } }),
      env,
    );
    expect(response.status).toBe(206);
    expect(response.headers.get('content-range')).toBe(`bytes 0-9/${OBJECT_SIZE}`);
    expect(await response.text()).toBe('');
  });

  it('rejects multiple ranges with 416 before any read', async () => {
    const token = await signToken();
    const { env, bucket } = createEnv();
    const response = await handle(
      makeRequest(MANIFEST_PATH, { headers: { ...bearer(token), Range: 'bytes=0-1,5-6' } }),
      env,
    );
    expect(response.status).toBe(416);
    expect(bucket.requestedKeys).toEqual([]);
  });

  const invalidRanges = ['bytes=abc', 'bytes=', 'bytes=9-0', 'items=0-9', 'bytes=0-1, 5-6', 'bytes=-0', 'bytes=1-2-3'];
  for (const range of invalidRanges) {
    it(`rejects the invalid range ${JSON.stringify(range)} with 416 before any read`, async () => {
      const token = await signToken();
      const { env, bucket } = createEnv();
      const response = await handle(
        makeRequest(MANIFEST_PATH, { headers: { ...bearer(token), Range: range } }),
        env,
      );
      expect(response.status).toBe(416);
      expect(bucket.requestedKeys).toEqual([]);
    });
  }

  it('answers an unsatisfiable range with 416', async () => {
    const token = await signToken();
    const { env } = createEnv();
    const response = await handle(
      makeRequest(MANIFEST_PATH, { headers: { ...bearer(token), Range: 'bytes=100-200' } }),
      env,
    );
    expect(response.status).toBe(416);
  });

  it('parses range syntax directly', () => {
    expect(parseRangeHeader(null)).toEqual({ kind: 'none' });
    expect(parseRangeHeader('bytes=0-9')).toEqual({ kind: 'single', start: 0, end: 9 });
    expect(parseRangeHeader('bytes=7-')).toEqual({ kind: 'single', start: 7, end: null });
    expect(parseRangeHeader('bytes=-7')).toEqual({ kind: 'suffix', length: 7 });
    expect(parseRangeHeader('bytes=0-1,5-6')).toEqual({ kind: 'invalid' });
    expect(parseRangeHeader('')).toEqual({ kind: 'invalid' });
  });
});

describe('rule 7 - protected response headers and content types', () => {
  it('sets the manifest headers', async () => {
    const token = await signToken();
    const { env } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toBe('application/vnd.apple.mpegurl');
    expect(response.headers.get('cache-control')).toBe('private, max-age=0, no-store');
    expect(response.headers.get('accept-ranges')).toBe('bytes');
    expect(response.headers.get('vary')).toBe('Authorization');
    expect(response.headers.get('content-length')).toBe(String(OBJECT_SIZE));
  });

  it('serves the first transcoder segment name (segment_00000.ts)', async () => {
    const token = await signToken();
    const { env, bucket } = createEnv();
    const path = `/hls/${MOVIE_ID}/${VERSION_ID}/${ATTEMPT_ID}/segment_00000.ts`;
    const response = await handle(makeRequest(path, { headers: bearer(token) }), env);
    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toBe('video/mp2t');
    expect(bucket.requestedKeys).toEqual([`hls/${MOVIE_ID}/${VERSION_ID}/${ATTEMPT_ID}/segment_00000.ts`]);
  });

  it('accepts a PREVIEW purpose token for the same prefix', async () => {
    const token = await signToken({ purpose: 'PREVIEW' });
    const { env } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(200);
  });

  it('rejects an unknown purpose value', async () => {
    const token = await signToken({ purpose: 'DOWNLOAD' });
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(401);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('sets the segment content type', async () => {
    const token = await signToken();
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(SEGMENT_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toBe('video/mp2t');
    expect(bucket.requestedKeys).toEqual([SEGMENT_KEY]);
  });

  it('serves HEAD without a body', async () => {
    const token = await signToken();
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { method: 'HEAD', headers: bearer(token) }), env);
    expect(response.status).toBe(200);
    expect(response.headers.get('cache-control')).toBe('private, max-age=0, no-store');
    expect(await response.text()).toBe('');
    expect(bucket.requestedKeys).toEqual([MANIFEST_KEY]);
  });

  it('derives unknown extensions as octet-stream', () => {
    expect(contentTypeFor('index.m3u8')).toBe('application/vnd.apple.mpegurl');
    expect(contentTypeFor('segment_00001.ts')).toBe('video/mp2t');
    expect(contentTypeFor('artifact.bin')).toBe('application/octet-stream');
  });

  it('rejects a missing object with 404', async () => {
    const token = await signToken();
    const { env } = createEnv({}, createRecordingBucket(OBJECT_BODY, { missing: true }));
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: bearer(token) }), env);
    expect(response.status).toBe(404);
  });
});

describe('public artwork route', () => {
  it('serves artwork anonymously with public caching headers', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(ARTWORK_PATH), env);
    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toBe('image/jpeg');
    expect(response.headers.get('cache-control')).toBe('public, max-age=3600');
    expect(await response.text()).toBe(OBJECT_BODY);
    expect(bucket.requestedKeys).toEqual([ARTWORK_KEY]);
  });

  it('serves a HEAD artwork request without a body', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(ARTWORK_PATH, { method: 'HEAD' }), env);
    expect(response.status).toBe(200);
    expect(await response.text()).toBe('');
    expect(bucket.requestedKeys).toEqual([ARTWORK_KEY]);
  });

  it('ignores a Range header on artwork', async () => {
    const { env } = createEnv();
    const response = await handle(makeRequest(ARTWORK_PATH, { headers: { Range: 'bytes=0-4' } }), env);
    expect(response.status).toBe(200);
    expect(await response.text()).toBe(OBJECT_BODY);
  });

  it('rejects a non-UUID asset id', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest('/public-artwork/not-a-uuid/image.jpg'), env);
    expect(response.status).toBe(403);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects artwork names that are not image.jpg', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(`/public-artwork/${ASSET_ID}/image.png`), env);
    expect(response.status).toBe(403);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('rejects an encoded separator in the artwork path', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(`/public-artwork%5c${ASSET_ID}/image.jpg`), env);
    expect(response.status).toBe(400);
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('does not require a token for artwork', async () => {
    const { env } = createEnv();
    const response = await handle(makeRequest(ARTWORK_PATH, { headers: { Authorization: 'Bearer garbage' } }), env);
    expect(response.status).toBe(200);
  });
});

describe('CORS', () => {
  it('answers an allowed preflight without any storage access', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(
      makeRequest(MANIFEST_PATH, { method: 'OPTIONS', headers: { Origin: ALLOWED_ORIGIN } }),
      env,
    );
    expect(response.status).toBe(204);
    expect(response.headers.get('access-control-allow-origin')).toBe(ALLOWED_ORIGIN);
    expect(response.headers.get('access-control-allow-methods')).toBe('GET, HEAD, OPTIONS');
    expect(response.headers.get('access-control-allow-headers')).toBe('Authorization');
    expect(response.headers.get('access-control-expose-headers')).toBe('Content-Range, Accept-Ranges');
    expect(response.headers.get('vary')).toBe('Origin');
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('answers a foreign-origin preflight without Access-Control-Allow-Origin', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(
      makeRequest(MANIFEST_PATH, { method: 'OPTIONS', headers: { Origin: FOREIGN_ORIGIN } }),
      env,
    );
    expect(response.status).toBe(204);
    expect(response.headers.get('access-control-allow-origin')).toBeNull();
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('answers a preflight with no Origin and no token', async () => {
    const { env, bucket } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { method: 'OPTIONS' }), env);
    expect(response.status).toBe(204);
    expect(response.headers.get('access-control-allow-origin')).toBeNull();
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('grants no CORS origin when the allowlist is empty', async () => {
    const { env } = createEnv({ ALLOWED_ORIGINS: '' });
    const response = await handle(
      makeRequest(MANIFEST_PATH, { method: 'OPTIONS', headers: { Origin: ALLOWED_ORIGIN } }),
      env,
    );
    expect(response.headers.get('access-control-allow-origin')).toBeNull();
  });

  it('echoes the allowed origin on a protected response and keeps Vary: Authorization', async () => {
    const token = await signToken();
    const { env } = createEnv();
    const response = await handle(
      makeRequest(MANIFEST_PATH, { headers: { ...bearer(token), Origin: ALLOWED_ORIGIN } }),
      env,
    );
    expect(response.status).toBe(200);
    expect(response.headers.get('access-control-allow-origin')).toBe(ALLOWED_ORIGIN);
    expect(response.headers.get('access-control-expose-headers')).toBe('Content-Range, Accept-Ranges');
    expect(response.headers.get('vary')).toBe('Authorization');
  });

  it('omits the allowed-origin header for a foreign origin', async () => {
    const token = await signToken();
    const { env } = createEnv();
    const response = await handle(
      makeRequest(MANIFEST_PATH, { headers: { ...bearer(token), Origin: FOREIGN_ORIGIN } }),
      env,
    );
    expect(response.status).toBe(200);
    expect(response.headers.get('access-control-allow-origin')).toBeNull();
  });

  it('returns CORS headers on a rejected protected request', async () => {
    const { env } = createEnv();
    const response = await handle(makeRequest(MANIFEST_PATH, { headers: { Origin: ALLOWED_ORIGIN } }), env);
    expect(response.status).toBe(401);
    expect(response.headers.get('access-control-allow-origin')).toBe(ALLOWED_ORIGIN);
    expect(response.headers.get('vary')).toBe('Origin');
  });

  it('applies CORS to artwork and varies on Origin', async () => {
    const { env } = createEnv();
    const response = await handle(makeRequest(ARTWORK_PATH, { headers: { Origin: ALLOWED_ORIGIN } }), env);
    expect(response.status).toBe(200);
    expect(response.headers.get('access-control-allow-origin')).toBe(ALLOWED_ORIGIN);
    expect(response.headers.get('vary')).toBe('Origin');
  });
});

describe('shared cache is consulted only after authorization', () => {
  function createRecordingCache(cached?: Response): {
    cache: GatewayCache;
    state: { matchCalls: number; putCalls: number };
  } {
    const state = { matchCalls: 0, putCalls: 0 };
    const cache: GatewayCache = {
      async match(): Promise<Response | undefined> {
        state.matchCalls += 1;
        return cached;
      },
      async put(): Promise<void> {
        state.putCalls += 1;
      },
    };
    return { cache, state };
  }

  function createContext(): { ctx: GatewayExecutionContext; promises: Promise<unknown>[] } {
    const promises: Promise<unknown>[] = [];
    return {
      ctx: {
        waitUntil(promise: Promise<unknown>): void {
          promises.push(promise);
        },
      },
      promises,
    };
  }

  it('never touches the cache for an unauthenticated protected request', async () => {
    const { cache, state } = createRecordingCache();
    const { env, bucket } = createEnv();
    const response = await createGateway({ cache }).fetch(makeRequest(MANIFEST_PATH), env);
    expect(response.status).toBe(401);
    expect(state).toEqual({ matchCalls: 0, putCalls: 0 });
    expect(bucket.requestedKeys).toEqual([]);
  });

  it('never touches the cache for an authorized protected request', async () => {
    const token = await signToken();
    const { cache, state } = createRecordingCache();
    const { env } = createEnv();
    const response = await createGateway({ cache }).fetch(
      makeRequest(MANIFEST_PATH, { headers: bearer(token) }),
      env,
    );
    expect(response.status).toBe(200);
    expect(state).toEqual({ matchCalls: 0, putCalls: 0 });
  });

  it('caches a public artwork miss', async () => {
    const { cache, state } = createRecordingCache();
    const { env, bucket } = createEnv();
    const { ctx, promises } = createContext();
    const response = await createGateway({ cache }).fetch(makeRequest(ARTWORK_PATH), env, ctx);
    await Promise.all(promises);
    expect(response.status).toBe(200);
    expect(state).toEqual({ matchCalls: 1, putCalls: 1 });
    expect(bucket.requestedKeys).toEqual([ARTWORK_KEY]);
  });

  it('serves a public artwork cache hit without a bucket read', async () => {
    const cached = new Response('cached-artwork', {
      status: 200,
      headers: { 'Content-Type': 'image/jpeg', 'Cache-Control': 'public, max-age=3600', Vary: 'Origin' },
    });
    const { cache } = createRecordingCache(cached);
    const { env, bucket } = createEnv();
    const response = await createGateway({ cache }).fetch(makeRequest(ARTWORK_PATH), env);
    expect(response.status).toBe(200);
    expect(await response.text()).toBe('cached-artwork');
    expect(bucket.requestedKeys).toEqual([]);
  });
});
