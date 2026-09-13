# Media gateway runbook

`media-gateway/` is a Cloudflare Worker (TypeScript, ESM, `wrangler` 4) that is the **only** way a
browser can read private delivery-bucket objects. It authenticates and authorizes a request
*before* any cache or bucket access, then streams the object body without buffering it.

## Trust boundary

| Item | Where it lives |
| --- | --- |
| Signing private key | Backend only. The Worker never receives it. |
| Verification public key / JWK Set | Worker secret (`JWT_PUBLIC_JWK` / `JWT_PUBLIC_JWKS`). |
| Source bucket | Not reachable from the Worker: `wrangler.jsonc` binds **only** `DELIVERY`. |
| Delivery bucket | R2 binding `DELIVERY` (`media-delivery` locally, the deployment bucket in production). |
| S3/R2 credentials | None in the Worker. Access is through the R2 binding. |

The Worker never logs a token, a claim, a storage key or a delivery URL, and every error body is
a generic phrase such as `{"error":"forbidden"}` so a rejected request cannot be used to probe
which part of a path or token was wrong.

## Configuration

`wrangler.jsonc` declares:

- `name: lvfast-media-gateway`, `main: src/index.ts`, `compatibility_date: 2025-08-01`,
- `r2_buckets: [{ binding: "DELIVERY", bucket_name: "media-delivery" }]`,
- `observability: { enabled: true }`,
- `vars`: `JWT_ISSUER` (`lvfast-media-backend`), `JWT_AUDIENCE` (`lvfast-media`),
  `ALLOWED_ORIGINS` (empty by default, which grants no browser origin CORS access).

No `routes` entry and no account id are declared. Routing is attached by the deployment
environment, not by this file.

Secrets (never placed in `wrangler.jsonc` or `vars`):

```sh
wrangler secret put JWT_PUBLIC_JWK    # a single public JWK, JSON, including "kid"
wrangler secret put JWT_PUBLIC_JWKS   # a JWK Set, {"keys":[...]}, for rotation
```

Both variables may be set at once; keys are selected by the token header `kid` across the union.
Only RSA public parameters are imported (`kty`, `n`, `e` plus `kid`/`alg`/`use`/`key_ops`); any
private parameter present in a misconfigured secret is discarded. A configuration with no usable
key, or with unparsable JSON, answers **500** (server fault) instead of pretending the caller is
unauthorized.

### Producing the verification key

The backend signs with the public key at `app.media.signing.public-key-location` (X.509 PEM) and
puts its media key id in the token header `kid`: `app.media.signing.key-id` when set, otherwise a
SHA-256 thumbprint of the public key. The secret the Worker holds must be a JWK that carries that
exact `kid`, otherwise every token is rejected with 401.

```sh
# Derive the JWK from the backend public key (Node built-ins only; jose is ESM-only).
# The kid must equal the backend key id.
node -e "const {createPublicKey}=require('node:crypto');const fs=require('fs');\
const jwk=createPublicKey(fs.readFileSync(process.argv[1],'utf8')).export({format:'jwk'});\
console.log(JSON.stringify({...jwk,kid:process.argv[2],alg:'RS256',use:'sig'}))" \
  backend-keys/media-public.pem "media-2026-01"
```

Prefer setting `app.media.signing.key-id` explicitly so the gateway secret does not have to
reproduce the backend's thumbprint derivation. Never include the private key (`d`, `p`, `q`, ...)
in the Worker secret; the gateway strips such parameters, but they must not be placed there at all.

## Media token contract

Header: `{alg:"RS256",typ:"JWT",kid:"<string>"}`.

Claims: `iss`, `aud` (string, or an array that contains the configured audience), `sub`, `sid`,
`movieId`, `versionId`, `prefix`, `purpose` (`VIEWER` or `PREVIEW`), `iat`, `nbf`, `exp`, `jti`.
All of them are required; a missing claim is a 401.

`prefix` must be exactly the canonical slash-terminated HLS prefix of the requested path:
`/hls/{movieId}/{versionId}/{attemptId}/`. The Worker also requires the `movieId` and `versionId`
claims to equal the path segments, so a genuine token for another attempt or version cannot be
replayed against a different object.

## Routes and wire behavior

| Request | Result |
| --- | --- |
| `GET /hls/{movieId}/{versionId}/{attemptId}/index.m3u8` | 200, `application/vnd.apple.mpegurl`, streamed |
| `GET /hls/{movieId}/{versionId}/{attemptId}/segment_00001.ts` | 200, `video/mp2t`, streamed |
| `GET|HEAD` on either path with `Range: bytes=a-b`, `bytes=a-`, `bytes=-n` | 206, `Content-Range: bytes a-b/total` |
| `GET /public-artwork/{assetId}/image.jpg` | 200 anonymous, `image/jpeg`, `Cache-Control: public, max-age=3600` |
| `OPTIONS` any path | 204 preflight, no token, no storage access |
| anything else | see the enforcement table below |

Response headers for protected HLS are always:
`Content-Type` (from the extension), `Cache-Control: private, max-age=0, no-store`,
`Accept-Ranges: bytes`, `Vary: Authorization`. Protected media is never written to or read from a
shared cache, so `Vary: Authorization` is the only variant required and is emitted exactly.

Anonymous access is limited to `public-artwork/{assetId}/image.jpg` (asset id must be a lowercase
canonical UUID). There is no anonymous route for `hls/**`, none for raw worker output
`artwork/**`, and none for any source object.

## Enforcement order

Nothing below the current step runs when a step refuses. In particular no `env.DELIVERY.get(...)`
and no `caches.default.match(...)` happens for a rejected request; this is asserted by the test
suite with a recording bucket fake.

| # | Condition | Status |
| --- | --- | --- |
| 1 | No `Authorization` header, or a query-string token, cookie or non-bearer scheme | 401 |
| 2 | Wrong signature, unknown `kid`, `alg` not RS256, expired, `nbf` in the future, wrong `iss`, `aud` without the configured audience, malformed token, missing claim, unusable key config | 401 (key config: 500) |
| 3 | Verified token whose `prefix` is not the exact canonical prefix of the path, or whose `movieId`/`versionId` disagrees, or a non-canonical `/hls/...` shape | 403 |
| 4 | Traversal (`..`), percent-encoding, `%2e%2e`, backslash, double slash, control characters, unknown route, `artwork/**`, source-style path | 400 (malformed) / 403 (outside the allowlist) |
| 5 | Method other than GET/HEAD | 405 with `Allow: GET, HEAD, OPTIONS` |
| 6 | More than one range, or a syntactically invalid range | 416 |
| 6 | One syntactically valid range the bucket cannot satisfy | 416 (after the read) |
| 7 | Authorized GET/HEAD, no range | 200 with the protected header set |
| 7 | Authorized GET/HEAD with one satisfiable range | 206 with `Content-Range` |

Two notes on real inputs:

- The WHATWG URL parser removes dot segments before the Worker sees the path, so `/hls/a/b/../c`
  arrives as a different (non-canonical) path and is refused as such. Encoded separators
  (`%2f`, `%5c`, `%2e`) survive parsing and are refused by the raw-path guard.
- `If-Range` and other conditional requests are not implemented; a conditional header is ignored.

## Byte ranges

Exactly one range is supported. `bytes=a-b`, `bytes=a-` and `bytes=-n` return 206 with an exact
`Content-Range`, `Content-Length` and `Accept-Ranges: bytes`. Multiple ranges (`bytes=0-1,5-6`),
unknown units (`items=0-9`), reversed bounds (`bytes=9-0`), non-numeric bounds, an empty spec and
unsafe integers are 416 *before* any read. Ranges are ignored on the anonymous artwork route,
which always returns the full object with 200.

## CORS

`ALLOWED_ORIGINS` is a comma-separated allowlist. For an allowed origin the Worker echoes the
origin (`Access-Control-Allow-Origin`), advertises `GET, HEAD, OPTIONS`, allows the
`Authorization` request header, exposes `Content-Range` and `Accept-Ranges`, and sets
`Vary: Origin` on preflight, artwork and error responses. For any other origin - including an
absent `Origin` - no `Access-Control-Allow-Origin` header is returned, so the browser hides the
response. Preflight is answered with 204 from configuration alone: it requires no token and never
touches the cache or the bucket.

## Local development and verification

```sh
npm.cmd install                 # in media-gateway/, creates package-lock.json
npm.cmd test                    # vitest run - 76 gateway tests
npm.cmd run typecheck           # tsc --noEmit
npm.cmd run dev                 # wrangler dev (needs a real R2 binding to serve objects)
npx.cmd wrangler deploy --dry-run --outdir .wrangler-dry   # bundle + config validation only
```

The test suite generates a test-only RSA key pair in-process, signs tokens with it, and injects a
fake `env.DELIVERY` that records every requested key. Rejections are asserted by checking that the
recorded key list is empty. `.wrangler/` and `.wrangler-dry/` are ignored by
`media-gateway/.gitignore`.

Local bucket setup (source and delivery buckets, MinIO) is described in
[media-local.md](media-local.md). The gateway does not create or seed buckets.

## Deployment

1. `wrangler secret put JWT_PUBLIC_JWK` (or `JWT_PUBLIC_JWKS`) in the target environment.
2. Set `ALLOWED_ORIGINS` to the exact browser origins that must play protected media.
3. `npm.cmd run deploy`.
4. Confirm the deployed route is attached by the environment, not by `wrangler.jsonc`.

Key rotation: publish the new public key in `JWT_PUBLIC_JWKS` alongside the current one, wait for
the longest token lifetime (at most five minutes) plus clock skew, then remove the old key.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Every protected request is 401 | `kid` in tokens does not match the configured key, or the wrong audience/issuer is deployed. |
| Freshly issued tokens are briefly 401 | Clock skew: `nbf` equals `iat`, and validation has zero tolerance. Sync NTP on the issuer host. |
| Every protected request is 500 | `JWT_PUBLIC_JWK`/`JWT_PUBLIC_JWKS` is missing, is not JSON, or is not an RSA public key. |
| 403 on a path that looks correct | The token `prefix`, `movieId` or `versionId` does not match the requested attempt, or the file name is outside the `index.m3u8` / `segment_NNNNN.ts` allowlist. |
| 416 on a segment request | The player sent multiple ranges; only one range is supported. |
| Browser blocks playback with no visible HTTP error | The page origin is not in `ALLOWED_ORIGINS`, so no CORS header is returned. |
| 403 for artwork | The path is not exactly `/public-artwork/{uuid}/image.jpg`; raw `artwork/**` output is intentionally not exposed. |

## Known limitations and assumptions

- The token `prefix` is the exact `/hls/{movieId}/{versionId}/{attemptId}/` produced by the
  backend media catalog, and it matches the delivery object layout
  `hls/{movieId}/{versionId}/{attemptId}/{file}` (`index.m3u8`, `segment_%05d.ts`) written by the
  transcoder. Deeper per-rendition playlists (for example `.../1080p/index.m3u8`) are not served;
  only `index.m3u8` and `segment_NNNNN.ts` objects are allowlisted.
- `segment_NNNNN.ts` accepts five or more zero-padded digits so a long playlist keeps working.
- `purpose` is validated to be `VIEWER` or `PREVIEW`; the Worker does not otherwise distinguish
  preview from viewer tokens, because the signed `prefix` already scopes the object.
- Standard claims are validated strictly with zero clock tolerance. The issuer sets `nbf` equal to
  `iat`, so the issuing host and the Worker must be clock-synchronized: an issuer clock ahead of
  Cloudflare by even a second makes a freshly issued token briefly unusable. Keep NTP enabled on
  both sides.
- Parsing is strict: canonical media paths are expected to contain no percent-encoding.
- A ranged read that R2 answers with `null` is reported as 416. A missing object requested with a
  range is therefore indistinguishable from an unsatisfiable range.
- `HEAD` uses the binding's metadata-only read when available and otherwise reads and discards the
  body; no `If-Range`, no conditional GET, no multi-range responses.
- Artwork is cacheable and may be served from the edge cache; protected HLS is never shared-cached.
- Local acceptance, deployment routing, WAF/rate limiting and log retention are outside this
  Worker and must be verified in the deployment environment.
