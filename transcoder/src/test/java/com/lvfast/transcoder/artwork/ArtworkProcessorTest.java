package com.lvfast.transcoder.artwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lvfast.transcoder.common.ProcessingFailure;
import com.lvfast.transcoder.config.TranscoderProperties;
import com.lvfast.transcoder.process.BoundedProcessRunner;
import com.lvfast.transcoder.probe.Dimensions;
import com.lvfast.transcoder.support.TranscoderTestSupport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtworkProcessorTest {

    @TempDir
    static Path workspace;

    private static final TranscoderProperties PROPERTIES = TranscoderTestSupport.properties();
    private static ArtworkProcessor processor;

    @BeforeAll
    static void setup() {
        org.junit.jupiter.api.Assumptions.assumeTrue(TranscoderTestSupport.ffmpegAvailable(),
                "ffmpeg/ffprobe not available on PATH");
        processor = new ArtworkProcessor(PROPERTIES, new BoundedProcessRunner(PROPERTIES));
    }

    @Test
    void centerCropsKeepingOnlyTheMiddleBand() {
        // 900x3600 with three 1200px horizontal bands (red/green/blue). A backdrop 1600x900
        // center-crop keeps only the vertical middle of the source (the green band); a stretch
        // would keep red at the top and blue at the bottom.
        Path source = generateThreeBands();
        Path output = newOutputDir("crop");

        processor.process(source, output, new Dimensions(1600, 900));

        BufferedImage image = read(output.resolve("image.jpg"));
        assertThat(image.getWidth()).isEqualTo(1600);
        assertThat(image.getHeight()).isEqualTo(900);
        assertGreen(image.getRGB(800, 0));
        assertGreen(image.getRGB(800, 450));
        assertGreen(image.getRGB(800, 899));
    }

    @Test
    void normalizesAPosterFromASquareSource() {
        // 1500x1500 is 1:1; the required poster is 2:3, so the pipeline must scale-to-cover then
        // center-crop rather than stretch.
        Path source = generateImage(1500, 1500, "poster-src.jpg");
        Path output = newOutputDir("poster");

        Dimensions result = processor.process(source, output, new Dimensions(600, 900));

        assertThat(result).isEqualTo(new Dimensions(600, 900));
        assertThat(probe(output.resolve("image.jpg"))).isEqualTo(new Dimensions(600, 900));
        assertThat(codecOf(output.resolve("image.jpg"))).isEqualTo("mjpeg");
    }

    @Test
    void normalizesPngArtworkToJpeg() {
        Path source = generatePng(1200, 1800, "poster-src.png");
        Path output = newOutputDir("png");

        processor.process(source, output, new Dimensions(600, 900));

        assertThat(probe(output.resolve("image.jpg"))).isEqualTo(new Dimensions(600, 900));
        assertThat(codecOf(output.resolve("image.jpg"))).isEqualTo("mjpeg");
    }

    @Test
    void stripsSourceExifMetadata() {
        Path source = generateImage(200, 300, "tagged.jpg");
        injectExifOrientation(source, 1);
        assertThat(hasExif(source)).as("source must carry EXIF before stripping").isTrue();

        processor.process(source, newOutputDir("stripped"), new Dimensions(600, 900));

        assertThat(hasExif(newOutputDir("stripped").resolve("image.jpg")))
                .as("output must not carry the source EXIF").isFalse();
    }

    @Test
    void normalizesExifOrientation() {
        // 200x300 portrait: red top half, black bottom half. EXIF orientation 6 rotates it 90
        // degrees, so the effective image is 300x200 landscape with black on the left and red on
        // the right. Ignoring the orientation would leave a red-top/black-bottom portrait.
        Path source = generateVerticalSplit();
        injectExifOrientation(source, 6);
        Path output = newOutputDir("oriented");

        processor.process(source, output, new Dimensions(1600, 900));

        BufferedImage image = read(output.resolve("image.jpg"));
        assertThat(image.getWidth()).isEqualTo(1600);
        assertThat(image.getHeight()).isEqualTo(900);
        assertBlack(image.getRGB(40, 450));
        assertRed(image.getRGB(1560, 450));
    }

    @Test
    void rejectsCorruptInput() throws Exception {
        Path corrupt = workspace.resolve("corrupt.jpg");
        Files.writeString(corrupt, "not an image", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> processor.process(corrupt, newOutputDir("corrupt"), new Dimensions(600, 900)))
                .isInstanceOf(ProcessingFailure.class)
                .hasMessageContaining("decodable");
    }

    @Test
    void rejectsOversizedInput() {
        // 6000x6000 = 36 megapixels, above the 24 megapixel ceiling.
        Path oversized = generateImage(6000, 6000, "oversized.jpg");

        assertThatThrownBy(() -> processor.process(oversized, newOutputDir("oversized"), new Dimensions(600, 900)))
                .isInstanceOf(ProcessingFailure.class)
                .hasMessageContaining("megapixel");
    }

    private Path generateImage(int width, int height, String name) {
        Path image = workspace.resolve(name);
        TranscoderTestSupport.run(List.of(
                "ffmpeg", "-hide_banner", "-y",
                "-f", "lavfi", "-i", "color=c=steelblue:s=" + width + "x" + height,
                "-frames:v", "1", image.toString()), workspace);
        return image;
    }

    private Path generatePng(int width, int height, String name) {
        Path image = workspace.resolve(name);
        TranscoderTestSupport.run(List.of(
                "ffmpeg", "-hide_banner", "-y",
                "-f", "lavfi", "-i", "color=c=steelblue:s=" + width + "x" + height,
                "-frames:v", "1", image.toString()), workspace);
        return image;
    }

    private Path generateThreeBands() {
        Path image = workspace.resolve("bands.jpg");
        TranscoderTestSupport.run(List.of(
                "ffmpeg", "-hide_banner", "-y",
                "-f", "lavfi", "-i", "color=0xFF0000:s=900x1200",
                "-f", "lavfi", "-i", "color=0x00FF00:s=900x1200",
                "-f", "lavfi", "-i", "color=0x0000FF:s=900x1200",
                "-filter_complex", "[0][1][2]vstack=inputs=3",
                "-frames:v", "1", image.toString()), workspace);
        return image;
    }

    private Path generateVerticalSplit() {
        Path image = workspace.resolve("split.jpg");
        TranscoderTestSupport.run(List.of(
                "ffmpeg", "-hide_banner", "-y",
                "-f", "lavfi", "-i", "color=0xFF0000:s=200x150",
                "-f", "lavfi", "-i", "color=0x000000:s=200x150",
                "-filter_complex", "[0][1]vstack=inputs=2",
                "-frames:v", "1", image.toString()), workspace);
        return image;
    }

    private Path newOutputDir(String name) {
        Path dir = workspace.resolve(name);
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Dimensions probe(Path image) {
        String output = TranscoderTestSupport.capture(List.of(
                "ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=width,height", "-of", "csv=s=x:p=0", image.toString()),
                workspace);
        String[] parts = output.trim().split("x");
        return new Dimensions(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    private String codecOf(Path image) {
        return TranscoderTestSupport.capture(List.of(
                "ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=codec_name", "-of", "csv=p=0", image.toString()),
                workspace).trim();
    }

    private BufferedImage read(Path image) {
        try {
            return ImageIO.read(image.toFile());
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read output image", failure);
        }
    }

    private void assertGreen(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        assertThat(g).as("green channel").isGreaterThan(150);
        assertThat(r).as("red channel").isLessThan(80);
        assertThat(b).as("blue channel").isLessThan(80);
    }

    private void assertRed(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        assertThat(r).as("red channel").isGreaterThan(150);
        assertThat(g).as("green channel").isLessThan(80);
        assertThat(b).as("blue channel").isLessThan(80);
    }

    private void assertBlack(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        assertThat(r).as("red channel").isLessThan(50);
        assertThat(g).as("green channel").isLessThan(50);
        assertThat(b).as("blue channel").isLessThan(50);
    }

    /** Injects a minimal EXIF APP1 segment (with the given orientation) just after the SOI marker. */
    private void injectExifOrientation(Path jpeg, int orientation) {
        try {
            byte[] base = Files.readAllBytes(jpeg);
            byte[] exif = new byte[] {
                'E', 'x', 'i', 'f', 0, 0,
                'I', 'I', 0x2A, 0, 0x08, 0, 0, 0,
                0x01, 0,
                0x12, 0x01, 0x03, 0, 0x01, 0, 0, 0, (byte) orientation, 0, 0, 0,
                0, 0, 0, 0
            };
            byte[] app1 = new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (exif.length >> 8),
                    (byte) (exif.length & 0xFF)};
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(base, 0, 2);
            out.write(app1);
            out.write(exif);
            out.write(base, 2, base.length - 2);
            Files.write(jpeg, out.toByteArray());
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to inject EXIF orientation", failure);
        }
    }

    private boolean hasExif(Path image) {
        try {
            String content = new String(Files.readAllBytes(image), StandardCharsets.ISO_8859_1);
            return content.contains("Exif");
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read image bytes", failure);
        }
    }
}
