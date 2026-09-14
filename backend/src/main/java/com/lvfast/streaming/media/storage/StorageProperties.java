package com.lvfast.streaming.media.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Named storage roles. Each role selects a bucket, an S3-compatible endpoint, a signing region and
 * a credential pair. The {@code source} role is used for internal server access and the
 * {@code source-browser} role for presigning browser part uploads, so browsers never receive
 * storage credentials.
 */
@ConfigurationProperties(prefix = "app.media.storage")
public record StorageProperties(Map<String, Role> roles) {

    public StorageProperties {
        roles = roles == null ? Map.of() : Map.copyOf(roles);
    }

    public Role role(String name) {
        Role role = roles.get(name);
        if (role == null) {
            throw new IllegalStateException("No storage role is configured for '" + name + "'");
        }
        return role;
    }

    public record Role(String bucket, String endpoint, String region, String accessKey, String secretKey) {
        public boolean hasEndpoint() {
            return endpoint != null && !endpoint.isBlank();
        }
    }
}
