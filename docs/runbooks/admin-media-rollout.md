# Admin media rollout runbook

This runbook records the staging/provider configuration the local acceptance harness cannot
exercise, and the provider checks that decide PASS / FAIL / NOT_RUN for a staging-readiness
decision. **Nothing here creates or deploys resources; provisioning requires a separate
authorization.**

## Cloudflare R2 buckets

Create two **private** buckets, e.g. `media-source-<env>` and `media-delivery-<env>`:

- `MEDIA_SOURCE_BUCKET` — receives presigned browser part uploads; read by the worker and backend.
- `MEDIA_DELIVERY_BUCKET` — holds validated HLS and artwork; read only through the media gateway.

No public R2 domain access, no public bucket policy. Browsers receive presigned operations only.

## R2 source-bucket CORS

The Admin SPA PUTs multipart parts straight to presigned source-bucket URLs, which is a separate
origin, so the source bucket needs CORS for exactly the Admin web origin. Configure it with the
Cloudflare dashboard or API:

```text
AllowedOrigins: ["https://admin.<environment-domain>"]
AllowedMethods: ["PUT", "HEAD", "OPTIONS"]
AllowedHeaders: ["*"]          # the presigned x-amz-* part headers
ExposeHeaders: ["ETag"]
MaxAgeSeconds: 3000
```

Do not use `*` for allowed origins. The local acceptance harness mirrors this configuration on its
disposable MinIO (`scripts/run_media_acceptance.py`), so a missing or wrong R2 CORS rule shows up
as a browser-blocked upload in staging.

## Credentials

- `S3_ACCESS_KEY` / `S3_SECRET_KEY` — backend + worker internal access.
- `S3_BROWSER_ACCESS_KEY` / `S3_BROWSER_SECRET_KEY` — a **signing-only** token: it must be able to
  sign multipart upload/part/completion operations on the source bucket and nothing else (no
  read/write on delivery, no bucket administration). These are the credentials the browser never
  receives; only URLs signed by them are.
- `MEDIA_WORKER_CREDENTIAL` — shared machine credential between backend and worker (backend rejects
  job-result callbacks without it).

## Backend deployment

Environment for the media stack (defaults in `backend/src/main/resources/application.yml`):

```text
MEDIA_SOURCE_BUCKET=media-source-<env>
MEDIA_DELIVERY_BUCKET=media-delivery-<env>
S3_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com   # internal server role
S3_BROWSER_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com  # browser-reachable signing
S3_REGION=auto
S3_ACCESS_KEY / S3_SECRET_KEY
S3_BROWSER_ACCESS_KEY / S3_BROWSER_SECRET_KEY
MEDIA_WORKER_CREDENTIAL=<random value shared with the worker>
MEDIA_SIGNING_KEY_ID=<key id>            # must equal the JWK "kid" the gateway holds
```

Media tokens are signed with the dedicated key pair at `app.media.signing.*-location`; give the
gateway only the **public** key as a JWK (see [media-gateway.md](media-gateway.md)).

## Frontend deployment

The web origin, the media gateway origin and the storage origin are three different origins, and
the bundle plus the CSP must all agree:

```text
VITE_MEDIA_BASE_URL=https://media.<env-domain>      # build argument, burned into the bundle
MEDIA_ORIGIN=https://media.<env-domain>             # container start, CSP connect-src
MEDIA_STORAGE_ORIGIN=https://<account-id>.r2.cloudflarestorage.com  # container start, CSP connect-src
```

`frontend/nginx.conf.template` renders both CSP origins through
`frontend/docker-entrypoint.d/05-render-nginx-config.sh`. If `MEDIA_STORAGE_ORIGIN` is missing,
uploads are blocked by `connect-src` (the same failure the acceptance harness diagnosed); if
`MEDIA_ORIGIN` disagrees with the bundle, playback is blocked or 401s.

## Media gateway deployment

Follow [media-gateway.md](media-gateway.md):

```sh
wrangler secret put JWT_PUBLIC_JWK   # public JWK with kid == MEDIA_SIGNING_KEY_ID
# ALLOWED_ORIGINS = https://<web-origin>  (comma-separated, exact origins)
npm.cmd run deploy
```

## Provider checks (record PASS / FAIL / NOT_RUN)

These are the `providerChecks` the local acceptance report always lists as NOT_RUN. Execute them
only in a separately authorized staging environment, once, and record results with reasons:

```text
1. cloudflare-r2-source-and-delivery-buckets
   - both buckets exist, are private, and the signing-only credential cannot read
     media-delivery-<env> (verify with an s3api GetObject attempt that must fail).
2. cloudflare-worker-protected-hls-deployment
   - deploy the worker bundle, then request a protected HLS path with a valid media token
     (200), without a token (401), and an authorized byte range (206).
3. cloudflare-edge-cache-and-waf
   - confirm protected HLS responses carry Cache-Control: private and that a repeat request
     for public artwork is served with its public cache policy.
```

Missing cloud access must be recorded as NOT_RUN with a reason — never as a pass.

## Rollout order

1. R2 buckets + CORS + credentials.
2. Backend + worker with the media environment and `MEDIA_SIGNING_KEY_ID`.
3. Gateway secrets and deployment.
4. Frontend build argument and container environment.
5. One real browser upload/playback pass on staging.
6. Record the provider-check results and attach them to the local acceptance report before a
   staging-readiness claim.
