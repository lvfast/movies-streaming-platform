package com.lvfast.streaming.administration;

public class StaleRevisionException extends RuntimeException {
    public StaleRevisionException(long expected, long actual) {
        super("Movie revision is stale: expected " + expected + " but current is " + actual);
    }
}
