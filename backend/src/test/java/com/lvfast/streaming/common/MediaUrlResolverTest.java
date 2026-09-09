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
