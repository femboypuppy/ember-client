package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.EmberAnim;
import meteordevelopment.meteorclient.utils.render.EmberIcons;
import meteordevelopment.meteorclient.utils.render.EmberHudCards;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class EmberNotificationsHud extends HudElement {
    public static final HudElementInfo<EmberNotificationsHud> INFO = new HudElementInfo<>(
        Hud.GROUP,
        "ember-notifications",
        "Custom bottom-right notifications for module toggles and client alerts.",
        EmberNotificationsHud::new
    );

    public static final Color GOOD = new Color(93, 217, 127, 255);
    public static final Color BAD = new Color(222, 86, 86, 255);
    public static final Color WARN = new Color(231, 164, 80, 255);

    public enum Kind {
        TOGGLE_ON(EmberIcons.Glyph.CHECK, "ON"),
        TOGGLE_OFF(EmberIcons.Glyph.CROSS, "OFF"),
        SUCCESS(EmberIcons.Glyph.CHECK, null),
        INFO(EmberIcons.Glyph.INFO, null),
        WARNING(EmberIcons.Glyph.WARNING, "WARN"),
        ERROR(EmberIcons.Glyph.CROSS, "ALERT");

        public final EmberIcons.Glyph glyph;
        public final String badge;

        Kind(EmberIcons.Glyph glyph, String badge) {
            this.glyph = glyph;
            this.badge = badge;
        }

        public Color resolveColor(Color custom) {
            if (custom != null) return custom;
            return switch (this) {
                case TOGGLE_ON -> EmberPalette.accent();
                case TOGGLE_OFF -> EmberPalette.textDim();
                case SUCCESS -> GOOD;
                case ERROR -> BAD;
                case WARNING -> WARN;
                case INFO -> EmberPalette.accent();
            };
        }
    }

    private static final ConcurrentLinkedQueue<Toast> INCOMING = new ConcurrentLinkedQueue<>();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the notifications.")
        .defaultValue(1.0)
        .min(0.5)
        .max(3.0)
        .sliderRange(0.5, 3.0)
        .build()
    );

    private final Setting<Double> duration = sgGeneral.add(new DoubleSetting.Builder()
        .name("duration")
        .description("Seconds each notification stays on screen.")
        .defaultValue(3.5)
        .min(0.5)
        .sliderRange(0.5, 10)
        .build()
    );

    private final Setting<Integer> maxShown = sgGeneral.add(new IntSetting.Builder()
        .name("max-shown")
        .description("Most notifications visible at once.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Boolean> moduleToggles = sgGeneral.add(new BoolSetting.Builder()
        .name("module-toggles")
        .description("Notify when a module is turned on or off.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> clientMessages = sgGeneral.add(new BoolSetting.Builder()
        .name("client-messages")
        .description("Display client and module notifications here instead of in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> glow = sgGeneral.add(new BoolSetting.Builder()
        .name("glow")
        .description("Soft glow behind each notification.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sound = sgGeneral.add(new BoolSetting.Builder()
        .name("sound")
        .description("Play an XP orb sound effect when a notification appears.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> soundVolume = sgGeneral.add(new DoubleSetting.Builder()
        .name("sound-volume")
        .description("Volume of the notification XP sound.")
        .defaultValue(0.9)
        .min(0.1)
        .max(2.0)
        .sliderRange(0.1, 2.0)
        .visible(sound::get)
        .build()
    );

    private final Setting<Double> soundPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("sound-pitch")
        .description("Base pitch of the notification XP sound.")
        .defaultValue(1.0)
        .min(0.5)
        .max(2.0)
        .sliderRange(0.5, 2.0)
        .visible(sound::get)
        .build()
    );

    private final List<Toast> toasts = new ArrayList<>();
    private final EmberAnim.Clock clock = new EmberAnim.Clock();
    private Set<Module> lastActive;
    private long lastSoundMs;

    public EmberNotificationsHud() {
        super(INFO);
    }

    public static EmberNotificationsHud getInstance() {
        Hud hud = Hud.get();
        if (hud == null) return null;
        for (HudElement element : hud) {
            if (element instanceof EmberNotificationsHud notif) return notif;
        }
        return null;
    }

    /** True when client notifications (ChatUtils / Module info, warning, error) should route here instead of chat. */
    public static boolean shouldInterceptChat() {
        EmberNotificationsHud inst = getInstance();
        return inst == null || (inst.isActive() && inst.clientMessages.get());
    }

    /** True when module toggle notifications are handled by the bottom-right notification UI. */
    public static boolean shouldInterceptToggles() {
        EmberNotificationsHud inst = getInstance();
        return inst == null || (inst.isActive() && inst.moduleToggles.get());
    }

    /** Shows a notification with a custom color. Safe to call from any thread. */
    public static void push(String title, String subtitle, Color color) {
        Kind kind = Kind.INFO;
        Color custom = color;
        if (color == GOOD) {
            kind = Kind.SUCCESS;
            custom = null;
        } else if (color == BAD) {
            kind = Kind.ERROR;
            custom = null;
        } else if (color == WARN) {
            kind = Kind.WARNING;
            custom = null;
        }
        push(0, title, subtitle, kind, custom);
    }

    public static void push(String title, String subtitle, Kind kind) {
        push(0, title, subtitle, kind, null);
    }

    public static void push(int id, String title, String subtitle, Kind kind) {
        push(id, title, subtitle, kind, null);
    }

    public static void push(int id, String title, String subtitle, Kind kind, Color customColor) {
        String safeTitle = (title == null || title.isBlank()) ? "Ember" : title.trim();
        String safeSub = (subtitle == null) ? "" : subtitle.trim();
        INCOMING.add(new Toast(id, safeTitle, safeSub, kind != null ? kind : Kind.INFO, customColor));

        // Prevent unbounded queue growth while widget is inactive.
        while (INCOMING.size() > 30) INCOMING.poll();
    }

    private static final class Toast {
        final int id;
        String title;
        String subtitle;
        Kind kind;
        Color customColor;
        int repeatCount = 1;
        double age;
        float anim;
        float pulse;
        double renderY = Double.NaN;

        Toast(int id, String title, String subtitle, Kind kind, Color customColor) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.kind = kind;
            this.customColor = customColor;
            this.pulse = 1f;
        }

        Color color() {
            return kind.resolveColor(customColor);
        }
    }

    private void playNotificationSound(Kind kind) {
        if (!sound.get() || mc == null || mc.getSoundManager() == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSoundMs < 45) return;
        lastSoundMs = now;

        float basePitch = soundPitch.get().floatValue();
        float pitch = switch (kind) {
            case TOGGLE_ON, SUCCESS -> basePitch * 1.08f;
            case TOGGLE_OFF -> basePitch * 0.84f;
            case WARNING, ERROR -> basePitch * 0.94f;
            case INFO -> basePitch;
        };
        float volume = soundVolume.get().floatValue();

        mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, pitch, volume));
    }

    private void drainIncoming() {
        Toast incoming;
        while ((incoming = INCOMING.poll()) != null) {
            boolean merged = false;

            // 1. Coalesce by explicit non-zero ID (e.g. same module toggled on/off or entity/totem ID)
            if (incoming.id != 0) {
                for (int i = toasts.size() - 1; i >= 0; i--) {
                    Toast existing = toasts.get(i);
                    if (existing.id == incoming.id) {
                        boolean sameText = Objects.equals(existing.title, incoming.title)
                            && Objects.equals(existing.subtitle, incoming.subtitle)
                            && existing.kind == incoming.kind;
                        existing.title = incoming.title;
                        existing.subtitle = incoming.subtitle;
                        existing.kind = incoming.kind;
                        existing.customColor = incoming.customColor;
                        existing.age = 0;
                        existing.pulse = 1f;
                        if (sameText) existing.repeatCount++;
                        else existing.repeatCount = 1;
                        // Move updated toast to the newest position at the bottom
                        toasts.remove(i);
                        toasts.add(existing);
                        merged = true;
                        break;
                    }
                }
            }

            // 2. Coalesce identical active notifications so spam increments a badge instead of flooding
            if (!merged) {
                for (int i = toasts.size() - 1; i >= 0; i--) {
                    Toast existing = toasts.get(i);
                    if (existing.age < duration.get()
                        && Objects.equals(existing.title, incoming.title)
                        && Objects.equals(existing.subtitle, incoming.subtitle)
                        && existing.kind == incoming.kind) {
                        existing.repeatCount++;
                        existing.age = 0;
                        existing.pulse = 1f;
                        toasts.remove(i);
                        toasts.add(existing);
                        merged = true;
                        break;
                    }
                }
            }

            if (!merged) {
                incoming.pulse = 1f;
                toasts.add(incoming);
            }

            playNotificationSound(incoming.kind);
        }
    }

    @Override
    public void render(HudRenderer renderer) {
        float dt = clock.tick();
        trackModuleToggles();
        drainIncoming();

        double maxLife = duration.get();

        // Expire oldest active toasts when over the limit so they animate out smoothly.
        int alive = 0;
        for (Toast toast : toasts) if (toast.age < maxLife) alive++;
        for (Toast toast : toasts) {
            if (alive <= maxShown.get()) break;
            if (toast.age < maxLife) {
                toast.age = maxLife;
                alive--;
            }
        }

        double s = scale.get();
        double titleScale = 0.90 * s;
        double subScale = 0.73 * s;
        double badgeScale = 0.59 * s;
        double titleH = renderer.textHeight(false, titleScale);
        double subH = renderer.textHeight(false, subScale);

        double maxW = Math.min(350 * s, Math.max(150, Utils.getWindowWidth() - 24 * s));
        double minW = Math.min(230 * s, maxW);
        double padX = 12 * s;
        double padY = 11 * s;
        double chipSize = 26 * s;
        double chipGap = 10 * s;
        double gap = 7 * s;
        double baseRowH = Math.max(chipSize + padY * 2, padY * 2 + titleH + subH * 2 + 5 * s) + 5 * s;
        double boxH = maxShown.get() * (baseRowH + gap);

        setSize(maxW, boxH);

        for (Toast toast : toasts) {
            toast.age += dt;
            float target = toast.age < maxLife ? 1f : 0f;
            toast.anim = EmberAnim.approach(toast.anim, target, dt, target > 0.5f ? 0.065 : 0.08);
            toast.pulse = EmberAnim.approach(toast.pulse, 0f, dt, 0.16);
        }
        toasts.removeIf(toast -> toast.age >= maxLife && toast.anim <= 0.01f);

        if (toasts.isEmpty()) {
            if (isInEditor()) {
                Toast preview = new Toast(0, "Notifications", "Custom bottom-right alerts & toggles", Kind.INFO, null);
                preview.anim = 1f;
                CardLayout layout = measureCard(renderer, preview, s, minW, maxW, padX, padY, chipSize, chipGap, titleScale, subScale, badgeScale, titleH, subH);
                drawToast(renderer, preview, layout, 1f, x + maxW - layout.w, y + boxH - layout.h, s, titleScale, subScale, badgeScale, titleH, subH, 0.72);
            }
            return;
        }

        // Stack from bottom to top, right-aligned to the bottom-right anchor.
        double targetY = y + boxH;
        for (int i = toasts.size() - 1; i >= 0; i--) {
            Toast toast = toasts.get(i);
            CardLayout layout = measureCard(renderer, toast, s, minW, maxW, padX, padY, chipSize, chipGap, titleScale, subScale, badgeScale, titleH, subH);

            targetY -= (layout.h + gap) * Math.min(1f, toast.anim * 1.15f);
            if (Double.isNaN(toast.renderY)) {
                toast.renderY = targetY + 6 * s;
            } else {
                toast.renderY = EmberAnim.approach(toast.renderY, targetY, dt, 0.09);
            }

            double slide = (1.0 - EmberAnim.easeOut(toast.anim)) * 24 * s;
            double cardX = x + maxW - layout.w + slide;
            double progress = Math.max(0, Math.min(1, 1.0 - toast.age / maxLife));

            drawToast(renderer, toast, layout, toast.anim, cardX, toast.renderY, s, titleScale, subScale, badgeScale, titleH, subH, progress);
        }
    }

    private record CardLayout(double w, double h, String title, List<String> lines, String badgeText, double badgeW) {}

    private CardLayout measureCard(HudRenderer renderer, Toast toast, double s, double minW, double maxW,
                                   double padX, double padY, double chipSize, double chipGap,
                                   double titleScale, double subScale, double badgeScale, double titleH, double subH) {
        String badgeText = toast.repeatCount > 1 ? "\u00d7" + toast.repeatCount : toast.kind.badge;
        double badgeW = badgeText == null ? 0 : EmberHudCards.badgeWidth(renderer, badgeText, badgeScale, s);
        double left = padX + chipSize + chipGap;
        double textLimit = Math.max(0, maxW - left - padX);
        double badgeSpace = badgeW > 0 ? badgeW + 8 * s : 0;
        String title = EmberHudCards.fit(renderer, toast.title, titleScale, textLimit - badgeSpace);
        List<String> lines = EmberHudCards.wrap(renderer, toast.subtitle, subScale, textLimit);
        double contentW = renderer.textWidth(title, false, titleScale) + badgeSpace;
        for (String line : lines) contentW = Math.max(contentW, renderer.textWidth(line, false, subScale));
        double w = Math.clamp(left + contentW + padX, minW, maxW);
        double textBlockH = titleH + (lines.isEmpty() ? 0 : 4 * s + lines.size() * subH + (lines.size() - 1) * 2 * s);
        double h = Math.max(chipSize, textBlockH) + padY * 2 + 5 * s;
        return new CardLayout(w, h, title, lines, badgeText, badgeW);
    }

    private void drawToast(HudRenderer renderer, Toast toast, CardLayout layout, float a,
                           double tx, double ty, double s,
                           double titleScale, double subScale, double badgeScale,
                           double titleH, double subH, double progress) {
        if (a <= 0.01f) return;
        double w = layout.w, h = layout.h;
        double pad = 12 * s, chipSize = 26 * s;
        Color accent = EmberPalette.accent(), status = toast.color();
        EmberHudCards.surface(renderer, tx, ty, w, h, s, a, glow.get());

        double chipX = tx + pad, chipY = ty + (h - 5 * s - chipSize) / 2;
        EmberHudCards.icon(renderer, toast.kind.glyph, chipX, chipY, chipSize, status, a);
        double textX = chipX + chipSize + 10 * s;
        double textH = titleH + (layout.lines.isEmpty() ? 0 : 4 * s + layout.lines.size() * subH + (layout.lines.size() - 1) * 2 * s);
        double textY = ty + (h - 5 * s - textH) / 2;
        renderer.text(layout.title, textX, textY, EmberHudCards.alpha(EmberPalette.textBright(), a), false, titleScale);
        if (layout.badgeText != null) {
            double badgeH = renderer.textHeight(false, badgeScale) + 6 * s;
            EmberHudCards.badge(renderer, layout.badgeText, tx + w - pad - layout.badgeW,
                textY + (titleH - badgeH) / 2, badgeScale, s, status, a);
        }
        double subY = textY + titleH + 4 * s;
        for (String line : layout.lines) {
            renderer.text(line, textX, subY, EmberHudCards.alpha(EmberPalette.textDim(), a), false, subScale);
            subY += subH + 2 * s;
        }

        double trackX = tx + pad, trackW = w - pad * 2, trackY = ty + h - 5 * s, trackH = Math.max(1, 1.4 * s);
        renderer.roundedQuad(trackX, trackY, trackW, trackH, trackH / 2, new Color(255, 255, 255, (int) (18 * a)));
        double barW = trackW * progress;
        if (barW > 0.5) renderer.roundedQuad(trackX, trackY, barW, trackH, trackH / 2,
            EmberHudCards.alpha(accent, a * (0.55 + toast.pulse * 0.2)));
    }

    private void trackModuleToggles() {
        Modules modules = Modules.get();
        if (modules == null) return;

        Set<Module> now = new HashSet<>(modules.getActive());

        if (lastActive != null && moduleToggles.get()) {
            for (Module module : now) {
                if (!lastActive.contains(module)) {
                    push(module.hashCode(), module.title, "Module enabled", Kind.TOGGLE_ON);
                }
            }
            for (Module module : lastActive) {
                if (!now.contains(module)) {
                    push(module.hashCode(), module.title, "Module disabled", Kind.TOGGLE_OFF);
                }
            }
        }

        lastActive = now;
    }

}
