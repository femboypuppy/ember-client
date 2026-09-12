package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.sound.SoundEvents;
import net.minecraft.sound.SoundCategory;
import net.minecraft.entity.player.PlayerEntity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PlayerDetection extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> notifyChat = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-chat")
        .description("Send notification in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifySound = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-sound")
        .description("Play sound on detection.")
        .defaultValue(true)
        .build()
    );

    private final Set<UUID> detectedPlayers = new HashSet<>();

    public PlayerDetection() {
        super(Categories.Donut, "player-detection", "Detects nearby players.");
    }

    @Override
    public void onActivate() {
        detectedPlayers.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        Set<UUID> present = new HashSet<>();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            present.add(player.getUuid());

            if (detectedPlayers.add(player.getUuid())) {
                if (notifyChat.get()) {
                    info("PlayerEntity detected: %s at %.0f blocks away", player.getName().getString(), mc.player.distanceTo(player));
                }
                if (notifySound.get()) {
                    mc.world.playSoundFromEntity(mc.player, mc.player, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.AMBIENT, 3.0F, 1.0F);
                }
            }
        }

        // Forget players who left render distance, so they alert again if they come back.
        detectedPlayers.retainAll(present);
    }
}
