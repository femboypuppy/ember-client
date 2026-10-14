/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.player;

import com.mojang.brigadier.StringReader;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.mixininterface.IChatHud;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.hud.elements.EmberNotificationsHud;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.meteorclient.utils.misc.text.MeteorClickEvent;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.Pair;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ChatUtils {
    private static final List<Pair<String, Supplier<Text>>> customPrefixes = new ArrayList<>();
    private static String forcedPrefixClassName;

    private static Text PREFIX;

    private ChatUtils() {
    }

    @PostInit
    public static void init() {
        PREFIX = Text.empty()
            .setStyle(Style.EMPTY.withFormatting(Formatting.GRAY))
            .append("[")
            .append(Text.literal("Meteor").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(MeteorClient.ADDON.color.getPacked()))))
            .append("] ");
    }

    public static Text getMeteorPrefix() {
        return PREFIX;
    }

    /**
     * Registers a custom prefix to be used when calling from a class in the specified package. When null is returned from the supplier the default Meteor prefix is used.
     */
    @SuppressWarnings("unused")
    public static void registerCustomPrefix(String packageName, Supplier<Text> supplier) {
        for (Pair<String, Supplier<Text>> pair : customPrefixes) {
            if (pair.getLeft().equals(packageName)) {
                pair.setRight(supplier);
                return;
            }
        }

        customPrefixes.add(new Pair<>(packageName, supplier));
    }

    /**
     * The package name must match exactly to the one provided through {@link #registerCustomPrefix(String, Supplier)}.
     */
    @SuppressWarnings("unused")
    public static void unregisterCustomPrefix(String packageName) {
        customPrefixes.removeIf(pair -> pair.getLeft().equals(packageName));
    }

    public static void forceNextPrefixClass(Class<?> klass) {
        forcedPrefixClassName = klass.getName();
    }

    // Player

    /**
     * Sends the message as if the user typed it into chat and adds it to the chat history.
     */
    public static void sendPlayerMsg(String message) {
        sendPlayerMsg(message, true);
    }

    /**
     * Sends the message as if the user typed it into chat.
     */
    public static void sendPlayerMsg(String message, boolean addToHistory) {
        if (addToHistory) mc.inGameHud.getChatHud().addToMessageHistory(message);

        if (message.startsWith("/")) mc.player.networkHandler.sendChatCommand(message.substring(1));
        else mc.player.networkHandler.sendChatMessage(message);
    }

    // Default

    public static void info(String message, Object... args) {
        sendMsg(Formatting.GRAY, message, args);
    }

    public static void infoPrefix(String prefix, String message, Object... args) {
        sendMsg(0, prefix, Formatting.LIGHT_PURPLE, Formatting.GRAY, message, args);
    }

    // Warning

    public static void warning(String message, Object... args) {
        sendMsg(Formatting.YELLOW, message, args);
    }

    public static void warningPrefix(String prefix, String message, Object... args) {
        sendMsg(0, prefix, Formatting.LIGHT_PURPLE, Formatting.YELLOW, message, args);
    }

    // Error

    public static void error(String message, Object... args) {
        sendMsg(Formatting.RED, message, args);
    }

    public static void errorPrefix(String prefix, String message, Object... args) {
        sendMsg(0, prefix, Formatting.LIGHT_PURPLE, Formatting.RED, message, args);
    }

    // Misc

    public static void sendMsg(Text message) {
        sendMsg(null, message);
    }

    public static void sendMsg(String prefix, Text message) {
        sendMsg(0, prefix, Formatting.LIGHT_PURPLE, message);
    }

    public static void sendMsg(Formatting color, String message, Object... args) {
        sendMsg(0, null, null, color, message, args);
    }

    public static void sendMsg(int id, Formatting color, String message, Object... args) {
        sendMsg(id, null, null, color, message, args);
    }

    public static void sendMsg(int id, @Nullable String prefixTitle, @Nullable Formatting prefixColor, Formatting messageColor, String messageContent, Object... args) {
        MutableText message = formatMsg(String.format(messageContent, args), messageColor);
        sendMsg(id, prefixTitle, prefixColor, message);
    }

    public static void sendMsg(int id, @Nullable String prefixTitle, @Nullable Formatting prefixColor, String messageContent, Formatting messageColor) {
        MutableText message = formatMsg(messageContent, messageColor);
        sendMsg(id, prefixTitle, prefixColor, message);
    }

    public static void sendMsg(int id, @Nullable String prefixTitle, @Nullable Formatting prefixColor, Text msg) {
        if (EmberNotificationsHud.shouldInterceptChat()) {
            String title = resolveNotificationTitle(prefixTitle);
            EmberNotificationsHud.Kind kind = resolveNotificationKind(msg);
            String body = cleanNotificationText(msg.getString());
            if (!body.isEmpty()) {
                EmberNotificationsHud.push(id, title, body, kind);
            }
            return;
        }

        if (mc.world == null) return;

        MutableText message = Text.empty();
        message.append(getPrefix());
        if (prefixTitle != null) message.append(getCustomPrefix(prefixTitle, prefixColor));
        message.append(msg);

        if (!Config.get().deleteChatFeedback.get()) id = 0;

        final int finalId = id; // Intellij copes about using non-final args in lambdas
        mc.execute(() -> ((IChatHud) mc.inGameHud.getChatHud()).meteor$add(message, finalId));
    }

    private static String resolveNotificationTitle(@Nullable String prefixTitle) {
        String callerClass = resolveCallerClass();
        if (prefixTitle != null && !prefixTitle.isBlank()) return prefixTitle;

        if (callerClass != null) {
            Modules modules = Modules.get();
            if (modules != null) {
                for (Module module : modules.getAll()) {
                    String mClass = module.getClass().getName();
                    if (callerClass.equals(mClass) || callerClass.startsWith(mClass + "$")) {
                        return module.title;
                    }
                }
            }

            for (Pair<String, Supplier<Text>> pair : customPrefixes) {
                if (callerClass.startsWith(pair.getLeft())) {
                    Text prefix = pair.getRight().get();
                    if (prefix != null) {
                        String raw = cleanNotificationText(prefix.getString())
                            .replace("[", "")
                            .replace("]", "")
                            .trim();
                        if (!raw.isEmpty()) return raw;
                    }
                }
            }
        }

        return "Ember";
    }

    private static @Nullable String resolveCallerClass() {
        if (forcedPrefixClassName != null) {
            String cls = forcedPrefixClassName;
            forcedPrefixClassName = null;
            return cls;
        }

        boolean foundChatUtils = false;
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String name = element.getClassName();
            if (foundChatUtils) {
                if (!name.equals(ChatUtils.class.getName()) && !name.equals(Module.class.getName())) {
                    return name;
                }
            } else if (name.equals(ChatUtils.class.getName())) {
                foundChatUtils = true;
            }
        }
        return null;
    }

    private static EmberNotificationsHud.Kind resolveNotificationKind(Text msg) {
        Integer rgb = extractColorRgb(msg);
        if (rgb != null) {
            if (rgb.equals(Formatting.RED.getColorValue())) return EmberNotificationsHud.Kind.ERROR;
            if (rgb.equals(Formatting.YELLOW.getColorValue()) || rgb.equals(Formatting.GOLD.getColorValue())) return EmberNotificationsHud.Kind.WARNING;
            if (rgb.equals(Formatting.GREEN.getColorValue())) return EmberNotificationsHud.Kind.SUCCESS;
        }

        String raw = msg.getString();
        String cleaned = cleanNotificationText(raw);
        if (cleaned.startsWith("[+]") || cleaned.endsWith(" joined.")) return EmberNotificationsHud.Kind.SUCCESS;
        if (cleaned.startsWith("[-]") || cleaned.endsWith(" left.")) return EmberNotificationsHud.Kind.WARNING;
        if (cleaned.contains(" died after popping ")) return EmberNotificationsHud.Kind.ERROR;
        if (cleaned.contains(" popped ") && cleaned.contains(" totem")) return EmberNotificationsHud.Kind.WARNING;

        return EmberNotificationsHud.Kind.INFO;
    }

    private static @Nullable Integer extractColorRgb(Text msg) {
        TextColor direct = msg.getStyle().getColor();
        Integer whiteRgb = Formatting.WHITE.getColorValue();
        if (direct != null && !Objects.equals(direct.getRgb(), whiteRgb)) {
            return direct.getRgb();
        }
        for (Text sibling : msg.getSiblings()) {
            TextColor sc = sibling.getStyle().getColor();
            if (sc != null && !Objects.equals(sc.getRgb(), whiteRgb)) {
                return sc.getRgb();
            }
        }
        return direct != null ? direct.getRgb() : null;
    }

    private static String cleanNotificationText(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("(?i)§[0-9A-FK-OR]", "")
            .replace("(highlight)", "")
            .replace("(default)", "")
            .replace("(bold)", "")
            .replace("(underline)", "")
            .trim();
        if (s.startsWith("[Meteor]")) s = s.substring("[Meteor]".length()).trim();
        return s;
    }

    private static MutableText getCustomPrefix(String prefixTitle, Formatting prefixColor) {
        MutableText prefix = Text.empty();
        prefix.setStyle(prefix.getStyle().withFormatting(Formatting.GRAY));

        prefix.append("[");

        MutableText moduleTitle = Text.literal(prefixTitle);
        moduleTitle.setStyle(moduleTitle.getStyle().withFormatting(prefixColor));
        prefix.append(moduleTitle);

        prefix.append("] ");

        return prefix;
    }

    private static Text getPrefix() {
        if (customPrefixes.isEmpty()) {
            forcedPrefixClassName = null;
            return PREFIX;
        }

        boolean foundChatUtils = false;
        String className = null;

        if (forcedPrefixClassName != null) {
            className = forcedPrefixClassName;
            forcedPrefixClassName = null;
        } else {
            for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                if (foundChatUtils) {
                    if (!element.getClassName().equals(ChatUtils.class.getName())) {
                        className = element.getClassName();
                        break;
                    }
                } else {
                    if (element.getClassName().equals(ChatUtils.class.getName())) foundChatUtils = true;
                }
            }
        }

        if (className == null) return PREFIX;

        for (Pair<String, Supplier<Text>> pair : customPrefixes) {
            if (className.startsWith(pair.getLeft())) {
                Text prefix = pair.getRight().get();
                return prefix != null ? prefix : PREFIX;
            }
        }

        return PREFIX;
    }

    private static MutableText formatMsg(String message, Formatting defaultColor) {
        StringReader reader = new StringReader(message);
        MutableText text = Text.empty();
        Style style = Style.EMPTY.withFormatting(defaultColor);
        StringBuilder result = new StringBuilder();
        boolean formatting = false;
        while (reader.canRead()) {
            char c = reader.read();
            if (c == '(') {
                text.append(Text.literal(result.toString()).setStyle(style));
                result.setLength(0);
                result.append(c);
                formatting = true;
            } else {
                result.append(c);

                if (formatting && c == ')') {
                    switch (result.toString()) {
                        case "(default)" -> {
                            style = style.withFormatting(defaultColor);
                            result.setLength(0);
                        }
                        case "(highlight)" -> {
                            style = style.withFormatting(Formatting.WHITE);
                            result.setLength(0);
                        }
                        case "(underline)" -> {
                            style = style.withFormatting(Formatting.UNDERLINE);
                            result.setLength(0);
                        }
                        case "(bold)" -> {
                            style = style.withFormatting(Formatting.BOLD);
                            result.setLength(0);
                        }
                    }
                    formatting = false;
                }
            }
        }

        if (!result.isEmpty()) text.append(Text.literal(result.toString()).setStyle(style));

        return text;
    }

    public static MutableText formatCoords(Vec3d pos) {
        String coordsString = String.format("(highlight)(underline)%.0f, %.0f, %.0f(default)", pos.x, pos.y, pos.z);
        MutableText coordsText = formatMsg(coordsString, Formatting.GRAY);

        if (BaritoneUtils.IS_AVAILABLE) {
            Style style = coordsText.getStyle().withFormatting(Formatting.BOLD)
                .withHoverEvent(new HoverEvent.ShowText(
                    Text.literal("Set as Baritone goal")
                ))
                .withClickEvent(new MeteorClickEvent(
                    String.format("%sgoto %d %d %d", BaritoneUtils.getPrefix(), (int) pos.x, (int) pos.y, (int) pos.z)
                ));

            coordsText.setStyle(style);
        }

        return coordsText;
    }
}
