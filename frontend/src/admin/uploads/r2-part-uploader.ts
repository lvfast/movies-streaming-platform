import type { SignedPart } from '../../api/admin-api';

/** A non-2xx response from the storage origin. */
export class StorageUploadError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Storage rejected the part upload with status ${status}`);
    this.name = 'StorageUploadError';
    this.status = status;
  }
}

/**
 * Uploads one part straight to the presigned storage URL. The request deliberately uses
 * `credentials: 'omit'` and only the signed headers, so no login cookie, bearer token or storage
 * credential ever reaches the storage origin.
 */
export async function uploadPartToSignedUrl(
  part: SignedPart,
  blob: Blob,
  fetchImpl: typeof fetch = fetch,
): Promise<string | null> {
  const request = new Request(part.url, {
    method: 'PUT',
    headers: part.headers,
    body: blob,
    credentials: 'omit',
  });
  const response = await fetchImpl(request);
  if (!response.ok) {
    throw new StorageUploadError(response.status);
  }
  const etag = response.headers.get('ETag');
  return etag ? etag.replace(/"/g, '') : null;
}
