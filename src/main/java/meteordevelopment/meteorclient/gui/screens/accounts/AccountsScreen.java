/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens.accounts;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.screens.EmberUI;
import meteordevelopment.meteorclient.systems.accounts.*;
import meteordevelopment.meteorclient.systems.accounts.Account;
import meteordevelopment.meteorclient.systems.accounts.types.*;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.render.EmberAnim;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.util.Util;
import net.minecraft.util.Uuids;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static meteordevelopment.meteorclient.utils.Utils.*;
import static org.lwjgl.glfw.GLFW.*;

/** Glass account library. Credentials never appear in rows, status messages or recorded labels. */
public class AccountsScreen extends WidgetScreen {
    private record Hit(String id, double x, double y, double w, double h, Runnable action) {
        boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
    }
    private final GuiRenderer renderer = new GuiRenderer();
    private final EmberAnim.Clock clock = new EmberAnim.Clock();
    private final List<Hit> hits = new ArrayList<>();
    private Account<?> selected;
    private AccountType adding = AccountType.Microsoft;
    private String query = "", value = "", field = "", focus = "", status = "Choose an account, or add another.", browserUrl = "";
    private boolean modal, busy, browser, error, selectAll, confirmRemove;
    private volatile long request;
    private float fade;
    private double px, py, pw, ph, unit, mx, my, scroll, listX, listY, listW, listH, detailScroll;

    public AccountsScreen(GuiTheme theme) { super(theme, "Accounts"); }
    @Override public void initWidgets() { clear(); }

    private static String typeName(AccountType type) {
        return switch (type) { case Cracked -> "Offline"; case Microsoft -> "Microsoft"; case Session -> "Session"; case TheAltening -> "Altening"; };
    }
    private static String name(Account<?> account) {
        String cached = account.getCache().username;
        if (cached != null && !cached.isBlank()) return cached;
        // Unverified online accounts use their credential as the backing name.
        return account.getType() == AccountType.Cracked ? account.getUsername() : typeName(account.getType()) + " account";
    }
    private static boolean current(Account<?> account) {
        var uuid = mc.getSession().getUuidOrNull();
        if (uuid == null) return false;
        String cached = account.getCache().uuid;
        if (cached != null && !cached.isBlank()) return cached.replace("-", "").equalsIgnoreCase(uuid.toString().replace("-", ""));
        return account.getType() == AccountType.Cracked && Uuids.getOfflinePlayerUuid(name(account)).equals(uuid);
    }
    private List<Account<?>> accounts() {
        List<Account<?>> result = new ArrayList<>();
        for (Account<?> account : Accounts.get()) if (name(account).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))
            || typeName(account.getType()).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) result.add(account);
        result.sort(Comparator.<Account<?>, Boolean>comparing(a -> !current(a)).thenComparing(a -> name(a).toLowerCase(Locale.ROOT)));
        return result;
    }

    @Override public void mouseMoved(double x, double y) {
        double scale = mc.getWindow().getScaleFactor(); mx = x * scale; my = y * scale; super.mouseMoved(x, y);
    }
    @Override protected void onRenderBefore(DrawContext context, float delta) {
        fade = EmberAnim.approach(fade, 1, clock.tick(), .10);
        unit = Math.clamp(Math.min(getWindowWidth() / 1100.0, getWindowHeight() / 700.0), .65, 1.25);
        pw = Math.min(940 * unit, getWindowWidth() - 24); ph = Math.min(570 * unit, getWindowHeight() - 24);
        px = (getWindowWidth() - pw) / 2; py = (getWindowHeight() - ph) / 2;
        hits.clear(); renderer.theme = theme; renderer.begin(context);
        renderer.quad(0, 0, getWindowWidth(), getWindowHeight(), new Color(0, 0, 0, (int) (90 * fade)));
        pane(px, py, pw, ph, 18 * unit);
        text("YOUR IDENTITIES", px + 24 * unit, py + 21 * unit, .62, EmberUI.TEXT_DIM, pw - 120 * unit);
        text("Accounts", px + 24 * unit, py + 43 * unit, 1.4, EmberUI.TEXT, pw - 190 * unit);
        button("close", px + pw - 60 * unit, py + 20 * unit, 36 * unit, 30 * unit, "X", this::close, false, !busy || browser);
        text("Playing as " + mc.getSession().getUsername(), px + 24 * unit, py + 80 * unit, .76, EmberUI.TEXT_DIM, pw - 210 * unit);
        button("add", px + pw - 178 * unit, py + 67 * unit, 154 * unit, 34 * unit, "+ Add account", this::openAdd, true, !busy);
        listX = px + 24 * unit; listW = (pw - 64 * unit) * .48; listY = py + 161 * unit; listH = Math.max(30 * unit, ph - 213 * unit);
        input("search", listX, py + 118 * unit, listW, query, "Search accounts...", false);
        var list = accounts(); if (selected == null && !list.isEmpty()) selected = list.getFirst();
        scroll = Math.clamp(scroll, 0, Math.max(0, list.size() * 70 * unit - listH));
        renderer.roundedRect(listX, listY, listW, listH, 10 * unit, new Color(0, 0, 0, 24));
        renderer.scissorStart(listX, listY, listW, listH);
        for (int i = 0; i < list.size(); i++) {
            var account = list.get(i); double y = listY + i * 70 * unit - scroll;
            if (y + 64 * unit <= listY || y >= listY + listH) continue;
            renderer.roundedRect(listX, y, listW, 64 * unit, 10 * unit, selected == account ? EmberUI.accent(42) : EmberUI.raised());
            if (inside(listX, y, listW, 64 * unit)) EmberUI.hoverFill(renderer, listX, y, listW, 64 * unit, 1);
            head(account, listX + 12 * unit, y + 13 * unit, 38 * unit);
            text(name(account), listX + 64 * unit, y + 12 * unit, .88, EmberUI.TEXT, listW - 143 * unit);
            text(typeName(account.getType()), listX + 64 * unit, y + 37 * unit, .67, EmberUI.TEXT_DIM, listW - 90 * unit);
            if (current(account)) badge("Current", listX + listW - 73 * unit, y + 12 * unit, 64 * unit);
            hit("account-" + i, listX, Math.max(y, listY), listW, Math.min(y + 64 * unit, listY + listH) - Math.max(y, listY),
                () -> { if (!busy) { selected = account; confirmRemove = false; detailScroll = 0; focus = "switch"; field = ""; } });
        }
        if (list.isEmpty()) {
            text(Accounts.get().size() == 0 ? "A fresh start." : "No matching accounts", listX + 16 * unit, listY + 22 * unit, .95, EmberUI.TEXT, listW - 32 * unit);
            text(Accounts.get().size() == 0 ? "Add your first account to switch here." : "Try a name or account type.", listX + 16 * unit, listY + 52 * unit, .69, EmberUI.TEXT_DIM, listW - 32 * unit);
        }
        renderer.scissorEnd();
        double dx = listX + listW + 16 * unit, dw = px + pw - 24 * unit - dx, top = py + 118 * unit;
        renderer.roundedRect(dx, top, dw, listY + listH - top, 12 * unit, EmberUI.raised());
        renderer.scissorStart(dx, top, dw, listY + listH - top);
        detailScroll = Math.clamp(detailScroll, 0, Math.max(0, 345 * unit - (listY + listH - top)));
        details(dx, top - detailScroll, dw, top, listY + listH); renderer.scissorEnd();
        text((busy ? "Working... " : "") + status, px + 24 * unit, py + ph - 31 * unit, .70,
            error ? new Color(255, 153, 151, 255) : EmberUI.TEXT_DIM, pw - 48 * unit);
        if (modal) { renderer.end(); renderer.begin(context); hits.clear(); addSheet(); } renderer.end();
    }
    private void details(double x, double y, double w, double top, double bottom) {
        if (selected == null) {
            text("Ready for your next session", x + 22 * unit, y + 26 * unit, .98, EmberUI.TEXT, w - 44 * unit);
            text("Saved accounts appear here.", x + 22 * unit, y + 58 * unit, .74, EmberUI.TEXT_DIM, w - 44 * unit); return;
        }
        var account = selected; head(account, x + 22 * unit, y + 24 * unit, 64 * unit);
        text("SELECTED ACCOUNT", x + 102 * unit, y + 27 * unit, .60, EmberUI.TEXT_DIM, w - 122 * unit);
        text(name(account), x + 102 * unit, y + 48 * unit, .98, EmberUI.TEXT, w - 122 * unit);
        text(typeName(account.getType()), x + 22 * unit, y + 110 * unit, .77, EmberUI.TEXT_DIM, w - 44 * unit);
        text(current(account) ? "You are playing with this account." : "Switch before joining your next server.", x + 22 * unit, y + 141 * unit, .72, EmberUI.TEXT_DIM, w - 44 * unit);
        text("PLAYER UUID", x + 22 * unit, y + 177 * unit, .59, EmberUI.TEXT_DIM, w - 44 * unit);
        String uuid = account.getCache().uuid;
        text(uuid == null || uuid.isBlank() ? "Available after online sign-in" : uuid, x + 22 * unit, y + 198 * unit, .68, EmberUI.TEXT_DIM, w - 44 * unit);
        clippedButton("switch", x + 22 * unit, y + 235 * unit, w - 44 * unit, 36 * unit, current(account) ? "Currently playing" : "Switch account",
            () -> signIn(account, false), true, !busy && !current(account), top, bottom);
        double bw = (w - 52 * unit) / 2;
        if (account instanceof TokenAccount token) clippedButton("copy-token", x + 22 * unit, y + 281 * unit, bw, 32 * unit, "Copy token",
            () -> { mc.keyboard.setClipboard(token.getToken()); message("Token copied.", false); }, false, !busy, top, bottom);
        clippedButton("remove", x + w - 22 * unit - bw, y + 281 * unit, bw, 32 * unit, confirmRemove ? "Confirm remove" : "Remove", this::removeSelected, false, !busy, top, bottom);
    }
    private void openAdd() { modal = true; value = ""; field = ""; focus = "type-Microsoft"; confirmRemove = false; error = false; }
    private void addSheet() {
        double savedUnit = unit;
        unit = Math.min(unit, (ph - 16 * unit) / 414);
        double w = Math.min(520 * unit, pw - 24 * unit), h = 414 * unit;
        double x = (getWindowWidth() - w) / 2, y = (getWindowHeight() - h) / 2;
        renderer.quad(0, 0, getWindowWidth(), getWindowHeight(), new Color(0, 0, 0, 155)); pane(x, y, w, h, 16 * unit);
        text("Add an account", x + 22 * unit, y + 20 * unit, 1.17, EmberUI.TEXT, w - 80 * unit);
        button("cancel", x + w - 58 * unit, y + 16 * unit, 36 * unit, 30 * unit, "X", this::cancelSheet, false, !busy || browser);
        AccountType[] types = {AccountType.Microsoft, AccountType.Cracked, AccountType.Session, AccountType.TheAltening}; double bw = (w - 52 * unit) / 2;
        for (int i = 0; i < types.length; i++) {
            var type = types[i]; button("type-" + type, x + 22 * unit + i % 2 * (bw + 8 * unit), y + (65 + i / 2 * 40) * unit,
                bw, 32 * unit, typeName(type), () -> { adding = type; value = ""; field = type == AccountType.Microsoft ? "" : "credential"; selectAll = false; }, adding == type, !busy);
        }
        if (adding == AccountType.Microsoft) {
            text(browser ? "Finish sign-in in your browser" : "Sign in with Microsoft", x + 22 * unit, y + 163 * unit, .93, EmberUI.TEXT, w - 44 * unit);
            text("Choose your Minecraft account in the browser.", x + 22 * unit, y + 194 * unit, .70, EmberUI.TEXT_DIM, w - 44 * unit);
            text("Your password stays with Microsoft.", x + 22 * unit, y + 216 * unit, .70, EmberUI.TEXT_DIM, w - 44 * unit);
            if (browser) {
                button("open-browser", x + 22 * unit, y + 253 * unit, bw, 35 * unit, "Open browser", () -> Util.getOperatingSystem().open(browserUrl), false, !browserUrl.isEmpty());
                button("copy-link", x + 30 * unit + bw, y + 253 * unit, bw, 35 * unit, "Copy link", () -> { mc.keyboard.setClipboard(browserUrl); message("Sign-in link copied.", false); }, false, !browserUrl.isEmpty());
            } else button("submit", x + 22 * unit, y + 253 * unit, w - 44 * unit, 35 * unit, "Continue in browser", this::startMicrosoft, true, !busy);
        } else {
            boolean offline = adding == AccountType.Cracked;
            text(offline ? "Minecraft username" : "Access token", x + 22 * unit, y + 163 * unit, .83, EmberUI.TEXT, w - 44 * unit);
            input("credential", x + 22 * unit, y + 193 * unit, w - 44 * unit, value, offline ? "Player name" : "Paste your token", !offline);
            text(offline ? "For offline-mode servers." : adding == AccountType.Session ? "Use an existing Minecraft access token." : "Use your Altening account token.", x + 22 * unit, y + 236 * unit, .69, EmberUI.TEXT_DIM, w - 44 * unit);
            button("submit", x + 22 * unit, y + 269 * unit, w - 44 * unit, 35 * unit, busy ? "Signing in..." : "Add & switch", this::submit, true, !busy);
        }
        text(status, x + 22 * unit, y + h - 70 * unit, .67, error ? new Color(255, 153, 151, 255) : EmberUI.TEXT_DIM, w - 44 * unit);
        button("back", x + 22 * unit, y + h - 42 * unit, w - 44 * unit, 28 * unit, browser ? "Cancel sign-in" : "Back to accounts", this::cancelSheet, false, !busy || browser);
        unit = savedUnit;
    }
    private void submit() {
        if (busy) return; String clean = value.strip();
        if (adding == AccountType.Cracked && !clean.matches("[A-Za-z0-9_]{1,16}")) { message("Use 1-16 letters, numbers or underscores.", true); return; }
        if (clean.isEmpty()) { message("Enter a token first.", true); return; }
        Account<?> account = switch (adding) { case Cracked -> new CrackedAccount(clean); case Session -> new SessionAccount(clean); case TheAltening -> new TheAlteningAccount(clean); default -> null; };
        if (account != null) signIn(account, true);
    }
    private void startMicrosoft() {
        if (busy || mc.world != null) { message("Leave the current world before switching accounts.", true); return; }
        long attempt = ++request; busy = browser = true; browserUrl = ""; message("Opening Microsoft sign-in...", false);
        MicrosoftLogin.startLogin(token -> mc.execute(() -> {
            if (request != attempt) return; busy = browser = false;
            if (token == null) { message("Sign-in did not finish. Please try again.", true); return; }
            signIn(new MicrosoftAccount(token), true);
        })).whenComplete((url, failure) -> mc.execute(() -> {
            if (request != attempt) return;
            if (failure != null) { busy = browser = false; message("Could not open Microsoft sign-in. Try again.", true); }
            else { browserUrl = url; message("Waiting for your browser sign-in...", false); }
        }));
    }
    private void signIn(Account<?> account, boolean newAccount) {
        if (busy) return;
        if (mc.world != null) { message("Leave the current world before switching accounts.", true); return; }
        long attempt = ++request; busy = true; message("Signing in...", false);
        MeteorExecutor.execute(() -> {
            try {
                if (!account.fetchInfo()) { completeFailure(attempt); return; } if (request != attempt) return;
                if (!account.login()) { completeFailure(attempt); return; }
                mc.execute(() -> {
                    if (request != attempt) return;
                    try {
                        if (newAccount) { Account<?> duplicate = duplicate(account); if (duplicate != null) Accounts.get().remove(duplicate); Accounts.get().add(account); }
                        Accounts.get().save(); account.getCache().loadHead(); selected = account; query = value = ""; scroll = detailScroll = 0;
                        modal = false; field = ""; confirmRemove = false; message("Now playing as " + name(account) + ".", false);
                    } catch (Exception e) { message("Signed in, but the account could not be saved. Try again.", true); }
                    finally { busy = false; }
                });
            } catch (Exception e) { completeFailure(attempt); }
        });
    }
    private void completeFailure(long attempt) { mc.execute(() -> { if (request == attempt) { busy = false; message("Could not sign in. Check the account and try again.", true); } }); }
    private Account<?> duplicate(Account<?> account) {
        for (Account<?> existing : Accounts.get()) {
            if (existing.getType() != account.getType()) continue; String a = existing.getCache().uuid, b = account.getCache().uuid;
            if (a != null && b != null && !a.isBlank() && a.replace("-", "").equalsIgnoreCase(b.replace("-", ""))) return existing;
            if (account.getType() == AccountType.Cracked && name(existing).equalsIgnoreCase(name(account))) return existing;
        } return null;
    }
    private void removeSelected() {
        if (selected == null || busy) return;
        if (!confirmRemove) { confirmRemove = true; message("Press Confirm remove to remove this saved account.", false); return; }
        Accounts.get().remove(selected); selected = null; confirmRemove = false; message("Removed from saved accounts. Your current session stays active.", false);
    }
    private void cancelSheet() {
        if (busy && !browser) return; if (browser) { ++request; MicrosoftLogin.cancelLogin(); }
        busy = browser = modal = false; value = browserUrl = field = focus = ""; selectAll = false; message("Choose an account, or add another.", false);
    }
    private void message(String text, boolean failed) { status = text; error = failed; }
    /** Compatibility for existing add-account screens and addons. */
    public static void addAccount(@Nullable AddAccountScreen screen, AccountsScreen parent, Account<?> account) {
        mc.execute(() -> { if (screen != null) screen.close(); mc.setScreen(parent); parent.signIn(account, true); });
    }
    private void pane(double x, double y, double w, double h, double radius) {
        EmberUI.shadow(renderer, x, y, w, h, fade); EmberUI.rim(renderer, x, y, w, h, radius, fade);
        renderer.roundedRect(x, y, w, h, radius, EmberUI.bg()); EmberUI.gloss(renderer, x, y, w, h, radius, fade);
    }
    private void head(Account<?> account, double x, double y, double size) {
        renderer.roundedRect(x - 3 * unit, y - 3 * unit, size + 6 * unit, size + 6 * unit, 8 * unit, EmberUI.accent(28));
        var texture = account.getCache().getHeadTexture(); renderer.texture(x, y, size, size, texture.needsRotate() ? 90 : 0, texture);
    }
    private void badge(String label, double x, double y, double w) { renderer.roundedRect(x, y, w, 20 * unit, 10 * unit, EmberUI.accent(60)); text(label, x + 8 * unit, y + 3 * unit, .60, EmberUI.TEXT, w - 16 * unit); }
    private void text(String value, double x, double y, double scale, Color color, double max) {
        double fontScale = theme.scale(scale * unit);
        renderer.post(() -> {
            var tr = theme.textRenderer(); tr.begin(fontScale); String shown = value == null ? "" : value;
            if (tr.getWidth(shown) > max) { while (!shown.isEmpty() && tr.getWidth(shown + "...") > max) shown = shown.substring(0, shown.offsetByCodePoints(shown.length(), -1)); shown += "..."; }
            tr.render(shown, x, y, color, false); tr.end();
        });
    }
    private boolean inside(double x, double y, double w, double h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    private void hit(String id, double x, double y, double w, double h, Runnable action) { if (w > 0 && h > 0) hits.add(new Hit(id, x, y, w, h, action)); }
    private void button(String id, double x, double y, double w, double h, String label, Runnable action, boolean primary, boolean enabled) {
        renderer.roundedRect(x, y, w, h, 8 * unit, primary ? EmberUI.accent(enabled ? 90 : 25) : EmberUI.raised());
        if (enabled && (inside(x, y, w, h) || focus.equals(id))) EmberUI.hoverFill(renderer, x, y, w, h, 1);
        text(label, x + 10 * unit, y + 8 * unit, .75, enabled ? EmberUI.TEXT : EmberUI.TEXT_DIM, w - 20 * unit);
        if (enabled) hit(id, x, y, w, h, () -> { field = ""; selectAll = false; action.run(); });
    }
    private void clippedButton(String id, double x, double y, double w, double h, String label, Runnable action, boolean primary, boolean enabled, double top, double bottom) {
        button(id, x, y, w, h, label, action, primary, enabled);
        if (enabled) { Hit last = hits.removeLast(); double yy = Math.max(y, top); hit(id, x, yy, w, Math.min(y + h, bottom) - yy, last.action); }
    }
    private void input(String id, double x, double y, double w, String content, String placeholder, boolean secret) {
        renderer.roundedRect(x, y, w, 33 * unit, 8 * unit, field.equals(id) ? EmberUI.accent(32) : EmberUI.WELL);
        String shown = secret && !content.isEmpty() ? "*".repeat(Math.min(32, content.length())) : content;
        if (!secret && field.equals(id) && shown.length() > 32) shown = "..." + shown.substring(shown.offsetByCodePoints(shown.length(), -32));
        text(shown.isEmpty() ? placeholder : shown + (field.equals(id) ? "|" : ""), x + 10 * unit, y + 8 * unit, .76, shown.isEmpty() ? EmberUI.TEXT_DIM : EmberUI.TEXT, w - 20 * unit);
        if (!busy) hit(id, x, y, w, 33 * unit, () -> { field = id; selectAll = false; });
    }
    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != GLFW_MOUSE_BUTTON_LEFT) return false;
        double scale = mc.getWindow().getScaleFactor(); mx = click.x() * scale; my = click.y() * scale;
        for (int i = hits.size() - 1; i >= 0; i--) { Hit hit = hits.get(i); if (hit.contains(mx, my)) { focus = hit.id; hit.action.run(); return true; } } field = ""; return true;
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (modal || busy) return true; double scale = mc.getWindow().getScaleFactor(), xx = x * scale, yy = y * scale;
        if (xx >= listX && xx < listX + listW && yy >= listY && yy < listY + listH) scroll -= vertical * 36 * unit;
        else if (xx >= listX + listW && yy >= py + 118 * unit && yy < listY + listH) detailScroll -= vertical * 36 * unit; return true;
    }
    @Override public boolean keyPressed(KeyInput input) {
        boolean control = (input.modifiers() & (GLFW_MOD_CONTROL | GLFW_MOD_SUPER)) != 0; int key = input.key();
        if (key == GLFW_KEY_ESCAPE) { if (modal) cancelSheet(); else close(); return true; } if (busy) return true;
        if (key == GLFW_KEY_TAB && !hits.isEmpty()) {
            int index = -1; for (int i = 0; i < hits.size(); i++) if (hits.get(i).id.equals(focus)) index = i;
            int next = Math.floorMod(index + ((input.modifiers() & GLFW_MOD_SHIFT) != 0 ? -1 : 1), hits.size()); focus = hits.get(next).id;
            field = focus.equals("search") || focus.equals("credential") ? focus : ""; selectAll = false; return true;
        }
        if (control && key == GLFW_KEY_F && !modal) { field = focus = "search"; selectAll = true; return true; }
        if (!field.isEmpty()) {
            if (control && key == GLFW_KEY_A) { selectAll = true; return true; } if (control && key == GLFW_KEY_V) { edit(mc.keyboard.getClipboard()); return true; }
            if (key == GLFW_KEY_BACKSPACE) { String text = field.equals("search") ? query : value; if (selectAll) text = ""; else if (!text.isEmpty()) text = text.substring(0, text.offsetByCodePoints(text.length(), -1)); assign(text); selectAll = false; return true; }
        }
        if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
            if (modal && field.equals("credential")) submit();
            else { Hit action = hits.stream().filter(h -> h.id.equals(focus)).findFirst().orElse(null);
                if (action != null && !action.id.equals("search") && !action.id.equals("credential")) action.action.run();
                else if (!modal && selected != null && !current(selected)) signIn(selected, false); } return true;
        }
        if (!modal && (key == GLFW_KEY_UP || key == GLFW_KEY_DOWN)) {
            var list = accounts(); if (list.isEmpty()) return true;
            int index = Math.clamp(list.indexOf(selected) + (key == GLFW_KEY_DOWN ? 1 : -1), 0, list.size() - 1); selected = list.get(index); confirmRemove = false; focus = "switch";
            double y = index * 70 * unit; if (y < scroll) scroll = y; if (y + 64 * unit > scroll + listH) scroll = y + 64 * unit - listH; return true;
        } return true;
    }
    @Override public boolean charTyped(CharInput input) { if (!busy && !field.isEmpty() && input.codepoint() >= 32 && input.codepoint() != 127) edit(new String(Character.toChars(input.codepoint()))); return true; }
    private void edit(String addition) {
        if (field.isEmpty()) return; String text = (selectAll ? "" : field.equals("search") ? query : value) + addition;
        int limit = field.equals("search") ? 128 : adding == AccountType.Cracked ? 16 : 8192;
        text = text.replaceAll("[\\p{Cntrl}]", ""); assign(text.substring(0, Math.min(text.length(), limit))); selectAll = false;
    }
    private void assign(String text) { if (field.equals("search")) { query = text; scroll = 0; } else value = text; }
    @Override public void close() { if (busy && !browser) { message("Finishing sign-in...", false); return; } if (browser) cancelSheet(); super.close(); }
    @Override public void removed() {
        ++request;
        if (browser) MicrosoftLogin.cancelLogin();
        busy = browser = modal = false; value = browserUrl = "";
        super.removed();
    }
}
