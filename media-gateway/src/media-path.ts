/**
 * Canonical request-path parsing, allowlisting and byte-range parsing.
 *
 * Nothing in this module touches a cache or the delivery bucket. It only turns an untrusted
 * request path plus (optionally) an untrusted `Range` header into a small, closed set of
 * decisions, so a rejected request can never reach storage.
 */

/** Canonical lowercase UUID, exactly as issued by the backend (`UUID.toString()`). */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/**
 * The only file names served from the protected HLS prefix. `segment_00001.ts` is the
 * canonical shape; longer zero-padded counters stay allowlisted so a playlist with more
 * than 99,999 segments keeps working.
 */
const HLS_FILE_PATTERN = /^(?:index\.m3u8|segment_[0-9]{5,}\.ts)$/;

const HLS_SEGMENT_PATTERN = /^\/hls\/([^/]+)\/([^/]+)\/([^/]+)\/([^/]+)$/;

const ARTWORK_SEGMENT_PATTERN = /^\/public-artwork\/([^/]+)\/image\.jpg$/;

/** Content type derived from the extension; anything else is opaque bytes. */
export function contentTypeFor(fileName: string): string {
  const lower = fileName.toLowerCase();
  if (lower.endsWith('.m3u8')) {
    return 'application/vnd.apple.mpegurl';
  }
  if (lower.endsWith('.ts')) {
    return 'video/mp2t';
  }
  return 'application/octet-stream';
}

/** A parsed, allowlisted protected HLS request. */
export interface HlsRoute {
  readonly kind: 'hls';
  /** Delivery-bucket object key: the request path without the leading slash. */
  readonly key: string;
  readonly movieId: string;
  readonly versionId: string;
  readonly attemptId: string;
  readonly fileName: string;
  readonly contentType: string;
}

/** A parsed, allowlisted anonymous artwork request. */
export interface ArtworkRoute {
  readonly kind: 'public-artwork';
  readonly key: string;
  readonly assetId: string;
  readonly contentType: string;
}

/** A path that must be refused before any authorization or storage work. */
export interface RejectedRoute {
  readonly kind: 'rejected';
  /** 400 for malformed/ambiguous paths, 403 for anything outside the allowlist. */
  readonly status: 400 | 403;
}

export type Route = HlsRoute | ArtworkRoute | RejectedRoute;

const BAD_REQUEST: RejectedRoute = { kind: 'rejected', status: 400 };
const FORBIDDEN: RejectedRoute = { kind: 'rejected', status: 403 };

/**
 * Resolve a request pathname to a route.
 *
 * Guards, in order:
 * - no percent-encoding at all (canonical media paths never need it, so `%2e%2e`, `%2f`
 *   and `%5c` can never be decoded into a traversal),
 * - no backslashes, no empty path segments (double slash), no `.`/`..` segments,
 * - no control characters,
 * - then an explicit allowlist of `/hls/{movieId}/{versionId}/{attemptId}/{file}` and
 *   `/public-artwork/{assetId}/image.jpg`.
 *
 * Everything else is refused: there is no route for raw worker output (`artwork/**`), for
 * a source object, or for a bare bucket key.
 */
export function parseRoute(pathname: string): Route {
  if (!pathname.startsWith('/')) {
    return BAD_REQUEST;
  }
  if (pathname.includes('%')) {
    // Any escape could hide a separator or a dot segment; canonical paths are plain ASCII.
    return BAD_REQUEST;
  }
  if (pathname.includes('\\')) {
    return BAD_REQUEST;
  }
  if (pathname.includes('//')) {
    return BAD_REQUEST;
  }
  if (/[\u0000-\u001f\u007f]/.test(pathname)) {
    return BAD_REQUEST;
  }
  const segments = pathname.split('/').slice(1);
  for (const segment of segments) {
    if (segment === '' || segment === '.' || segment === '..') {
      return BAD_REQUEST;
    }
  }

  const hlsMatch = HLS_SEGMENT_PATTERN.exec(pathname);
  if (hlsMatch !== null) {
    const movieId = hlsMatch[1];
    const versionId = hlsMatch[2];
    const attemptId = hlsMatch[3];
    const fileName = hlsMatch[4];
    if (!UUID_PATTERN.test(movieId) || !UUID_PATTERN.test(versionId) || !UUID_PATTERN.test(attemptId)) {
      return FORBIDDEN;
    }
    if (!HLS_FILE_PATTERN.test(fileName)) {
      return FORBIDDEN;
    }
    return {
      kind: 'hls',
      key: `hls/${movieId}/${versionId}/${attemptId}/${fileName}`,
      movieId,
      versionId,
      attemptId,
      fileName,
      contentType: contentTypeFor(fileName),
    };
  }

  const artworkMatch = ARTWORK_SEGMENT_PATTERN.exec(pathname);
  if (artworkMatch !== null) {
    const assetId = artworkMatch[1];
    if (!UUID_PATTERN.test(assetId)) {
      return FORBIDDEN;
    }
    return {
      kind: 'public-artwork',
      key: `public-artwork/${assetId}/image.jpg`,
      assetId,
      contentType: 'image/jpeg',
    };
  }

  return FORBIDDEN;
}

/** The canonical, slash-terminated prefix of a protected HLS route. */
export function canonicalPrefix(route: HlsRoute): string {
  return `/hls/${route.movieId}/${route.versionId}/${route.attemptId}/`;
}

/** The claims of a verified media token that bind it to one immutable HLS prefix. */
export interface MediaTokenClaims {
  readonly movieId: string;
  readonly versionId: string;
  readonly prefix: string;
}

export type BindingResult = { readonly ok: true } | { readonly ok: false; readonly status: 403 };

/**
 * Bind a verified token to the requested path.
 *
 * The signed `prefix` must be exactly the canonical `/hls/{movieId}/{versionId}/{attemptId}/`
 * of the requested path (which also makes it a prefix of the object key), and the duplicated
 * `movieId`/`versionId` claims must agree. Any disagreement is a 403: the token is genuine but
 * it does not authorize this object.
 */
export function bindClaimsToPath(claims: MediaTokenClaims, route: HlsRoute): BindingResult {
  const expected = canonicalPrefix(route);
  if (claims.prefix !== expected) {
    return { ok: false, status: 403 };
  }
  if (claims.movieId !== route.movieId || claims.versionId !== route.versionId) {
    return { ok: false, status: 403 };
  }
  // The prefix (without its leading slash) is the leading path of the delivery object key.
  if (!route.key.startsWith(claims.prefix.slice(1))) {
    return { ok: false, status: 403 };
  }
  return { ok: true };
}

/** No range header, one satisfiable range, or a syntactically invalid request. */
export type RangeHeader =
  | { readonly kind: 'none' }
  | { readonly kind: 'single'; readonly start: number; readonly end: number | null }
  | { readonly kind: 'suffix'; readonly length: number };

export type ParsedRange = RangeHeader | { readonly kind: 'invalid' };

const INVALID_RANGE: ParsedRange = { kind: 'invalid' };

/**
 * Parse exactly one `bytes=` range.
 *
 * `bytes=a-b`, `bytes=a-` and `bytes=-n` are accepted. Multiple ranges (any comma) and any
 * other syntax (unknown unit, reversed bounds, non-digit values, unsafe integers, empty
 * spec) are `invalid`, which the caller must answer with 416 without touching storage.
 */
export function parseRangeHeader(value: string | null): ParsedRange {
  if (value === null) {
    return { kind: 'none' };
  }
  const match = /^bytes=([^,]*)$/i.exec(value.trim());
  if (match === null) {
    return INVALID_RANGE;
  }
  const spec = match[1].trim();
  if (spec === '') {
    return INVALID_RANGE;
  }

  const suffix = /^-([0-9]+)$/.exec(spec);
  if (suffix !== null) {
    const length = Number(suffix[1]);
    if (!Number.isSafeInteger(length) || length <= 0) {
      return INVALID_RANGE;
    }
    return { kind: 'suffix', length };
  }

  const bounds = /^([0-9]+)-([0-9]*)$/.exec(spec);
  if (bounds === null) {
    return INVALID_RANGE;
  }
  const start = Number(bounds[1]);
  if (!Number.isSafeInteger(start)) {
    return INVALID_RANGE;
  }
  const endText = bounds[2];
  if (endText === '') {
    return { kind: 'single', start, end: null };
  }
  const end = Number(endText);
  if (!Number.isSafeInteger(end) || end < start) {
    return INVALID_RANGE;
  }
  return { kind: 'single', start, end };
}
