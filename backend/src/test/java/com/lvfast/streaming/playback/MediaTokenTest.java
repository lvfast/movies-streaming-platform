package com.lvfast.streaming.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lvfast.streaming.media.catalog.ReadyMediaVersion;
import com.lvfast.streaming.ops.AdminRoleCommand;
import com.lvfast.streaming.support.ApiTestSupport;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * The media token is a separate authorization artifact: it uses its own RS256 key and audience, binds
 * session, movie, version, purpose and one immutable prefix, expires within five minutes, and cannot
 * be used as a login token. Token issuance is only reachable through the session service, so this
 * test also exercises the session-ownership and publication gates around refresh.
 */
class MediaTokenTest extends ApiTestSupport {

    @Autowired MediaSessionService sessions;
    @Autowired MediaProperties mediaProperties;
    @Autowired AdminRoleCommand roleCommand;
    @Autowired @Qualifier("mediaJwtDecoder") JwtDecoder mediaDecoder;

    @Test
    void mediaTokenBindsSessionMovieVersionPurposeAndPrefix() {
        String adminToken = adminToken("p5_token_admin");
        UUID adminId = userIdOf(adminToken);
        UUID movieId = movie(adminToken, "p5-token");
        UUID versionId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String prefix = "/hls/" + movieId + "/" + versionId + "/" + attemptId + "/";
        jdbc.update("""
                insert into media_version(id, movie_id, state, source_key, duration_seconds)
                values (?, ?, 'READY', ?, 5400)
                """, versionId, movieId, "source/" + movieId + "/" + versionId + "/original");

        PlaybackGrant granted = sessions.preview(
                adminId, movieId, version(movieId, versionId, attemptId), 12);

        Jwt decoded = mediaDecoder.decode(granted.mediaToken());
        assertThat(decoded.getAudience()).containsExactly("lvfast-media");
        assertThat(decoded.getClaimAsString("purpose")).isEqualTo("PREVIEW");
        assertThat(decoded.getClaimAsString("movieId")).isEqualTo(movieId.toString());
        assertThat(decoded.getClaimAsString("versionId")).isEqualTo(versionId.toString());
        assertThat(decoded.getClaimAsString("prefix")).endsWith(attemptId + "/");
        assertThat(decoded.getClaimAsString("prefix")).isEqualTo(prefix);
        assertThat(decoded.getClaimAsString("sid")).isEqualTo(granted.sessionId().toString());
        assertThat(decoded.getId()).isNotBlank();

        Instant expiresAt = Instant.parse(granted.mediaTokenExpiresAt());
        Duration lifetime = Duration.between(Instant.now(), expiresAt);
        assertThat(lifetime).as("a media token must be short lived").isPositive();
        assertThat(lifetime).as("a media token may not outlive five minutes")
                .isLessThanOrEqualTo(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(decoded.getExpiresAt()).isNotNull();
    }

    @Test
    void loginTokensAreNotAcceptedByTheMediaDecoder() {
        String loginToken = registerAndToken("p5_media_key_isolation");

        assertThatThrownBy(() -> mediaDecoder.decode(loginToken))
                .isInstanceOfAny(org.springframework.security.oauth2.jwt.JwtException.class,
                        DataAccessException.class, IllegalArgumentException.class);
    }

    @Test
    void refreshIsDeniedForAForcedRevokedOrForeignSession() {
        String adminToken = adminToken("p5_refresh_admin");
        UUID movieId = movie(adminToken, "p5-refresh");
        UUID versionId = readyTranscode(movieId);

        Result grant = get("/api/v1/movies/" + movieId + "/playback", adminToken);
        assertThat(grant.status()).isEqualTo(200);
        String sessionId = bodyOf(grant).get("sessionId").asText();

        Result refreshed = postNoBody(
                "/api/v1/me/playback-sessions/" + sessionId + "/token", adminToken, Map.of());
        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(bodyOf(refreshed).get("mediaToken").asText()).isNotBlank();

        String otherUser = registerAndToken("p5_refresh_other");
        Result foreign = postNoBody(
                "/api/v1/me/playback-sessions/" + sessionId + "/token", otherUser, Map.of());
        assertThat(foreign.status()).isEqualTo(404);

        Result unknown = postNoBody(
                "/api/v1/me/playback-sessions/" + UUID.randomUUID() + "/token", adminToken, Map.of());
        assertThat(unknown.status()).isEqualTo(404);

        Result anonymous = postNoBody(
                "/api/v1/me/playback-sessions/" + sessionId + "/token", null, Map.of());
        assertThat(anonymous.status()).isEqualTo(401);

        jdbc.update("update media_playback_session set revoked_at=now() where id=?",
                UUID.fromString(sessionId));
        Result revoked = postNoBody(
                "/api/v1/me/playback-sessions/" + sessionId + "/token", adminToken, Map.of());
        assertThat(revoked.status()).isEqualTo(404);
    }

    @Test
    void progressRequiresTheSessionMovieAndVersionToMatch() {
        String adminToken = adminToken("p5_progress_admin");
        UUID movieId = movie(adminToken, "p5-progress");
        UUID versionId = readyTranscode(movieId);

        Result grant = get("/api/v1/movies/" + movieId + "/playback", adminToken);
        String sessionId = bodyOf(grant).get("sessionId").asText();

        Result stored = putJson("/api/v1/me/progress/" + movieId, adminToken, null,
                "{\"positionSeconds\":30,\"durationSeconds\":100,"
                        + "\"clientUpdatedAt\":\"2026-09-12T10:00:00Z\",\"sessionId\":\"" + sessionId
                        + "\",\"mediaVersionId\":\"" + versionId + "\"}");
        assertThat(stored.status()).isEqualTo(200);
        assertThat(bodyOf(stored).get("positionSeconds").asInt()).isEqualTo(30);
        assertThat(bodyOf(stored).get("mediaVersionId").asText()).isEqualTo(versionId.toString());

        Result mismatched = putJson("/api/v1/me/progress/" + movieId, adminToken, null,
                "{\"positionSeconds\":31,\"durationSeconds\":100,"
                        + "\"clientUpdatedAt\":\"2026-09-12T10:01:00Z\",\"sessionId\":\"" + sessionId
                        + "\",\"mediaVersionId\":\"" + UUID.randomUUID() + "\"}");
        assertThat(mismatched.status()).isEqualTo(404);

        Result partial = putJson("/api/v1/me/progress/" + movieId, adminToken, null,
                "{\"positionSeconds\":31,\"durationSeconds\":100,"
                        + "\"clientUpdatedAt\":\"2026-09-12T10:01:00Z\",\"sessionId\":\"" + sessionId + "\"}");
        assertThat(partial.status()).isEqualTo(400);

        Result noSession = putJson("/api/v1/me/progress/" + movieId, adminToken, null,
                "{\"positionSeconds\":32,\"durationSeconds\":100,"
                        + "\"clientUpdatedAt\":\"2026-09-12T10:02:00Z\"}");
        assertThat(noSession.status()).isEqualTo(200);
    }

    /**
     * Watching, closing the player and opening it again must resume where the viewer stopped, and a
     * newly activated version must not inherit that position. The active pointer moves only through
     * the explicit version-pinned shape this test writes, so the assertions stay about resume
     * resolution instead of the admin command contract.
     */
    @Test
    void progressResumesAcrossSessionsForTheSameVersionAndIsNotInheritedByANewVersion() {
        String token = adminToken("p5_resume");
        UUID movieId = movie(token, "p5-resume");
        UUID firstVersion = publishedReadyTranscode(movieId, 5400);

        Result firstGrant = get("/api/v1/movies/" + movieId + "/playback", token);
        assertThat(firstGrant.status()).isEqualTo(200);
        String firstSessionId = bodyOf(firstGrant).get("sessionId").asText();
        assertThat(bodyOf(firstGrant).get("resumePositionSeconds").asInt()).isZero();

        Result reopenedAfterWatching = watchThenReopen(
                token, movieId, UUID.fromString(firstSessionId), firstVersion, 30,
                "2026-09-12T10:00:00Z");
        assertThat(reopenedAfterWatching.status()).isEqualTo(200);
        assertThat(bodyOf(reopenedAfterWatching).get("sessionId").asText())
                .as("a new grant must create a new session")
                .isNotEqualTo(firstSessionId);
        assertThat(bodyOf(reopenedAfterWatching).get("resumePositionSeconds").asInt())
                .as("a fresh session for the same version must resume the stored position")
                .isEqualTo(30);

        // A viewer's progress belongs to the version it was watched on, not to one session id.
        UUID newerVersion = transcode(movieId, 7200).versionId();
        jdbc.update("""
                update movie set active_media_version_id=?, runtime_seconds=7200,
                    revision=revision+1, updated_at=now()
                where id=?
                """, newerVersion, movieId);

        Result grantForNewVersion = get("/api/v1/movies/" + movieId + "/playback", token);
        assertThat(grantForNewVersion.status()).isEqualTo(200);
        assertThat(bodyOf(grantForNewVersion).get("mediaVersionId").asText())
                .isEqualTo(newerVersion.toString());
        assertThat(bodyOf(grantForNewVersion).get("resumePositionSeconds").asInt())
                .as("progress from the previous version must never resume the new one")
                .isZero();
        assertThat(jdbc.queryForObject("""
                select media_version_id from viewing_progress where user_id=? and movie_id=?
                """, UUID.class, userIdOf(token), movieId))
                .as("activating a version must not rewrite stored progress")
                .isEqualTo(firstVersion);
    }

    @Test
    void anExpiredSessionIsDeniedForBothRefreshAndProgress() {
        String token = adminToken("p5_expiry");
        UUID movieId = movie(token, "p5-expiry");
        UUID versionId = publishedReadyTranscode(movieId, 5400);

        Result grant = get("/api/v1/movies/" + movieId + "/playback", token);
        assertThat(grant.status()).isEqualTo(200);
        UUID sessionId = UUID.fromString(bodyOf(grant).get("sessionId").asText());

        jdbc.update("update media_playback_session set expires_at=now() - interval '1 minute' where id=?",
                sessionId);

        Result expiredRefresh = postNoBody(
                "/api/v1/me/playback-sessions/" + sessionId + "/token", token, Map.of());
        assertThat(expiredRefresh.status()).isEqualTo(404);

        Result expiredProgress = putJson("/api/v1/me/progress/" + movieId, token, null,
                "{\"positionSeconds\":31,\"durationSeconds\":5400,"
                        + "\"clientUpdatedAt\":\"2026-09-12T11:00:00Z\",\"sessionId\":\"" + sessionId
                        + "\",\"mediaVersionId\":\"" + versionId + "\"}");
        assertThat(expiredProgress.status()).isEqualTo(404);
    }

    @Test
    void previewRefreshStaysAdminOwnedAndStopsWhenTheRoleIsRevoked() {
        String adminName = "p5_preview_refresh";
        String adminToken = adminToken(adminName);
        UUID movieId = movie(adminToken, "p5-preview-refresh");
        UUID versionId = readyTranscode(movieId);

        Result preview = postNoBody("/api/v1/admin/movies/" + movieId + "/preview", adminToken, Map.of());
        assertThat(preview.status()).isEqualTo(200);
        String sessionId = bodyOf(preview).get("sessionId").asText();
        assertThat(bodyOf(preview).get("resumePositionSeconds").asInt())
                .as("an ADMIN preview always starts at the beginning")
                .isZero();

        Result refreshed = postNoBody(
                "/api/v1/admin/playback-sessions/" + sessionId + "/token", adminToken, Map.of());
        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(bodyOf(refreshed).get("mediaToken").asText()).isNotBlank();

        // A viewer session endpoint must not refresh an ADMIN preview, and vice versa.
        Result wrongEndpoint = postNoBody(
                "/api/v1/me/playback-sessions/" + sessionId + "/token", adminToken, Map.of());
        assertThat(wrongEndpoint.status()).isEqualTo(404);

        String viewerToken = registerAndToken("p5_preview_bystander");
        Result viewerRefreshingPreview = postNoBody(
                "/api/v1/admin/playback-sessions/" + sessionId + "/token", viewerToken, Map.of());
        assertThat(viewerRefreshingPreview.status()).isEqualTo(403);

        String otherAdminToken = adminToken("p5_preview_other_admin");
        Result foreignAdmin = postNoBody(
                "/api/v1/admin/playback-sessions/" + sessionId + "/token", otherAdminToken, Map.of());
        assertThat(foreignAdmin.status())
                .as("another administrator must not refresh somebody else's preview session")
                .isEqualTo(404);

        assertThat(jdbc.queryForObject("""
                select media_version_id from media_playback_session where id=?
                """, UUID.class, UUID.fromString(sessionId)))
                .as("a denied refresh must not move the pinned version")
                .isEqualTo(versionId);

        // Revocation closes the write path last: the admin-only authorization runs before the
        // controller, so no preview session can be refreshed once the database role is gone.
        roleCommand.revokeAdmin(adminName);
        Result revokedRole = postNoBody(
                "/api/v1/admin/playback-sessions/" + sessionId + "/token", adminToken, Map.of());
        assertThat(revokedRole.status())
                .as("a revoked ADMIN role must lose preview refresh even with a valid login token")
                .isEqualTo(403);
    }

    private ReadyMediaVersion version(UUID movieId, UUID versionId, UUID attemptId) {
        String prefix = "/hls/" + movieId + "/" + versionId + "/" + attemptId + "/";
        return new ReadyMediaVersion(movieId, versionId, attemptId, UUID.randomUUID(), prefix,
                prefix + "index.m3u8");
    }

    private UUID userIdOf(String token) {
        Result me = get("/api/v1/auth/me", token);
        assertThat(me.status()).isEqualTo(200);
        return UUID.fromString(bodyOf(me).get("id").asText());
    }

    private UUID movie(String token, String slug) {
        Result created = postAdminJson("/api/v1/admin/movies", token, "movie-" + slug,
                "{\"title\":\"" + slug + "\",\"slug\":\"" + slug
                        + "\",\"synopsis\":\"A published film.\",\"releaseYear\":2026,"
                        + "\"maturityRating\":\"PG\",\"genreIds\":[],\"featured\":false}");
        assertThat(created.status()).isEqualTo(201);
        UUID movieId = idOf(bodyOf(created));
        Integer genreId = jdbc.queryForObject("select id from genre order by id limit 1", Integer.class);
        jdbc.update("insert into movie_genre(movie_id, genre_id) values (?, ?)",
                movieId, genreId.shortValue());
        return movieId;
    }

    /**
     * A READY, published transcode whose active pointer is already set, so a playback grant resolves
     * immediately. Session, refresh and progress tests need a playable movie, not a publication
     * command.
     */
    private UUID readyTranscode(UUID movieId) {
        return publishedReadyTranscode(movieId, 5400);
    }

    /**
     * A READY version with a SUCCEEDED transcode attempt that has no movie projection yet. The
     * attempt id is the immutable HLS prefix segment the media catalog resolves.
     */
    private Transcode transcode(UUID movieId, int durationSeconds) {
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
        return new Transcode(versionId, attemptId);
    }

    /** Publishes a READY transcode without going through the HTTP command. */
    private UUID publishedReadyTranscode(UUID movieId, int durationSeconds) {
        Transcode transcode = transcode(movieId, durationSeconds);
        long revision = jdbc.queryForObject("select revision from movie where id=?", Long.class, movieId);
        jdbc.update("""
                update movie set lifecycle='PUBLISHED', published=true, active_media_version_id=?,
                    hls_manifest_url=?, runtime_seconds=?, revision=revision+1, updated_at=now()
                where id=?
                """, transcode.versionId(),
                "/hls/" + movieId + "/" + transcode.versionId() + "/" + transcode.attemptId() + "/index.m3u8",
                durationSeconds, movieId);
        assertThat(revision).isGreaterThanOrEqualTo(0);
        return transcode.versionId();
    }

    /**
     * Stores the viewer progress a browser would have written while watching {@code versionId} with
     * {@code sessionId}, then opens a brand-new playback grant and returns that response.
     */
    private Result watchThenReopen(
            String token, UUID movieId, UUID sessionId, UUID versionId,
            int positionSeconds, String clientUpdatedAt) {
        jdbc.update("""
                insert into viewing_progress(
                    user_id, movie_id, position_seconds, duration_seconds, completed, client_updated_at,
                    session_id, media_version_id)
                values (?, ?, ?, 5400, false, ?, ?, ?)
                on conflict (user_id, movie_id) do update set
                    position_seconds=excluded.position_seconds,
                    duration_seconds=excluded.duration_seconds,
                    completed=excluded.completed,
                    client_updated_at=excluded.client_updated_at,
                    session_id=excluded.session_id,
                    media_version_id=excluded.media_version_id,
                    updated_at=now()
                """, userIdOf(token), movieId, positionSeconds,
                Timestamp.from(Instant.parse(clientUpdatedAt)), sessionId, versionId);
        return get("/api/v1/movies/" + movieId + "/playback", token);
    }

    private String adminToken(String username) {
        registerAndToken(username);
        roleCommand.grantAdmin(username);
        Result login = postJson("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"LongEnough9X\"}");
        assertThat(login.status()).isEqualTo(200);
        return tokenFrom(login);
    }

    private record Transcode(UUID versionId, UUID attemptId) {
    }
}
