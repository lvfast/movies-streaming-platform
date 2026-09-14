package com.lvfast.transcoder.artwork;

import com.lvfast.transcoder.common.ProcessingFailure;
import com.lvfast.transcoder.config.TranscoderProperties;
import com.lvfast.transcoder.process.BoundedProcessRunner;
import com.lvfast.transcoder.probe.Dimensions;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Decodes JPEG/PNG artwork, rejects corrupt or oversized input, center-crops to the required aspect
 * ratio and encodes a metadata-stripped JPEG at the exact required dimensions.
 */
@Component
public class ArtworkProcessor {

    private static final long MAX_PIXELS = 24_000_000;

    private final String ffmpegPath;
    private final String ffprobePath;
    private final BoundedProcessRunner runner;
    private final ObjectMapper json = new ObjectMapper();

    public ArtworkProcessor(TranscoderProperties properties, BoundedProcessRunner runner) {
        this.ffmpegPath = properties.ffmpegPath();
        this.ffprobePath = properties.ffprobePath();
        this.runner = runner;
    }

    public Dimensions process(Path source, Path outputDir, Dimensions target) {
        Dimensions sourceDimensions = probe(source);
        if ((long) sourceDimensions.width() * sourceDimensions.height() > MAX_PIXELS) {
            throw new ProcessingFailure("SOURCE_UNSUPPORTED", "Artwork exceeds the 24 megapixel limit");
        }
        BoundedProcessRunner.Result result = runner.run(List.of(
                ffmpegPath, "-hide_banner", "-y",
                "-i", source.toString(),
                "-vf", "scale=" + target.width() + ":" + target.height()
                        + ":force_original_aspect_ratio=increase,crop=" + target.width() + ":" + target.height(),
                "-frames:v", "1", "-q:v", "2", "-map_metadata", "-1",
                outputDir.resolve("image.jpg").toString()), outputDir);
        if (!result.succeeded()) {
            throw new ProcessingFailure("SOURCE_INVALID", "Artwork could not be decoded or normalized");
        }
        return target;
    }

    private Dimensions probe(Path source) {
        BoundedProcessRunner.Result result = runner.run(List.of(
                ffprobePath, "-v", "error", "-print_format", "json",
                "-show_streams", source.toString()), source.getParent());
        if (!result.succeeded()) {
            throw new ProcessingFailure("SOURCE_INVALID", "Artwork is not a decodable JPEG/PNG image");
        }
        try {
            JsonNode root = json.readTree(result.stdout());
            for (JsonNode stream : root.path("streams")) {
                if ("video".equals(stream.path("codec_type").asText())) {
                    String codec = stream.path("codec_name").asText("");
                    if (!"mjpeg".equals(codec) && !"png".equals(codec) && !"webp".equals(codec)) {
                        throw new ProcessingFailure("SOURCE_UNSUPPORTED",
                                "Artwork must be a JPEG or PNG image");
                    }
                    int width = stream.path("width").asInt(0);
                    int height = stream.path("height").asInt(0);
                    if (width < 1 || height < 1) {
                        throw new ProcessingFailure("SOURCE_INVALID",
                                "Artwork is not a decodable JPEG/PNG image");
                    }
                    return new Dimensions(width, height);
                }
            }
            throw new ProcessingFailure("SOURCE_INVALID", "Artwork has no image stream");
        } catch (ProcessingFailure expected) {
            throw expected;
        } catch (Exception unreadable) {
            throw new ProcessingFailure("SOURCE_INVALID", "Artwork could not be probed");
        }
    }
}
