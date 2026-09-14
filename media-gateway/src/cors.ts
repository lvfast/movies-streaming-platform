/**
 * Origin allowlisting, preflight handling and CORS response headers.
 *
 * `ALLOWED_ORIGINS` is a comma-separated list and is empty by default, which grants no
 * browser origin any CORS access. A disallowed origin simply receives no
 * `Access-Control-Allow-Origin` header, so the browser hides the response.
 */

export interface CorsEnv {
  /** Comma-separated origin allowlist; empty or absent means "no origin allowed". */
  readonly ALLOWED_ORIGINS?: string | undefined;
}

export interface CorsDecision {
  readonly allowed: boolean;
  readonly origin: string | null;
}

export const ALLOWED_METHODS = 'GET, HEAD, OPTIONS';
export const ALLOWED_REQUEST_HEADERS = 'Authorization';
export const EXPOSED_RESPONSE_HEADERS = 'Content-Range, Accept-Ranges';

/** Split and normalize the configured origin allowlist. */
export function allowedOrigins(raw: string | undefined): string[] {
  if (raw === undefined || raw.trim() === '') {
    return [];
  }
  return raw
    .split(',')
    .map((origin) => origin.trim())
    .filter((origin) => origin !== '');
}

/** Decide whether the request origin may receive CORS headers. */
export function resolveCors(origin: string | null, raw: string | undefined): CorsDecision {
  if (origin === null || origin === '') {
    return { allowed: false, origin: null };
  }
  return { allowed: allowedOrigins(raw).includes(origin), origin };
}

/** Add `value` to a `Vary` header without duplicating an existing token. */
export function appendVary(headers: Headers, value: string): void {
  const current = headers.get('Vary');
  if (current === null || current.trim() === '') {
    headers.set('Vary', value);
    return;
  }
  const tokens = current.split(',').map((token) => token.trim().toLowerCase());
  if (!tokens.includes(value.toLowerCase())) {
    headers.set('Vary', `${current}, ${value}`);
  }
}

/**
 * Apply CORS response headers for an actual (non-preflight) response.
 *
 * `varyOrigin` is set for responses that a shared cache may store. Protected HLS responses
 * keep their contract-mandated `Vary: Authorization` and are never shared-cached
 * (`Cache-Control: private, max-age=0, no-store`), so they pass `false`.
 */
export function applyCors(headers: Headers, cors: CorsDecision, options: { varyOrigin?: boolean } = {}): void {
  if (options.varyOrigin === true) {
    appendVary(headers, 'Origin');
  }
  if (cors.allowed && cors.origin !== null) {
    headers.set('Access-Control-Allow-Origin', cors.origin);
    headers.set('Access-Control-Expose-Headers', EXPOSED_RESPONSE_HEADERS);
  }
}

/**
 * Answer a CORS preflight.
 *
 * This never requires a token and never touches the cache or the bucket: the response is a
 * fixed 204 derived from the origin allowlist only. A disallowed (or absent) origin gets 204
 * without `Access-Control-Allow-Origin`.
 */
export function preflightResponse(cors: CorsDecision): Response {
  const headers = new Headers();
  headers.set('Vary', 'Origin');
  if (cors.allowed && cors.origin !== null) {
    headers.set('Access-Control-Allow-Origin', cors.origin);
    headers.set('Access-Control-Allow-Methods', ALLOWED_METHODS);
    headers.set('Access-Control-Allow-Headers', ALLOWED_REQUEST_HEADERS);
    headers.set('Access-Control-Expose-Headers', EXPOSED_RESPONSE_HEADERS);
  }
  return new Response(null, { status: 204, headers });
}
