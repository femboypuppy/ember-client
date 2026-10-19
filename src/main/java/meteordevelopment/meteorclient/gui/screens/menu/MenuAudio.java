package meteordevelopment.meteorclient.gui.screens.menu;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.MusicType;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/** Uses Minecraft's soundtrack and UI sound. Never rewrites GameOptions sound volumes. */
public final class MenuAudio {
    private static double previous=-1;
    private static final SoundEvent CLICK=SoundEvent.of(Identifier.of("ember","ui.click"));
    private MenuAudio() {}
    public static void click() {
        if(ConfigManager.get().clicks)MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(CLICK,1.05f,.22f));
    }
    public static float musicFactor() {
        if(!MenuRuntime.enabled())return 1;
        var c=ConfigManager.get();return c.music?(float)c.musicVolume:0;
    }
    public static void tick() {
        var mc=MinecraftClient.getInstance();double factor=musicFactor();
        if(factor!=previous){previous=factor;mc.getSoundManager().refreshSoundVolumes(SoundCategory.MUSIC);}
        if(ConfigManager.get().music && MenuRuntime.enabled() && !mc.getMusicTracker().isPlayingType(MusicType.MENU))mc.getMusicTracker().play(MusicType.MENU);
    }
    public static void refreshAfterScreenChange() {
        MinecraftClient.getInstance().execute(()->{previous=-1;MinecraftClient.getInstance().getSoundManager().refreshSoundVolumes(SoundCategory.MUSIC);});
    }
}
