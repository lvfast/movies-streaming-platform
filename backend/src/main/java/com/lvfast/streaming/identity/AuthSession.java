package com.lvfast.streaming.identity;

public record AuthSession(
        String accessToken,
        String refreshToken,
        long expiresIn,
        UserAccount user) {
}
