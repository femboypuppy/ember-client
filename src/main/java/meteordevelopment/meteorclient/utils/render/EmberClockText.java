package meteordevelopment.meteorclient.utils.render;

import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;

import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.Texture;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.utils.render.color.Color;

import org.lwjgl.BufferUtils;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Objects;

/** Rasterizes the bold clock at its actual pixel size, cached until its text or size changes. */
public final class EmberClockText {
    private static final FontRenderContext FONT_CONTEXT = new FontRenderContext(null, true, true);
    private static final int PAD = 2;
    private static FontFace activeFace;
    private static Font font;
    private static Texture texture;
    private static String cachedText;
    private static int cachedHeight;
    private static int width, height;

    private EmberClockText() {}

    public static void draw(
            String text, double centerX, double top, double pixelHeight, Color color) {
        if (text.isEmpty() || color.a == 0) return;
        FontFace face = EmberMenuFont.boldFace();
        int pixels = Math.max(1, (int) Math.ceil(pixelHeight));
        boolean changedFace = face != activeFace;
        if (changedFace) {
            try {
                font = load(face);
            } catch (Exception e) {
                try {
                    font = load(EmberMenuFont.boldFallback());
                } catch (Exception fallback) {
                    throw new IllegalStateException(
                            "Unable to load the bundled bold clock font", fallback);
                }
            }
            activeFace = face;
        }
        if (texture == null
                || changedFace
                || pixels != cachedHeight
                || !Objects.equals(text, cachedText)) {
            BufferedImage image = rasterize(font, text, pixels);
            int nextWidth = image.getWidth(), nextHeight = image.getHeight();
            var data = BufferUtils.createByteBuffer(nextWidth * nextHeight * 4);
            for (int y = 0; y < nextHeight; y++) {
                for (int x = 0; x < nextWidth; x++) {
                    int argb = image.getRGB(x, y);
                    data.put((byte) (argb >> 16))
                            .put((byte) (argb >> 8))
                            .put((byte) argb)
                            .put((byte) (argb >>> 24));
                }
            }
            data.flip();
            Texture next =
                    new Texture(
                            nextWidth,
                            nextHeight,
                            TextureFormat.RGBA8,
                            FilterMode.LINEAR,
                            FilterMode.LINEAR);
            next.upload(data);
            if (texture != null) texture.close();
            texture = next;
            width = nextWidth;
            height = nextHeight;
            cachedText = text;
            cachedHeight = pixels;
        }
        Renderer2D.TEXTURE.begin();
        Renderer2D.TEXTURE.texQuad(
                Math.rint(centerX - width / 2.0), Math.rint(top) - PAD, width, height, color);
        Renderer2D.TEXTURE.render(texture.getGlTextureView(), texture.getSampler());
    }

    private static Font load(FontFace face) throws Exception {
        var data = face.readToDirectByteBuffer();
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        return Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes));
    }

    /** Pure rasterization path also used by the headless font checks. */
    static BufferedImage rasterize(Font face, String text, int pixels) {
        var sized = face.deriveFont((float) pixels);
        var metrics = sized.getLineMetrics(text, FONT_CONTEXT);
        sized = sized.deriveFont(sized.getSize2D() * pixels / metrics.getHeight());
        metrics = sized.getLineMetrics(text, FONT_CONTEXT);
        int width =
                Math.max(
                        1,
                        (int) Math.ceil(sized.getStringBounds(text, FONT_CONTEXT).getWidth())
                                + PAD * 2);
        BufferedImage image =
                new BufferedImage(width, pixels + PAD * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(
                    RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            graphics.setFont(sized);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.drawString(text, PAD, PAD + metrics.getAscent());
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
