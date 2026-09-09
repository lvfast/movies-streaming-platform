package com.lvfast.streaming.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class CatalogManifestTest {

    @Test
    void defaultManifestUsesSameOriginMediaAndContainsExactlyThreeLocalHlsFixtures() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/catalog/catalog-v3.json")) {
            assertThat(input).isNotNull();
            JsonNode root = new ObjectMapper().readTree(input);

            assertThat(root.path("version").asText()).isEqualTo("catalog-v3");
            assertThat(root.path("movies")).hasSize(20);
            assertThat(StreamSupport.stream(root.path("movies").spliterator(), false)
                    .map(movie -> movie.path("hlsManifestUrl").asText())
                    .toList())
                    .filteredOn(url -> url.startsWith("/media/fixtures/"))
                    .doesNotHaveDuplicates()
                    .hasSize(3);

            for (JsonNode movie : root.path("movies")) {
                assertLocalArtworkUrl(movie.path("posterUrl").asText());
                assertLocalArtworkUrl(movie.path("backdropUrl").asText());
                if (!movie.path("hlsManifestUrl").isNull()) {
                    assertThat(movie.path("runtimeSeconds").asInt()).isEqualTo(2);
                    Path playlist = Path.of("..", movie.path("hlsManifestUrl").asText().substring(1));
                    assertThat(Files.readString(playlist)).contains("#EXTINF:2.000,");
                }
            }
            assertThat(Files.isRegularFile(Path.of("..", "media", "artwork", "poster-placeholder.svg"))).isTrue();
            assertThat(Files.isRegularFile(Path.of("..", "media", "artwork", "backdrop-placeholder.svg"))).isTrue();
        }
    }

    private void assertLocalArtworkUrl(String url) {
        URI uri = URI.create(url);
        assertThat(uri.isAbsolute()).isFalse();
        assertThat(uri.getPath()).startsWith("/media/artwork/");
    }
}
