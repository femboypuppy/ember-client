package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.OverlayTextureAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.random.Random;

/**
 * Client-side hit feedback: recolours the red flash entities get when hurt, and adds particles and a sound when you
 * hit something. Nothing here is sent to the server.
 */
public class HitEffects extends Module {
    // Vanilla's hurt overlay: red at 70% "keep original colour".
    private static final int VANILLA_HURT = 0xB2FF0000;

    private final SettingGroup sgColor = settings.getDefaultGroup();
    private final SettingGroup sgParticles = settings.createGroup("Particles");
    private final SettingGroup sgSound = settings.createGroup("Sound");

    private final Setting<Boolean> hurtColor = sgColor.add(new BoolSetting.Builder()
        .name("hurt-color")
        .description("Recolour the flash entities get when they take damage.")
        .defaultValue(true)
        .onChanged(v -> applyOverlay())
        .build()
    );

    private final Setting<SettingColor> color = sgColor.add(new ColorSetting.Builder()
        .name("color")
        .description("Hurt flash colour. Alpha is how strong the tint is.")
        .defaultValue(new SettingColor(255, 110, 40, 120))
        .visible(hurtColor::get)
        .build()
    );

    private final Setting<Boolean> particles = sgParticles.add(new BoolSetting.Builder()
        .name("particles")
        .description("Spawn particles on whatever you hit.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ParticleStyle> particleStyle = sgParticles.add(new EnumSetting.Builder<ParticleStyle>()
        .name("style")
        .description("Which particles to spawn.")
        .defaultValue(ParticleStyle.Sparks)
        .visible(particles::get)
        .build()
    );

    private final Setting<Integer> amount = sgParticles.add(new IntSetting.Builder()
        .name("amount")
        .description("Particles per hit.")
        .defaultValue(14)
        .range(1, 60)
        .sliderRange(1, 40)
        .visible(particles::get)
        .build()
    );

    private final Setting<Boolean> livingOnly = sgParticles.add(new BoolSetting.Builder()
        .name("living-only")
        .description("Only spawn particles when hitting mobs and players, not boats or item frames.")
        .defaultValue(true)
        .visible(particles::get)
        .build()
    );

    private final Setting<Boolean> sound = sgSound.add(new BoolSetting.Builder()
        .name("hit-sound")
        .description("Play a sound when you hit something.")
        .defaultValue(false)
        .build()
    );

    private final Setting<HitSound> hitSound = sgSound.add(new EnumSetting.Builder<HitSound>()
        .name("sound")
        .description("Which sound to play.")
        .defaultValue(HitSound.Click)
        .visible(sound::get)
        .build()
    );

    private final Setting<Double> volume = sgSound.add(new DoubleSetting.Builder()
        .name("volume")
        .defaultValue(0.6)
        .range(0, 1)
        .sliderRange(0, 1)
        .visible(sound::get)
        .build()
    );

    private final Setting<Double> pitch = sgSound.add(new DoubleSetting.Builder()
        .name("pitch")
        .defaultValue(1.2)
        .range(0.5, 2)
        .sliderRange(0.5, 2)
        .visible(sound::get)
        .build()
    );

    private final Random random = Random.create();
    private int appliedColor;

    public HitEffects() {
        super(Categories.Render, "hit-effects", "Custom hurt colour, hit particles and hit sounds.");
    }

    @Override
    public void onActivate() {
        appliedColor = 0;
        applyOverlay();
    }

    @Override
    public void onDeactivate() {
        writeOverlay(VANILLA_HURT);
        appliedColor = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Rainbow colours change every tick.
        if (hurtColor.get() && color.get().getPacked() != appliedColor) applyOverlay();
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        Entity target = event.entity;
        if (target == null || mc.world == null) return;

        if (particles.get() && (!livingOnly.get() || target instanceof LivingEntity)) spawnParticles(target);

        if (sound.get()) {
            mc.world.playSoundClient(target.getX(), target.getY(), target.getZ(), hitSound.get().sound(), SoundCategory.PLAYERS,
                volume.get().floatValue(), pitch.get().floatValue(), false);
        }
    }

    private void spawnParticles(Entity target) {
        Box box = target.getBoundingBox();
        ParticleEffect effect = particleStyle.get().effect();

        for (int i = 0; i < amount.get(); i++) {
            double x = box.minX + random.nextDouble() * box.getLengthX();
            double y = box.minY + (0.3 + random.nextDouble() * 0.6) * box.getLengthY();
            double z = box.minZ + random.nextDouble() * box.getLengthZ();
            double vx = (random.nextDouble() - 0.5) * 0.35;
            double vy = random.nextDouble() * 0.25;
            double vz = (random.nextDouble() - 0.5) * 0.35;
            mc.world.addParticleClient(effect, x, y, z, vx, vy, vz);
        }
    }

    private void applyOverlay() {
        if (!isActive()) return;

        if (!hurtColor.get()) {
            writeOverlay(VANILLA_HURT);
            appliedColor = 0;
            return;
        }

        SettingColor c = color.get();
        // The entity shader keeps overlay.a of the original colour, so a stronger tint is a lower alpha.
        int keep = 255 - c.a;
        if (writeOverlay(keep << 24 | c.r << 16 | c.g << 8 | c.b)) appliedColor = c.getPacked();
    }

    private boolean writeOverlay(int argb) {
        if (mc.gameRenderer == null) return false;

        NativeImageBackedTexture texture = ((OverlayTextureAccessor) mc.gameRenderer.getOverlayTexture()).meteor$getTexture();
        NativeImage image = texture.getImage();
        if (image == null) return false;

        // Rows 0-7 are the hurt colour, rows 8-15 the white fuse flash (creepers), which stays untouched.
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 16; x++) image.setColorArgb(x, y, argb);
        }
        texture.upload();
        return true;
    }

    public enum ParticleStyle {
        Sparks(ParticleTypes.ELECTRIC_SPARK),
        Crit(ParticleTypes.CRIT),
        Magic(ParticleTypes.ENCHANTED_HIT),
        Flame(ParticleTypes.FLAME),
        SoulFlame(ParticleTypes.SOUL_FIRE_FLAME),
        Hearts(ParticleTypes.HEART),
        Totem(ParticleTypes.TOTEM_OF_UNDYING),
        Stars(ParticleTypes.END_ROD),
        Blood(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.getDefaultState()));

        private final ParticleEffect effect;

        ParticleStyle(ParticleEffect effect) {
            this.effect = effect;
        }

        ParticleEffect effect() {
            return effect;
        }
    }

    public enum HitSound {
        Click(SoundEvents.UI_BUTTON_CLICK.value()),
        Orb(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP),
        Bell(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value()),
        Pling(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value()),
        Crit(SoundEvents.ENTITY_PLAYER_ATTACK_CRIT),
        Amethyst(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME);

        private final SoundEvent sound;

        HitSound(SoundEvent sound) {
            this.sound = sound;
        }

        SoundEvent sound() {
            return sound;
        }
    }
}
