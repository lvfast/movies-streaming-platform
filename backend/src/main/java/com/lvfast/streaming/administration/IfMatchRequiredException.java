package com.lvfast.streaming.administration;

public class IfMatchRequiredException extends RuntimeException {
    public IfMatchRequiredException() {
        super("The If-Match header with the current movie revision is required");
    }
}
