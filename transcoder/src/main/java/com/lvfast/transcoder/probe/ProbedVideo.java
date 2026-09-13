package com.lvfast.transcoder.probe;

/** Validated result of probing a source video. */
public record ProbedVideo(
        Dimensions dimensions,
        double durationSeconds,
        String videoCodec,
        String audioCodec,
        double fps) {
}
