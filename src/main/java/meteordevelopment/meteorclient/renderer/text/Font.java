/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer.text;

import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.renderer.Texture;
import meteordevelopment.meteorclient.utils.render.color.Color;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.*;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public class Font {
    public final Texture texture;
    private final int height;
    private final float scale;
    private final float ascent;
    private final Int2ObjectOpenHashMap<CharData> charMap = new Int2ObjectOpenHashMap<>();
    private static final int size = 2048;

    public Font(ByteBuffer buffer, int height) {
        this.height = height;

        // Initialize font
        STBTTFontinfo fontInfo = STBTTFontinfo.create();
        STBTruetype.stbtt_InitFont(fontInfo, buffer);

        // Allocate buffers
        ByteBuffer bitmap = BufferUtils.createByteBuffer(size * size);
        STBTTPackedchar.Buffer[] cdata = {
            STBTTPackedchar.create(95), // Basic Latin
            STBTTPackedchar.create(96), // Latin 1 Supplement
            STBTTPackedchar.create(128), // Latin Extended-A
            STBTTPackedchar.create(144), // Greek and Coptic
            STBTTPackedchar.create(256), // Cyrillic
            STBTTPackedchar.create(1) // infinity symbol
        };

        // create and initialise packing context
        STBTTPackContext packContext = STBTTPackContext.create();
        STBTruetype.stbtt_PackBegin(packContext, bitmap, size, size, 0 ,1);

        // create the pack range, populate with the specific packing ranges
        STBTTPackRange.Buffer packRange = STBTTPackRange.create(cdata.length);
        packRange.put(STBTTPackRange.create().set(height, 32, null, 95, cdata[0], (byte) 2, (byte) 2));
        packRange.put(STBTTPackRange.create().set(height, 160, null, 96, cdata[1], (byte) 2, (byte) 2));
        packRange.put(STBTTPackRange.create().set(height, 256, null, 128, cdata[2], (byte) 2, (byte) 2));
        packRange.put(STBTTPackRange.create().set(height, 880, null, 144, cdata[3], (byte) 2, (byte) 2));
        packRange.put(STBTTPackRange.create().set(height, 1024, null, 256, cdata[4], (byte) 2, (byte) 2));
        packRange.put(STBTTPackRange.create().set(height, 8734, null, 1, cdata[5], (byte) 2, (byte) 2)); // lol
        packRange.flip();

        // write and finish
        STBTruetype.stbtt_PackFontRanges(packContext, buffer, 0, packRange);
        STBTruetype.stbtt_PackEnd(packContext);

        // Create texture object and get font scale
        texture = new Texture(size, size, TextureFormat.RED8, FilterMode.LINEAR, FilterMode.LINEAR);
        texture.upload(bitmap);
        scale = STBTruetype.stbtt_ScaleForPixelHeight(fontInfo, height);

        // Get font vertical ascent
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer ascent = stack.mallocInt(1);
            STBTruetype.stbtt_GetFontVMetrics(fontInfo, ascent, null, null);
            this.ascent = ascent.get(0);
        }

        for (int i = 0; i < cdata.length; i++) {
            STBTTPackedchar.Buffer cbuf = cdata[i];
            int offset = packRange.get(i).first_unicode_codepoint_in_range();

            for (int j = 0; j < cbuf.capacity(); j++) {
                STBTTPackedchar packedChar = cbuf.get(j);

                float ipw = 1f / size; // pixel width and height
                float iph = 1f / size;

                charMap.put(j + offset, new CharData(
                    packedChar.xoff(),
                    packedChar.yoff(),
                    packedChar.xoff2(),
                    packedChar.yoff2(),
                    packedChar.x0() * ipw,
                    packedChar.y0() * iph,
                    packedChar.x1() * ipw,
                    packedChar.y1() * iph,
                    packedChar.xadvance()
                ));
            }
        }
    }

    public double getWidth(String string, int length) {
        double width = 0;

        for (int i = 0; i < length; i++) {
            int cp = string.charAt(i);
            CharData c = charMap.get(cp);
            if (c == null) c = charMap.get(32);

            width += c.xAdvance;
        }

        return width;
    }

    public int getHeight() {
        return height;
    }

    public double render(MeshBuilder mesh, String string, double x, double y, Color color, double scale) {
        return renderClipped(mesh, string, x, y, color, scale, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    /** Clip glyph geometry and UVs together without flushing other HUD text batches. */
    public double renderClipped(MeshBuilder mesh, String string, double x, double y, Color color, double scale, double left, double right) {
        return renderClipped(mesh, string, x, y, color, scale, left, right, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    public double renderClipped(MeshBuilder mesh, String string, double x, double y, Color color, double scale, double left, double right, double top, double bottom) {
        y += ascent * this.scale * scale;

        int length = string.length();
        mesh.ensureCapacity(length * 4, length * 6);

        for (int i = 0; i < length; i++) {
            int cp = string.charAt(i);
            CharData c = charMap.get(cp);
            if (c == null) c = charMap.get(32);

            double gx0 = x + c.x0 * scale, gx1 = x + c.x1 * scale;
            double gy0 = y + c.y0 * scale, gy1 = y + c.y1 * scale;
            double clipped0 = Math.max(left, gx0), clipped1 = Math.min(right, gx1);
            double clippedY0 = Math.max(top, gy0), clippedY1 = Math.min(bottom, gy1);
            if (clipped1 > clipped0 && clippedY1 > clippedY0) {
                double u0 = c.u0 + (c.u1 - c.u0) * (clipped0 - gx0) / (gx1 - gx0);
                double u1 = c.u0 + (c.u1 - c.u0) * (clipped1 - gx0) / (gx1 - gx0);
                double v0 = c.v0 + (c.v1 - c.v0) * (clippedY0 - gy0) / (gy1 - gy0);
                double v1 = c.v0 + (c.v1 - c.v0) * (clippedY1 - gy0) / (gy1 - gy0);
                mesh.quad(
                    mesh.vec2(clipped0, clippedY0).vec2(u0, v0).color(color).next(),
                    mesh.vec2(clipped0, clippedY1).vec2(u0, v1).color(color).next(),
                    mesh.vec2(clipped1, clippedY1).vec2(u1, v1).color(color).next(),
                    mesh.vec2(clipped1, clippedY0).vec2(u1, v0).color(color).next()
                );
            }

            x += c.xAdvance * scale;
        }

        return x;
    }

    private record CharData(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, float xAdvance) {}
}
