/**
 * Delivery-bucket reads and response construction.
 *
 * This is the only module that touches `env.DELIVERY`. Callers must have already
 * authenticated, authorized and validated the path/range, because every call here performs a
 * bucket read. Object bodies are streamed straight through: nothing is buffered.
 */

import type { RangeHeader } from './media-path';

/** Range option accepted by an R2 `get`; exactly one of the three shapes is used. */
export interface DeliveryRangeOption {
  readonly offset?: number;
  readonly length?: number;
  readonly suffix?: number;
}

/** The subset of `R2ObjectBody` the gateway relies on. */
export interface DeliveryObject {
  readonly body?: ReadableStream<Uint8Array> | null;
  /** The object's size in bytes (R2 reports the stored object size). */
  readonly size: number;
  /** The portion actually returned for a ranged read. */
  readonly range?: { readonly offset: number; readonly length: number } | undefined;
}

/** The subset of an R2 bucket binding the gateway relies on. */
export interface DeliveryBucket {
  get(key: string, options?: { readonly range?: DeliveryRangeOption }): Promise<DeliveryObject | null>;
  /** Optional metadata-only read; used for HEAD when the binding provides it. */
  head?(key: string): Promise<{ readonly size: number } | null>;
}

export const PROTECTED_CACHE_CONTROL = 'private, max-age=0, no-store';
export const ARTWORK_CACHE_CONTROL = 'public, max-age=3600';
export const ARTWORK_CONTENT_TYPE = 'image/jpeg';

/** Headers shared by every protected HLS response. */
export function protectedHeaders(contentType: string): Headers {
  const headers = new Headers();
  headers.set('Content-Type', contentType);
  headers.set('Cache-Control', PROTECTED_CACHE_CONTROL);
  headers.set('Accept-Ranges', 'bytes');
  headers.set('Vary', 'Authorization');
  return headers;
}

/** Headers for the anonymous artwork route. */
export function artworkHeaders(): Headers {
  const headers = new Headers();
  headers.set('Content-Type', ARTWORK_CONTENT_TYPE);
  headers.set('Cache-Control', ARTWORK_CACHE_CONTROL);
  headers.set('Vary', 'Origin');
  return headers;
}

export interface ServeObjectOptions {
  readonly method: 'GET' | 'HEAD';
  readonly key: string;
  readonly contentType: string;
  readonly range: RangeHeader;
}

export type ServeObjectResult =
  | { readonly ok: true; readonly response: Response }
  | { readonly ok: false; readonly status: 404 | 416 };

function toBucketRange(range: RangeHeader): DeliveryRangeOption {
  if (range.kind === 'suffix') {
    return { suffix: range.length };
  }
  if (range.kind === 'single') {
    if (range.end === null) {
      return { offset: range.start };
    }
    return { offset: range.start, length: range.end - range.start + 1 };
  }
  return {};
}

interface ResolvedRange {
  readonly start: number;
  readonly length: number;
  readonly total: number;
}

/**
 * Resolve the authoritative range from a ranged read.
 *
 * R2 reports the returned portion in `object.range` and the stored object size in
 * `object.size`; when a range is not reported the requested bounds are applied to the known
 * size. The advertised total falls back to `start + length` so `Content-Range` stays coherent
 * even if a binding reports the slice length as `size`.
 */
function resolveRange(range: RangeHeader, object: DeliveryObject): ResolvedRange | null {
  const reported = object.range;
  const size = object.size;
  let start: number;
  let length: number;

  if (reported !== undefined && Number.isFinite(reported.offset) && Number.isFinite(reported.length)) {
    start = reported.offset;
    length = reported.length;
  } else if (range.kind === 'suffix') {
    if (size <= 0) {
      return null;
    }
    length = Math.min(range.length, size);
    start = size - length;
  } else if (range.kind === 'single') {
    start = range.start;
    const end = range.end === null ? size - 1 : Math.min(range.end, size - 1);
    length = end - start + 1;
  } else {
    return null;
  }

  if (!Number.isFinite(start) || !Number.isFinite(length) || start < 0 || length <= 0) {
    return null;
  }
  const total = size >= start + length ? size : start + length;
  return { start, length, total };
}

export async function discardBody(body: ReadableStream<Uint8Array> | null | undefined): Promise<void> {
  if (body !== null && body !== undefined && typeof body.cancel === 'function') {
    try {
      await body.cancel();
    } catch {
      // The body is being discarded for a HEAD response; a failed cancel is not actionable.
    }
  }
}

const cancelBody = discardBody;

/**
 * Read one allowlisted object and build its response.
 *
 * - no `Range`: 200 with the full streamed body (or headers only for HEAD),
 * - one satisfiable `Range`: 206 with `Content-Range`,
 * - missing object: 404,
 * - a range the bucket cannot satisfy: 416.
 */
export async function serveObject(
  bucket: DeliveryBucket,
  options: ServeObjectOptions,
): Promise<ServeObjectResult> {
  const { method, key, contentType, range } = options;

  if (range.kind === 'none') {
    if (method === 'HEAD' && typeof bucket.head === 'function') {
      const meta = await bucket.head(key);
      if (meta === null) {
        return { ok: false, status: 404 };
      }
      const headers = protectedHeaders(contentType);
      headers.set('Content-Length', String(meta.size));
      return { ok: true, response: new Response(null, { status: 200, headers }) };
    }

    const object = await bucket.get(key);
    if (object === null) {
      return { ok: false, status: 404 };
    }
    const headers = protectedHeaders(contentType);
    headers.set('Content-Length', String(object.size));
    if (method === 'HEAD') {
      await cancelBody(object.body);
      return { ok: true, response: new Response(null, { status: 200, headers }) };
    }
    return { ok: true, response: new Response(object.body ?? null, { status: 200, headers }) };
  }

  const object = await bucket.get(key, { range: toBucketRange(range) });
  if (object === null) {
    // R2 returns null when the requested range cannot be satisfied for the stored object.
    return { ok: false, status: 416 };
  }
  const resolved = resolveRange(range, object);
  if (resolved === null) {
    await cancelBody(object.body);
    return { ok: false, status: 416 };
  }

  const headers = protectedHeaders(contentType);
  headers.set('Content-Range', `bytes ${resolved.start}-${resolved.start + resolved.length - 1}/${resolved.total}`);
  headers.set('Content-Length', String(resolved.length));
  if (method === 'HEAD') {
    await cancelBody(object.body);
    return { ok: true, response: new Response(null, { status: 206, headers }) };
  }
  return { ok: true, response: new Response(object.body ?? null, { status: 206, headers }) };
}
