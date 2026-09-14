package com.lvfast.streaming.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

public interface AccessTokenIssuer {
    String issue(UserAccount user, Set<String> roles, Instant issuedAt, Duration ttl);
}
