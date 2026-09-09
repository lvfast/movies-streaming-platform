package com.lvfast.streaming.identity;

import java.time.Duration;
import java.time.Instant;

public interface AccessTokenIssuer {
    String issue(UserAccount user, Instant issuedAt, Duration ttl);
}
