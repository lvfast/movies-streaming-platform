package com.lvfast.streaming.media.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only projections for READY media. The manifest path is derived from the immutable attempt that
 * produced the output, so a published movie, an admin preview and an already-issued playback session
 * all resolve the same prefix for the same READY version.
 */
@Repository
public class JdbcMediaCatalog implements MediaCatalog {

    private static final String MANIFEST = "index.m3u8";

    private final JdbcTemplate jdbc;

    public JdbcMediaCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ReadyMediaVersion> readyVersion(UUID movieId, UUID versionId) {
        if (movieId == null || versionId == null) {
            return Optional.empty();
        }
        return attempt(movieId, versionId).map(this::readVersion);
    }

    @Override
    public Optional<ReadyMediaVersion> readyTranscode(UUID versionId) {
        if (versionId == null) {
            return Optional.empty();
        }
        return attempt(null, versionId).map(this::readVersion);
    }

    @Override
    public Optional<ReadyMediaAsset> readyAsset(UUID movieId, UUID assetId) {
        if (movieId == null || assetId == null) {
            return Optional.empty();
        }
        List<ReadyMediaAsset> assets = jdbc.query("""
                select a.id as asset_id, a.movie_id, a.kind as asset_kind,
                       att.output_prefix as source_prefix
                from media_asset a
                join media_job j on j.asset_id = a.id and j.state='SUCCEEDED'
                join media_job_attempt att on att.job_id = j.id and att.state='SUCCEEDED'
                where a.id=? and a.movie_id=? and a.state='READY'
                order by att.attempt_number desc
                limit 1
                """,
                (rs, row) -> {
                    UUID assetIdValue = rs.getObject("asset_id", UUID.class);
                    String prefix = rs.getString("source_prefix");
                    String normalized = prefix.endsWith("/") ? prefix : prefix + "/";
                    return new ReadyMediaAsset(
                            rs.getObject("movie_id", UUID.class),
                            assetIdValue,
                            rs.getString("asset_kind"),
                            normalized + ReadyMediaAsset.ARTWORK_OBJECT,
                            ReadyMediaAsset.publicKeyFor(assetIdValue));
                },
                assetId, movieId);
        return assets.stream().findFirst();
    }

    @Override
    public Optional<ReadyMediaAsset> readyAssetById(UUID assetId) {
        if (assetId == null) {
            return Optional.empty();
        }
        List<ReadyMediaAsset> assets = jdbc.query("""
                select a.id as asset_id, a.movie_id, a.kind as asset_kind,
                       att.output_prefix as source_prefix
                from media_asset a
                join media_job j on j.asset_id = a.id and j.state='SUCCEEDED'
                join media_job_attempt att on att.job_id = j.id and att.state='SUCCEEDED'
                where a.id=? and a.state='READY'
                order by att.attempt_number desc
                limit 1
                """,
                (rs, row) -> {
                    UUID assetIdValue = rs.getObject("asset_id", UUID.class);
                    String prefix = rs.getString("source_prefix");
                    String normalized = prefix.endsWith("/") ? prefix : prefix + "/";
                    return new ReadyMediaAsset(
                            rs.getObject("movie_id", UUID.class),
                            assetIdValue,
                            rs.getString("asset_kind"),
                            normalized + ReadyMediaAsset.ARTWORK_OBJECT,
                            ReadyMediaAsset.publicKeyFor(assetIdValue));
                },
                assetId);
        return assets.stream().findFirst();
    }

    @Override
    public Optional<UUID> latestReadyVersionId(UUID movieId) {
        List<UUID> rows = jdbc.query("""
                select id from media_version
                where movie_id=? and state='READY'
                order by created_at desc, id
                limit 1
                """, (rs, row) -> rs.getObject("id", UUID.class), movieId);
        return rows.stream().findFirst();
    }

    @Override
    public int runtimeSeconds(UUID versionId) {
        List<Integer> rows = jdbc.query("""
                select duration_seconds from media_version where id=?
                """, (rs, row) -> rs.getObject("duration_seconds", Integer.class), versionId);
        return rows.stream().findFirst().orElse(0);
    }

    private Optional<AttemptKey> attempt(UUID movieId, UUID versionId) {
        List<AttemptKey> rows = movieId == null
                ? jdbc.query("""
                        select v.movie_id, v.id as version_id, a.id as attempt_id, a.job_id
                        from media_version v
                        join media_job j on j.media_version_id = v.id and j.state='SUCCEEDED'
                        join media_job_attempt a on a.job_id = j.id and a.state='SUCCEEDED'
                        where v.state='READY' and v.id=?
                        order by a.attempt_number desc
                        limit 1
                        """, this::attemptKey, versionId)
                : jdbc.query("""
                        select v.movie_id, v.id as version_id, a.id as attempt_id, a.job_id
                        from media_version v
                        join media_job j on j.media_version_id = v.id and j.state='SUCCEEDED'
                        join media_job_attempt a on a.job_id = j.id and a.state='SUCCEEDED'
                        where v.state='READY' and v.id=? and v.movie_id=?
                        order by a.attempt_number desc
                        limit 1
                        """, this::attemptKey, versionId, movieId);
        return rows.stream().findFirst();
    }

    private ReadyMediaVersion readVersion(AttemptKey key) {
        String prefix = "/hls/" + key.movieId() + "/" + key.versionId() + "/" + key.attemptId() + "/";
        return new ReadyMediaVersion(
                key.movieId(), key.versionId(), key.attemptId(), key.jobId(), prefix, prefix + MANIFEST);
    }

    private AttemptKey attemptKey(ResultSet rs, int row) throws SQLException {
        return new AttemptKey(
                rs.getObject("movie_id", UUID.class),
                rs.getObject("version_id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                rs.getObject("job_id", UUID.class));
    }

    private record AttemptKey(UUID movieId, UUID versionId, UUID attemptId, UUID jobId) {
    }
}
