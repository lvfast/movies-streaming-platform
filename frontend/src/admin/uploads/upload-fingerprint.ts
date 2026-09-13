/**
 * Resume fingerprint for a private upload. It is a resume guard, not authoritative content
 * validation: the backend stores it on the session so reselecting the same file can resume, while a
 * different file is rejected before any part is scheduled.
 *
 * The hashed byte layout is exactly UTF-8 decimal size + "\n" + first up-to-1-MiB block + last
 * up-to-1-MiB block.
 */
export const FINGERPRINT_BLOCK_BYTES = 1_048_576;

export type FingerprintDigest = (bytes: Uint8Array) => Promise<ArrayBuffer>;

export interface FingerprintDependencies {
  digest?: FingerprintDigest;
}

function defaultDigest(bytes: Uint8Array): Promise<ArrayBuffer> {
  return crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource);
}

function toHex(buffer: ArrayBuffer): string {
  return Array.from(new Uint8Array(buffer))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

export async function computeResumeFingerprint(
  file: Blob,
  dependencies: FingerprintDependencies = {},
): Promise<string> {
  const digest = dependencies.digest ?? defaultDigest;
  const size = file.size;
  const prefix = new TextEncoder().encode(`${size}\n`);
  const first = new Uint8Array(
    await file.slice(0, Math.min(size, FINGERPRINT_BLOCK_BYTES)).arrayBuffer(),
  );
  const lastStart = Math.max(0, size - FINGERPRINT_BLOCK_BYTES);
  const last = new Uint8Array(await file.slice(lastStart, size).arrayBuffer());

  const payload = new Uint8Array(prefix.length + first.length + last.length);
  payload.set(prefix, 0);
  payload.set(first, prefix.length);
  payload.set(last, prefix.length + first.length);

  return `sha256:${toHex(await digest(payload))}`;
}
