/**
 * `media-gateway` Worker entry point.
 *
 * Request order is the security contract of this Worker:
 *   1. CORS preflight is answered from configuration only.
 *   2. Only GET/HEAD continue.
 *   3. The path is allowlisted and canonicalized.
 *   4. Protected HLS requests are authenticated and the token is bound to the exact path.
 *   5. The byte range is validated.
 *   6. Only now is the cache or the delivery bucket read.
 *
 * No error response ever repeats the request path, a storage key, the token or any claim.
 */

import { authenticate, loadVerificationConfig } from './auth';
import type { AuthEnv, VerificationConfig } from './auth';
import { applyCors, preflightResponse, resolveCors } from './cors';
import type { CorsDecision, CorsEnv } from './cors';
import { artworkHeaders, discardBody, serveObject } from './delivery';
import type { DeliveryBucket, DeliveryObject, ServeObjectResult } from './delivery';
import { bindClaimsToPath, parseRangeHeader, parseRoute } from './media-path';
import type { ArtworkRoute } from './media-path';

/** Worker bindings and configuration. */
export interface Env extends AuthEnv, CorsEnv {
  /** Private delivery bucket. The only storage this Worker can reach. */
  readonly DELIVERY: DeliveryBucket;
}

/** Minimal shared-cache surface (Cloudflare `caches.default`). */
export interface GatewayCache {
  match(request: Request): Promise<Response | undefined>;
  put(request: Request, response: Response): Promise<void>;
}

/** Minimal execution context surface (Cloudflare `ExecutionContext`). */
export interface GatewayExecutionContext {
  waitUntil(promise: Promise<unknown>): void;
}

export interface GatewayDeps {
  /**
   * Optional shared-cache adapter. It is consulted for the anonymous artwork route only, and
   * only after the path has been allowlisted, so a rejected request can never reach it.
   */
  readonly cache?: GatewayCache | undefined;
}

const ERROR_PHRASES: Record<number, string> = {
  400: 'bad request',
  401: 'unauthorized',
  403: 'forbidden',
  404: 'not found',
  405: 'method not allowed',
  416: 'range not satisfiable',
  500: 'internal error',
};

/** Generic, information-free error response. */
function errorResponse(status: number, cors: CorsDecision, extra?: Record<string, string>): Response {
  const headers = new Headers();
  headers.set('Content-Type', 'application/json; charset=utf-8');
  headers.set('Cache-Control', 'no-store');
  if (extra !== undefined) {
    for (const [name, value] of Object.entries(extra)) {
      headers.set(name, value);
    }
  }
  applyCors(headers, cors, { varyOrigin: true });
  const phrase = ERROR_PHRASES[status] ?? 'error';
  return new Response(JSON.stringify({ error: phrase }), { status, headers });
}

/** Resolve the Cloudflare edge cache when the runtime provides one. */
function runtimeCache(): GatewayCache | undefined {
  const globalCaches = (globalThis as { caches?: { default?: GatewayCache } }).caches;
  return globalCaches?.default;
}

/** Treat an unusable key configuration as a server fault instead of an authentication failure. */
function resolveVerificationConfig(env: Env): VerificationConfig | null {
  try {
    return loadVerificationConfig(env);
  } catch {
    return null;
  }
}

async function servePublicArtwork(options: {
  request: Request;
  env: Env;
  ctx: GatewayExecutionContext | undefined;
  cors: CorsDecision;
  route: ArtworkRoute;
  cache: GatewayCache | undefined;
}): Promise<Response> {
  const { request, env, ctx, cors, route, cache } = options;
  const isHead = request.method === 'HEAD';
  const cacheKey = new Request(request.url, { method: 'GET' });

  // Artwork is public and cacheable, so a shared-cache lookup is allowed here - after the
  // path has already been allowlisted, and with no token requirement.
  if (cache !== undefined && !isHead) {
    try {
      const hit = await cache.match(cacheKey);
      if (hit !== undefined) {
        const headers = new Headers(hit.headers);
        applyCors(headers, cors, { varyOrigin: true });
        return new Response(hit.body, { status: hit.status, headers });
      }
    } catch {
      // A cache failure must never break delivery; fall through to the bucket.
    }
  }

  let object: DeliveryObject | null;
  try {
    object = await env.DELIVERY.get(route.key);
  } catch {
    return errorResponse(500, cors);
  }
  if (object === null) {
    return errorResponse(404, cors);
  }

  const headers = artworkHeaders();
  headers.set('Content-Length', String(object.size));
  applyCors(headers, cors, { varyOrigin: true });

  if (isHead) {
    await discardBody(object.body);
    return new Response(null, { status: 200, headers });
  }

  const response = new Response(object.body ?? null, { status: 200, headers });
  if (cache !== undefined && ctx !== undefined) {
    try {
      ctx.waitUntil(cache.put(cacheKey, response.clone()));
    } catch {
      // Caching is an optimization; ignore an unusable cache binding.
    }
  }
  return response;
}

/**
 * Build a gateway handler.
 *
 * Exposed as a factory so tests can inject a shared-cache adapter; the default export is the
 * production handler.
 */
export function createGateway(deps: GatewayDeps = {}) {
  return {
    async fetch(request: Request, env: Env, ctx?: GatewayExecutionContext): Promise<Response> {
      const cors = resolveCors(request.headers.get('Origin'), env.ALLOWED_ORIGINS);

      // 1. Preflight: configuration only, no token required, no cache or bucket access.
      if (request.method === 'OPTIONS') {
        return preflightResponse(cors);
      }

      // 2. Only GET and HEAD reach the media routes.
      const method = request.method;
      if (method !== 'GET' && method !== 'HEAD') {
        return errorResponse(405, cors, { Allow: 'GET, HEAD, OPTIONS' });
      }

      // 3. Allowlist and canonicalize the path before anything else.
      const route = parseRoute(new URL(request.url).pathname);
      if (route.kind === 'rejected') {
        return errorResponse(route.status, cors);
      }

      if (route.kind === 'public-artwork') {
        return servePublicArtwork({
          request,
          env,
          ctx,
          cors,
          route,
          cache: deps.cache ?? runtimeCache(),
        });
      }

      // 4. Authenticate and bind the token to this exact path, still without any storage read.
      const config = resolveVerificationConfig(env);
      if (config === null) {
        return errorResponse(500, cors);
      }
      const auth = await authenticate(request, config);
      if (!auth.ok) {
        return errorResponse(auth.status, cors);
      }
      if (!bindClaimsToPath(auth.claims, route).ok) {
        return errorResponse(403, cors);
      }

      // 5. Validate the single byte range before reading anything.
      const range = parseRangeHeader(request.headers.get('Range'));
      if (range.kind === 'invalid') {
        return errorResponse(416, cors);
      }

      // 6. Authorized: read the delivery object and stream it back.
      let result: ServeObjectResult;
      try {
        result = await serveObject(env.DELIVERY, {
          method,
          key: route.key,
          contentType: route.contentType,
          range,
        });
      } catch {
        return errorResponse(500, cors);
      }
      if (!result.ok) {
        return errorResponse(result.status, cors);
      }
      applyCors(result.response.headers, cors);
      return result.response;
    },
  };
}

export default createGateway();
