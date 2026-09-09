package com.lvfast.streaming.playback;

import com.lvfast.streaming.catalog.MovieNotFoundException;
import com.lvfast.streaming.common.MediaUrlResolver;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaybackService {
    private final PlaybackRepository repository;
    private final MediaUrlResolver mediaUrls;

    PlaybackService(PlaybackRepository repository, MediaUrlResolver mediaUrls) {
        this.repository = repository;
        this.mediaUrls = mediaUrls;
    }

    public Playback playback(UUID userId, UUID movieId) {
        PlayableMovie movie = repository.playableMovie(movieId)
                .orElseThrow(() -> new MovieNotFoundException(movieId));
        int resumePosition = repository.progress(userId, movieId)
                .filter(progress -> !progress.completed())
                .map(ViewingProgress::positionSeconds)
                .orElse(0);
        return new Playback(movie.id(), mediaUrls.resolve(movie.manifestUrl()), resumePosition);
    }

    @Transactional
    public ViewingProgress updateProgress(
            UUID userId,
            UUID movieId,
            int positionSeconds,
            int durationSeconds,
            Instant clientUpdatedAt) {
        validate(positionSeconds, durationSeconds, clientUpdatedAt);
        repository.playableMovie(movieId)
                .orElseThrow(() -> new MovieNotFoundException(movieId));
        boolean completed = (long) positionSeconds * 10 >= (long) durationSeconds * 9;
        return repository.save(
                userId, movieId, positionSeconds, durationSeconds, clientUpdatedAt, completed);
    }

    private void validate(int positionSeconds, int durationSeconds, Instant clientUpdatedAt) {
        if (positionSeconds < 0) {
            throw new ProgressValidationException("Position must be non-negative");
        }
        if (durationSeconds < 1) {
            throw new ProgressValidationException("Duration must be at least one second");
        }
        if (positionSeconds > durationSeconds) {
            throw new ProgressValidationException("Position cannot exceed duration");
        }
        if (clientUpdatedAt == null) {
            throw new ProgressValidationException("Client update time is required");
        }
    }
}
