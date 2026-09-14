package com.lvfast.transcoder.encode;

import static org.assertj.core.api.Assertions.assertThat;

import com.lvfast.transcoder.config.TranscoderProperties;
import com.lvfast.transcoder.process.BoundedProcessRunner;
import com.lvfast.transcoder.probe.Dimensions;
import com.lvfast.transcoder.support.TranscoderTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HlsEncoderTest {

    @TempDir
    static Path workspace;

    private static final TranscoderProperties PROPERTIES = TranscoderTestSupport.properties();
    private static HlsEncoder encoder;

    @BeforeAll
    static void setup() {
        org.junit.jupiter.api.Assumptions.assumeTrue(TranscoderTestSupport.ffmpegAvailable(),
                "ffmpeg/ffprobe not available on PATH");
        encoder = new HlsEncoder(PROPERTIES, new BoundedProcessRunner(PROPERTIES));
    }

    @Test
    void producesDecodableH264AacVodHls() {
        Path source = generateVideo();
        Path output = workspace.resolve("hls");
        try {
            Files.createDirectories(output);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }

        encoder.encode(source, output, new Dimensions(640, 360));

        Path playlist = output.resolve("index.m3u8");
        assertThat(playlist).exists();
        try (var stream = Files.list(output)) {
            assertThat(stream.filter(p -> p.getFileName().toString().endsWith(".ts")).count())
                    .isGreaterThanOrEqualTo(1);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }

        // VOD playlist: ended and references the segments it produced.
        String content;
        try {
            content = Files.readString(playlist);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        assertThat(content).contains("#EXT-X-ENDLIST").contains("segment_00000.ts");

        // Decode the entire playlist back (every segment) with ffmpeg; a non-zero exit means some
        // frame/segment failed to decode.
        TranscoderTestSupport.run(List.of(
                "ffmpeg", "-hide_banner", "-v", "error",
                "-i", playlist.toString(), "-f", "null", "-"), output);

        // Assert the actual codecs are H.264 video + AAC audio.
        String codecs = TranscoderTestSupport.capture(List.of(
                "ffprobe", "-v", "error", "-show_entries", "stream=codec_name",
                "-of", "csv=p=0", playlist.toString()), output);
        assertThat(codecs).contains("h264").contains("aac");
    }

    private Path generateVideo() {
        Path video = workspace.resolve("source.mp4");
        TranscoderTestSupport.run(List.of(
                "ffmpeg", "-hide_banner", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=2:size=1280x720:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", video.toString()), workspace);
        return video;
    }
}
