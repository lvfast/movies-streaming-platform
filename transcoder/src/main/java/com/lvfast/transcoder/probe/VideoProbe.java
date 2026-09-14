package com.lvfast.transcoder.probe;

import com.lvfast.transcoder.common.ProcessingFailure;
import com.lvfast.transcoder.config.TranscoderProperties;
import com.lvfast.transcoder.process.BoundedProcessRunner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Probes actual video bytes with ffprobe and validates container, track count, HDR, decoded bounds
 * and duration before encoding. The source path is a local file, never a client-supplied URL.
 */
@Component
public class VideoProbe {

    private static final double MAX_DURATION_SECONDS = 6 * 60 * 60;
    private static final int MAX_WIDTH = 3840;
    private static final int MAX_HEIGHT = 2160;
    private static final double MAX_FPS = 60.0;
    private static final List<String> HDR_TRANSFERS = List.of("smpte2084", "arib-std-b67");

    private final String ffprobePath;
    private final BoundedProcessRunner runner;
    private final ObjectMapper json = new ObjectMapper();

    public VideoProbe(TranscoderProperties properties, BoundedProcessRunner runner) {
        this.ffprobePath = properties.ffprobePath();
        this.runner = runner;
    }

    public ProbedVideo probe(Path source) {
        BoundedProcessRunner.Result result = runner.run(
                List.of(ffprobePath, "-v", "error", "-print_format", "json",
                        "-show_format", "-show_streams", source.toString()),
                source.getParent());
        if (!result.succeeded()) {
            throw new ProcessingFailure("SOURCE_INVALID", "Source media is malformed or unreadable");
        }
        JsonNode root;
        try {
            root = json.readTree(result.stdout());
        } catch (Exception unreadable) {
            throw new ProcessingFailure("SOURCE_INVALID", "ffprobe output could not be parsed");
        }

        JsonNode streams = root.path("streams");
        List<JsonNode> videoStreams = new ArrayList<>();
        List<JsonNode> audioStreams = new ArrayList<>();
        for (JsonNode stream : streams) {
            if ("video".equals(stream.path("codec_type").asText())) {
                videoStreams.add(stream);
            } else if ("audio".equals(stream.path("codec_type").asText())) {
                audioStreams.add(stream);
            }
        }

        if (videoStreams.size() != 1 || audioStreams.isEmpty()) {
            throw new ProcessingFailure("SOURCE_INVALID",
                    "Source must contain exactly one video track and at least one audio track");
        }
        JsonNode video = videoStreams.getFirst();

        String container = root.path("format").path("format_name").asText("");
        if (!isAcceptedContainer(container)) {
            throw new ProcessingFailure("SOURCE_UNSUPPORTED",
                    "Unsupported container '" + container + "'; expected MP4, MOV or Matroska");
        }

        double duration = durationSeconds(root);
        if (duration <= 0 || duration > MAX_DURATION_SECONDS) {
            throw new ProcessingFailure("SOURCE_UNSUPPORTED",
                    "Source duration must be positive and at most six hours");
        }

        String colorTransfer = video.path("color_transfer").asText(null);
        String colorPrimaries = video.path("color_primaries").asText(null);
        if ((colorTransfer != null && HDR_TRANSFERS.contains(colorTransfer)) || "bt2020".equals(colorPrimaries)) {
            throw new ProcessingFailure("SOURCE_UNSUPPORTED", "HDR source media is not supported");
        }

        int width = video.path("width").asInt(0);
        int height = video.path("height").asInt(0);
        double fps = frameRate(video);
        if (width > MAX_WIDTH || height > MAX_HEIGHT || fps > MAX_FPS) {
            throw new ProcessingFailure("SOURCE_UNSUPPORTED",
                    "Decoded bounds exceed the 4K/60fps input ceiling");
        }
        if (width < 1 || height < 1) {
            throw new ProcessingFailure("SOURCE_INVALID", "Video stream has no usable dimensions");
        }

        JsonNode audio = audioStreams.getFirst();
        return new ProbedVideo(
                new Dimensions(width, height),
                duration,
                video.path("codec_name").asText(""),
                audio.path("codec_name").asText(""),
                fps);
    }

    private boolean isAcceptedContainer(String container) {
        String lower = container.toLowerCase();
        return lower.contains("mp4") || lower.contains("mov") || lower.contains("matroska");
    }

    private double durationSeconds(JsonNode root) {
        String value = root.path("format").path("duration").asText(null);
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private double frameRate(JsonNode video) {
        String value = video.path("avg_frame_rate").asText("0/1");
        String[] parts = value.split("/");
        try {
            double numerator = Double.parseDouble(parts[0]);
            double denominator = parts.length > 1 ? Double.parseDouble(parts[1]) : 1.0;
            return denominator == 0 ? 0 : numerator / denominator;
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }
}
