import { describe, expect, it, vi } from 'vitest';
import type { SignedPart, UploadSession } from '../../api/admin-api';
import { completedUploadSession, uploadSession } from '../../test/fixtures';
import { computeResumeFingerprint, FINGERPRINT_BLOCK_BYTES } from './upload-fingerprint';
import { uploadPartToSignedUrl } from './r2-part-uploader';
import { createUploadManager, FingerprintMismatchError } from './upload-manager';

function blobOf(size: number, name = 'source.mp4'): File {
  const bytes = new Uint8Array(size);
  for (let index = 0; index < size; index += 1) bytes[index] = index % 251;
  return new File([bytes], name);
}

function sessionWith(overrides: Partial<UploadSession>): UploadSession {
  return { ...uploadSession, ...overrides };
}

describe('resume fingerprint', () => {
  it('hashes the decimal size plus the bounded first and last blocks in a fixed layout', { timeout: 15_000 }, async () => {
    const file = blobOf(2 * FINGERPRINT_BLOCK_BYTES + 1);
    const captured: { bytes: Uint8Array | null } = { bytes: null };

    const fingerprint = await computeResumeFingerprint(file, {
      digest: async (payload) => {
        captured.bytes = payload;
        return new ArrayBuffer(32);
      },
    });

    expect(fingerprint).toMatch(/^sha256:[0-9a-f]{64}$/);
    const prefix = new TextEncoder().encode(`${file.size}\n`);
    const first = new Uint8Array(await file.slice(0, FINGERPRINT_BLOCK_BYTES).arrayBuffer());
    const last = new Uint8Array(await file.slice(file.size - FINGERPRINT_BLOCK_BYTES).arrayBuffer());
    const expected = new Uint8Array(prefix.length + first.length + last.length);
    expected.set(prefix, 0);
    expected.set(first, prefix.length);
    expected.set(last, prefix.length + first.length);

    expect(captured.bytes).toEqual(expected);
  });
});

describe('presigned part upload', () => {
  it('omits credentials and never sends a login or media bearer token to storage', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(null, { status: 200, headers: { ETag: '"etag-1"' } }),
    );
    const part: SignedPart = {
      partNumber: 1,
      url: 'https://storage.example.test/part-1?signature=abc',
      expiresAt: '2099-01-01T00:00:00.000Z',
      headers: { 'x-amz-storage-class': 'STANDARD' },
    };

    const etag = await uploadPartToSignedUrl(part, new Blob(['chunk']), fetchMock);

    const request = fetchMock.mock.calls[0][0] as Request;
    expect(request.credentials).toBe('omit');
    expect(request.headers.get('Authorization')).toBeNull();
    expect(request.headers.get('x-amz-storage-class')).toBe('STANDARD');
    expect(etag).toBe('etag-1');
  });
});

describe('upload manager', () => {
  it('uploads missing parts in bounded parallel batches and sizes the last part', async () => {
    const file = blobOf(10_000_000);
    const session = sessionWith({ partSizeBytes: 4_000_000, totalParts: 3, declaredBytes: 10_000_000 });
    const uploaded: number[] = [];
    const sizes: Record<number, number> = {};
    let inFlight = 0;
    let maxInFlight = 0;

    const manager = createUploadManager({
      listUploadParts: vi.fn().mockResolvedValue({
        items: [{ partNumber: 1, etag: 'etag-1', sizeBytes: 4_000_000 }],
        nextMarker: null,
      }),
      signUploadParts: vi.fn(async (_uploadId, partNumbers) => partNumbers.map(signedPartFor)),
      completeUpload: vi.fn().mockResolvedValue(completedUploadSession),
      abortUpload: vi.fn(),
      fingerprint: async () => `sha256:${'a'.repeat(64)}`,
      uploadPart: async (part, blob) => {
        inFlight += 1;
        maxInFlight = Math.max(maxInFlight, inFlight);
        sizes[part.partNumber] = blob.size;
        await new Promise((resolve) => setTimeout(resolve, 1));
        uploaded.push(part.partNumber);
        inFlight -= 1;
        return 'etag';
      },
    });

    const result = await manager.start(file, session);

    expect(result.state).toBe('COMPLETED');
    expect(uploaded.sort()).toEqual([2, 3]);
    expect(maxInFlight).toBeLessThanOrEqual(4);
    expect(sizes[2]).toBe(4_000_000);
    expect(sizes[3]).toBe(2_000_000);
  });

  it('never runs more than four part requests at once', async () => {
    const file = blobOf(32_000_000);
    const session = sessionWith({ partSizeBytes: 4_000_000, totalParts: 8, declaredBytes: 32_000_000 });
    let inFlight = 0;
    let maxInFlight = 0;

    const manager = createUploadManager({
      listUploadParts: vi.fn().mockResolvedValue({ items: [], nextMarker: null }),
      signUploadParts: vi.fn(async (_uploadId, partNumbers) => partNumbers.map(signedPartFor)),
      completeUpload: vi.fn().mockResolvedValue(completedUploadSession),
      abortUpload: vi.fn(),
      fingerprint: async () => `sha256:${'b'.repeat(64)}`,
      uploadPart: async () => {
        inFlight += 1;
        maxInFlight = Math.max(maxInFlight, inFlight);
        await new Promise((resolve) => setTimeout(resolve, 2));
        inFlight -= 1;
        return 'etag';
      },
    });

    await manager.start(file, session);

    expect(maxInFlight).toBe(4);
  });

  it('refuses to resume when the reselected file fingerprint differs', async () => {
    const manager = createUploadManager({
      listUploadParts: vi.fn(),
      signUploadParts: vi.fn(),
      completeUpload: vi.fn(),
      abortUpload: vi.fn(),
      fingerprint: async () => `sha256:${'c'.repeat(64)}`,
      uploadPart: vi.fn(),
    });

    await expect(manager.resume(blobOf(1000), uploadSession, `sha256:${'d'.repeat(64)}`))
      .rejects.toBeInstanceOf(FingerprintMismatchError);
  });

  it('retries completion with the same idempotency key when the response is lost', async () => {
    const file = blobOf(1000);
    const session = sessionWith({ partSizeBytes: 1000, totalParts: 1, declaredBytes: 1000 });
    const completeUpload = vi.fn()
      .mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValueOnce(completedUploadSession);
    const requestKey = vi.fn(() => 'fixed-completion-key');

    const manager = createUploadManager({
      listUploadParts: vi.fn().mockResolvedValue({ items: [], nextMarker: null }),
      signUploadParts: vi.fn(async (_uploadId, partNumbers) => partNumbers.map(signedPartFor)),
      completeUpload,
      abortUpload: vi.fn(),
      fingerprint: async () => `sha256:${'e'.repeat(64)}`,
      requestKey,
      uploadPart: async () => 'etag',
    });

    const result = await manager.start(file, session);

    expect(result.jobId).toBe(completedUploadSession.jobId);
    expect(completeUpload).toHaveBeenCalledTimes(2);
    expect(completeUpload.mock.calls[0][1]).toBe('fixed-completion-key');
    expect(completeUpload.mock.calls[1][1]).toBe('fixed-completion-key');
  });

  it('aborts an OPEN session but never a session that has moved past OPEN', async () => {
    const abortUpload = vi.fn().mockResolvedValue({ ...uploadSession, state: 'ABORTED' });
    const manager = createUploadManager({
      listUploadParts: vi.fn().mockResolvedValue({ items: [], nextMarker: null }),
      signUploadParts: vi.fn(async (_uploadId, partNumbers) => partNumbers.map(signedPartFor)),
      completeUpload: vi.fn(() => new Promise<UploadSession>(() => undefined)),
      abortUpload,
      fingerprint: async () => `sha256:${'f'.repeat(64)}`,
      uploadPart: async () => 'etag',
    });

    await expect(manager.abort()).rejects.toThrow(/no active upload/i);

    const openSession = sessionWith({ partSizeBytes: 1000, totalParts: 1, declaredBytes: 1000 });
    void manager.start(blobOf(1000), openSession);
    await vi.waitFor(() => expect(manager.session).not.toBeNull());
    await manager.abort();
    expect(abortUpload).toHaveBeenCalledWith(openSession.id);

    const completing = sessionWith({
      partSizeBytes: 1000, totalParts: 1, declaredBytes: 1000, state: 'COMPLETING',
    });
    abortUpload.mockClear();
    void manager.start(blobOf(1000), completing);
    await vi.waitFor(() => expect(manager.session?.state).toBe('COMPLETING'));
    const result = await manager.abort();
    expect(abortUpload).not.toHaveBeenCalled();
    expect(result.state).toBe('COMPLETING');
  });
});

function signedPartFor(partNumber: number): SignedPart {
  return {
    partNumber,
    url: `https://storage.example.test/part-${partNumber}`,
    expiresAt: '2099-01-01T00:00:00.000Z',
    headers: {},
  };
}
