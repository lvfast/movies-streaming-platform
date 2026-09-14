package com.lvfast.streaming.administration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lvfast.streaming.ops.AdminRoleCommand;
import com.lvfast.streaming.support.ApiTestSupport;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Publication and protected-playback behavior (P5-A to P5-D). Artwork promotion runs against a real
 * MinIO delivery bucket so a publish that claims to copy validated artwork really copies it, and the
 * untouched-version guarantees are asserted against durable rows rather than service return values.
 */
@Testcontainers(disabledWithoutDocker = true)
class PublicationPlaybackTest extends ApiTestSupport {

    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";
    private static final String SOURCE_BUCKET = "media-source";
    private static final String DELIVERY_BUCKET = "media-delivery";

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("quay.io/minio/minio:latest")
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server /data")
            .withExposedPorts(9000);

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.media.storage.roles.delivery.bucket", () -> DELIVERY_BUCKET);
        registry.add("app.media.storage.roles.delivery.endpoint", PublicationPlaybackTest::minioEndpoint);
        registry.add("app.media.storage.roles.delivery.region", () -> "us-east-1");
        registry.add("app.media.storage.roles.delivery.access-key", () -> ACCESS_KEY);
        registry.add("app.media.storage.roles.delivery.secret-key", () -> SECRET_KEY);
        registry.add("app.media.storage.roles.source.bucket", () -> SOURCE_BUCKET);
        registry.add("app.media.storage.roles.source.endpoint", PublicationPlaybackTest::minioEndpoint);
        registry.add("app.media.storage.roles.source.region", () -> "us-east-1");
        registry.add("app.media.storage.roles.source.access-key", () -> ACCESS_KEY);
        registry.add("app.media.storage.roles.source.secret-key", () -> SECRET_KEY);
        registry.add("app.media.outbox.poll-interval-ms", () -> "600000");
        registry.add("app.media.recovery.poll-interval-ms", () -> "600000");
    }

    @BeforeAll
    static void createBuckets() {
        try (S3Client s3 = s3()) {
            s3.createBucket(CreateBucketRequest.builder().bucket(SOURCE_BUCKET).build());
            s3.createBucket(CreateBucketRequest.builder().bucket(DELIVERY_BUCKET).build());
        }
    }

    @Autowired AdminRoleCommand roleCommand;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void cleanMedia() {
        // This class registers more users than the 10-per-minute auth rate limit allows, so clear the
        // limiter between methods; otherwise a later method's registration is refused with 429.
        Set<String> rateLimitKeys = redis.keys("rate:auth:*");
        if (rateLimitKeys != null && !rateLimitKeys.isEmpty()) {
            redis.delete(rateLimitKeys);
        }
        jdbc.update("delete from media_publication");
        jdbc.update("delete from media_playback_session");
        jdbc.update("delete from outbox_event");
        jdbc.update("delete from inbox_event");
        jdbc.update("delete from media_job_attempt");
        jdbc.update("delete from upload_session");
        jdbc.update("delete from media_job");
        jdbc.update("delete from media_asset");
        jdbc.update("delete from media_version");
        jdbc.update("delete from movie where management_mode='MANAGED'");
        // The catalog revision is a shared counter; each test asserts deltas from its own baseline.
        jdbc.update("update catalog_revision set revision=0 where id=1");
    }

    @Test
    void publishRejectsIncompleteMetadataAndNonReadyMedia() {
        String token = admin("p5_reject");
        UUID movieId = movie(token, "p5-reject", false);
        UUID readyVersion = readyTranscode(movieId, 5400);
        UUID otherMovie = movie(token, "p5-reject-other", true);
        UUID foreignVersion = readyTranscode(otherMovie, 5400);
        long catalogRevisionBefore = catalogRevision();
        // Fixture setup already audited the role grant and the two movie creations; a refusal must
        // not add anything on top of that.
        int auditsBefore = auditCount(movieId);

        // Empty synopsis: the movie is created without one, so publication must refuse on metadata.
        Result missingSynopsis = publish(token, movieId, publishBody(readyVersion));
        assertThat(missingSynopsis.status()).isEqualTo(400);
        assertThat(bodyOf(missingSynopsis).get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(lifecycle(movieId)).isEqualTo("DRAFT");

        jdbc.update("update movie set synopsis='A published film.' where id=?", movieId);
        Result missingGenre = publish(token, movieId, publishBody(readyVersion));
        assertThat(missingGenre.status()).isEqualTo(400);
        assertThat(bodyOf(missingGenre).get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(lifecycle(movieId)).isEqualTo("DRAFT");

        addGenre(movieId);
        UUID notReadyVersion = processingVersion(movieId);

        Result notReady = publish(token, movieId, publishBody(notReadyVersion));
        assertThat(notReady.status()).isEqualTo(409);
        assertThat(bodyOf(notReady).get("code").asText()).isEqualTo("PUBLICATION_REJECTED");
        assertThat(lifecycle(movieId)).isEqualTo("DRAFT");
        assertThat(activeVersionId(movieId)).isNull();

        Result crossMovie = publish(token, movieId, publishBody(foreignVersion));
        assertThat(crossMovie.status()).isEqualTo(409);
        assertThat(bodyOf(crossMovie).get("code").asText()).isEqualTo("PUBLICATION_REJECTED");
        assertThat(lifecycle(movieId)).isEqualTo("DRAFT");
        assertThat(activeVersionId(movieId)).isNull();

        Result missingIfMatch = postNoBody(publishPath(movieId), token, Map.of("Idempotency-Key", key()));
        assertThat(missingIfMatch.status()).isEqualTo(428);

        assertThat(catalogRevision())
                .as("no refused publication may bump the catalog revision")
                .isEqualTo(catalogRevisionBefore);
        assertThat(auditCount(movieId))
                .as("no refused publication may be audited")
                .isEqualTo(auditsBefore);
        assertThat(jdbc.queryForObject("select published from movie where id=?", Boolean.class, movieId))
                .isFalse();
        assertThat(movieRevisionValue(movieId))
                .as("a refused publication must not move the movie revision")
                .isEqualTo(0L);
        assertThat(jdbc.queryForObject("""
                select count(*) from media_publication where movie_id=?
                """, Integer.class, movieId)).isZero();
    }

    @Test
    void publishRejectsArtworkThatIsForeignUnreadyOrOfTheWrongKind() {
        String token = admin("p5_artwork");
        UUID movieId = movie(token, "p5-artwork", true);
        UUID versionId = readyTranscode(movieId, 5400);
        Long revisionBefore = movieRevisionValue(movieId);

        // READY artwork of this movie whose processing job has not succeeded yet.
        UUID unreadyArtwork = unreadyArtwork(movieId, "POSTER");
        Result unready = publish(token, movieId, publishBody(versionId, unreadyArtwork, null));
        assertThat(unready.status()).isEqualTo(409);
        assertThat(bodyOf(unready).get("code").asText()).isEqualTo("PUBLICATION_REJECTED");
        assertThat(readDelivery("public-artwork/" + unreadyArtwork + "/image.jpg")).isNull();

        // A validated BACKDROP offered as the poster (and the mirror image) must never be published
        // under the wrong pointer, because the two kinds have different public contracts.
        UUID backdropId = readyArtwork(movieId, "BACKDROP");
        Result backdropAsPoster = publish(token, movieId, publishBody(versionId, backdropId, null));
        assertThat(backdropAsPoster.status()).isEqualTo(409);
        assertThat(bodyOf(backdropAsPoster).get("code").asText()).isEqualTo("PUBLICATION_REJECTED");
        assertThat(readDelivery("public-artwork/" + backdropId + "/image.jpg"))
                .as("a refused publish must not promote anything")
                .isNull();

        UUID posterId = readyArtwork(movieId, "POSTER");
        Result posterAsBackdrop = publish(token, movieId, publishBody(versionId, null, posterId));
        assertThat(posterAsBackdrop.status()).isEqualTo(409);
        assertThat(bodyOf(posterAsBackdrop).get("code").asText()).isEqualTo("PUBLICATION_REJECTED");
        assertThat(readDelivery("public-artwork/" + posterId + "/image.jpg")).isNull();

        UUID otherMovie = movie(token, "p5-artwork-other", true);
        UUID foreignArtwork = readyArtwork(otherMovie, "POSTER");
        Result crossMovie = publish(token, movieId, publishBody(versionId, foreignArtwork, null));
        assertThat(crossMovie.status()).isEqualTo(409);
        assertThat(bodyOf(crossMovie).get("code").asText()).isEqualTo("PUBLICATION_REJECTED");
        assertThat(readDelivery("public-artwork/" + foreignArtwork + "/image.jpg")).isNull();

        assertThat(movieRevisionValue(movieId))
                .as("no refused artwork publish may touch the movie row")
                .isEqualTo(revisionBefore);
        assertThat(lifecycle(movieId)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select poster_url from movie where id=?", String.class, movieId))
                .isNull();
        assertThat(jdbc.queryForObject("select backdrop_url from movie where id=?", String.class, movieId))
                .isNull();
        assertThat(jdbc.queryForObject("""
                select count(*) from media_publication where movie_id=?
                """, Integer.class, movieId)).isZero();
    }

    @Test
    void publishPromotesValidatedArtworkAndChangesTheProjectionAtomically() {
        String token = admin("p5_publish");
        UUID movieId = movie(token, "p5-publish", true);
        UUID versionId = readyTranscode(movieId, 5400);
        UUID posterId = readyArtwork(movieId, "POSTER");
        UUID backdropId = readyArtwork(movieId, "BACKDROP");
        long catalogRevisionBefore = catalogRevision();

        String publishKey = key();
        Result published = publishWithKey(token, movieId, publishKey, publishBody(versionId, posterId, backdropId));

        assertThat(published.status()).isEqualTo(200);
        assertThat(lifecycle(movieId)).isEqualTo("PUBLISHED");
        assertThat(activeVersionId(movieId)).isEqualTo(versionId);
        assertThat(runtimeSeconds(movieId)).isEqualTo(5400);
        assertThat(jdbc.queryForObject("select poster_url from movie where id=?", String.class, movieId))
                .isEqualTo("public-artwork/" + posterId + "/image.jpg");
        assertThat(jdbc.queryForObject("select backdrop_url from movie where id=?", String.class, movieId))
                .isEqualTo("public-artwork/" + backdropId + "/image.jpg");
        assertThat(jdbc.queryForObject("select hls_manifest_url from movie where id=?", String.class, movieId))
                .endsWith("/index.m3u8");
        assertThat(jdbc.queryForObject("select first_published_at from movie where id=?", String.class, movieId))
                .isNotNull();
        assertThat(catalogRevision()).isGreaterThan(catalogRevisionBefore);
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_event where entity_id=? and action='MOVIE_PUBLISHED'
                """, Integer.class, movieId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from media_publication where movie_id=?",
                Integer.class, movieId)).isEqualTo(2);

        // Only validated artwork output reaches the public namespace, with the source bytes intact.
        assertThat(readDelivery("public-artwork/" + posterId + "/image.jpg")).isEqualTo("poster-bytes");
        assertThat(readDelivery("public-artwork/" + backdropId + "/image.jpg")).isEqualTo("backdrop-bytes");

        Result replayed = publishWithKey(
                token, movieId, publishKey, publishBody(versionId, posterId, backdropId));
        assertThat(replayed.status()).isEqualTo(200);
        assertThat(bodyOf(replayed).get("revision").asLong())
                .as("a replayed publish must not bump the revision again")
                .isEqualTo(bodyOf(published).get("revision").asLong());
        assertThat(jdbc.queryForObject("select count(*) from media_publication where movie_id=?",
                Integer.class, movieId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_event where entity_id=? and action='MOVIE_PUBLISHED'
                """, Integer.class, movieId)).isEqualTo(1);
    }

    @Test
    void replacementStaysInactiveUntilActivationAndExistingSessionsStayPinned() throws Exception {
        String token = admin("p5_replace");
        UUID movieId = movie(token, "p5-replace", true);
        UUID firstVersion = readyTranscode(movieId, 3600);
        assertThat(publish(token, movieId, publishBody(firstVersion)).status()).isEqualTo(200);

        Result firstGrant = get("/api/v1/movies/" + movieId + "/playback", token);
        assertThat(firstGrant.status()).isEqualTo(200);
        String firstSessionId = bodyOf(firstGrant).get("sessionId").asText();
        assertThat(bodyOf(firstGrant).get("mediaVersionId").asText()).isEqualTo(firstVersion.toString());

        UUID secondVersion = readyTranscode(movieId, 7200);
        assertThat(activeVersionId(movieId)).as("a READY replacement is inert until activation")
                .isEqualTo(firstVersion);
        assertThat(lifecycle(movieId)).isEqualTo("PUBLISHED");

        Result activated = postAdminCommand(
                activatePath(movieId), token, key(), movieRevision(movieId), publishBody(secondVersion));
        assertThat(activated.status()).isEqualTo(200);
        assertThat(activeVersionId(movieId)).isEqualTo(secondVersion);
        assertThat(runtimeSeconds(movieId)).isEqualTo(7200);
        assertThat(lifecycle(movieId)).isEqualTo("PUBLISHED");

        Result refreshed = postNoBody(
                "/api/v1/me/playback-sessions/" + firstSessionId + "/token", token, Map.of());
        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(bodyOf(refreshed).get("mediaToken").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("select media_version_id from media_playback_session where id=?",
                UUID.class, UUID.fromString(firstSessionId))).isEqualTo(firstVersion);

        Result afterActivation = get("/api/v1/movies/" + movieId + "/playback", token);
        assertThat(bodyOf(afterActivation).get("mediaVersionId").asText())
                .isEqualTo(secondVersion.toString());

        // A repeated activation of the same version is idempotent but still an explicit command.
        Result repeated = postAdminCommand(
                activatePath(movieId), token, key(), movieRevision(movieId), publishBody(secondVersion));
        assertThat(repeated.status()).isEqualTo(200);

        // A stale revision is refused and the active pointer is untouched.
        Result staleRevision = postAdminCommand(
                activatePath(movieId), token, key(), "\"1\"", publishBody(firstVersion));
        assertThat(staleRevision.status()).isEqualTo(412);
        assertThat(activeVersionId(movieId)).isEqualTo(secondVersion);

        // A version that is not READY for this movie is refused with a conflict.
        Result notReady = postAdminCommand(
                activatePath(movieId), token, key(), movieRevision(movieId), publishBody(UUID.randomUUID()));
        assertThat(notReady.status()).isEqualTo(409);
        assertThat(activeVersionId(movieId)).isEqualTo(secondVersion);
    }

    @Test
    void anAlreadyPublishedMovieSurvivesARefusedPublishUntouched() {
        String token = admin("p5_published_refusal");
        UUID movieId = movie(token, "p5-published-refusal", true);
        UUID firstVersion = readyTranscode(movieId, 3600);
        UUID firstPoster = readyArtwork(movieId, "POSTER");
        assertThat(publish(token, movieId, publishBody(firstVersion, firstPoster, null)).status())
                .isEqualTo(200);

        // Everything after this snapshot is either fixture data or the refused command itself, so
        // this is the exact state the refusal has to leave behind. Creating another movie bumps the
        // shared catalog revision, so that fixture is built first.
        UUID otherMovie = movie(token, "p5-published-refusal-other", true);
        UUID foreignArtwork = readyArtwork(otherMovie, "POSTER");
        UUID replacement = readyTranscode(movieId, 7200);
        Long revisionAfterFirstPublish = movieRevisionValue(movieId);
        long catalogRevisionAfterFirstPublish = catalogRevision();
        int auditsAfterFirstPublish = auditCount(movieId);
        int publicationsAfterFirstPublish = jdbc.queryForObject(
                "select count(*) from media_publication where movie_id=?", Integer.class, movieId);

        Result refused = publish(token, movieId, publishBody(replacement, foreignArtwork, null));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(bodyOf(refused).get("code").asText()).isEqualTo("PUBLICATION_REJECTED");
        assertThat(activeVersionId(movieId))
                .as("the previous active version must survive a refused publish")
                .isEqualTo(firstVersion);
        assertThat(lifecycle(movieId)).isEqualTo("PUBLISHED");
        assertThat(runtimeSeconds(movieId)).isEqualTo(3600);
        assertThat(jdbc.queryForObject("select poster_asset_id from movie where id=?", UUID.class, movieId))
                .isEqualTo(firstPoster);
        assertThat(movieRevisionValue(movieId)).isEqualTo(revisionAfterFirstPublish);
        assertThat(catalogRevision()).isEqualTo(catalogRevisionAfterFirstPublish);
        assertThat(auditCount(movieId)).isEqualTo(auditsAfterFirstPublish);
        assertThat(jdbc.queryForObject("""
                select count(*) from media_publication where movie_id=?
                """, Integer.class, movieId)).isEqualTo(publicationsAfterFirstPublish);
        assertThat(readDelivery("public-artwork/" + foreignArtwork + "/image.jpg")).isNull();
    }

    @Test
    void aFailureAfterPromotionRollsBackLifecycleProjectionAuditAndPromotionLog() throws Exception {
        String token = admin("p5_rollback");
        // The artwork is promoted before the active pointer moves, so it is pre-promoted by an
        // earlier successful publish; the injected failure then stays about the transaction instead
        // of the object copy.
        UUID movieId = movie(token, "p5-rollback", true);
        UUID versionId = readyTranscode(movieId, 5400);
        UUID posterId = readyArtwork(movieId, "POSTER");
        Result baseline = publish(token, movieId, publishBody(versionId, posterId, null));
        assertThat(baseline.status())
                .as("pre-promoting the artwork must not make the first publish fail: %s", baseline.body())
                .isEqualTo(200);

        postAdminCommand(unpublishPath(movieId), token, key(), movieRevision(movieId), null);

        // Unpublishing keeps the projection pointers, so the refusal rollback must restore exactly
        // the row as it stands after the unpublish command.
        Long revisionBefore = movieRevisionValue(movieId);
        long catalogRevisionBefore = catalogRevision();
        int auditsBefore = auditCount(movieId);
        int publicationsBefore = jdbc.queryForObject(
                "select count(*) from media_publication where movie_id=?", Integer.class, movieId);
        String lifecycleBefore = lifecycle(movieId);
        String activeVersionBefore = jdbc.queryForObject(
                "select active_media_version_id::text from movie where id=?", String.class, movieId);
        String manifestBefore = jdbc.queryForObject(
                "select hls_manifest_url from movie where id=?", String.class, movieId);
        String posterBefore = jdbc.queryForObject(
                "select poster_asset_id::text from movie where id=?", String.class, movieId);

        // The audit write happens after the lifecycle/active-version/projection update and before the
        // catalog revision bump, so a failing audit row proves the whole unit of work unwinds.
        jdbc.execute("""
                create function p5_fail_audit() returns trigger as $$
                begin
                    raise exception 'injected audit failure for the atomicity test';
                end;
                $$ language plpgsql
                """);
        jdbc.execute("""
                create trigger p5_fail_audit before insert on audit_event
                for each row execute function p5_fail_audit()
                """);
        try {
            Result failing = publish(token, movieId, publishBody(versionId, posterId, null));

            assertThat(failing.status())
                    .as("the injected failure must reach the caller as a server error")
                    .isEqualTo(500);
            assertThat(lifecycle(movieId))
                    .as("the lifecycle update must be rolled back")
                    .isEqualTo(lifecycleBefore);
            assertThat(jdbc.queryForObject("select published from movie where id=?", Boolean.class, movieId))
                    .isFalse();
            assertThat(jdbc.queryForObject("""
                    select active_media_version_id::text from movie where id=?
                    """, String.class, movieId))
                    .as("the active version must keep its pre-command value")
                    .isEqualTo(activeVersionBefore);
            assertThat(jdbc.queryForObject("select hls_manifest_url from movie where id=?", String.class, movieId))
                    .as("the manifest pointer must keep its pre-command value")
                    .isEqualTo(manifestBefore);
            assertThat(jdbc.queryForObject(
                    "select poster_asset_id::text from movie where id=?", String.class, movieId))
                    .as("the artwork pointer must keep its pre-command value")
                    .isEqualTo(posterBefore);
            assertThat(movieRevisionValue(movieId)).isEqualTo(revisionBefore);
            assertThat(catalogRevision()).isEqualTo(catalogRevisionBefore);
            assertThat(auditCount(movieId)).isEqualTo(auditsBefore);
            assertThat(jdbc.queryForObject("""
                    select count(*) from media_publication where movie_id=?
                    """, Integer.class, movieId))
                    .as("the promotion row is written inside the rolled-back transaction")
                    .isEqualTo(publicationsBefore);
        } finally {
            jdbc.execute("drop trigger if exists p5_fail_audit on audit_event");
            jdbc.execute("drop function if exists p5_fail_audit()");
        }
    }

    @Test
    void archivedMoviesDenyPlaybackDirectlyAndRestoreNeverRepublishes() {
        String token = admin("p5_archived_denial");
        UUID movieId = movie(token, "p5-archived-denial", true);
        UUID versionId = readyTranscode(movieId, 5400);
        assertThat(publish(token, movieId, publishBody(versionId)).status()).isEqualTo(200);

        Result grant = get("/api/v1/movies/" + movieId + "/playback", token);
        assertThat(grant.status()).isEqualTo(200);
        String viewerSessionId = bodyOf(grant).get("sessionId").asText();

        assertThat(postNoBody(unpublishPath(movieId), token, ifMatch(movieRevision(movieId))).status())
                .isEqualTo(200);
        assertThat(postNoBody(archivePath(movieId), token, ifMatch(movieRevision(movieId))).status())
                .isEqualTo(200);
        assertThat(lifecycle(movieId)).isEqualTo("ARCHIVED");

        Result preview = postNoBody("/api/v1/admin/movies/" + movieId + "/preview", token, Map.of());
        assertThat(preview.status()).isEqualTo(200);
        String previewSessionId = bodyOf(preview).get("sessionId").asText();

        assertThat(get("/api/v1/movies/" + movieId + "/playback", token).status()).isEqualTo(404);
        assertThat(postNoBody(
                "/api/v1/me/playback-sessions/" + viewerSessionId + "/token", token, Map.of()).status())
                .isEqualTo(404);

        // Restoring returns the movie to UNPUBLISHED; it never republishes and never un-archives.
        assertThat(postNoBody(restorePath(movieId), token, ifMatch(movieRevision(movieId))).status())
                .isEqualTo(200);
        assertThat(lifecycle(movieId)).isEqualTo("UNPUBLISHED");
        assertThat(get("/api/v1/movies/" + movieId + "/playback", token).status()).isEqualTo(404);
        assertThat(activeVersionId(movieId)).isEqualTo(versionId);

        // An ADMIN preview session outlives the movie's publication state by design.
        Result previewRefresh = postNoBody(
                "/api/v1/admin/playback-sessions/" + previewSessionId + "/token", token, Map.of());
        assertThat(previewRefresh.status()).isEqualTo(200);
        assertThat(bodyOf(previewRefresh).get("mediaToken").asText()).isNotBlank();
    }

    @Test
    void unpublishAndArchiveDenyNewSessionsAndRefreshWithoutAutoPublishingOnRestore() {
        String token = admin("p5_lifecycle");
        UUID movieId = movie(token, "p5-lifecycle", true);
        UUID versionId = readyTranscode(movieId, 5400);
        assertThat(publish(token, movieId, publishBody(versionId)).status()).isEqualTo(200);

        Result grant = get("/api/v1/movies/" + movieId + "/playback", token);
        String sessionId = bodyOf(grant).get("sessionId").asText();

        Result unpublished = postNoBody(unpublishPath(movieId), token, ifMatch(movieRevision(movieId)));
        assertThat(unpublished.status()).isEqualTo(200);
        assertThat(lifecycle(movieId)).isEqualTo("UNPUBLISHED");
        assertThat(activeVersionId(movieId)).isEqualTo(versionId);

        assertThat(getNoAuth("/api/v1/movies/" + movieId + "/playback").status()).isEqualTo(401);
        assertThat(get("/api/v1/movies/" + movieId + "/playback", token).status()).isEqualTo(404);
        Result deniedRefresh = postNoBody(
                "/api/v1/me/playback-sessions/" + sessionId + "/token", token, Map.of());
        assertThat(deniedRefresh.status()).isEqualTo(404);

        Result archived = postNoBody(archivePath(movieId), token, ifMatch(movieRevision(movieId)));
        assertThat(archived.status()).isEqualTo(200);
        assertThat(lifecycle(movieId)).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("select published from movie where id=?", Boolean.class, movieId)).isFalse();

        Result restored = postNoBody(restorePath(movieId), token, ifMatch(movieRevision(movieId)));
        assertThat(restored.status()).isEqualTo(200);
        assertThat(lifecycle(movieId)).isEqualTo("UNPUBLISHED");
        assertThat(jdbc.queryForObject("select published from movie where id=?", Boolean.class, movieId)).isFalse();
        assertThat(activeVersionId(movieId)).isEqualTo(versionId);
        assertThat(get("/api/v1/movies/" + movieId + "/playback", token).status()).isEqualTo(404);
    }

    @Test
    void previewRequiresAdminAndReturnsAPinnedGrantForAReadyVersion() {
        String adminToken = admin("p5_preview");
        UUID movieId = movie(adminToken, "p5-preview", true);
        UUID versionId = readyTranscode(movieId, 5400);

        Result preview = postNoBody("/api/v1/admin/movies/" + movieId + "/preview", adminToken, Map.of());
        assertThat(preview.status()).isEqualTo(200);
        assertThat(bodyOf(preview).get("mediaVersionId").asText()).isEqualTo(versionId.toString());
        assertThat(bodyOf(preview).get("manifestUrl").asText())
                .contains("/hls/" + movieId + "/" + versionId);
        assertThat(bodyOf(preview).get("mediaToken").asText()).isNotBlank();

        String viewerToken = registerAndToken("p5_preview_viewer");
        Result denied = postNoBody("/api/v1/admin/movies/" + movieId + "/preview", viewerToken, Map.of());
        assertThat(denied.status()).isEqualTo(403);
    }

    @Test
    void previewsTheRequestedReadyCandidateAndFallsBackToTheActiveVersion() {
        String token = admin("p7_preview_candidate");
        UUID movieId = movie(token, "p7-preview-candidate", true);
        UUID activeVersion = readyTranscode(movieId, 3600);
        assertThat(publish(token, movieId, publishBody(activeVersion)).status()).isEqualTo(200);
        UUID candidateVersion = readyTranscode(movieId, 7200);

        Result candidate = postAdminJson(
                "/api/v1/admin/movies/" + movieId + "/preview", token, null,
                "{\"mediaVersionId\":\"" + candidateVersion + "\"}");
        assertThat(candidate.status()).isEqualTo(200);
        assertThat(bodyOf(candidate).get("mediaVersionId").asText())
                .as("an admin must be able to preview the selected candidate, not only the active version")
                .isEqualTo(candidateVersion.toString());

        Result fallback = postNoBody("/api/v1/admin/movies/" + movieId + "/preview", token, Map.of());
        assertThat(fallback.status()).isEqualTo(200);
        assertThat(bodyOf(fallback).get("mediaVersionId").asText()).isEqualTo(activeVersion.toString());

        Result foreign = postAdminJson(
                "/api/v1/admin/movies/" + movieId + "/preview", token, null,
                "{\"mediaVersionId\":\"" + UUID.randomUUID() + "\"}");
        assertThat(foreign.status()).isEqualTo(409);
        assertThat(bodyOf(foreign).get("code").asText()).isEqualTo("PUBLICATION_REJECTED");
    }

    private Result publish(String token, UUID movieId, String body) {
        return publishWithKey(token, movieId, key(), body);
    }

    private Result publishWithKey(String token, UUID movieId, String idempotencyKey, String body) {
        return postAdminCommand(publishPath(movieId), token, idempotencyKey, movieRevision(movieId), body);
    }

    private String publishPath(UUID movieId) {
        return "/api/v1/admin/movies/" + movieId + "/publish";
    }

    private String activatePath(UUID movieId) {
        return "/api/v1/admin/movies/" + movieId + "/activate";
    }

    private String unpublishPath(UUID movieId) {
        return "/api/v1/admin/movies/" + movieId + "/unpublish";
    }

    private String archivePath(UUID movieId) {
        return "/api/v1/admin/movies/" + movieId + "/archive";
    }

    private String restorePath(UUID movieId) {
        return "/api/v1/admin/movies/" + movieId + "/restore";
    }

    private Map<String, String> ifMatch(String etag) {
        return Map.of("If-Match", etag);
    }

    private String movieRevision(UUID movieId) {
        return "\"" + movieRevisionValue(movieId) + "\"";
    }

    private Long movieRevisionValue(UUID movieId) {
        return jdbc.queryForObject("select revision from movie where id=?", Long.class, movieId);
    }

    private int auditCount(UUID movieId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from audit_event where entity_id=?", Integer.class, movieId);
        return count == null ? 0 : count;
    }

    private String publishBody(UUID versionId) {
        return "{\"mediaVersionId\":\"" + versionId + "\"}";
    }

    private String publishBody(UUID versionId, UUID posterId, UUID backdropId) {
        return "{\"mediaVersionId\":\"" + versionId + "\",\"posterAssetId\":"
                + (posterId == null ? "null" : "\"" + posterId + "\"")
                + ",\"backdropAssetId\":"
                + (backdropId == null ? "null" : "\"" + backdropId + "\"") + "}";
    }

    private String key() {
        return "p5-" + UUID.randomUUID();
    }

    private String lifecycle(UUID movieId) {
        return jdbc.queryForObject("select lifecycle from movie where id=?", String.class, movieId);
    }

    private Integer runtimeSeconds(UUID movieId) {
        return jdbc.queryForObject("select runtime_seconds from movie where id=?", Integer.class, movieId);
    }

    private UUID activeVersionId(UUID movieId) {
        return jdbc.queryForObject(
                "select active_media_version_id from movie where id=?", UUID.class, movieId);
    }

    private long catalogRevision() {
        Long revision = jdbc.queryForObject("select revision from catalog_revision where id=1", Long.class);
        return revision == null ? 0 : revision;
    }

    private void addGenre(UUID movieId) {
        List<Integer> genreIds = jdbc.queryForList("select id from genre order by id limit 1", Integer.class);
        assertThat(genreIds).as("the seeded catalog provides at least one genre").isNotEmpty();
        jdbc.update("insert into movie_genre(movie_id, genre_id) values (?, ?)",
                movieId, genreIds.getFirst().shortValue());
    }

    private UUID movie(String token, String slug, boolean withGenre) {
        Result created = postAdminJson("/api/v1/admin/movies", token, "movie-" + slug,
                "{\"title\":\"" + slug + "\",\"slug\":\"" + slug
                        + "\",\"synopsis\":\"A published film.\",\"releaseYear\":2026,"
                        + "\"maturityRating\":\"PG\",\"genreIds\":[],\"featured\":false}");
        assertThat(created.status()).isEqualTo(201);
        UUID movieId = idOf(bodyOf(created));
        if (withGenre) {
            addGenre(movieId);
        }
        return movieId;
    }

    private UUID readyTranscode(UUID movieId, int durationSeconds) {
        UUID versionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                insert into media_version(id, movie_id, state, source_key, duration_seconds)
                values (?, ?, 'READY', ?, ?)
                """, versionId, movieId, "source/" + movieId + "/" + versionId + "/original", durationSeconds);
        jdbc.update("""
                insert into media_job(id, movie_id, media_version_id, kind, state, attempt_number)
                values (?, ?, ?, 'TRANSCODE', 'SUCCEEDED', 1)
                """, jobId, movieId, versionId);
        jdbc.update("""
                insert into media_job_attempt(
                    id, job_id, attempt_number, worker_id, state, lease_until, output_prefix)
                values (?, ?, 1, 'worker-test', 'SUCCEEDED', now() + interval '1 hour', ?)
                """, attemptId, jobId, "hls/" + movieId + "/" + versionId + "/" + attemptId + "/");
        return versionId;
    }

    private UUID readyArtwork(UUID movieId, String kind) {
        UUID assetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                insert into media_asset(id, movie_id, kind, state, source_key)
                values (?, ?, ?, 'READY', ?)
                """, assetId, movieId, kind, "source/" + movieId + "/" + assetId + "/original");
        jdbc.update("""
                insert into media_job(id, movie_id, asset_id, kind, state, attempt_number)
                values (?, ?, ?, 'ARTWORK', 'SUCCEEDED', 1)
                """, jobId, movieId, assetId);
        jdbc.update("""
                insert into media_job_attempt(
                    id, job_id, attempt_number, worker_id, state, lease_until, output_prefix)
                values (?, ?, 1, 'worker-test', 'SUCCEEDED', now() + interval '1 hour', ?)
                """, attemptId, jobId, "artwork/" + movieId + "/" + assetId + "/" + attemptId + "/");
        putDelivery("artwork/" + movieId + "/" + assetId + "/" + attemptId + "/image.jpg",
                kind.equals("POSTER") ? "poster-bytes" : "backdrop-bytes");
        return assetId;
    }

    /**
     * Artwork the movie considers READY whose processing job has not succeeded yet, so it is not a
     * validated artifact and publication must refuse it.
     */
    private UUID unreadyArtwork(UUID movieId, String kind) {
        UUID assetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into media_asset(id, movie_id, kind, state, source_key)
                values (?, ?, ?, 'READY', ?)
                """, assetId, movieId, kind, "source/" + movieId + "/" + assetId + "/original");
        jdbc.update("""
                insert into media_job(id, movie_id, asset_id, kind, state, attempt_number)
                values (?, ?, ?, 'ARTWORK', 'RUNNING', 1)
                """, jobId, movieId, assetId);
        return assetId;
    }

    /** A media version that exists for the movie but is still processing. */
    private UUID processingVersion(UUID movieId) {
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                insert into media_version(id, movie_id, state, source_key)
                values (?, ?, 'PROCESSING', ?)
                """, versionId, movieId, "source/" + movieId + "/" + versionId + "/original");
        return versionId;
    }

    private void putDelivery(String key, String content) {
        try (S3Client s3 = s3()) {
            s3.putObject(PutObjectRequest.builder().bucket(DELIVERY_BUCKET).key(key).build(),
                    RequestBody.fromBytes(content.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private String readDelivery(String key) {
        try (S3Client s3 = s3()) {
            byte[] body = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(DELIVERY_BUCKET).key(key).build()).asByteArray();
            return new String(body, StandardCharsets.UTF_8);
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException missing) {
            return null;
        }
    }

    private String admin(String username) {
        String token = registerAndToken(username);
        roleCommand.grantAdmin(username);
        return token;
    }

    private static S3Client s3() {
        return S3Client.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .endpointOverride(URI.create(minioEndpoint()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }
}
