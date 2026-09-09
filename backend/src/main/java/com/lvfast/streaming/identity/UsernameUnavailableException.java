package com.lvfast.streaming.identity;

public class UsernameUnavailableException extends RuntimeException {
    public UsernameUnavailableException() {
        super("username is unavailable");
    }
}
