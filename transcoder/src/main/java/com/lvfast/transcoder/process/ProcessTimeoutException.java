package com.lvfast.transcoder.process;

/** An FFmpeg/ffprobe invocation exceeded its configured time bound. */
public class ProcessTimeoutException extends RuntimeException {

    public ProcessTimeoutException(String message) {
        super(message);
    }
}
