package com.lvfast.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OpenApiContractTest {

    @Test
    @SuppressWarnings("unchecked")
    void contractIsValidYamlAndContainsEveryLockedMvpPath() throws IOException {
        Path contract = Path.of("..", "docs", "api", "openapi.yaml");
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(contract)) {
            document = new Yaml().load(input);
        }

        assertThat(document.get("openapi")).isEqualTo("3.1.0");
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        assertThat(paths.keySet()).containsAll(Set.of(
                "/auth/register",
                "/auth/login",
                "/auth/refresh",
                "/auth/logout",
                "/auth/me",
                "/catalog/home",
                "/movies/{slug}",
                "/search",
                "/me/watchlist",
                "/me/watchlist/{movieId}",
                "/movies/{movieId}/playback",
                "/me/progress/{movieId}"));

        Map<String, Object> components = (Map<String, Object>) document.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> movieSummary = (Map<String, Object>) schemas.get("MovieSummary");
        Map<String, Object> movieProperties = (Map<String, Object>) movieSummary.get("properties");
        assertThat((Map<String, Object>) movieProperties.get("posterUrl"))
                .containsEntry("format", "uri-reference");
        assertThat((Map<String, Object>) movieProperties.get("backdropUrl"))
                .containsEntry("format", "uri-reference");
        Map<String, Object> playback = (Map<String, Object>) schemas.get("Playback");
        Map<String, Object> playbackProperties = (Map<String, Object>) playback.get("properties");
        assertThat((Map<String, Object>) playbackProperties.get("manifestUrl"))
                .containsEntry("format", "uri-reference");
    }
}
