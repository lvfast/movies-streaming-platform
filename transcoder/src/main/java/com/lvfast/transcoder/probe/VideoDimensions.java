package com.lvfast.transcoder.probe;

/**
 * Selects output geometry within the 1920x1080 ceiling while preserving display aspect ratio and
 * never upscaling. Width and height are rounded down to even values so H.264 yuv420p stays valid.
 */
public final class VideoDimensions {

    private static final int MAX_WIDTH = 1920;
    private static final int MAX_HEIGHT = 1080;

    private VideoDimensions() {
    }

    public static Dimensions fit(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Source dimensions must be positive");
        }
        double scale = Math.min(1.0, Math.min((double) MAX_WIDTH / width, (double) MAX_HEIGHT / height));
        int targetWidth = even((int) Math.floor(width * scale));
        int targetHeight = even((int) Math.floor(height * scale));
        return new Dimensions(Math.max(2, targetWidth), Math.max(2, targetHeight));
    }

    private static int even(int value) {
        return value - (value % 2);
    }
}
