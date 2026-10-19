/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.NameProtect;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.EmberShapes;
import meteordevelopment.meteorclient.utils.render.EmberNametagCard;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.Identifier;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.*;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.EnchantmentTags;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.joml.Vector3d;

import java.util.*;

public class Nametags extends Module {
    private final SettingGroup sgAppearance = settings.createGroup("Appearance");
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlayers = settings.createGroup("Players");
    private final SettingGroup sgEquipment = settings.createGroup("Equipment");
    private final SettingGroup sgItems = settings.createGroup("Items");
    private final SettingGroup sgRender = settings.createGroup("Render");

    public final Setting<EmberNametagCard.Style> cardStyle = sgAppearance.add(new EnumSetting.Builder<EmberNametagCard.Style>()
        .name("card-style").description("Glass follows Ember's Liquid Glass; Slate is flat; Compact uses a smaller card.")
        .defaultValue(EmberNametagCard.Style.Glass).build());
    public final Setting<Double> cardOpacity = sgAppearance.add(new DoubleSetting.Builder()
        .name("card-opacity").description("Opacity of the nametag surface and its shadow; text stays readable.")
        .defaultValue(.9).range(0, 1).sliderRange(0, 1).build());
    public final Setting<Boolean> playerHead = sgAppearance.add(new BoolSetting.Builder()
        .name("player-avatar").description("Show the player's rounded skin avatar.").defaultValue(true)
        .visible(() -> cardStyle.get() != EmberNametagCard.Style.Compact).build());
    public final Setting<Boolean> healthBar = sgAppearance.add(new BoolSetting.Builder()
        .name("health-bar").description("Show a thin health bar with a gold absorption segment.").defaultValue(true)
        .visible(() -> this.displayHealth.get() && cardStyle.get() != EmberNametagCard.Style.Compact).build());
    public final Setting<Boolean> cardShadow = sgAppearance.add(new BoolSetting.Builder()
        .name("card-shadow").description("Soft shadow around the nametag.").defaultValue(true).build());
    public final Setting<Integer> maxCardWidth = sgAppearance.add(new IntSetting.Builder()
        .name("maximum-card-width").description("Keep long server display names inside the card.")
        .defaultValue(280).range(160, 420).sliderRange(160, 420).build());
    public final Setting<Boolean> friendBadge = sgPlayers.add(new BoolSetting.Builder()
        .name("friend-badge").description("Add a FRIEND badge to friends' tags.").defaultValue(true).build());

    // General

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Select entities to draw nametags on.")
        .defaultValue(EntityType.PLAYER, EntityType.ITEM)
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("The scale of the nametag.")
        .defaultValue(1.1)
        .min(0.1)
        .build()
    );

    private final Setting<Boolean> ignoreSelf = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-self")
        .description("Ignore yourself when in third person or freecam.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Ignore rendering nametags for friends.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreBots = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-bots")
        .description("Only render non-bot nametags.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> culling = sgGeneral.add(new BoolSetting.Builder()
        .name("culling")
        .description("Only render a certain number of nametags at a certain distance.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> maxCullRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("culling-range")
        .description("Only render nametags within this distance of your player.")
        .defaultValue(20)
        .min(0)
        .sliderMax(200)
        .visible(culling::get)
        .build()
    );

    private final Setting<Integer> maxCullCount = sgGeneral.add(new IntSetting.Builder()
        .name("culling-count")
        .description("Only render this many nametags.")
        .defaultValue(50)
        .min(1)
        .sliderRange(1, 100)
        .visible(culling::get)
        .build()
    );

    //Players

    private final Setting<Boolean> displayHealth = sgPlayers.add(new BoolSetting.Builder()
        .name("health")
        .description("Shows the player's health.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> displayPrefix = sgPlayers.add(new BoolSetting.Builder()
        .name("use-display-name")
        .description("Uses the players server display name instead of their account name.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> displayGameMode = sgPlayers.add(new BoolSetting.Builder()
        .name("gamemode")
        .description("Shows the player's GameMode.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> displayDistance = sgPlayers.add(new BoolSetting.Builder()
        .name("distance")
        .description("Shows the distance between you and the player.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> displayPing = sgPlayers.add(new BoolSetting.Builder()
        .name("ping")
        .description("Shows the player's ping.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> displayItems = sgEquipment.add(new BoolSetting.Builder()
        .name("items")
        .description("Displays armor and hand items above the name tags.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> itemSpacing = sgEquipment.add(new DoubleSetting.Builder()
        .name("item-spacing")
        .description("The spacing between items.")
        .defaultValue(2)
        .range(0, 10)
        .visible(displayItems::get)
        .build()
    );

    private final Setting<Boolean> ignoreEmpty = sgEquipment.add(new BoolSetting.Builder()
        .name("ignore-empty-slots")
        .description("Doesn't add spacing where an empty item stack would be.")
        .defaultValue(true)
        .visible(displayItems::get)
        .build()
    );

    private final Setting<Durability> itemDurability = sgEquipment.add(new EnumSetting.Builder<Durability>()
        .name("durability")
        .description("Displays item durability as either a total, percentage, or neither.")
        .defaultValue(Durability.None)
        .visible(displayItems::get)
        .build()
    );

    private final Setting<Boolean> displayEnchants = sgEquipment.add(new BoolSetting.Builder()
        .name("display-enchants")
        .description("Displays item enchantments on the items.")
        .defaultValue(false)
        .visible(displayItems::get)
        .build()
    );

    private final Setting<Set<RegistryKey<Enchantment>>> shownEnchantments = sgEquipment.add(new EnchantmentListSetting.Builder()
        .name("shown-enchantments")
        .description("The enchantments that are shown on nametags.")
        .visible(() -> displayItems.get() && displayEnchants.get())
        .defaultValue(
            Enchantments.PROTECTION,
            Enchantments.BLAST_PROTECTION,
            Enchantments.FIRE_PROTECTION,
            Enchantments.PROJECTILE_PROTECTION
        )
        .build()
    );

    private final Setting<Position> enchantPos = sgEquipment.add(new EnumSetting.Builder<Position>()
        .name("enchantment-position")
        .description("Where the enchantments are rendered.")
        .defaultValue(Position.Above)
        .visible(() -> displayItems.get() && displayEnchants.get())
        .build()
    );

    private final Setting<Integer> enchantLength = sgEquipment.add(new IntSetting.Builder()
        .name("enchant-name-length")
        .description("The length enchantment names are trimmed to.")
        .defaultValue(3)
        .range(1, 5)
        .sliderRange(1, 5)
        .visible(() -> displayItems.get() && displayEnchants.get())
        .build()
    );

    private final Setting<Double> enchantTextScale = sgEquipment.add(new DoubleSetting.Builder()
        .name("enchant-text-scale")
        .description("The scale of the enchantment text.")
        .defaultValue(1)
        .range(0.1, 2)
        .sliderRange(0.1, 2)
        .visible(() -> displayItems.get() && displayEnchants.get())
        .build()
    );

    //Items

    private final Setting<Boolean> itemCount = sgItems.add(new BoolSetting.Builder()
        .name("show-count")
        .description("Displays the number of items in the stack.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<SettingColor> background = sgRender.add(new ColorSetting.Builder()
        .name("background-color")
        .description("The color of the nametag background.")
        .defaultValue(new SettingColor(14, 16, 23, 210))
        .build()
    );

    private final Setting<SettingColor> nameColor = sgRender.add(new ColorSetting.Builder()
        .name("name-color")
        .description("The color of the nametag names.")
        .defaultValue(new SettingColor())
        .build()
    );

    private final Setting<SettingColor> pingColor = sgRender.add(new ColorSetting.Builder()
        .name("ping-color")
        .description("The color of the nametag ping.")
        .defaultValue(new SettingColor(20, 170, 170))
        .visible(displayPing::get)
        .build()
    );

    private final Setting<SettingColor> gamemodeColor = sgRender.add(new ColorSetting.Builder()
        .name("gamemode-color")
        .description("The color of the nametag gamemode.")
        .defaultValue(new SettingColor(232, 185, 35))
        .visible(displayGameMode::get)
        .build()
    );

    private final Setting<DistanceColorMode> distanceColorMode = sgRender.add(new EnumSetting.Builder<DistanceColorMode>()
        .name("distance-color-mode")
        .description("The mode to color the nametag distance with.")
        .defaultValue(DistanceColorMode.Gradient)
        .visible(displayDistance::get)
        .build()
    );

    private final Setting<SettingColor> distanceColor = sgRender.add(new ColorSetting.Builder()
        .name("distance-color")
        .description("The color of the nametag distance.")
        .defaultValue(new SettingColor(150, 150, 150))
        .visible(() -> displayDistance.get() && distanceColorMode.get() == DistanceColorMode.Flat)
        .build()
    );

    private final Color WHITE = new Color(255, 255, 255);
    private final Color RED = new Color(255, 25, 25);
    private final Color AMBER = new Color(255, 105, 25);
    private final Color GREEN = new Color(25, 252, 25);
    private final Color GOLD = new Color(232, 185, 35);

    private final Vector3d pos = new Vector3d();
    private final double[] itemWidths = new double[6];

    private final List<Entity> entityList = new ArrayList<>();

    public Nametags() {
        super(Categories.Render, "nametags", "Displays customizable nametags above players, items and other entities.");
    }

    @Override public Module fromTag(net.minecraft.nbt.NbtCompound tag) {
        super.fromTag(tag);
        var groups = tag.getCompoundOrEmpty("settings").getListOrEmpty("groups");
        Set<String> current = new HashSet<>();
        for (var value : groups) if (value instanceof net.minecraft.nbt.NbtCompound group && group.getString("name", "").equals("Equipment")) {
            for (var item : group.getListOrEmpty("settings")) if (item instanceof net.minecraft.nbt.NbtCompound setting) current.add(setting.getString("name", ""));
        }
        // Older shared configs stored equipment controls in Players. Explicit new values win.
        for (var value : groups) if (value instanceof net.minecraft.nbt.NbtCompound group && group.getString("name", "").equals("Players")) {
            for (var item : group.getListOrEmpty("settings")) if (item instanceof net.minecraft.nbt.NbtCompound setting) {
                String name = setting.getString("name", "");
                Setting<?> target = sgEquipment.get(name);
                if (target != null && !current.contains(name)) target.fromTag(setting);
            }
        }
        return this;
    }

    @Override public void onDeactivate() { entityList.clear(); }

    private static String ticksToTime(int ticks) {
        if (ticks > 20 * 3600) {
            int h = ticks / 20 / 3600;
            return h + " h";
        } else if (ticks > 20 * 60) {
            int m = ticks / 20 / 60;
            return m + " m";
        } else {
            int s = ticks / 20;
            int ms = (ticks % 20) / 2;
            return s + "." + ms + " s";
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        entityList.clear();

        boolean freecamNotActive = !Modules.get().isActive(Freecam.class);
        boolean notThirdPerson = mc.options.getPerspective().isFirstPerson();
        Vec3d cameraPos = mc.gameRenderer.getCamera().getCameraPos();

        for (Entity entity : mc.world.getEntities()) {
            EntityType<?> type = entity.getType();
            if (!entities.get().contains(type)) continue;

            if (type == EntityType.PLAYER) {
                if ((ignoreSelf.get() || (freecamNotActive && notThirdPerson)) && entity == mc.player) continue;
                if (EntityUtils.getGameMode((PlayerEntity) entity) == null && ignoreBots.get()) continue;
                if (Friends.get().isFriend((PlayerEntity) entity) && ignoreFriends.get()) continue;
            }

            if (!culling.get() || PlayerUtils.isWithinCamera(entity, maxCullRange.get())) {
                entityList.add(entity);
            }
        }

        entityList.sort(Comparator.comparing(e -> e.squaredDistanceTo(cameraPos)));
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        int count = getRenderCount();
        boolean shadow = Config.get().customFont.get();

        for (int i = count - 1; i > -1; i--) {
            Entity entity = entityList.get(i);

            Utils.set(pos, entity, event.tickDelta);
            pos.add(0, getHeight(entity), 0);

            EntityType<?> type = entity.getType();

            if (NametagUtils.to2D(pos, scale.get())) {
                if (type == EntityType.PLAYER) renderNametagPlayer(event, (PlayerEntity) entity, shadow);
                else if (type == EntityType.ITEM) renderNametagItem(((ItemEntity) entity).getStack(), shadow);
                else if (type == EntityType.ITEM_FRAME || type == EntityType.GLOW_ITEM_FRAME)
                    renderNametagItem(((ItemFrameEntity) entity).getHeldItemStack(), shadow);
                else if (type == EntityType.TNT) renderTntNametag(ticksToTime(((TntEntity) entity).getFuse()), shadow);
                else if (type == EntityType.TNT_MINECART && ((TntMinecartEntity) entity).isPrimed())
                    renderTntNametag(ticksToTime(((TntMinecartEntity) entity).getFuseTicks()), shadow);
                else if (entity instanceof LivingEntity) renderGenericLivingNametag((LivingEntity) entity, shadow);
                else renderGenericNametag(entity, shadow);
            }
        }
    }

    private int getRenderCount() {
        int count = culling.get() ? maxCullCount.get() : entityList.size();
        count = MathHelper.clamp(count, 0, entityList.size());

        return count;
    }

    @Override
    public String getInfoString() {
        return Integer.toString(getRenderCount());
    }

    private double getHeight(Entity entity) {
        double height = entity.getEyeHeight(entity.getPose());

        if (entity.getType() == EntityType.ITEM || entity.getType() == EntityType.ITEM_FRAME || entity.getType() == EntityType.GLOW_ITEM_FRAME) height += 0.2;
        else height += 0.5;

        return height;
    }

    private void renderNametagPlayer(Render2DEvent event, PlayerEntity player, boolean shadow) {
        NametagUtils.begin(pos, event.drawContext);
        try {
            var data = playerCard(player);
            var layout = EmberNametagCard.draw(data, pos.x, pos.y, NametagUtils.scale);
            renderEquipment(event, player, shadow, layout.height());
        } finally { NametagUtils.end(event.drawContext); }
    }

    private EmberNametagCard.Data playerCard(PlayerEntity player) {
        String name = player == mc.player ? Modules.get().get(NameProtect.class).getName(player.getName().getString())
            : displayPrefix.get() && player.getDisplayName() != null ? player.getDisplayName().getString() : player.getName().getString();
        double max = Math.max(1, player.getMaxHealth()), hp = Math.max(0, player.getHealth()), absorption = Math.max(0, player.getAbsorptionAmount());
        double ratio = hp / max;
        Color health = ratio < .34 ? new Color(250, 99, 112) : ratio < .67 ? new Color(255, 189, 100) : new Color(116, 224, 162);
        List<EmberNametagCard.Chip> chips = new ArrayList<>(4);
        if (friendBadge.get() && Friends.get().isFriend(player)) chips.add(new EmberNametagCard.Chip("FRIEND", new Color(120, 224, 176)));
        if (displayPing.get()) {
            int ping = EntityUtils.getPing(player);
            chips.add(new EmberNametagCard.Chip(ping < 0 ? "Ping ?" : ping + " ms", pingColor.get()));
        }
        if (displayDistance.get() && (player != mc.getCameraEntity() || Modules.get().isActive(Freecam.class))) {
            double distance = PlayerUtils.distanceToCamera(player);
            Color color = distanceColorMode.get() == DistanceColorMode.Flat ? distanceColor.get() :
                distance < 16 ? new Color(255, 184, 118) : new Color(164, 185, 219);
            chips.add(new EmberNametagCard.Chip(Math.round(distance * 10) / 10.0 + " m", color));
        }
        if (displayGameMode.get()) {
            GameMode mode = EntityUtils.getGameMode(player);
            String label = mode == null ? "BOT" : switch (mode) {
                case SURVIVAL -> "Survival"; case CREATIVE -> "Creative"; case ADVENTURE -> "Adventure"; case SPECTATOR -> "Spectator";
            };
            chips.add(new EmberNametagCard.Chip(label, gamemodeColor.get()));
        }
        return new EmberNametagCard.Data(name, displayHealth.get() ? Math.round(hp + absorption) + " HP" : "",
            hp / (max + absorption), absorption / (max + absorption), PlayerUtils.getPlayerColor(player, nameColor.get()), health,
            EmberPalette.accent(), background.get(), skinOf(player), chips, playerHead.get(), displayHealth.get() && healthBar.get(),
            cardShadow.get(), cardOpacity.get(), maxCardWidth.get(), cardStyle.get());
    }

    public void renderPreview(double centerX, double bottomY, double previewScale) {
        String name = mc.player == null ? "EmberPlayer" : Modules.get().get(NameProtect.class).getName(mc.player.getName().getString());
        var data = new EmberNametagCard.Data(name, displayHealth.get() ? "24 HP" : "", .83, .17,
            nameColor.get(), new Color(116, 224, 162), EmberPalette.accent(), background.get(), mc.player == null ? null : skinOf(mc.player),
            previewChips(), playerHead.get(), displayHealth.get() && healthBar.get(), cardShadow.get(), cardOpacity.get(), maxCardWidth.get(), cardStyle.get());
        double previousScale = NametagUtils.scale;
        NametagUtils.scale = previewScale;
        NametagUtils.begin(new Vector3d(centerX, bottomY, 0));
        try { EmberNametagCard.draw(data, centerX, bottomY, previewScale); }
        finally { NametagUtils.end(); NametagUtils.scale = previousScale; }
    }

    private List<EmberNametagCard.Chip> previewChips() {
        List<EmberNametagCard.Chip> chips = new ArrayList<>();
        if (friendBadge.get()) chips.add(new EmberNametagCard.Chip("FRIEND", new Color(120, 224, 176)));
        if (displayPing.get()) chips.add(new EmberNametagCard.Chip("42 ms", pingColor.get()));
        if (displayDistance.get()) chips.add(new EmberNametagCard.Chip("12.4 m", distanceColor.get()));
        if (displayGameMode.get()) chips.add(new EmberNametagCard.Chip("Survival", gamemodeColor.get()));
        return chips;
    }

    private void renderEquipment(Render2DEvent event, PlayerEntity player, boolean shadow, double heightDown) {
        TextRenderer text = TextRenderer.get();


        if (displayItems.get()) {
            // Item calc
            Arrays.fill(itemWidths, 0);
            boolean hasItems = false;
            int maxEnchantCount = 0;

            for (int i = 0; i < 6; i++) {
                ItemStack itemStack = getItem(player, i);

                // Setting up widths
                if (itemWidths[i] == 0 && (!ignoreEmpty.get() || !itemStack.isEmpty()))
                    itemWidths[i] = 32 + itemSpacing.get();

                if (!itemStack.isEmpty()) hasItems = true;

                if (displayEnchants.get()) {
                    ItemEnchantmentsComponent enchantments = EnchantmentHelper.getEnchantments(itemStack);

                    int size = 0;
                    for (RegistryEntry<Enchantment> enchantment : enchantments.getEnchantments()) {
                        if (enchantment.getKey().isPresent() && !shownEnchantments.get().contains(enchantment.getKey().get())) continue;
                        String enchantName = Utils.getEnchantSimpleName(enchantment, enchantLength.get()) + " " + enchantments.getLevel(enchantment);
                        itemWidths[i] = Math.max(itemWidths[i], EmberNametagCard.measure(text, enchantName, .5 * enchantTextScale.get()));
                        size++;
                    }

                    maxEnchantCount = Math.max(maxEnchantCount, size);
                }
            }

            double itemsHeight = (hasItems ? 32 : 0);
            double itemWidthTotal = 0;
            for (double w : itemWidths) itemWidthTotal += w;
            double itemWidthHalf = itemWidthTotal / 2;

            double y = -heightDown - 7 - itemsHeight;
            double x = -itemWidthHalf;

            if (itemWidthTotal > 0 && (hasItems || !ignoreEmpty.get())) {
                Renderer2D.COLOR.begin();
                EmberShapes.roundedGradient(Renderer2D.COLOR, x - 4, y - 4, itemWidthTotal + 8, 40, 7,
                    new Color(25, 28, 37, (int) (190 * cardOpacity.get())), new Color(12, 14, 20, (int) (210 * cardOpacity.get())));
                Renderer2D.COLOR.render();
            }
            // Rendering items and enchants
            for (int i = 0; i < 6; i++) {
                ItemStack stack = getItem(player, i);

                if (!stack.isEmpty()) RenderUtils.drawItem(event.drawContext, stack, (int) x, (int) y, 2, true, null, false);

                if (stack.isDamageable() && itemDurability.get() != Durability.None) {
                    text.begin(0.75, false, true);

                    String damageText = switch (itemDurability.get()) {
                        case Percentage -> String.format("%.0f%%", ((stack.getMaxDamage() - stack.getDamage()) * 100f) / (float) stack.getMaxDamage());
                        case Total -> Integer.toString(stack.getMaxDamage() - stack.getDamage());
                        default -> "err";
                    };
                    Color damageColor = new Color(stack.getItemBarColor());

                    text.render(damageText, (int) x, (int) y, damageColor.a(255), true);
                    text.end();
                }

                if (maxEnchantCount > 0 && displayEnchants.get()) {
                    text.begin(0.5 * enchantTextScale.get(), false, true);

                    ItemEnchantmentsComponent enchantments = EnchantmentHelper.getEnchantments(stack);
                    Object2IntMap<RegistryEntry<Enchantment>> enchantmentsToShow = new Object2IntOpenHashMap<>();

                    for (RegistryEntry<Enchantment> enchantment : enchantments.getEnchantments()) {
                        if (enchantment.matches(shownEnchantments.get()::contains)) {
                            enchantmentsToShow.put(enchantment, enchantments.getLevel(enchantment));
                        }
                    }

                    double aW = itemWidths[i];
                    double enchantY = 0;

                    double addY = switch (enchantPos.get()) {
                        case Above -> -((enchantmentsToShow.size() + 1) * text.getHeight(shadow));
                        case OnTop -> (itemsHeight - enchantmentsToShow.size() * text.getHeight(shadow)) / 2;
                    };

                    double enchantX;

                    for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : Object2IntMaps.fastIterable(enchantmentsToShow)) {
                        String enchantName = Utils.getEnchantSimpleName(entry.getKey(), enchantLength.get()) + " " + entry.getIntValue();

                        Color enchantColor = WHITE;
                        if (entry.getKey().isIn(EnchantmentTags.CURSE)) enchantColor = RED;

                        enchantX = switch (enchantPos.get()) {
                            case Above -> x + (aW / 2) - EmberNametagCard.measure(text, enchantName, .5 * enchantTextScale.get());
                            case OnTop -> x + (aW - text.getWidth(enchantName, shadow)) / 2;
                        };

                        text.render(enchantName, enchantX, y + addY + enchantY, enchantColor, shadow);

                        enchantY += text.getHeight(shadow);
                    }

                    text.end();
                }

                x += itemWidths[i];
            }
        }

    }

    private void renderNametagItem(ItemStack stack, boolean shadow) {
        if (stack.isEmpty()) return;

        TextRenderer text = TextRenderer.get();
        NametagUtils.begin(pos);

        String name = Names.get(stack);
        String count = " x" + stack.getCount();

        double nameWidth = EmberNametagCard.measure(text, name, 1);
        double countWidth = EmberNametagCard.measure(text, count, 1);
        double heightDown = EmberNametagCard.height(text, 1);

        double width = nameWidth;
        if (itemCount.get()) width += countWidth;
        double widthHalf = width / 2;

        drawBg(-widthHalf, -heightDown, width, heightDown);

        text.beginBig();
        double hX = -widthHalf;
        double hY = -heightDown;

        hX = text.render(name, hX, hY, nameColor.get(), shadow);
        if (itemCount.get()) text.render(count, hX, hY, GOLD, shadow);
        text.end();

        NametagUtils.end();
    }

    private void renderGenericLivingNametag(LivingEntity entity, boolean shadow) {
        TextRenderer text = TextRenderer.get();
        NametagUtils.begin(pos);

        //Name
        String nameText = entity.getType().getName().getString();
        nameText += " ";

        //Health
        float absorption = entity.getAbsorptionAmount();
        int health = Math.round(entity.getHealth() + absorption);
        double healthPercentage = health / (entity.getMaxHealth() + absorption);

        String healthText = String.valueOf(health);
        Color healthColor;

        if (healthPercentage <= 0.333) healthColor = RED;
        else if (healthPercentage <= 0.666) healthColor = AMBER;
        else healthColor = GREEN;

        double nameWidth = EmberNametagCard.measure(text, nameText, 1);
        double healthWidth = EmberNametagCard.measure(text, healthText, 1);
        double heightDown = EmberNametagCard.height(text, 1);

        double width = nameWidth + healthWidth;
        double widthHalf = width / 2;

        drawBg(-widthHalf, -heightDown, width, heightDown);

        text.beginBig();
        double hX = -widthHalf;
        double hY = -heightDown;

        hX = text.render(nameText, hX, hY, nameColor.get(), shadow);
        text.render(healthText, hX, hY, healthColor, shadow);
        text.end();

        NametagUtils.end();
    }

    private void renderGenericNametag(Entity entity, boolean shadow) {
        TextRenderer text = TextRenderer.get();
        NametagUtils.begin(pos);

        //Name
        String nameText = entity.getType().getName().getString();

        double nameWidth = EmberNametagCard.measure(text, nameText, 1);
        double heightDown = EmberNametagCard.height(text, 1);
        double widthHalf = nameWidth / 2;

        drawBg(-widthHalf, -heightDown, nameWidth, heightDown);

        text.beginBig();
        double hX = -widthHalf;
        double hY = -heightDown;

        text.render(nameText, hX, hY, nameColor.get(), shadow);
        text.end();

        NametagUtils.end();
    }

    private void renderTntNametag(String fuseText, boolean shadow) {
        TextRenderer text = TextRenderer.get();
        NametagUtils.begin(pos);

        double width = EmberNametagCard.measure(text, fuseText, 1);
        double heightDown = EmberNametagCard.height(text, 1);

        double widthHalf = width / 2;

        drawBg(-widthHalf, -heightDown, width, heightDown);

        text.beginBig();
        double hX = -widthHalf;
        double hY = -heightDown;

        text.render(fuseText, hX, hY, nameColor.get(), shadow);
        text.end();

        NametagUtils.end();
    }

    private ItemStack getItem(PlayerEntity entity, int index) {
        return switch (index) {
            case 0 -> entity.getMainHandStack();
            case 1 -> entity.getEquippedStack(EquipmentSlot.HEAD);
            case 2 -> entity.getEquippedStack(EquipmentSlot.CHEST);
            case 3 -> entity.getEquippedStack(EquipmentSlot.LEGS);
            case 4 -> entity.getEquippedStack(EquipmentSlot.FEET);
            case 5 -> entity.getOffHandStack();
            default -> ItemStack.EMPTY;
        };
    }

    private void drawBg(double x, double y, double width, double height) {
        Renderer2D.COLOR.begin();
        Color bg = background.get();
        EmberShapes.roundedGradient(Renderer2D.COLOR, x - 6, y - 4, width + 12, height + 8, 7,
            new Color(bg.r, bg.g, bg.b, (int) (bg.a * cardOpacity.get())), new Color(bg.r, bg.g, bg.b, (int) (bg.a * cardOpacity.get())));
        Renderer2D.COLOR.render();
    }

    private Identifier skinOf(PlayerEntity player) {
        if (player instanceof AbstractClientPlayerEntity client) return client.getSkin().body().texturePath();
        return null;
    }

    public enum Position {
        Above,
        OnTop
    }

    public enum Durability {
        None,
        Total,
        Percentage
    }

    public enum DistanceColorMode {
        Gradient,
        Flat
    }

    public boolean excludeBots() {
        return ignoreBots.get();
    }

    public boolean playerNametags() {
        return isActive() && entities.get().contains(EntityType.PLAYER);
    }
}
