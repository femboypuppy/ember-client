package meteordevelopment.meteorclient.utils.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import meteordevelopment.meteorclient.systems.modules.render.Chams;

/** Cached layers keep model materials on Minecraft's normal deferred rendering path. */
public final class ChamsMaterials {
    private static final RenderLayer[][] LAYERS = new RenderLayer[ChamsMaterial.values().length][2];
    private ChamsMaterials() {}

    public static RenderLayer layer(ChamsMaterial material, boolean animated) {
        int animation = animated ? 1 : 0;
        RenderLayer layer = LAYERS[material.ordinal()][animation];
        if (layer != null) return layer;
        RenderPipeline pipeline = meteordevelopment.meteorclient.renderer.MeteorRenderPipelines.chamsSurface(material, animated);
        layer = RenderLayer.of("ember_chams_" + material + "_" + animation,
            RenderSetup.builder(pipeline).texture("Sampler0", Chams.BLANK).useLightmap().useOverlay().translucent().build());
        LAYERS[material.ordinal()][animation] = layer;
        return layer;
    }
}
