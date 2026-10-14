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
import meteordevelopment.meteorclient.utils.render.EmberStrip;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

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
                case TOGGLE_ON, SUCCESS -> GOOD;
                case TOGGLE_OFF, ERROR -> BAD;
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
        .defaultValue(3.0)
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

    private final List<Toast> toasts = new ArrayList<>();
    private final EmberAnim.Clock clock = new EmberAnim.Clock();
    private Set<Module> lastActive;

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
        }

        Color color() {
            return kind.resolveColor(customColor);
        }
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
                toasts.add(incoming);
            }
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
        double titleScale = 0.88 * s;
        double subScale = 0.74 * s;
        double badgeScale = 0.62 * s;
        double titleH = renderer.textHeight(true, titleScale);
        double subH = renderer.textHeight(true, subScale);

        double minW = 195 * s;
        double maxW = 305 * s;
        double padX = 10 * s;
        double padY = 8.5 * s;
        double chipSize = 22 * s;
        double chipGap = 8.5 * s;
        double gap = 6 * s;
        double baseRowH = Math.max(chipSize + padY * 2, padY * 2 + titleH + subH + 3 * s) + 3 * s;
        double boxH = maxShown.get() * (baseRowH + gap);

        setSize(maxW, boxH);

        for (Toast toast : toasts) {
            toast.age += dt;
            float target = toast.age < maxLife ? 1f : 0f;
            toast.anim = EmberAnim.approach(toast.anim, target, dt, target > 0.5f ? 0.065 : 0.08);
            toast.pulse = EmberAnim.approach(toast.pulse, 0f, dt, 0.12);
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
                toast.renderY = targetY + 10 * s;
            } else {
                toast.renderY = EmberAnim.approach(toast.renderY, targetY, dt, 0.055);
            }

            boolean entering = toast.age < maxLife;
            double eased = entering ? EmberAnim.backOut(Math.min(1.0, toast.anim)) : EmberAnim.easeOut(toast.anim);
            double slide = (1.0 - eased) * 52 * s;
            double cardX = x + maxW - layout.w + slide;
            double progress = Math.max(0, Math.min(1, 1.0 - toast.age / maxLife));

            drawToast(renderer, toast, layout, toast.anim, cardX, toast.renderY, s, titleScale, subScale, badgeScale, titleH, subH, progress);
        }
    }

    private record CardLayout(double w, double h, List<String> lines, String badgeText, double badgeW) {}

    private CardLayout measureCard(HudRenderer renderer, Toast toast, double s, double minW, double maxW,
                                   double padX, double padY, double chipSize, double chipGap,
                                   double titleScale, double subScale, double badgeScale, double titleH, double subH) {
        String badgeText = toast.repeatCount > 1 ? "x" + toast.repeatCount : toast.kind.badge;
        double badgeW = badgeText != null ? renderer.textWidth(badgeText, true, badgeScale) + 10 * s : 0;

        double leftContentOffset = padX + 4 * s + chipSize + chipGap;
        double maxTextW = maxW - leftContentOffset - padX;

        List<String> lines = wrapSubtitle(renderer, toast.subtitle, maxTextW, subScale);
        double longestSubW = 0;
        for (String line : lines) {
            longestSubW = Math.max(longestSubW, renderer.textWidth(line, true, subScale));
        }

        double titleRowW = renderer.textWidth(toast.title, true, titleScale) + (badgeW > 0 ? badgeW + 8 * s : 0);
        double contentW = Math.max(titleRowW, longestSubW);
        double w = Math.max(minW, Math.min(maxW, leftContentOffset + contentW + padX));

        int lineCount = Math.max(1, lines.size());
        double textBlockH = titleH + 2.5 * s + lineCount * subH + (lineCount - 1) * (1.5 * s);
        double h = Math.max(chipSize + padY * 2, padY * 2 + textBlockH) + 3.5 * s;

        return new CardLayout(w, h, lines, badgeText, badgeW);
    }

    private List<String> wrapSubtitle(HudRenderer renderer, String text, double maxW, double scale) {
        List<String> out = new ArrayList<>(2);
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }

        if (renderer.textWidth(text, true, scale) <= maxW) {
            out.add(text);
            return out;
        }

        String[] words = text.split("\\s+");
        StringBuilder line1 = new StringBuilder();
        int idx = 0;

        while (idx < words.length) {
            String candidate = line1.isEmpty() ? words[idx] : line1 + " " + words[idx];
            if (renderer.textWidth(candidate, true, scale) <= maxW || line1.isEmpty()) {
                line1.setLength(0);
                line1.append(candidate);
                idx++;
            } else {
                break;
            }
        }
        out.add(line1.toString());

        if (idx < words.length) {
            StringBuilder line2 = new StringBuilder();
            while (idx < words.length) {
                if (!line2.isEmpty()) line2.append(' ');
                line2.append(words[idx++]);
            }
            String second = line2.toString();
            while (second.length() > 4 && renderer.textWidth(second + "...", true, scale) > maxW) {
                second = second.substring(0, second.length() - 1).trim();
            }
            if (second.length() < line2.length()) second = second + "...";
            out.add(second);
        }

        return out;
    }

    private void drawToast(HudRenderer renderer, Toast toast, CardLayout layout, float a,
                           double tx, double ty, double s,
                           double titleScale, double subScale, double badgeScale,
                           double titleH, double subH, double progress) {
        if (a <= 0.01f) return;

        double w = layout.w;
        double h = layout.h;
        double padX = 10 * s;
        double padY = 8.5 * s;
        double chipSize = 22 * s;
        double chipGap = 8.5 * s;
        double radius = 8 * s;

        Color color = toast.color();
        Color bright = new Color(255, 255, 255, (int) (255 * a));
        Color dim = new Color(165, 165, 178, (int) (245 * a));
        Color bg = EmberStrip.background();

        if (glow.get()) {
            int glowAlpha = (int) ((135 + 60 * toast.pulse) * a);
            renderer.softGlow(tx, ty, w, h, radius, 9 * s, withAlpha(color, glowAlpha));
        }

        renderer.dropShadow(tx, ty, w, h, radius, 20 * s, a);
        EmberStrip.panel(renderer, tx, ty, w, h, radius, a);

        // Left vertical accent bar
        double barH = h - 14 * s;
        renderer.roundedQuad(tx + 3.5 * s, ty + 6 * s, 2.5 * s, barH, 1.25 * s, withAlpha(color, (int) (245 * a)));

        // Rounded icon chip with tinted background and procedural glyph
        double chipX = tx + padX + 2 * s;
        double chipY = ty + (h - 3 * s - chipSize) / 2;
        double chipRadius = 6 * s;
        renderer.roundedQuad(chipX - 0.8 * s, chipY - 0.8 * s, chipSize + 1.6 * s, chipSize + 1.6 * s, chipRadius + 0.8 * s, withAlpha(color, (int) (75 * a)));
        renderer.roundedQuad(chipX, chipY, chipSize, chipSize, chipRadius, new Color(
            (bg.r * 4 + color.r) / 5,
            (bg.g * 4 + color.g) / 5,
            (bg.b * 4 + color.b) / 5,
            (int) (235 * a)
        ));

        double iconSize = 11.5 * s;
        EmberIcons.draw(
            renderer,
            toast.kind.glyph,
            chipX + (chipSize - iconSize) / 2,
            chipY + (chipSize - iconSize) / 2,
            iconSize,
            withAlpha(color, (int) (255 * a)),
            bg
        );

        // Title and optional right status/repeat pill badge
        double textX = chipX + chipSize + chipGap;
        double textTopY = ty + padY;
        renderer.text(toast.title, textX, textTopY, bright, true, titleScale);

        if (layout.badgeText != null) {
            double badgeH = renderer.textHeight(true, badgeScale) + 3.5 * s;
            double badgeX = tx + w - padX - layout.badgeW;
            double badgeY = textTopY + (titleH - badgeH) / 2;
            renderer.roundedQuad(badgeX, badgeY, layout.badgeW, badgeH, badgeH / 2, withAlpha(color, (int) (42 * a)));
            double btW = renderer.textWidth(layout.badgeText, true, badgeScale);
            double btH = renderer.textHeight(true, badgeScale);
            renderer.text(
                layout.badgeText,
                badgeX + (layout.badgeW - btW) / 2,
                badgeY + (badgeH - btH) / 2,
                withAlpha(color, (int) (245 * a)),
                true,
                badgeScale
            );
        }

        // Subtitle lines
        double subY = textTopY + titleH + 2.5 * s;
        for (String line : layout.lines) {
            if (!line.isEmpty()) {
                renderer.text(line, textX, subY, dim, true, subScale);
            }
            subY += subH + 1.5 * s;
        }

        // Recessed bottom progress track + smooth draining bar
        double trackX = tx + 8 * s;
        double trackW = w - 16 * s;
        double trackY = ty + h - 4.2 * s;
        double trackH = 2.0 * s;
        renderer.roundedQuad(trackX, trackY, trackW, trackH, trackH / 2, new Color(0, 0, 0, (int) (130 * a)));

        double barW = trackW * progress;
        if (barW > 1.5) {
            renderer.roundedQuad(trackX, trackY, barW, trackH, trackH / 2, withAlpha(color, (int) (210 * a)));
        }
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

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.r, c.g, c.b, Math.max(0, Math.min(255, alpha)));
    }
}
