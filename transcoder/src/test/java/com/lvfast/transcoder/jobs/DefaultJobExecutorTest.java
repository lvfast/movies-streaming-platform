package com.lvfast.transcoder.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lvfast.transcoder.artwork.ArtworkProcessor;
import com.lvfast.transcoder.encode.HlsEncoder;
import com.lvfast.transcoder.events.MediaEventPublisher;
import com.lvfast.transcoder.output.ArtifactWriter;
import com.lvfast.transcoder.probe.Dimensions;
import com.lvfast.transcoder.probe.ProbedVideo;
import com.lvfast.transcoder.probe.VideoProbe;
import com.lvfast.transcoder.storage.WorkerObjectStore;
import com.lvfast.transcoder.support.TranscoderTestSupport;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the immutable-output invariant: the executor uploads every output object before it uploads
 * the {@code artifact.json} manifest, so a partially uploaded attempt is never announced complete.
 */
class DefaultJobExecutorTest {

    @Test
    void uploadsOutputObjectsBeforeTheArtifactManifest() {
        byte[] sourceBytes = "video-bytes".getBytes(StandardCharsets.UTF_8);
        RecordingStore store = new RecordingStore(sourceBytes);

        VideoProbe probe = mock(VideoProbe.class);
        when(probe.probe(any()))
                .thenReturn(new ProbedVideo(new Dimensions(640, 360), 3.0, "h264", "aac", 24.0));

        HlsEncoder encoder = mock(HlsEncoder.class);
        doAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            Files.createDirectories(output);
            Files.writeString(output.resolve("index.m3u8"),
                    "#EXTM3U\n#EXTINF:6.0,\nsegment_00000.ts\n#EXT-X-ENDLIST\n");
            Files.write(output.resolve("segment_00000.ts"), new byte[] {1, 2, 3, 4});
            return null;
        }).when(encoder).encode(any(), any(), any());

        ArtworkProcessor artwork = mock(ArtworkProcessor.class);
        MediaEventPublisher publisher = mock(MediaEventPublisher.class);
        ArtifactWriter writer = new ArtifactWriter();

        DefaultJobExecutor executor = new DefaultJobExecutor(
                store, probe, encoder, artwork, writer, publisher, TranscoderTestSupport.properties());

        ClaimResult claim = new ClaimResult(
                "job-1", "CLAIMED", null, "attempt-1",
                Instant.now().plus(Duration.ofMinutes(2)).toString(), "TRANSCODE",
                "source/original", "hls/movie/version/attempt-1/", "movie", "version", null,
                "h264-aac-1080p30");
        LeaseGuard lease = mock(LeaseGuard.class);

        executor.execute(claim, lease);

        assertThat(store.putKeys).containsSubsequence(
                "hls/movie/version/attempt-1/index.m3u8",
                "hls/movie/version/attempt-1/segment_00000.ts",
                "hls/movie/version/attempt-1/artifact.json");
        assertThat(store.putKeys.getLast()).isEqualTo("hls/movie/version/attempt-1/artifact.json");
    }

    private static final class RecordingStore implements WorkerObjectStore {

        private final byte[] source;
        private final List<String> putKeys = new ArrayList<>();

        RecordingStore(byte[] source) {
            this.source = source;
        }

        @Override
        public InputStream read(String role, String key) {
            return new ByteArrayInputStream(source);
        }

        @Override
        public void put(String role, String key, Path file, String contentType) {
            putKeys.add(key);
        }

        @Override
        public Head head(String role, String key) {
            return new Head(source.length, "video/mp4");
        }
    }
}
