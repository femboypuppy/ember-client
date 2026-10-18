package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.mixininterface.IEntityRenderState;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Hats;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Follows the same pose as helmets, including crouching, swimming, gliding and player scaling. */
public final class HatFeatureRenderer extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {
    private static final RenderLayer LAYER = RenderLayer.of("ember_holographic_hat",
        RenderSetup.builder(MeteorRenderPipelines.HOLOGRAPHIC_HAT).translucent().build());
    private final CelestialMesh mesh = new CelestialMesh(HatGeometry.MAX_VERTICES);

    public HatFeatureRenderer(FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> context) { super(context); }

    @Override public void render(MatrixStack matrices, OrderedRenderCommandQueue queue, int light, PlayerEntityRenderState state,
                                 float limbAngle, float limbDistance) {
        Hats hats = Modules.get().get(Hats.class);
        if (hats == null || !hats.isActive() || mc.player == null || mc.world == null
            || ((IEntityRenderState) state).meteor$getEntity() != mc.player || mc.player.isSpectator() || mc.player.isInvisible()) return;
        if (mc.options.getPerspective().isFirstPerson() && mc.getCameraEntity() == mc.player) return;
        if (hats.hideWithHelmet() && !mc.player.getEquippedStack(EquipmentSlot.HEAD).isEmpty()) return;
        var appearance = hats.appearance(mc.getRenderTickCounter().getTickProgress(false));
        if (appearance.opacity() <= 0) return;

        // Vanilla sets the pose before invoking features; use the same root/head path as head equipment.
        matrices.push();
        getContextModel().getRootPart().applyTransform(matrices);
        getContextModel().head.applyTransform(matrices);
        matrices.translate(0, -.5 - appearance.height(), 0);
        matrices.scale(1, -1, 1);
        queue.submitCustom(matrices, LAYER, (entry, vertices) -> {
            mesh.clear();
            HatGeometry.build(mesh, appearance.style(), appearance.time(), appearance.size(), appearance.opacity(),
                appearance.glow(), appearance.scanlines(), appearance.iridescence(), appearance.rgb());
            for (int i = 0; i < mesh.size; i++) {
                int p = i * 3;
                vertices.vertex(entry, mesh.positions[p], mesh.positions[p + 1], mesh.positions[p + 2]).color(mesh.colors[i]);
            }
        });
        matrices.pop();
    }
}
