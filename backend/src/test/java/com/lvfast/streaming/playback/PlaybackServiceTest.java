package com.lvfast.streaming.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.lvfast.streaming.catalog.MovieNotFoundException;
import com.lvfast.streaming.common.MediaUrlResolver;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlaybackServiceTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MOVIE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CLIENT_TIME = Instant.parse("2026-09-07T00:00:00Z");

    @Mock private PlaybackRepository repository;
    @Mock private MediaSessionService sessions;
    private PlaybackService service;

    @BeforeEach
    void setUp() {
        service = new PlaybackService(repository, sessions, new MediaUrlResolver(""));
    }

    @Test
    void playbackReturnsTheStoredResumePositionForAnIncompleteMovie() {
        when(repository.playableMovie(MOVIE_ID))
                .thenReturn(Optional.of(new PlayableMovie(MOVIE_ID, "/media/movie/index.m3u8", "LEGACY", null)));
        when(repository.progress(USER_ID, MOVIE_ID))
                .thenReturn(Optional.of(new ViewingProgress(
                        MOVIE_ID, 45, 100, CLIENT_TIME, false)));

        Playback result = service.playback(USER_ID, MOVIE_ID);

        assertThat(result.manifestUrl()).isEqualTo("/media/movie/index.m3u8");
        assertThat(result.resumePositionSeconds()).isEqualTo(45);
    }

    @Test
    void playbackResolvesRelativeManifestAgainstConfiguredMediaBase() {
        when(repository.playableMovie(MOVIE_ID))
                .thenReturn(Optional.of(new PlayableMovie(MOVIE_ID, "/media/movie/index.m3u8", "LEGACY", null)));
        when(repository.progress(USER_ID, MOVIE_ID)).thenReturn(Optional.empty());
        PlaybackService externallyHosted = new PlaybackService(
                repository, sessions, new MediaUrlResolver("https://media.example.test/library"));

        assertThat(externallyHosted.playback(USER_ID, MOVIE_ID).manifestUrl())
                .isEqualTo("https://media.example.test/library/movie/index.m3u8");
    }

    @Test
    void playbackStartsAtZeroWhenThereIsNoProgressOrTheMovieWasCompleted() {
        when(repository.playableMovie(MOVIE_ID))
                .thenReturn(Optional.of(new PlayableMovie(MOVIE_ID, "/media/movie/index.m3u8", "LEGACY", null)));
        when(repository.progress(USER_ID, MOVIE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new ViewingProgress(
                        MOVIE_ID, 90, 100, CLIENT_TIME, true)));

        assertThat(service.playback(USER_ID, MOVIE_ID).resumePositionSeconds()).isZero();
        assertThat(service.playback(USER_ID, MOVIE_ID).resumePositionSeconds()).isZero();
    }

    @Test
    void unpublishedOrUnplayableMoviesAreNotExposedForPlayback() {
        when(repository.playableMovie(MOVIE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.playback(USER_ID, MOVIE_ID))
                .isInstanceOf(MovieNotFoundException.class);
    }

    @Test
    void progressRejectsInvalidPositionsAndMissingClientTime() {
        assertThatThrownBy(() -> service.updateProgress(USER_ID, MOVIE_ID, -1, 100, CLIENT_TIME))
                .isInstanceOf(ProgressValidationException.class);
        assertThatThrownBy(() -> service.updateProgress(USER_ID, MOVIE_ID, 101, 100, CLIENT_TIME))
                .isInstanceOf(ProgressValidationException.class);
        assertThatThrownBy(() -> service.updateProgress(USER_ID, MOVIE_ID, 0, 0, CLIENT_TIME))
                .isInstanceOf(ProgressValidationException.class);
        assertThatThrownBy(() -> service.updateProgress(USER_ID, MOVIE_ID, 0, 100, null))
                .isInstanceOf(ProgressValidationException.class);
    }

    @Test
    void progressBecomesCompletedAtExactlyNinetyPercent() {
        when(repository.playableMovie(MOVIE_ID))
                .thenReturn(Optional.of(new PlayableMovie(MOVIE_ID, "/media/movie/index.m3u8", "LEGACY", null)));
        when(repository.save(USER_ID, MOVIE_ID, 89, 100, CLIENT_TIME, false))
                .thenReturn(new ViewingProgress(MOVIE_ID, 89, 100, CLIENT_TIME, false));
        when(repository.save(USER_ID, MOVIE_ID, 90, 100, CLIENT_TIME, true))
                .thenReturn(new ViewingProgress(MOVIE_ID, 90, 100, CLIENT_TIME, true));

        assertThat(service.updateProgress(USER_ID, MOVIE_ID, 89, 100, CLIENT_TIME).completed()).isFalse();
        assertThat(service.updateProgress(USER_ID, MOVIE_ID, 90, 100, CLIENT_TIME).completed()).isTrue();
    }
}
