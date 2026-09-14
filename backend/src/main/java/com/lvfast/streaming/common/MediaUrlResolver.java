package com.lvfast.streaming.common;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MediaUrlResolver {
    private static final String LOCAL_MEDIA_PREFIX = "/media";
    private final String mediaBaseUrl;

    public MediaUrlResolver(@Value("${app.media-base-url:}") String mediaBaseUrl) {
        this.mediaBaseUrl = stripTrailingSlash(mediaBaseUrl == null ? "" : mediaBaseUrl.trim());
    }

    public String resolve(String reference) {
        if (reference == null || reference.isBlank()
                || URI.create(reference).isAbsolute() || reference.startsWith("//")) {
            return reference;
        }
        if (mediaBaseUrl.isEmpty()) {
            return reference.startsWith("/") ? reference : "/" + reference;
        }
        String suffix = reference.startsWith(LOCAL_MEDIA_PREFIX + "/")
                ? reference.substring(LOCAL_MEDIA_PREFIX.length())
                : "/" + reference.replaceFirst("^/+", "");
        return mediaBaseUrl + suffix;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
