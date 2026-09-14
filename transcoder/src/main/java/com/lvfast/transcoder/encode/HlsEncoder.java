package com.lvfast.transcoder.encode;

import com.lvfast.transcoder.common.ProcessingFailure;
import com.lvfast.transcoder.config.TranscoderProperties;
import com.lvfast.transcoder.process.BoundedProcessRunner;
import com.lvfast.transcoder.probe.Dimensions;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/** Encodes a validated source into a single H.264/AAC VOD HLS rendition using libx264. */
@Component
public class HlsEncoder {

    private final String ffmpegPath;
    private final BoundedProcessRunner runner;

    public HlsEncoder(TranscoderProperties properties, BoundedProcessRunner runner) {
        this.ffmpegPath = properties.ffmpegPath();
        this.runner = runner;
    }

    public void encode(Path source, Path outputDir, Dimensions target) {
        BoundedProcessRunner.Result result = runner.run(List.of(
                ffmpegPath, "-hide_banner", "-y",
                "-i", source.toString(),
                "-vf", "scale=" + target.width() + ":" + target.height(),
                "-r", "30",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "128k", "-ac", "2",
                "-f", "hls", "-hls_time", "6", "-hls_playlist_type", "vod",
                "-hls_segment_filename", outputDir.resolve("segment_%05d.ts").toString(),
                outputDir.resolve("index.m3u8").toString()), outputDir);
        if (!result.succeeded()) {
            throw new ProcessingFailure("INTERNAL_ERROR", "HLS encoding failed");
        }
    }
}
