package com.lvfast.streaming.media.upload;

import com.lvfast.streaming.catalog.MovieNotFoundException;
import com.lvfast.streaming.media.job.JdbcMediaJobRepository;
import com.lvfast.streaming.media.storage.CompletedPart;
import com.lvfast.streaming.media.storage.MediaObjectStore;
import com.lvfast.streaming.media.storage.ObjectHead;
import com.lvfast.streaming.media.storage.PartPage;
import com.lvfast.streaming.media.storage.SignedPart;
import com.lvfast.streaming.media.storage.StorageUnavailableException;
import com.lvfast.streaming.media.storage.StoredPart;
import com.lvfast.streaming.messaging.JdbcOutboxRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Coordinates private multipart uploads. Storage network calls happen outside any database
 * transaction; multi-statement database writes run inside an explicit {@link TransactionTemplate}
 * so a committed upload always produces exactly one QUEUED job and one outbox event.
 */
@Service
public class UploadService {

    private static final String SOURCE_ROLE = "source";
    private static final String BROWSER_ROLE = "source-browser";
    private static final String UPLOAD_CREATE = "UPLOAD_CREATE";
    private static final Pattern FINGERPRINT = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Set<String> KINDS = Set.of("VIDEO", "POSTER", "BACKDROP");

    private final JdbcUploadRepository uploads;
    private final JdbcMediaJobRepository jobs;
    private final JdbcOutboxRepository outbox;
    private final MediaObjectStore store;
    private final UploadPolicy policy;
    private final TransactionTemplate tx;

    public UploadService(
            JdbcUploadRepository uploads,
            JdbcMediaJobRepository jobs,
            JdbcOutboxRepository outbox,
            MediaObjectStore store,
            UploadPolicy policy,
            PlatformTransactionManager txManager) {
        this.uploads = uploads;
        this.jobs = jobs;
        this.outbox = outbox;
        this.store = store;
        this.policy = policy;
        this.tx = new TransactionTemplate(txManager);
    }

    public UploadSession get(UUID uploadId) {
        return uploads.findView(uploadId).orElseThrow(() -> new UploadNotFoundException(uploadId));
    }

    public UploadSession create(UUID actorId, String idempotencyKey, UUID movieId, UploadInput input) {
        requireIdempotencyKey(idempotencyKey);
        UploadSession replay = uploads.findReplay(actorId, UPLOAD_CREATE, idempotencyKey).orElse(null);
        if (replay != null) {
            return replay;
        }

        String kind = normalizeKind(input.kind());
        long size = input.sizeBytes();
        if (size > policy.maxBytes(kind)) {
            throw new UploadSizeExceededException(kind, size, policy.maxBytes(kind));
        }
        String fingerprint = input.resumeFingerprint() == null ? "" : input.resumeFingerprint().trim();
        if (!FINGERPRINT.matcher(fingerprint).matches()) {
            throw new MediaValidationException(
                    "resumeFingerprint must be 'sha256:' followed by 64 lowercase hexadecimal characters");
        }
        String contentType = input.contentType() == null ? "" : input.contentType().trim();
        if (contentType.isEmpty()) {
            throw new MediaValidationException("contentType is required");
        }

        JdbcUploadRepository.MovieState movie =
                uploads.findMovieState(movieId).orElseThrow(() -> new MovieNotFoundException(movieId));
        if (!"MANAGED".equals(movie.managementMode())) {
            throw new UploadStateException("Movie is not managed and cannot accept uploads");
        }
        if ("ARCHIVED".equals(movie.lifecycle())) {
            throw new UploadStateException("Archived movie cannot accept uploads");
        }

        UUID sessionId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String objectKey = "source/" + movieId + "/" + sessionId + "/original";
        Instant expiresAt = Instant.now().plus(policy.sessionTtl());
        int totalParts = policy.totalParts(size);

        String storageUploadId = store.initiate(SOURCE_ROLE, objectKey, contentType,
                Map.of("resumefingerprint", fingerprint, "kind", kind));

        JdbcUploadRepository.UploadRow row = new JdbcUploadRepository.UploadRow(
                sessionId,
                movieId,
                "VIDEO".equals(kind) ? targetId : null,
                "VIDEO".equals(kind) ? null : targetId,
                kind,
                "OPEN",
                objectKey,
                storageUploadId,
                contentType,
                fingerprint,
                policy.partSizeBytes(),
                totalParts,
                size,
                expiresAt,
                null);

        try {
            return tx.execute(status -> {
                uploads.insertTarget(targetId, movieId, kind, objectKey);
                uploads.insertSession(row);
                UploadSession view = uploads.findView(sessionId).orElseThrow();
                uploads.storeReplay(actorId, UPLOAD_CREATE, idempotencyKey, 201, view);
                return view;
            });
        } catch (DataIntegrityViolationException conflict) {
            abortQuietly(SOURCE_ROLE, objectKey, storageUploadId);
            UploadSession concurrent = uploads.findReplay(actorId, UPLOAD_CREATE, idempotencyKey).orElse(null);
            if (concurrent != null) {
                return concurrent;
            }
            if ("VIDEO".equals(kind)) {
                throw new UploadStateException("Movie already has a video replacement in progress");
            }
            throw new UploadStateException("Movie already has " + kind.toLowerCase(Locale.ROOT) + " artwork in progress");
        }
    }

    public SignedPartList signParts(UUID uploadId, PartSignRequest request) {
        JdbcUploadRepository.UploadRow row = findRow(uploadId);
        if (!"OPEN".equals(row.state())) {
            throw new UploadStateException("Upload in state " + row.state() + " cannot sign parts");
        }
        List<Integer> numbers = request.partNumbers();
        if (numbers.size() > policy.maxPartsPerRequest()) {
            throw new MediaValidationException(
                    "At most " + policy.maxPartsPerRequest() + " parts can be signed per request");
        }
        if (new HashSet<>(numbers).size() != numbers.size()) {
            throw new MediaValidationException("partNumbers must be unique");
        }
        List<SignedPart> items = new ArrayList<>();
        for (Integer number : numbers) {
            if (number < 1 || number > row.totalParts()) {
                throw new MediaValidationException(
                        "part number " + number + " is out of range 1.." + row.totalParts());
            }
            items.add(store.signPart(BROWSER_ROLE, row.objectKey(), row.storageUploadId(), number, policy.signTtl()));
        }
        return new SignedPartList(items);
    }

    public PartPage listParts(UUID uploadId, Integer marker) {
        JdbcUploadRepository.UploadRow row = findRow(uploadId);
        if (!"OPEN".equals(row.state())) {
            throw new UploadStateException("Upload in state " + row.state() + " has no active multipart upload");
        }
        return store.listParts(SOURCE_ROLE, row.objectKey(), row.storageUploadId(), marker);
    }

    public UploadSession complete(String idempotencyKey, UUID uploadId) {
        requireIdempotencyKey(idempotencyKey);
        JdbcUploadRepository.UploadRow row = findRow(uploadId);
        return switch (row.state()) {
            case "OPEN" -> completeOpen(row);
            case "COMPLETING" -> reconcile(row);
            case "COMPLETED" -> uploads.findView(uploadId).orElseThrow();
            default -> throw new UploadStateException("Upload in state " + row.state() + " cannot be completed");
        };
    }

    public UploadSession abort(UUID uploadId) {
        JdbcUploadRepository.UploadRow row = findRow(uploadId);
        if ("ABORTED".equals(row.state()) || "EXPIRED".equals(row.state())) {
            return uploads.findView(uploadId).orElseThrow();
        }
        if (!"OPEN".equals(row.state())) {
            throw new UploadStateException("Upload in state " + row.state() + " cannot be aborted");
        }
        Integer marked = tx.execute(status -> uploads.markAborted(row.id()));
        if (marked == null || marked == 0) {
            JdbcUploadRepository.UploadRow current = findRow(uploadId);
            if ("ABORTED".equals(current.state()) || "EXPIRED".equals(current.state())) {
                return uploads.findView(uploadId).orElseThrow();
            }
            throw new UploadStateException("Upload in state " + current.state() + " cannot be aborted");
        }
        abortQuietly(SOURCE_ROLE, row.objectKey(), row.storageUploadId());
        return uploads.findView(uploadId).orElseThrow();
    }

    @Scheduled(
            fixedDelayString = "${app.media.upload.sweep-interval-ms:60000}",
            initialDelayString = "${app.media.upload.sweep-initial-delay-ms:60000}")
    public int sweepExpired() {
        List<JdbcUploadRepository.UploadRow> expired =
                uploads.findExpiredOpenSessions(Instant.now(), policy.sweepBatchSize());
        int swept = 0;
        for (JdbcUploadRepository.UploadRow row : expired) {
            Integer marked = tx.execute(status -> uploads.markExpired(row.id()));
            if (marked == null || marked == 0) {
                continue;
            }
            abortQuietly(SOURCE_ROLE, row.objectKey(), row.storageUploadId());
            swept++;
        }
        return swept;
    }

    private UploadSession completeOpen(JdbcUploadRepository.UploadRow row) {
        List<StoredPart> parts = listAllParts(row);
        verifyParts(row, parts);
        int marked = uploads.markCompleting(row.id());
        if (marked == 0) {
            return reconcile(findRow(row.id()));
        }
        completeStorage(row, parts);
        return finalizeUpload(row);
    }

    private UploadSession reconcile(JdbcUploadRepository.UploadRow row) {
        ObjectHead head = store.head(SOURCE_ROLE, row.objectKey());
        if (head == null) {
            List<StoredPart> parts = listAllParts(row);
            verifyParts(row, parts);
            completeStorage(row, parts);
            return finalizeUpload(row);
        }
        if (head.sizeBytes() == row.declaredBytes()
                && row.resumeFingerprint().equals(head.metadata().get("resumefingerprint"))) {
            return finalizeUpload(row);
        }
        tx.execute(status -> {
            uploads.markFailed(row.id());
            return null;
        });
        throw new UploadStateException("Reserved object does not match the declared upload and cannot be reconciled");
    }

    private UploadSession finalizeUpload(JdbcUploadRepository.UploadRow row) {
        boolean video = "VIDEO".equals(row.kind());
        String targetState = video ? "QUEUED" : "STORED";
        String jobKind = video ? "TRANSCODE" : "ARTWORK";
        String eventType = video ? "transcode.requested.v1" : "artwork.requested.v1";
        UUID jobId = UUID.randomUUID();
        return tx.execute(status -> {
            int finalized = uploads.markCompleted(row.id());
            if (finalized == 0) {
                return uploads.findView(row.id()).orElseThrow();
            }
            jobs.insertQueued(jobId, row.movieId(), row.mediaVersionId(), row.assetId(), jobKind);
            uploads.setJobId(row.id(), jobId);
            uploads.markTargetState(row.mediaVersionId(), row.assetId(), targetState);
            outbox.append(jobId, eventType);
            return uploads.findView(row.id()).orElseThrow();
        });
    }

    private JdbcUploadRepository.UploadRow findRow(UUID uploadId) {
        return uploads.findRow(uploadId).orElseThrow(() -> new UploadNotFoundException(uploadId));
    }

    private List<StoredPart> listAllParts(JdbcUploadRepository.UploadRow row) {
        List<StoredPart> parts = new ArrayList<>();
        Integer marker = null;
        do {
            PartPage page = store.listParts(SOURCE_ROLE, row.objectKey(), row.storageUploadId(), marker);
            parts.addAll(page.items());
            marker = page.nextMarker();
        } while (marker != null);
        return parts.stream()
                .sorted(java.util.Comparator.comparingInt(StoredPart::partNumber))
                .toList();
    }

    private void verifyParts(JdbcUploadRepository.UploadRow row, List<StoredPart> parts) {
        if (parts.size() != row.totalParts()) {
            throw new UploadStateException(
                    "Uploaded part count " + parts.size() + " does not match declared total " + row.totalParts());
        }
        long total = 0;
        for (int i = 0; i < parts.size(); i++) {
            StoredPart part = parts.get(i);
            int expected = i + 1;
            if (part.partNumber() != expected) {
                throw new UploadStateException("Uploaded parts must be contiguous starting at part 1");
            }
            if (part.etag() == null || part.etag().isBlank()) {
                throw new UploadStateException("Uploaded part " + expected + " has no ETag");
            }
            if (part.sizeBytes() <= 0) {
                throw new UploadStateException("Uploaded part " + expected + " has invalid size");
            }
            total += part.sizeBytes();
        }
        if (total != row.declaredBytes()) {
            throw new UploadStateException(
                    "Uploaded size " + total + " does not match declared size " + row.declaredBytes());
        }
    }

    private void completeStorage(JdbcUploadRepository.UploadRow row, List<StoredPart> parts) {
        List<CompletedPart> completed = parts.stream()
                .map(part -> new CompletedPart(part.partNumber(), part.etag()))
                .toList();
        store.complete(SOURCE_ROLE, row.objectKey(), row.storageUploadId(), completed);
    }

    private void abortQuietly(String role, String key, String uploadId) {
        try {
            store.abort(role, key, uploadId);
        } catch (StorageUnavailableException ignored) {
            // Best-effort cleanup; orphaned multipart uploads are reclaimed by storage lifecycle.
        }
    }

    private String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new MediaValidationException("kind is required");
        }
        String normalized = kind.trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(normalized)) {
            throw new MediaValidationException("kind must be VIDEO, POSTER or BACKDROP");
        }
        return normalized;
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MediaValidationException("Idempotency-Key header is required");
        }
    }
}
