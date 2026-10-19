package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import meteordevelopment.meteorclient.gui.screens.menu.MenuAudio;
import net.minecraft.client.sound.SoundSystem;
import net.minecraft.sound.SoundCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SoundSystem.class)
public abstract class EmberMenuSoundMixin {
    @ModifyReturnValue(method="getAdjustedVolume(FLnet/minecraft/sound/SoundCategory;)F",at=@At("RETURN"))
    private float ember$menuMusic(float volume,float raw,SoundCategory category){return category==SoundCategory.MUSIC?volume*MenuAudio.musicFactor():volume;}
}
