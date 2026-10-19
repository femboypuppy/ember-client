/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.resource.ResourceManager;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gl.GlImportProcessor;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.util.Identifier;
import meteordevelopment.meteorclient.utils.render.ChamsMaterial;

public abstract class MeteorRenderPipelines {
    private static final List<RenderPipeline> PIPELINES = new ArrayList<>();

    // Snippets

    private static final RenderPipeline.Snippet MESH_UNIFORMS = RenderPipeline.builder()
        .withUniform("MeshData", UniformType.UNIFORM_BUFFER)
        .buildSnippet();

    // World

    public static final RenderPipeline WORLD_COLORED = add(new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLocation(MeteorClient.identifier("pipeline/world_colored"))
        .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/pos_color.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/pos_color.frag"))
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withCull(false)
        .build()
    );

    public static final RenderPipeline WORLD_COLORED_LINES = add(new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLineSmooth()
        .withLocation(MeteorClient.identifier("pipeline/world_colored_lines"))
        .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.DEBUG_LINES)
        .withVertexShader(MeteorClient.identifier("shaders/pos_color.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/pos_color.frag"))
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withCull(false)
        .build()
    );

    public static final RenderPipeline WORLD_COLORED_DEPTH = add(new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLocation(MeteorClient.identifier("pipeline/world_colored_depth"))
        .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/pos_color.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/pos_color.frag"))
        .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
        .withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withCull(false)
        .build()
    );

    public static final RenderPipeline WORLD_COLORED_LINES_DEPTH = add(new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLineSmooth()
        .withLocation(MeteorClient.identifier("pipeline/world_colored_lines_depth"))
        .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.DEBUG_LINES)
        .withVertexShader(MeteorClient.identifier("shaders/pos_color.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/pos_color.frag"))
        .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
        .withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withCull(false)
        .build()
    );

    /** Colored ground illumination brightens the scene instead of painting over its texture. */
    public static final RenderPipeline WORLD_ACCENT_LIGHT = add(new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLocation(MeteorClient.identifier("pipeline/world_accent_light"))
        .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/pos_color.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/pos_color.frag"))
        .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
        .withDepthWrite(false)
        .withBlend(new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.ONE))
        .withCull(false)
        .build());

    public static final RenderPipeline CELESTIAL_SKY = add(new ExtendedRenderPipelineBuilder()
        .withLocation(MeteorClient.identifier("pipeline/celestial_sky"))
        .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/celestial_sky.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/celestial_sky.frag"))
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withUniform("SkyData", UniformType.UNIFORM_BUFFER)
        .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT).withCull(false).build());

    public static final RenderPipeline CELESTIAL_STARS = add(new ExtendedRenderPipelineBuilder()
        .withLocation(MeteorClient.identifier("pipeline/celestial_stars"))
        .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/celestial_sky.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/pos_color.frag"))
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(false)
        .withBlend(new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.ONE)).withCull(false).build());

    /** Uses vanilla's deferred entity uniforms, retaining depth without obscuring later geometry. */
    public static final RenderPipeline HOLOGRAPHIC_HAT = add(new ExtendedRenderPipelineBuilder()
        .withLocation(MeteorClient.identifier("pipeline/holographic_hat"))
        .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/holographic_hat.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/pos_color.frag"))
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT).withCull(false).build());

    // UI

    public static final RenderPipeline UI_COLORED = add(new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLocation(MeteorClient.identifier("pipeline/ui_colored"))
        .withVertexFormat(MeteorVertexFormats.POS2_COLOR, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/pos_color.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/pos_color.frag"))
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withCull(true)
        .build()
    );

    public static final RenderPipeline UI_COLORED_LINES = add(new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLocation(MeteorClient.identifier("pipeline/ui_colored_lines"))
        .withVertexFormat(MeteorVertexFormats.POS2_COLOR, VertexFormat.DrawMode.DEBUG_LINES)
        .withVertexShader(MeteorClient.identifier("shaders/pos_color.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/pos_color.frag"))
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withCull(true)
        .build()
    );

    public static final RenderPipeline UI_TEXTURED = add(new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLocation(MeteorClient.identifier("pipeline/ui_textured"))
        .withVertexFormat(MeteorVertexFormats.POS2_TEXTURE_COLOR, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/pos_tex_color.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/pos_tex_color.frag"))
        .withSampler("u_Texture")
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withCull(true)
        .build()
    );

    public static final RenderPipeline UI_TEXT = add(new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLocation(MeteorClient.identifier("pipeline/ui_text"))
        .withVertexFormat(MeteorVertexFormats.POS2_TEXTURE_COLOR, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/text.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/text.frag"))
        .withSampler("u_Texture")
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withCull(true)
        .build()
    );

    // Post Process

    public static final RenderPipeline UI_LIQUID_GLASS = add(new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLocation(MeteorClient.identifier("pipeline/ui_liquid_glass"))
        .withVertexFormat(MeteorVertexFormats.POS2_TEXTURE_COLOR, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/pos_tex_color.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/liquid_glass.frag"))
        .withSampler("u_Texture")
        .withUniform("GlassData", UniformType.UNIFORM_BUFFER)
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withCull(false)
        .build()
    );

    public static final RenderPipeline POST_OUTLINE = add(new ExtendedRenderPipelineBuilder()
        .withLocation(MeteorClient.identifier("pipeline/post/outline"))
        .withVertexFormat(MeteorVertexFormats.POS2, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/post-process/base.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/post-process/outline.frag"))
        .withSampler("u_Texture")
        .withUniform("PostData", UniformType.UNIFORM_BUFFER)
        .withUniform("OutlineData", UniformType.UNIFORM_BUFFER)
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withCull(false)
        .build()
    );

    public static final RenderPipeline POST_IMAGE = add(new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLocation(MeteorClient.identifier("pipeline/post/image"))
        .withVertexFormat(MeteorVertexFormats.POS2, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/post-process/base.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/post-process/image.frag"))
        .withSampler("u_Texture")
        .withSampler("u_TextureI")
        .withUniform("PostData", UniformType.UNIFORM_BUFFER)
        .withUniform("ImageData", UniformType.UNIFORM_BUFFER)
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withCull(false)
        .build()
    );

    public static final RenderPipeline SCENE_FOG = add(new ExtendedRenderPipelineBuilder()
        .withLocation(MeteorClient.identifier("pipeline/scene_fog"))
        .withVertexFormat(MeteorVertexFormats.POS2, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/blur.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/scene_fog.frag"))
        .withSampler("u_Depth").withUniform("SceneFogData", UniformType.UNIFORM_BUFFER)
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT).withCull(false).build());

    public static final RenderPipeline BLOOM_EXTRACT = add(new ExtendedRenderPipelineBuilder()
        .withLocation(MeteorClient.identifier("pipeline/bloom_extract"))
        .withVertexFormat(MeteorVertexFormats.POS2, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/blur.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/bloom_extract.frag"))
        .withSampler("u_Texture").withUniform("BloomData", UniformType.UNIFORM_BUFFER)
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false).withCull(false).build());

    public static final RenderPipeline BLOOM_COMPOSITE = add(new ExtendedRenderPipelineBuilder()
        .withLocation(MeteorClient.identifier("pipeline/bloom_composite"))
        .withVertexFormat(MeteorVertexFormats.POS2, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/blur.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/bloom_composite.frag"))
        .withSampler("u_Texture").withUniform("BloomData", UniformType.UNIFORM_BUFFER)
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false)
        .withBlend(new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.ONE)).withCull(false).build());

    // Blur

    public static final RenderPipeline CHAMS_PREVIEW = add(new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLocation(MeteorClient.identifier("pipeline/chams_preview"))
        .withVertexFormat(MeteorVertexFormats.POS2_TEXTURE_COLOR, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/pos_tex_color.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/chams_preview.frag"))
        .withUniform("MaterialData", UniformType.UNIFORM_BUFFER)
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT).withCull(false).build());

    private static final RenderPipeline[][] CHAMS_SURFACES = createChamsSurfaces();

    private static RenderPipeline[][] createChamsSurfaces() {
        RenderPipeline[][] result = new RenderPipeline[ChamsMaterial.values().length][2];
        for (ChamsMaterial material : ChamsMaterial.values()) for (int animated = 0; animated < 2; animated++) {
            var builder = new ExtendedRenderPipelineBuilder(RenderPipelines.ENTITY_SNIPPET)
                .withLocation(MeteorClient.identifier("pipeline/chams/" + material.name().toLowerCase(java.util.Locale.ROOT) + "_" + animated))
                .withVertexShader(MeteorClient.identifier("shaders/chams_surface.vert"))
                .withFragmentShader(MeteorClient.identifier("shaders/chams_surface.frag"))
                .withUniform("Globals", UniformType.UNIFORM_BUFFER)
                .withShaderDefine("MATERIAL", material.ordinal())
                .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                .withDepthWrite(false).withCull(false).withBlend(BlendFunction.TRANSLUCENT);
            if (animated == 1) builder.withShaderDefine("ANIMATED");
            result[material.ordinal()][animated] = add(builder.build());
        }
        return result;
    }

    public static RenderPipeline chamsSurface(ChamsMaterial material, boolean animated) {
        return CHAMS_SURFACES[material.ordinal()][animated ? 1 : 0];
    }

    public static final RenderPipeline BLUR_DOWN = add(new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLocation(MeteorClient.identifier("pipeline/blur/down"))
        .withVertexFormat(MeteorVertexFormats.POS2, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/blur.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/blur_down.frag"))
        .withSampler("u_Texture")
        .withUniform("BlurData", UniformType.UNIFORM_BUFFER)
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withCull(false)
        .build()
    );

    public static final RenderPipeline BLUR_UP = add(new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLocation(MeteorClient.identifier("pipeline/blur/up"))
        .withVertexFormat(MeteorVertexFormats.POS2, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/blur.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/blur_up.frag"))
        .withSampler("u_Texture")
        .withUniform("BlurData", UniformType.UNIFORM_BUFFER)
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withCull(false)
        .build()
    );

    public static final RenderPipeline BLUR_PASSTHROUGH = add(new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLocation(MeteorClient.identifier("pipeline/blur/up"))
        .withVertexFormat(MeteorVertexFormats.POS2, VertexFormat.DrawMode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/passthrough.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/passthrough.frag"))
        .withSampler("u_Texture")
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withDepthWrite(false)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withCull(false)
        .build()
    );

    private static RenderPipeline add(RenderPipeline pipeline) {
        PIPELINES.add(pipeline);
        return pipeline;
    }

    public static void precompile() {
        GpuDevice device = RenderSystem.getDevice();
        ResourceManager resources = MinecraftClient.getInstance().getResourceManager();

        for (RenderPipeline pipeline : PIPELINES) {
            device.precompilePipeline(pipeline, (identifier, shaderType) -> {
                var resource = resources.getResource(identifier).get();

                try (var in = resource.getInputStream()) {
                    String source = IOUtils.toString(in, StandardCharsets.UTF_8);
                    var imported = new java.util.HashSet<Identifier>();
                    return String.join("", new GlImportProcessor() {
                        @Override public String loadImport(boolean inline, String name) {
                            Identifier include = inline ? identifier.withPath(p -> p.substring(0, p.lastIndexOf('/') + 1) + name)
                                : Identifier.of(name).withPrefixedPath("shaders/include/");
                            if (!imported.add(include)) return null;
                            try (var stream = resources.getResource(include).orElseThrow().getInputStream()) {
                                return IOUtils.toString(stream, StandardCharsets.UTF_8);
                            } catch (IOException error) { throw new RuntimeException("Cannot load shader include " + include, error); }
                        }
                    }.readSource(source));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private MeteorRenderPipelines() {}
}
