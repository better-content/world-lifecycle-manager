import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/** Rebuilds the 16x16 runtime block textures from the high-resolution art masters. */
public final class DownsampleTextures {
    private static final int MINECRAFT_SIZE = 16;
    private static final List<String> TEXTURES = List.of(
            "world_condenser_hull",
            "world_condenser_interface_side",
            "world_condenser_interface_top");

    private DownsampleTextures() {}

    public static void main(String[] args) throws IOException {
        Path project = Path.of("").toAbsolutePath();
        Path sourceDir = project.resolve("art/source-textures");
        Path outputDir = project.resolve("src/main/resources/assets/prestige/textures/block");
        Path previewDir = project.resolve("build/texture-previews");
        Files.createDirectories(outputDir);
        Files.createDirectories(previewDir);

        for (String texture : TEXTURES) {
            Path source = sourceDir.resolve(texture + ".png");
            BufferedImage image = ImageIO.read(source.toFile());
            if (image == null) {
                throw new IOException("Unsupported source texture: " + source);
            }
            if (image.getWidth() != image.getHeight() || image.getWidth() < MINECRAFT_SIZE) {
                throw new IOException("Source must be a square at least 16x16: " + source);
            }

            BufferedImage downsampled = finishMinecraftTexture(
                    progressiveDownsample(image, MINECRAFT_SIZE));
            Path output = outputDir.resolve(texture + ".png");
            if (!ImageIO.write(downsampled, "png", output.toFile())) {
                throw new IOException("No PNG writer available for " + output);
            }
            ImageIO.write(nearestNeighborPreview(downsampled), "png",
                    previewDir.resolve(texture + ".png").toFile());
            System.out.printf("%s: %dx%d -> %dx%d%n", texture,
                    image.getWidth(), image.getHeight(), downsampled.getWidth(), downsampled.getHeight());
        }
    }

    private static BufferedImage progressiveDownsample(BufferedImage source, int targetSize) {
        BufferedImage current = source;
        while (current.getWidth() > targetSize || current.getHeight() > targetSize) {
            int nextWidth = Math.max(targetSize, current.getWidth() / 2);
            int nextHeight = Math.max(targetSize, current.getHeight() / 2);
            if (nextWidth == current.getWidth() && nextHeight == current.getHeight()) break;

            BufferedImage next = new BufferedImage(nextWidth, nextHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = next.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(current, 0, 0, nextWidth, nextHeight, null);
            graphics.dispose();
            current = next;
        }
        return current;
    }

    private static BufferedImage finishMinecraftTexture(BufferedImage source) {
        BufferedImage output = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int original = source.getRGB(x, y);
                int red = (original >>> 16) & 0xff;
                int green = (original >>> 8) & 0xff;
                int blue = original & 0xff;
                int blurRed = 0;
                int blurGreen = 0;
                int blurBlue = 0;
                int samples = 0;
                for (int offsetY = -1; offsetY <= 1; offsetY++) {
                    for (int offsetX = -1; offsetX <= 1; offsetX++) {
                        int sample = source.getRGB(
                                Math.floorMod(x + offsetX, source.getWidth()),
                                Math.floorMod(y + offsetY, source.getHeight()));
                        blurRed += (sample >>> 16) & 0xff;
                        blurGreen += (sample >>> 8) & 0xff;
                        blurBlue += sample & 0xff;
                        samples++;
                    }
                }

                red = recoverChannel(red, blurRed / samples);
                green = recoverChannel(green, blurGreen / samples);
                blue = recoverChannel(blue, blurBlue / samples);
                double luminance = red * 0.2126 + green * 0.7152 + blue * 0.0722;
                red = quantize(clamp((int) Math.round(luminance + (red - luminance) * 1.18)));
                green = quantize(clamp((int) Math.round(luminance + (green - luminance) * 1.18)));
                blue = quantize(clamp((int) Math.round(luminance + (blue - luminance) * 1.18)));
                output.setRGB(x, y, 0xff000000 | red << 16 | green << 8 | blue);
            }
        }
        return output;
    }

    private static int recoverChannel(int value, int blurred) {
        double sharpened = value + (value - blurred) * 0.72;
        return clamp((int) Math.round((sharpened - 54.0) * 1.16 + 60.0));
    }

    private static int quantize(int value) {
        return clamp((int) Math.round(value / 8.0) * 8);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static BufferedImage nearestNeighborPreview(BufferedImage source) {
        int previewSize = 256;
        BufferedImage preview = new BufferedImage(previewSize, previewSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = preview.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(source, 0, 0, previewSize, previewSize, null);
        graphics.dispose();
        return preview;
    }
}
