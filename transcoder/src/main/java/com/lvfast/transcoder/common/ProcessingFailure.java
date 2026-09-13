package com.lvfast.transcoder.common;

/**
 * A job-level failure with a stable machine-readable code. The worker publishes the code and summary
 * as a {@code media.failed.v1} result; the backend decides whether the failure is terminal.
 */
public class ProcessingFailure extends RuntimeException {

    private final String code;

    public ProcessingFailure(String code, String summary) {
        super(summary);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
