/**
 * Local media-gateway runner for the P5 browser smoke.
 *
 * Runs the real media gateway in Node. The bundle comes from `media-gateway/src/index.ts`, and only
 * the runtime environment is supplied here: an HTTP server, an R2-compatible delivery bucket backed
 * by the Compose MinIO delivery bucket, and the gateway's configuration bindings. Every
 * authorization and delivery decision is therefore the production code path, so the browser smoke
 * proves the Worker logic without a deploy. Cloudflare-specific behavior (workerd, the edge cache and
 * real R2) stays covered by `test/gateway.test.ts` and the deployment runbook.
 *
 * Usage: node p5-gateway-node.mjs <deliveryBucket> <accessKey> <secretKey> <port> <jwtIssuer>
 *        <jwtAudience> <allowedOrigins> <publicJwkJson>
 *
 * `GET /__smoke/requests` answers the smoke-only request journal described below.
 */

import { mkdtemp, rm } from 'node:fs/promises';
import http from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { build } from 'esbuild';

const [
  deliveryBucket,
  accessKey,
  secretKey,
  port,
  jwtIssuer,
  jwtAudience,
  allowedOrigins,
  publicJwk,
] = process.argv.slice(2);

const minioEndpoint = process.env.AWS_ENDPOINT_URL_S3 ?? 'http://127.0.0.1:19000';

const bundleDirectory = await mkdtemp(join(tmpdir(), 'p5-gateway-'));
const bundlePath = join(bundleDirectory, 'gateway.mjs');
await build({
  entryPoints: ['src/index.ts'],
  outfile: bundlePath,
  bundle: true,
  format: 'esm',
  platform: 'node',
  target: 'node22',
  logLevel: 'warning',
});

const { createGateway } = await import(new URL(`file://${bundlePath.replace(/\\/g, '/')}`).href);

/**
 * The subset of the R2 binding `delivery.ts` uses, backed by the Compose MinIO delivery bucket.
 * The smoke stack's MinIO policies allow anonymous reads of the delivery bucket only from the local
 * host, so no storage credential reaches the gateway: the real deployment read path is unchanged.
 */
const deliveryBucketBinding = {
  async get(key, options = {}) {
    const range = options?.range;
    let rangeHeader;
    if (range?.suffix !== undefined) {
      rangeHeader = `bytes=-${range.suffix}`;
    } else if (range?.offset !== undefined && range?.length !== undefined) {
      rangeHeader = `bytes=${range.offset}-${range.offset + range.length - 1}`;
    } else if (range?.offset !== undefined) {
      rangeHeader = `bytes=${range.offset}-`;
    }
    const response = await fetch(`${minioEndpoint}/${deliveryBucket}/${key}`, {
      headers: rangeHeader ? { Range: rangeHeader } : {},
    });
    if (response.status === 404 || response.status === 416) {
      await response.body?.cancel();
      return null;
    }
    if (!response.ok) {
      await response.body?.cancel();
      throw new Error(`delivery bucket read failed with ${response.status}`);
    }
    const contentRange = response.headers.get('content-range');
    const span = contentRange ? /^bytes (\d+)-(\d+)\/(\d+)$/.exec(contentRange) : null;
    if (span) {
      return {
        body: response.body,
        size: Number(span[3]),
        range: { offset: Number(span[1]), length: Number(span[2]) - Number(span[1]) + 1 },
      };
    }
    return { body: response.body, size: Number(response.headers.get('content-length') ?? 0) };
  },
};

const gateway = createGateway();

/**
 * Smoke-only request journal. The browser smoke has to prove that the media bearer header reached
 * the gateway on the manifest and segment requests and nowhere else, and the gateway deliberately
 * logs nothing about authorization. The journal records the method, path, whether an Authorization
 * header was present and the response status - never a token or a header value - and is served only
 * from this local runner.
 */
const requests = [];

const server = http.createServer(async (request, response) => {
  if (request.method === 'GET' && request.url === '/__smoke/requests') {
    const body = Buffer.from(JSON.stringify(requests));
    response.writeHead(200, {
      'Content-Type': 'application/json',
      'Content-Length': String(body.byteLength),
      'Cache-Control': 'no-store',
    });
    response.end(body);
    return;
  }
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  const headers = new Headers();
  for (const [name, value] of Object.entries(request.headers)) {
    if (typeof value === 'string') headers.set(name, value);
    else if (Array.isArray(value)) headers.set(name, value.join(', '));
  }
  const hasBody = chunks.length > 0;
  const gatewayRequest = new Request(`http://127.0.0.1:${port}${request.url}`, {
    method: request.method,
    headers,
    body: hasBody ? Buffer.concat(chunks) : undefined,
  });
  let gatewayResponse;
  try {
    gatewayResponse = await gateway.fetch(gatewayRequest, {
      DELIVERY: deliveryBucketBinding,
      JWT_PUBLIC_JWK: publicJwk,
      JWT_ISSUER: jwtIssuer,
      JWT_AUDIENCE: jwtAudience,
      ALLOWED_ORIGINS: allowedOrigins,
    });
  } catch (error) {
    requests.push({
      method: request.method,
      path: request.url,
      authorized: headers.has('authorization'),
      origin: headers.get('origin'),
      status: 500,
    });
    throw error;
  }
  requests.push({
    method: request.method,
    path: request.url,
    authorized: headers.has('authorization'),
    origin: headers.get('origin'),
    status: gatewayResponse.status,
  });
  const responseHeaders = {};
  gatewayResponse.headers.forEach((value, name) => {
    responseHeaders[name] = value;
  });
  response.writeHead(gatewayResponse.status, responseHeaders);
  if (!gatewayResponse.body) {
    response.end();
    return;
  }
  const reader = gatewayResponse.body.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    response.write(Buffer.from(value));
  }
  response.end();
});

server.listen(Number(port), '127.0.0.1', () => {
  console.log('media gateway listening on http://127.0.0.1:' + port);
});

const stop = async () => {
  server.close();
  await rm(bundleDirectory, { recursive: true, force: true });
  process.exit(0);
};
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
process.on('message', (message) => {
  if (message === 'shutdown') void stop();
});
