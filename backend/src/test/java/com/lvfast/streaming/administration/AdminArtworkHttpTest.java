package com.lvfast.streaming.administration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lvfast.streaming.ops.AdminRoleCommand;
import com.lvfast.streaming.support.ApiTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * HTTP behavior for the P7 artwork-selection command. Selecting a validated READY poster/backdrop is
 * an explicit revision-checked edit: a wrong-kind, foreign or unvalidated asset is refused without
 * touching the movie row.
 */
class AdminArtworkHttpTest extends ApiTestSupport {

    /**
     * Gives this class its own Spring context so it cannot reuse a cached context whose Testcontainers
     * Postgres port was remapped after the previous test class stopped the shared container.
     */
    @DynamicPropertySource
    static void contextKey(DynamicPropertyRegistry registry) {
        registry.add("app.test.context-key", () -> "p7-artwork");
    }

    @Autowired AdminRoleCommand roleCommand;

    @BeforeEach
    void cleanMedia() {
        jdbc.update("delete from outbox_event");
        jdbc.update("delete from inbox_event");
        jdbc.update("delete from media_job_attempt");
        jdbc.update("delete from upload_session");
        jdbc.update("delete from media_job");
        jdbc.update("delete from media_asset");
        jdbc.update("delete from media_version");
        jdbc.update("delete from movie where management_mode='MANAGED'");
    }

    @Test
    void attachesReadyArtworkAndBumpsTheRevision() {
        String token = admin("p7_artwork_ok");
        UUID movieId = movie(token, "p7-artwork-ok");
        UUID posterId = readyArtwork(movieId, "POSTER");

        Result attached = postAdminCommand(
                artworkPath(movieId), token, null, movieRevision(movieId),
                "{\"kind\":\"POSTER\",\"assetId\":\"" + posterId + "\"}");

        assertThat(attached.status()).isEqualTo(200);
        assertThat(attached.etag()).isEqualTo("\"1\"");
        assertThat(bodyOf(attached).get("posterAssetId").asText()).isEqualTo(posterId.toString());
        assertThat(jdbc.queryForObject(
                "select poster_asset_id from movie where id=?", UUID.class, movieId)).isEqualTo(posterId);
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_event where entity_id=? and action='MOVIE_ARTWORK_ATTACHED'
                """, Integer.class, movieId)).isEqualTo(1);
    }

    @Test
    void attachesBackdropWithoutDisturbingThePosterPointer() {
        String token = admin("p7_artwork_backdrop");
        UUID movieId = movie(token, "p7-artwork-backdrop");
        UUID posterId = readyArtwork(movieId, "POSTER");
        UUID backdropId = readyArtwork(movieId, "BACKDROP");

        assertThat(attach(token, movieId, "POSTER", posterId).status()).isEqualTo(200);
        Result attached = attach(token, movieId, "BACKDROP", backdropId);

        assertThat(attached.status()).isEqualTo(200);
        assertThat(attached.etag()).isEqualTo("\"2\"");
        assertThat(jdbc.queryForObject(
                "select poster_asset_id from movie where id=?", UUID.class, movieId)).isEqualTo(posterId);
        assertThat(jdbc.queryForObject(
                "select backdrop_asset_id from movie where id=?", UUID.class, movieId)).isEqualTo(backdropId);
        assertThat(jdbc.queryForObject(
                "select poster_url from movie where id=?", String.class, movieId))
                .isEqualTo("public-artwork/" + posterId + "/image.jpg");
        assertThat(jdbc.queryForObject(
                "select backdrop_url from movie where id=?", String.class, movieId))
                .isEqualTo("public-artwork/" + backdropId + "/image.jpg");
    }

    @Test
    void refusesWrongKindForeignAndUnvalidatedArtworkWithoutTouchingTheMovie() {
        String token = admin("p7_artwork_reject");
        UUID movieId = movie(token, "p7-artwork-reject");
        UUID otherMovie = movie(token, "p7-artwork-reject-other");
        UUID backdropId = readyArtwork(movieId, "BACKDROP");
        UUID foreignPoster = readyArtwork(otherMovie, "POSTER");
        UUID unvalidated = unvalidatedArtwork(movieId, "POSTER");
        Long revisionBefore = movieRevisionValue(movieId);

        Result wrongKind = attach(token, movieId, "POSTER", backdropId);
        assertThat(wrongKind.status()).isEqualTo(409);
        assertThat(bodyOf(wrongKind).get("code").asText()).isEqualTo("PUBLICATION_REJECTED");

        Result foreign = attach(token, movieId, "POSTER", foreignPoster);
        assertThat(foreign.status()).isEqualTo(409);
        assertThat(bodyOf(foreign).get("code").asText()).isEqualTo("PUBLICATION_REJECTED");

        Result notReady = attach(token, movieId, "POSTER", unvalidated);
        assertThat(notReady.status()).isEqualTo(409);
        assertThat(bodyOf(notReady).get("code").asText()).isEqualTo("PUBLICATION_REJECTED");

        assertThat(jdbc.queryForObject(
                "select poster_asset_id from movie where id=?", UUID.class, movieId)).isNull();
        assertThat(movieRevisionValue(movieId)).isEqualTo(revisionBefore);
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_event where entity_id=? and action='MOVIE_ARTWORK_ATTACHED'
                """, Integer.class, movieId)).isZero();
    }

    @Test
    void requiresIfMatchAndRejectsStaleRevisionsAndNonAdmins() {
        String token = admin("p7_artwork_guard");
        UUID movieId = movie(token, "p7-artwork-guard");
        UUID posterId = readyArtwork(movieId, "POSTER");

        Result missing = postNoBody(
                artworkPath(movieId), token, Map.of("Content-Type", "application/json"));
        assertThat(missing.status()).isEqualTo(428);

        Result stale = postAdminCommand(
                artworkPath(movieId), token, null, "\"9\"",
                "{\"kind\":\"POSTER\",\"assetId\":\"" + posterId + "\"}");
        assertThat(stale.status()).isEqualTo(412);

        String viewer = registerAndToken("p7_artwork_viewer");
        Result forbidden = postAdminCommand(
                artworkPath(movieId), viewer, null, movieRevision(movieId),
                "{\"kind\":\"POSTER\",\"assetId\":\"" + posterId + "\"}");
        assertThat(forbidden.status()).isEqualTo(403);
    }

    private Result attach(String token, UUID movieId, String kind, UUID assetId) {
        return postAdminCommand(
                artworkPath(movieId), token, null, movieRevision(movieId),
                "{\"kind\":\"" + kind + "\",\"assetId\":\"" + assetId + "\"}");
    }

    private String artworkPath(UUID movieId) {
        return "/api/v1/admin/movies/" + movieId + "/artwork";
    }

    private String movieRevision(UUID movieId) {
        return "\"" + movieRevisionValue(movieId) + "\"";
    }

    private Long movieRevisionValue(UUID movieId) {
        return jdbc.queryForObject("select revision from movie where id=?", Long.class, movieId);
    }

    private UUID movie(String token, String slug) {
        Result created = postAdminJson("/api/v1/admin/movies", token, "movie-" + slug,
                "{\"title\":\"" + slug + "\",\"slug\":\"" + slug
                        + "\",\"synopsis\":\"Artwork selection.\",\"releaseYear\":2026,"
                        + "\"maturityRating\":\"PG\",\"genreIds\":[],\"featured\":false}");
        assertThat(created.status()).isEqualTo(201);
        return idOf(bodyOf(created));
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
        return assetId;
    }

    private UUID unvalidatedArtwork(UUID movieId, String kind) {
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

    private String admin(String username) {
        String token = registerAndToken(username);
        roleCommand.grantAdmin(username);
        return token;
    }
}
