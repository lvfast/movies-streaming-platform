package com.lvfast.streaming.media;

import com.lvfast.streaming.catalog.MovieNotFoundException;
import com.lvfast.streaming.media.job.JdbcMediaJobRepository;
import com.lvfast.streaming.media.job.JobView;
import com.lvfast.streaming.media.job.MediaJobNotFoundException;
import com.lvfast.streaming.media.upload.MediaValidationException;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin read APIs for media versions, artwork assets and processing jobs. These endpoints return
 * server-owned projections only: raw source keys, storage credentials and signed URLs are never
 * exposed. Write access and lifecycle commands arrive in their owning packets.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminMediaController {

    private final JdbcAdminMediaRepository media;
    private final JdbcMediaJobRepository jobs;
    private final AssetPreviewService previews;

    public AdminMediaController(
            JdbcAdminMediaRepository media, JdbcMediaJobRepository jobs, AssetPreviewService previews) {
        this.media = media;
        this.jobs = jobs;
        this.previews = previews;
    }

    @GetMapping("/movies/{movieId}/versions")
    MediaPage<MediaVersionView> versions(
            @PathVariable UUID movieId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireMovie(movieId);
        validate(page, size);
        return new MediaPage<>(media.listVersions(movieId, page, size), page, size,
                media.countVersions(movieId));
    }

    @GetMapping("/movies/{movieId}/assets")
    MediaPage<MediaAssetView> assets(
            @PathVariable UUID movieId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireMovie(movieId);
        validate(page, size);
        return new MediaPage<>(media.listAssets(movieId, page, size), page, size,
                media.countAssets(movieId));
    }

    @GetMapping("/jobs")
    MediaPage<JobView> jobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        validate(page, size);
        return new MediaPage<>(jobs.listViews(page, size), page, size, jobs.count());
    }

    @GetMapping("/jobs/{jobId}")
    JobView job(@PathVariable UUID jobId) {
        return jobs.findView(jobId).orElseThrow(() -> new MediaJobNotFoundException(jobId));
    }

    @PostMapping("/assets/{assetId}/preview")
    AssetPreview preview(@PathVariable UUID assetId) {
        return previews.preview(assetId);
    }

    private void requireMovie(UUID movieId) {
        if (!media.movieExists(movieId)) {
            throw new MovieNotFoundException(movieId);
        }
    }

    private void validate(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new MediaValidationException("Page must be non-negative and size must be between 1 and 100");
        }
    }
}
