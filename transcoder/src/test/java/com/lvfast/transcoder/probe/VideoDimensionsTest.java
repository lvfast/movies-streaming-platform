package com.lvfast.transcoder.probe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VideoDimensionsTest {

    @Test
    void fitsWithinThe1080pCeilingPreservingAspectRatio() {
        assertThat(VideoDimensions.fit(3840, 2160)).isEqualTo(new Dimensions(1920, 1080));
        assertThat(VideoDimensions.fit(2560, 1440)).isEqualTo(new Dimensions(1920, 1080));
    }

    @Test
    void neverUpscalesSmallSources() {
        assertThat(VideoDimensions.fit(640, 360)).isEqualTo(new Dimensions(640, 360));
        assertThat(VideoDimensions.fit(1920, 1080)).isEqualTo(new Dimensions(1920, 1080));
        assertThat(VideoDimensions.fit(1280, 720)).isEqualTo(new Dimensions(1280, 720));
    }
}
