package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.textures.FilterMode;
import meteordevelopment.meteorclient.gui.screens.menu.MenuScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.render.state.GlyphGuiElementRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep native glyph rendering, but smooth its atlas sampling on Ember's scaled menu. */
@Mixin(GlyphGuiElementRenderState.class)
public abstract class EmberMenuGlyphMixin {
    // 1.21.11's GUI glyph state selects NEAREST directly, ignoring texture metadata.
    // The sampler cache owns the linear sampler; this never uploads or rebuilds a font.
    @ModifyExpressionValue(method = "textureSetup", at = @At(value = "FIELD",
        target = "Lcom/mojang/blaze3d/textures/FilterMode;NEAREST:Lcom/mojang/blaze3d/textures/FilterMode;"))
    private FilterMode ember$smoothMenuGlyphs(FilterMode original) {
        return MinecraftClient.getInstance().currentScreen instanceof MenuScreen ? FilterMode.LINEAR : original;
    }
}
