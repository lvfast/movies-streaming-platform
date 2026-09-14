-- Provider compatibility (Cloudflare R2). S3 multipart upload ids are opaque and not length-bounded:
-- R2 returns roughly 340-character ids, which overflowed the original VARCHAR(200) column. The
-- resulting integrity error was reported as "artwork in progress". Widen the column; MinIO's short
-- ids are unaffected. Nothing already applied is edited.

ALTER TABLE upload_session
    ALTER COLUMN storage_upload_id TYPE VARCHAR(1024);
