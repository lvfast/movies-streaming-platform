/**
 * Bearer extraction, RS256 verification and claim validation for media tokens.
 *
 * The gateway holds public verification keys only: it never sees the signing private key or
 * any storage credential. Every failure path is collapsed into a bare 401 so a caller cannot
 * learn whether a token was missing, malformed, expired or signed by a different key, and no
 * token, claim, or storage key is ever echoed back or logged.
 */

import { decodeProtectedHeader, importJWK, jwtVerify, type JWK, type JWTPayload } from 'jose';

import type { MediaTokenClaims } from './media-path';

export const DEFAULT_ISSUER = 'lvfast-media-backend';
export const DEFAULT_AUDIENCE = 'lvfast-media';

/** The `env` surface needed for token verification. */
export interface AuthEnv {
  /** A single public JWK as JSON. Must carry the `kid` used in token headers. */
  readonly JWT_PUBLIC_JWK?: string | undefined;
  /** A JWK Set as JSON (`{"keys":[...]}`) so keys can be rotated by `kid`. */
  readonly JWT_PUBLIC_JWKS?: string | undefined;
  readonly JWT_ISSUER?: string | undefined;
  readonly JWT_AUDIENCE?: string | undefined;
}

/** Raised when the worker configuration itself is unusable; never a client error. */
export class GatewayConfigurationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'GatewayConfigurationError';
  }
}

/** Claims the gateway is allowed to trust once the signature and standard claims check out. */
export interface VerifiedMediaToken extends MediaTokenClaims {
  readonly sub: string;
  readonly sid: string;
  readonly purpose: 'VIEWER' | 'PREVIEW';
  readonly expiresAt: number;
}

export interface VerificationConfig {
  readonly issuer: string;
  readonly audience: string;
  readonly keys: readonly JWK[];
}

export type AuthResult =
  | { readonly ok: true; readonly claims: VerifiedMediaToken }
  | { readonly ok: false; readonly status: 401 };

const UNAUTHORIZED: AuthResult = { ok: false, status: 401 };

/** Claims that must be present in a media token, per the backend contract. */
const REQUIRED_CLAIMS = [
  'iss',
  'aud',
  'sub',
  'sid',
  'movieId',
  'versionId',
  'prefix',
  'purpose',
  'iat',
  'nbf',
  'exp',
  'jti',
] as const;

const PURPOSES = new Set(['VIEWER', 'PREVIEW']);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Whitelist RSA public parameters. Private parameters (`d`, `p`, `q`, ...) are dropped so a
 * misconfigured secret can never make the Worker hold signing material.
 */
function asPublicJwk(value: unknown): JWK {
  if (!isRecord(value)) {
    throw new GatewayConfigurationError('media gateway verification key is not a JSON object');
  }
  if (value.kty !== 'RSA') {
    throw new GatewayConfigurationError('media gateway verification key must be an RSA public key');
  }
  if (typeof value.n !== 'string' || typeof value.e !== 'string') {
    throw new GatewayConfigurationError('media gateway verification key is missing RSA public parameters');
  }
  const publicJwk: JWK = { kty: 'RSA', n: value.n, e: value.e };
  if (typeof value.kid === 'string' && value.kid !== '') {
    publicJwk.kid = value.kid;
  }
  if (typeof value.alg === 'string') {
    publicJwk.alg = value.alg;
  }
  if (typeof value.use === 'string') {
    publicJwk.use = value.use;
  }
  if (Array.isArray(value.key_ops)) {
    const keyOps = value.key_ops.filter((op): op is string => typeof op === 'string');
    if (keyOps.length > 0) {
      publicJwk.key_ops = keyOps;
    }
  }
  return publicJwk;
}

function parseJson(raw: string | undefined, label: string): unknown {
  if (raw === undefined || raw.trim() === '') {
    return undefined;
  }
  try {
    return JSON.parse(raw);
  } catch {
    throw new GatewayConfigurationError(`media gateway ${label} is not valid JSON`);
  }
}

/**
 * Build the verification configuration from `env`.
 *
 * `JWT_PUBLIC_JWKS` (rotation set) and `JWT_PUBLIC_JWK` (single key) may both be present;
 * keys are looked up by the token header `kid` across the union. Throws
 * `GatewayConfigurationError` when the configuration cannot verify anything, which the
 * caller answers with 500 rather than pretending the client is unauthorized.
 */
export function loadVerificationConfig(env: AuthEnv): VerificationConfig {
  const keys: JsonWebKey[] = [];

  const jwks = parseJson(env.JWT_PUBLIC_JWKS, 'JWT_PUBLIC_JWKS');
  if (jwks !== undefined) {
    if (!isRecord(jwks) || !Array.isArray(jwks.keys)) {
      throw new GatewayConfigurationError('media gateway JWT_PUBLIC_JWKS must be a JWK Set with a keys array');
    }
    for (const key of jwks.keys) {
      keys.push(asPublicJwk(key));
    }
  }

  const single = parseJson(env.JWT_PUBLIC_JWK, 'JWT_PUBLIC_JWK');
  if (single !== undefined) {
    keys.push(asPublicJwk(single));
  }

  if (keys.length === 0) {
    throw new GatewayConfigurationError('media gateway has no JWT public key configured');
  }

  return {
    issuer: env.JWT_ISSUER !== undefined && env.JWT_ISSUER !== '' ? env.JWT_ISSUER : DEFAULT_ISSUER,
    audience: env.JWT_AUDIENCE !== undefined && env.JWT_AUDIENCE !== '' ? env.JWT_AUDIENCE : DEFAULT_AUDIENCE,
    keys,
  };
}

/**
 * Extract the raw bearer token from the `Authorization` header.
 *
 * Only the header is consulted: a query-string token, a cookie, or any non-bearer scheme
 * (including a bare token) never authenticates. The scheme is matched case-insensitively per
 * RFC 9110 and exactly one credential is accepted.
 */
export function bearerToken(request: Request): string | null {
  const header = request.headers.get('Authorization');
  if (header === null) {
    return null;
  }
  const parts = header.trim().split(/[ \t]+/);
  if (parts.length !== 2) {
    return null;
  }
  const scheme = parts[0];
  const credential = parts[1];
  if (scheme === undefined || credential === undefined) {
    return null;
  }
  if (scheme.toLowerCase() !== 'bearer' || credential === '') {
    return null;
  }
  return credential;
}

function selectKey(config: VerificationConfig, kid: unknown): JWK | null {
  if (typeof kid !== 'string' || kid === '') {
    return null;
  }
  for (const key of config.keys) {
    if (key.kid === kid) {
      return key;
    }
  }
  return null;
}

function stringClaim(payload: JWTPayload, name: string): string | null {
  const value = payload[name];
  return typeof value === 'string' && value !== '' ? value : null;
}

function readClaims(payload: JWTPayload): VerifiedMediaToken | null {
  const sub = stringClaim(payload, 'sub');
  const sid = stringClaim(payload, 'sid');
  const movieId = stringClaim(payload, 'movieId');
  const versionId = stringClaim(payload, 'versionId');
  const prefix = stringClaim(payload, 'prefix');
  const purpose = stringClaim(payload, 'purpose');
  const expiresAt = payload.exp;
  if (sub === null || sid === null || movieId === null || versionId === null || prefix === null) {
    return null;
  }
  if (purpose === null || !PURPOSES.has(purpose)) {
    return null;
  }
  if (typeof expiresAt !== 'number' || !Number.isFinite(expiresAt)) {
    return null;
  }
  return { sub, sid, movieId, versionId, prefix, purpose: purpose as 'VIEWER' | 'PREVIEW', expiresAt };
}

/**
 * Verify a request's media token.
 *
 * Rejects with 401 when there is no bearer credential, the header is unusable, `alg` is not
 * RS256, `kid` is absent or unknown, the signature does not verify, a required claim is
 * missing, `iss`/`aud` do not match the configured values, or `exp`/`nbf` are outside their
 * validity window. No cache or bucket access happens here or because of this.
 */
export async function authenticate(request: Request, config: VerificationConfig): Promise<AuthResult> {
  const token = bearerToken(request);
  if (token === null) {
    return UNAUTHORIZED;
  }

  let kid: unknown;
  try {
    const header = decodeProtectedHeader(token);
    if (header.alg !== 'RS256') {
      return UNAUTHORIZED;
    }
    kid = header.kid;
  } catch {
    return UNAUTHORIZED;
  }

  const jwk = selectKey(config, kid);
  if (jwk === null) {
    return UNAUTHORIZED;
  }

  try {
    const key = await importJWK(jwk, 'RS256');
    const { payload } = await jwtVerify(token, key, {
      algorithms: ['RS256'],
      issuer: config.issuer,
      audience: config.audience,
      requiredClaims: [...REQUIRED_CLAIMS],
    });
    const claims = readClaims(payload);
    if (claims === null) {
      return UNAUTHORIZED;
    }
    return { ok: true, claims };
  } catch {
    return UNAUTHORIZED;
  }
}
