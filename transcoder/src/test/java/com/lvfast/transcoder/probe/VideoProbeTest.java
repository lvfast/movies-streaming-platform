package com.lvfast.transcoder.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lvfast.transcoder.common.ProcessingFailure;
import com.lvfast.transcoder.config.TranscoderProperties;
import com.lvfast.transcoder.process.BoundedProcessRunner;
import com.lvfast.transcoder.support.TranscoderTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoProbeTest {

    @TempDir
    static Path workspace;

    private static final TranscoderProperties PROPERTIES = TranscoderTestSupport.properties();
    private static VideoProbe probe;

    @BeforeAll
    static void setup() {
        org.junit.jupiter.api.Assumptions.assumeTrue(TranscoderTestSupport.ffmpegAvailable(),
                "ffmpeg/ffprobe not available on PATH");
        probe = new VideoProbe(PROPERTIES, new BoundedProcessRunner(PROPERTIES));
    }

    @Test
    void probesActualTracksDurationAndDimensions() {
        Path video = generateVideo(1280, 720, 2);

        ProbedVideo probed = probe.probe(video);

        assertThat(probed.dimensions()).isEqualTo(new Dimensions(1280, 720));
        assertThat(probed.durationSeconds()).isGreaterThan(1.0);
        assertThat(probed.videoCodec()).isEqualTo("h264");
        assertThat(probed.audioCodec()).isEqualTo("aac");
    }

    @Test
    void rejectsMalformedMedia() throws Exception {
        Path garbage = workspace.resolve("garbage.mp4");
        Files.writeString(garbage, "not a video", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> probe.probe(garbage))
                .isInstanceOf(ProcessingFailure.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void rejectsVideoWithoutAnAudioTrack() {
        Path silent = workspace.resolve("silent.mp4");
        TranscoderTestSupport.run(List.of(
                "ffmpeg", "-hide_banner", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=24",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-an", silent.toString()), workspace);

        assertThatThrownBy(() -> probe.probe(silent))
                .isInstanceOf(ProcessingFailure.class)
                .hasMessageContaining("audio");
    }

    @Test
    void rejectsMediaWithoutAVideoTrack() {
        Path audioOnly = workspace.resolve("audio-only.m4a");
        TranscoderTestSupport.run(List.of(
                "ffmpeg", "-hide_banner", "-y",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-c:a", "aac", audioOnly.toString()), workspace);

        assertThatThrownBy(() -> probe.probe(audioOnly))
                .isInstanceOf(ProcessingFailure.class)
                .hasMessageContaining("video");
    }

    private Path generateVideo(int width, int height, int seconds) {
        Path video = workspace.resolve("source-" + width + "x" + height + ".mp4");
        TranscoderTestSupport.run(List.of(
                "ffmpeg", "-hide_banner", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=" + seconds + ":size=" + width + "x" + height + ":rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=" + seconds,
                "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", video.toString()), workspace);
        return video;
    }
}
