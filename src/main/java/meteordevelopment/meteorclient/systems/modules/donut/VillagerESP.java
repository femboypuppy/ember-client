package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;

public class VillagerESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Color of the ESP.")
        .defaultValue(new SettingColor(0, 255, 0, 100))
        .build()
    );

    public VillagerESP() {
        super(Categories.Donut, "villager-esp", "Highlights villagers.");
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null) return;

        for (Entity entity : mc.world.getEntities()) {
            String typeName = entity.getType().getTranslationKey();
            if (typeName.contains("villager")) {
                event.renderer.box(entity.getBoundingBox(), color.get(), color.get(), ShapeMode.Both, 0);
            }
        }
    }
}
