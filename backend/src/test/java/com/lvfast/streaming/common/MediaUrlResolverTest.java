package com.lvfast.streaming.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MediaUrlResolverTest {

    @Test
    void emptyBasePreservesSameOriginMediaReferences() {
        MediaUrlResolver resolver = new MediaUrlResolver("");

        assertThat(resolver.resolve("/media/artwork/poster.svg"))
                .isEqualTo("/media/artwork/poster.svg");
        assertThat(resolver.resolve(null)).isNull();
    }

    @Test
    void emptyBaseRootsPromotedArtworkKeys() {
        MediaUrlResolver resolver = new MediaUrlResolver("");

        assertThat(resolver.resolve("public-artwork/5e1c0b4a-0000-0000-0000-000000000001/image.jpg"))
                .isEqualTo("/public-artwork/5e1c0b4a-0000-0000-0000-000000000001/image.jpg");
    }

    @Test
    void configuredBaseReplacesTheStoredMediaPrefix() {
        MediaUrlResolver resolver = new MediaUrlResolver("https://media.example.test/library/");

        assertThat(resolver.resolve("/media/artwork/poster.svg"))
                .isEqualTo("https://media.example.test/library/artwork/poster.svg");
        assertThat(resolver.resolve("/media/fixtures/movie/index.m3u8"))
                .isEqualTo("https://media.example.test/library/fixtures/movie/index.m3u8");
    }

    @Test
    void absoluteCatalogUrlsRemainUnchanged() {
        MediaUrlResolver resolver = new MediaUrlResolver("https://media.example.test/library");

        assertThat(resolver.resolve("https://archive.example.test/poster.svg"))
                .isEqualTo("https://archive.example.test/poster.svg");
    }
}
