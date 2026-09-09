package com.lvfast.streaming.identity;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("invalid username or password");
    }
}
