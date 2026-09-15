/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.hud;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.meteor.CustomFontChangedEvent;
import meteordevelopment.meteorclient.mixininterface.IGameRenderer;
import meteordevelopment.meteorclient.renderer.*;
import meteordevelopment.meteorclient.renderer.text.CustomTextRenderer;
import meteordevelopment.meteorclient.renderer.text.Font;
import meteordevelopment.meteorclient.renderer.text.VanillaTextRenderer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class HudRenderer {
    public static final HudRenderer INSTANCE = new HudRenderer();

    private static final double SCALE_TO_HEIGHT = 1.0 / 18.0;

    /** Largest glyph atlas that still packs the full character set into one 2048px texture. */
    private static final int MAX_ATLAS_HEIGHT = 80;

    private final Hud hud = Hud.get();
    private final List<Runnable> postTasks = new ArrayList<>();

    private final Int2ObjectMap<FontHolder> fontsInUse = new Int2ObjectOpenHashMap<>();
    private final LoadingCache<Integer, FontHolder> fontCache = CacheBuilder.newBuilder()
        .maximumSize(4)
        .expireAfterAccess(Duration.ofMinutes(10))
        .removalListener(notification -> {
            if (notification.wasEvicted())
                ((FontHolder) notification.getValue()).destroy();
        })
        .build(CacheLoader.from(HudRenderer::loadFont));

    public DrawContext drawContext;
    public double delta;

    private HudRenderer() {
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public void begin(DrawContext drawContext) {
        Renderer2D.COLOR.begin();

        this.drawContext = drawContext;
        this.delta = Utils.frameTime;

        drawContext.createNewRootLayer();

        if (!hud.hasCustomFont()) {
            VanillaTextRenderer.INSTANCE.scaleIndividually = true;
            VanillaTextRenderer.INSTANCE.begin();
        }
    }

    public void end() {
        Renderer2D.COLOR.render();

        if (hud.hasCustomFont()) {
            // Render fonts that were visited this frame and move to cache which weren't visited
            for (Iterator<FontHolder> it = fontsInUse.values().iterator(); it.hasNext(); ) {
                FontHolder fontHolder = it.next();

                if (fontHolder.visited) {
                    MeshRenderer.begin()
                        .attachments(mc.getFramebuffer())
                        .pipeline(MeteorRenderPipelines.UI_TEXT)
                        .mesh(fontHolder.getMesh())
                        .sampler("u_Texture", fontHolder.font.texture.getGlTextureView(), fontHolder.font.texture.getSampler())
                        .end();
                }
                else {
                    it.remove();
                    fontCache.put(fontHolder.font.getHeight(), fontHolder);
                }

                fontHolder.visited = false;
            }
        }
        else {
            VanillaTextRenderer.INSTANCE.end();
            VanillaTextRenderer.INSTANCE.scaleIndividually = false;
        }

        for (Runnable task : postTasks) task.run();
        postTasks.clear();

        drawContext.createNewRootLayer();

        drawContext = null;
    }

    public void line(double x1, double y1, double x2, double y2, Color color) {
        Renderer2D.COLOR.line(x1, y1, x2, y2, color);
    }

    public void quad(double x, double y, double width, double height, Color color) {
        Renderer2D.COLOR.quad(x, y, width, height, color);
    }

    /**
     * Ember: rounded rectangle with antialiased corners.
     *
     * The corners were cut into hard-edged slices, and since nothing here antialiases, every
     * slice snapped to a whole pixel and the curve came out as a visible staircase. Each row
     * is one pixel tall now, and the pixel the curve passes through is drawn separately at an
     * alpha equal to how much of it the shape actually covers - analytic coverage, which costs
     * a couple of extra quads rather than a texture lookup.
     *
     * A texture would be smoother still, but a drop shadow stacks ten of these per panel and
     * each one would become its own draw call.
     */
    public void roundedQuad(double x, double y, double width, double height, double radius, Color color) {
        if (radius <= 0) {
            quad(x, y, width, height, color);
            return;
        }

        radius = Math.min(radius, Math.min(width, height) / 2);

        // Straight middle band, full width.
        quad(x, y + radius, width, height - radius * 2, color);

        int rows = (int) Math.ceil(radius);

        for (int i = 0; i < rows; i++) {
            double top = i;
            double bottom = Math.min(radius, i + 1.0);
            double rowH = bottom - top;
            if (rowH <= 0.0001) continue;

            // Corner circle sits radius in from both edges; solve it at the row's midline.
            double dy = radius - (top + rowH / 2);
            double dx = Math.sqrt(Math.max(0, radius * radius - dy * dy));
            double edge = radius - dx;

            double solid = Math.ceil(edge - 0.0001);
            double coverage = solid - edge;

            double innerW = width - solid * 2;
            double topY = y + top;
            double botY = y + height - bottom;

            if (innerW > 0) {
                quad(x + solid, topY, innerW, rowH, color);
                quad(x + solid, botY, innerW, rowH, color);
            }

            // The one pixel the curve cuts through, weighted by its coverage.
            if (coverage > 0.02 && solid >= 1) {
                Color soft = new Color(color.r, color.g, color.b, (int) (color.a * coverage));

                quad(x + solid - 1, topY, 1, rowH, soft);
                quad(x + width - solid, topY, 1, rowH, soft);
                quad(x + solid - 1, botY, 1, rowH, soft);
                quad(x + width - solid, botY, 1, rowH, soft);
            }
        }
    }

    /**
     * Ember: soft glow from stacked rounded quads, following the shape's own corner radius. Deliberately not the GUI's texture-based
     * glow - drawing that mid-HUD-frame renders nothing and corrupts the next text draw.
     */
    public void softGlow(double x, double y, double width, double height, double radius, double size, Color color) {
        if (width <= 0 || height <= 0 || size <= 0 || color.a <= 0) return;

        int layers = 6;

        for (int i = layers; i >= 1; i--) {
            double spread = size * ((double) i / layers);

            int alpha = (int) (color.a * 0.18 * (1.0 - (double) (i - 1) / layers));
            if (alpha <= 0) continue;

            double w = width + spread * 2, h = height + spread * 2;
            roundedQuad(x - spread, y - spread, w, h, Math.min(Math.min(w, h) / 2, radius + spread),
                new Color(color.r, color.g, color.b, alpha));
        }
    }

    /**
     * Ember: a soft shadow around a panel, so a widget reads as lifted off the world instead
     * of merely outlined - which matters over bright terrain, where a thin shadow disappears.
     *
     * Centred on the panel rather than offset downwards: an offset shadow implies a light
     * source, which looks wrong on widgets that sit in any corner of the screen.
     */
    public void dropShadow(double x, double y, double width, double height, double radius, double size) {
        dropShadow(x, y, width, height, radius, size, 1f);
    }

    /** As above, with {@code opacity} so a shadow can fade in step with the panel casting it. */
    public void dropShadow(double x, double y, double width, double height, double radius, double size, float opacity) {
        if (width <= 0 || height <= 0 || size <= 0 || opacity <= 0.01f) return;

        // Glass wants far less shadow: a heavy one under a translucent panel darkens the
        // scene showing through it, which is the opposite of what frosted is for.
        if (meteordevelopment.meteorclient.utils.render.EmberAppearance.frosted()) opacity *= 0.4f;

        int layers = 10;

        for (int i = layers; i >= 1; i--) {
            double t = (double) i / layers;
            double spread = size * t;

            // Near-transparent at the outer edge, stacking into a denser core underneath.
            int alpha = (int) ((30 * (1.0 - t) + 4) * opacity);
            if (alpha <= 0) continue;

            double w = width + spread * 2, h = height + spread * 2;
            roundedQuad(x - spread, y - spread, w, h,
                Math.min(Math.min(w, h) / 2, radius + spread), new Color(0, 0, 0, alpha));
        }
    }

    public void quad(double x, double y, double width, double height, Color cTopLeft, Color cTopRight, Color cBottomRight, Color cBottomLeft) {
        Renderer2D.COLOR.quad(x, y, width, height, cTopLeft, cTopRight, cBottomRight, cBottomLeft);
    }

    public void triangle(double x1, double y1, double x2, double y2, double x3, double y3, Color color) {
        Renderer2D.COLOR.triangle(x1, y1, x2, y2, x3, y3, color);
    }

    public void texture(Identifier id, double x, double y, double width, double height, Color color) {
        Renderer2D.TEXTURE.begin();
        Renderer2D.TEXTURE.texQuad(x, y, width, height, color);
        Renderer2D.TEXTURE.render(mc.getTextureManager().getTexture(id).getGlTextureView(), mc.getTextureManager().getTexture(id).getSampler());
    }

    /**
     * Ember: part of a texture, given normalised UVs - the whole-texture {@link #texture} is
     * no use for a sprite sheet like a player skin, where the face is one 8x8 patch of 64x64.
     * Call it inside {@link #post} like any other texture work in the HUD.
     */
    public void textureRegion(Identifier id, double x, double y, double width, double height,
                              double u1, double v1, double u2, double v2, Color color) {
        var texture = mc.getTextureManager().getTexture(id);

        Renderer2D.TEXTURE.begin();
        Renderer2D.TEXTURE.texQuad(x, y, width, height, 0, u1, v1, u2, v2, color);
        Renderer2D.TEXTURE.render(texture.getGlTextureView(), texture.getSampler());
    }

    /**
     * Ember: a square patch of a texture with rounded corners. There is no stencil to mask
     * with and painting the corners over only works against a known flat colour, so the quad
     * is cut into horizontal strips instead, each inset to follow the corner arc with its UVs
     * inset to match. All strips go into one batch, so it stays a single draw.
     */
    public void roundedTextureRegion(Identifier id, double x, double y, double size, double radius,
                                     double u1, double v1, double u2, double v2, Color color) {
        if (size <= 0) return;

        radius = Math.min(radius, size / 2);

        var texture = mc.getTextureManager().getTexture(id);
        int steps = Math.max(6, (int) Math.ceil(radius * 2));
        double sliceH = size / (steps * 2.0);

        Renderer2D.TEXTURE.begin();

        for (int i = 0; i < steps * 2; i++) {
            double top = i * sliceH;
            double bottom = top + sliceH;

            // How far into a corner zone this strip sits, measured from the nearer edge.
            double dy;
            if (bottom <= radius) dy = radius - top;
            else if (top >= size - radius) dy = bottom - (size - radius);
            else dy = 0;

            double inset = dy <= 0 ? 0 : radius - Math.sqrt(Math.max(0, radius * radius - dy * dy));
            double w = size - inset * 2;
            if (w <= 0) continue;

            // UVs track the geometry, or the face would stretch as the strips narrow.
            double uInset = (u2 - u1) * (inset / size);
            double vTop = v1 + (v2 - v1) * (top / size);
            double vBottom = v1 + (v2 - v1) * (bottom / size);

            Renderer2D.TEXTURE.texQuad(x + inset, y + top, w, sliceH, 0,
                u1 + uInset, vTop, u2 - uInset, vBottom, color);
        }

        Renderer2D.TEXTURE.render(texture.getGlTextureView(), texture.getSampler());
    }

    /**
     * Ember: fills a rounded panel with the blurred scene behind it. Returns false when no
     * blurred frame is available, so the caller can fall back to a flat fill rather than
     * leaving a hole.
     */
    public boolean glassFill(double x, double y, double width, double height, double radius, Color tint) {
        if (!meteordevelopment.meteorclient.utils.render.EmberGlass.ready()) return false;

        var view = meteordevelopment.meteorclient.utils.render.EmberGlass.texture();
        if (view == null) return false;

        double sw = meteordevelopment.meteorclient.utils.Utils.getWindowWidth();
        double sh = meteordevelopment.meteorclient.utils.Utils.getWindowHeight();
        var sampler = meteordevelopment.meteorclient.utils.render.EmberGlass.sampler();

        // Driven by liquidity alone, so a sharp pane can still bend hard at its edges.
        double liquid = meteordevelopment.meteorclient.utils.render.EmberAppearance.glassLiquid();
        double lens = 4 + liquid * 44;

        // Base refraction.
        Renderer2D.TEXTURE.begin();
        meteordevelopment.meteorclient.utils.render.EmberShapes.liquidGlass(
            Renderer2D.TEXTURE, x, y, width, height, radius, sw, sh, true, lens, tint, false);
        Renderer2D.TEXTURE.render(view, sampler);

        // Chromatic aberration: red and blue sample from slightly different depths, fading out
        // away from the rim. Real glass splits wavelengths at a curved edge, and this fringe is
        // most of what separates a lens from a blur to the eye.
        int fringe = (int) (95 * liquid);

        if (fringe > 4) {
            Renderer2D.TEXTURE.begin();
            meteordevelopment.meteorclient.utils.render.EmberShapes.liquidGlass(
                Renderer2D.TEXTURE, x, y, width, height, radius, sw, sh, true, lens * 1.35,
                new Color(255, 90, 90, fringe), true);
            Renderer2D.TEXTURE.render(view, sampler);

            Renderer2D.TEXTURE.begin();
            meteordevelopment.meteorclient.utils.render.EmberShapes.liquidGlass(
                Renderer2D.TEXTURE, x, y, width, height, radius, sw, sh, true, lens * 0.7,
                new Color(90, 150, 255, fringe), true);
            Renderer2D.TEXTURE.render(view, sampler);
        }

        return true;
    }

    public double text(String text, double x, double y, Color color, boolean shadow, double scale) {
        if (scale == -1) scale = hud.getTextScale();

        if (!hud.hasCustomFont()) {
            VanillaTextRenderer.INSTANCE.scale = scale * 2;
            return VanillaTextRenderer.INSTANCE.render(text, x, y, color, shadow);
        }

        FontHolder fontHolder = getFontHolder(scale, true);

        Font font = fontHolder.font;
        MeshBuilder mesh = fontHolder.getMesh();

        double rs = renderScale(scale);
        double width;

        if (shadow) {
            int preShadowA = CustomTextRenderer.SHADOW_COLOR.a;
            CustomTextRenderer.SHADOW_COLOR.a = (int) (color.a / 255.0 * preShadowA);

            // Offset with the text, or the shadow vanishes under large glyphs.
            double off = Math.max(1, renderedHeight(scale) / 18);

            width = font.render(mesh, text, x + off, y + off, CustomTextRenderer.SHADOW_COLOR, rs);
            font.render(mesh, text, x, y, color, rs);

            CustomTextRenderer.SHADOW_COLOR.a = preShadowA;
        }
        else {
            width = font.render(mesh, text, x, y, color, rs);
        }

        return width;
    }
    public double text(String text, double x, double y, Color color, boolean shadow) {
        return text(text, x, y, color, shadow, -1);
    }

    public double textWidth(String text, boolean shadow, double scale) {
        if (text.isEmpty()) return 0;

        if (hud.hasCustomFont()) {
            double s = scale == -1 ? hud.getTextScale() : scale;
            return getFont(s).getWidth(text, text.length()) * renderScale(s) + (shadow ? 1 : 0);
        }

        VanillaTextRenderer.INSTANCE.scale = (scale == -1 ? hud.getTextScale() : scale) * 2;
        return VanillaTextRenderer.INSTANCE.getWidth(text, shadow);
    }
    public double textWidth(String text, boolean shadow) {
        return textWidth(text, shadow, -1);
    }
    public double textWidth(String text, double scale) {
        return textWidth(text, false, scale);
    }
    public double textWidth(String text) {
        return textWidth(text, false, -1);
    }

    public double textHeight(boolean shadow, double scale) {
        if (hud.hasCustomFont()) {
            double s = scale == -1 ? hud.getTextScale() : scale;
            return (getFont(s).getHeight() + 1 + (shadow ? 1 : 0)) * renderScale(s);
        }

        VanillaTextRenderer.INSTANCE.scale = (scale == -1 ? hud.getTextScale() : scale) * 2;
        return VanillaTextRenderer.INSTANCE.getHeight(shadow);
    }
    public double textHeight(boolean shadow) {
        return textHeight(shadow, -1);
    }
    public double textHeight() {
        return textHeight(false, -1);
    }

    public void post(Runnable task) {
        postTasks.add(task);
    }

    public void item(ItemStack itemStack, int x, int y, float scale, boolean overlay, String countOverlay) {
        RenderUtils.drawItem(drawContext, itemStack, x, y, scale, overlay, countOverlay, true);
    }

    public void item(ItemStack itemStack, int x, int y, float scale, boolean overlay) {
        RenderUtils.drawItem(drawContext, itemStack, x, y, scale, overlay);
    }

    public void entity(LivingEntity entity,  int x, int y, int width, int height, float yaw, float pitch) {
        float previousBodyYaw = entity.bodyYaw;
        float previousYaw = entity.getYaw();
        float previousPitch = entity.getPitch();
        float lastLastHeadYaw = entity.lastHeadYaw;
        float lastHeadYaw = entity.headYaw;

        float tanYaw = (float) Math.atan((yaw) / 40.0f);
        float tanPitch = (float) Math.atan((pitch) / 40.0f);
        entity.bodyYaw = 180.0f + tanYaw * 20.0f;
        entity.setYaw(180.0f + tanYaw * 40.0f);
        entity.setPitch(-tanPitch * 20.0f);
        entity.headYaw = entity.getYaw();
        entity.lastHeadYaw = entity.getYaw();

        var state = (LivingEntityRenderState) mc.getEntityRenderDispatcher().getRenderer(entity).getAndUpdateRenderState(entity, 1);

        entity.bodyYaw = previousBodyYaw;
        entity.setYaw(previousYaw);
        entity.setPitch(previousPitch);
        entity.lastHeadYaw = lastLastHeadYaw;
        entity.headYaw = lastHeadYaw;

        float s = 1.0f / mc.getWindow().getScaleFactor();
        int x1 = (int) (x * s);
        int y1 = (int) (y * s);
        int x2 = (int) ((x + width) * s);
        int y2 = (int) ((y + height) * s);

        float scale = Math.max(width, height) * s / 2f;
        Vector3f translation = new Vector3f(0, 1f, 0);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);

        drawContext.addEntity(state, scale, translation, rotation, null, x1, y1, x2, y2);
    }

    /** Nominal size the layout maths is based on. */
    private static int nominalHeight(double scale) {
        return Math.max(1, (int) Math.round(scale / SCALE_TO_HEIGHT));
    }

    /** The pixel height glyphs actually end up at once the draw scale is applied. */
    private static double renderedHeight(double scale) {
        return nominalHeight(scale) * scale;
    }

    /**
     * Size the glyph atlas is rasterised at. This follows the rendered height rather than the
     * nominal one: the atlas used to be baked at nominal size and then multiplied by scale
     * again at draw time, so any scale above 1 was a straight bitmap upscale and went soft.
     *
     * Snapped up onto a coarse ladder, because every distinct height builds its own atlas -
     * a 4MB direct buffer plus a 4MB texture. Keying them off an exact pixel height let a
     * slider drag mint one per step and exhaust native memory. The ladder bounds the number
     * that can ever exist, and is finer for small text where a step costs proportionally more.
     *
     * Snapping up rather than to nearest also means the atlas is never smaller than the text
     * drawn from it, so glyphs are always minified - which stays sharp - and never magnified.
     *
     * Capped because the ~720 packed characters stop fitting one 2048px texture a little past
     * 90px; past the cap the atlas is reused and scaled, which is softer but still complete.
     */
    private static int atlasHeight(double scale) {
        double wanted = renderedHeight(scale);
        int step = wanted < 24 ? 4 : 8;
        int snapped = (int) (Math.ceil(wanted / step) * step);

        return Math.max(step, Math.min(MAX_ATLAS_HEIGHT, snapped));
    }

    /** Draw-time factor that lands the cached atlas on its intended pixel height. */
    private static double renderScale(double scale) {
        return renderedHeight(scale) / atlasHeight(scale);
    }

    private FontHolder getFontHolder(double scale, boolean render) {
        // Calculate font height
        if (scale == -1) scale = hud.getTextScale();
        int height = atlasHeight(scale);

        // Check fonts in use
        FontHolder fontHolder = fontsInUse.get(height);
        if (fontHolder != null) {
            if (render) fontHolder.visited = true;
            return fontHolder;
        }

        // Create font if not in cache otherwise remove from cache and add to fonts in use
        if (render) {
            fontHolder = fontCache.getIfPresent(height);
            if (fontHolder == null) fontHolder = loadFont(height);
            else fontCache.invalidate(height);

            fontsInUse.put(height, fontHolder);
            fontHolder.visited = true;

            return fontHolder;
        }

        // Otherwise get from cache
        return fontCache.getUnchecked(height);
    }

    private Font getFont(double scale) {
        return getFontHolder(scale, false).font;
    }

    @EventHandler
    private void onCustomFontChanged(CustomFontChangedEvent event) {
        // Need to destroy both fonts in use and in cache because they were not evicted from the cache automatically
        for (FontHolder fontHolder : fontsInUse.values()) fontHolder.destroy();
        for (FontHolder fontHolder : fontCache.asMap().values()) fontHolder.destroy();

        // Clear collections
        fontsInUse.clear();
        fontCache.invalidateAll();
    }

    private static FontHolder loadFont(int height) {
        try {
            ByteBuffer buffer = Fonts.RENDERER.fontFace.readToDirectByteBuffer();
            return new FontHolder(new Font(buffer, height));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load font: " + Fonts.RENDERER.fontFace, e);
        }
    }

    private static class FontHolder {
        public final Font font;
        public boolean visited;

        private MeshBuilder mesh;

        public FontHolder(Font font) {
            this.font = font;
        }

        public MeshBuilder getMesh() {
            if (mesh == null) mesh = new MeshBuilder(MeteorRenderPipelines.UI_TEXT);
            if (!mesh.isBuilding()) mesh.begin();
            return mesh;
        }

        public void destroy() {
            font.texture.close();
        }
    }
}
