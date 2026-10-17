"""Exercise production atmosphere gates and sky submission with a recording GPU device.

No OpenGL context or game launch is needed. The method is compiled unchanged from
its source; collaborators reject uploads inside a pass just like Minecraft does.
Environment checks keep the palette active in liquids without overriding their fog ranges.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--source", type=Path, default=ROOT / "src/main/java/meteordevelopment/meteorclient/utils/render/EmberWorldAtmosphere.java")
args = parser.parse_args()
source = args.source.read_text(encoding="utf-8")
start = source.index("    private static void submitSky(")
brace = source.index("{", start)
depth, end = 1, brace + 1
while depth:
    depth += (source[end] == "{") - (source[end] == "}")
    end += 1
method = source[start:end]
environment = source[source.index("    private static boolean enabled()"):
                     source.index("    public static int lightingState()")]

HARNESS = r'''
import java.util.*;
import java.util.function.Supplier;
import com.mojang.blaze3d.buffers.GpuBufferSlice;

public class SkySubmissionChecks {
    static final List<String> calls = new ArrayList<>();
    static boolean active, failDraw;
    static final Client mc = new Client();
    static final Object VERTEX = new Object(), INDEX = new Object();
    static final GpuBufferSlice DATA = new GpuBufferSlice();
    static void require(boolean condition) { if (!condition) throw new AssertionError(calls); }
    static void upload(String kind) {
        if (active) throw new IllegalStateException("Close the existing render pass before performing additional commands");
        calls.add(kind);
    }
    static class Mesh {
        final int count;
        Mesh(int count) { this.count = count; }
        void end() { calls.add("end"); }
        int getIndicesCount() { return count; }
        Object getVertexBuffer() { upload("vertex upload"); return VERTEX; }
        Object getIndexBuffer() { upload("index upload"); return INDEX; }
    }
    static class Renderer3D {
        final Mesh lines = new Mesh(0), triangles;
        Renderer3D(int count) { triangles = new Mesh(count); }
    }
    enum CameraSubmersionType { NONE, WATER, LAVA, POWDER_SNOW }
    enum StatusEffects { BLINDNESS, DARKNESS }
    static class Setting<T> {
        T value;
        Setting(T value) { this.value = value; }
        T get() { return value; }
    }
    static class Config {
        static final Config INSTANCE = new Config();
        final Setting<Boolean> ambience = new Setting<>(true), worldAtmosphere = new Setting<>(true),
            accentFog = new Setting<>(true), accentSky = new Setting<>(true), worldLighting = new Setting<>(true);
        final Setting<Double> fogAmount = new Setting<>(0.5), skyIntensity = new Setting<>(0.85),
            worldDarkness = new Setting<>(0.42), worldLightTint = new Setting<>(0.8);
        static Config get() { return INSTANCE; }
    }
    static class Dimension {
        boolean skylight = true;
        boolean hasSkyLight() { return skylight; }
    }
    static class World {
        final Dimension dimension = new Dimension();
        Dimension getDimension() { return dimension; }
    }
    static class Player {
        final Set<StatusEffects> effects = new HashSet<>();
        boolean hasStatusEffect(StatusEffects effect) { return effects.contains(effect); }
    }
    static class Camera {
        CameraSubmersionType medium = CameraSubmersionType.NONE;
        CameraSubmersionType getSubmersionType() { return medium; }
    }
    static class GameRenderer {
        final Camera camera = new Camera();
        Camera getCamera() { return camera; }
    }
    static class Client {
        World world = new World();
        Player player = new Player();
        final GameRenderer gameRenderer = new GameRenderer();
        Target getFramebuffer() { return new Target(); }
    }
    static class Target {
        Object getColorAttachmentView() { return this; }
        Object getDepthAttachmentView() { return this; }
    }
    static class Vector4f { Vector4f(int value) {} }
    static class Vector3f {}
    static class Matrix4f {}
    static class VertexFormat { enum IndexType { INT } }
    static class MeteorRenderPipelines {
        static final String CELESTIAL_SKY = "sky", CELESTIAL_STARS = "stars";
    }
    static class RenderSystem {
        static Device getDevice() { return new Device(); }
        static DynamicUniforms getDynamicUniforms() { return new DynamicUniforms(); }
        static Object getModelViewMatrix() { return new Object(); }
        static void bindDefaultUniforms(Pass pass) { require(active); }
    }
    static class DynamicUniforms {
        GpuBufferSlice write(Object matrix, Vector4f color, Vector3f offset, Matrix4f texture) {
            upload("uniform upload"); return DATA;
        }
    }
    static class Device { Encoder createCommandEncoder() { return new Encoder(); } }
    static class Encoder {
        Pass createRenderPass(Supplier<String> label, Object color, OptionalInt clearColor, Object depth, OptionalDouble clearDepth) {
            require(!active); active = true; calls.add("open"); return new Pass();
        }
    }
    static class Pass implements AutoCloseable {
        void setPipeline(String pipeline) { require(active); calls.add(pipeline); }
        void setUniform(String name, GpuBufferSlice slice) { require(active && slice == DATA); calls.add(name); }
        void setVertexBuffer(int slot, Object buffer) { require(active && buffer == VERTEX); }
        void setIndexBuffer(Object buffer, VertexFormat.IndexType type) { require(active && buffer == INDEX); }
        void drawIndexed(int base, int first, int count, int instances) {
            require(active && count > 0 && instances == 1); calls.add("draw " + count);
            if (failDraw) throw new UnsupportedOperationException("simulated device failure");
        }
        public void close() { require(active); active = false; calls.add("close"); }
    }

    // PRODUCTION_METHOD
    // PRODUCTION_ENVIRONMENT

    static void checkLiquids() {
        // Repeated boundary crossings must never toggle the palette or sky off.
        for (int crossing = 0; crossing < 30; crossing++) {
            for (CameraSubmersionType medium : CameraSubmersionType.values()) {
                mc.gameRenderer.camera.medium = medium;
                require(openSky() && skyActive() && fogEnabled() && lightingEnabled());
                // Air haze may affect long view distances; it must not change liquid fog.
                require(fogHazeEnabled() == (medium == CameraSubmersionType.NONE));
            }
        }
        mc.player.effects.add(StatusEffects.BLINDNESS);
        require(!openSky() && !lightingEnabled());
        mc.player.effects.clear();
        mc.player.effects.add(StatusEffects.DARKNESS);
        require(!skyActive() && !fogEnabled());
        mc.player.effects.clear();
        Config.INSTANCE.ambience.value = false;
        require(!openSky()); Config.INSTANCE.ambience.value = true;
        Config.INSTANCE.worldAtmosphere.value = false;
        require(!openSky()); Config.INSTANCE.worldAtmosphere.value = true;
        Config.INSTANCE.accentSky.value = false;
        require(!skyActive() && fogEnabled() && lightingEnabled()); Config.INSTANCE.accentSky.value = true;
        Config.INSTANCE.accentFog.value = false;
        require(!fogEnabled() && skyActive()); Config.INSTANCE.accentFog.value = true;
        mc.world.dimension.skylight = false;
        require(!openSky()); mc.world.dimension.skylight = true;
        mc.world = null; require(!openSky()); mc.world = new World();
        mc.player = null; require(!openSky()); mc.player = new Player();
        System.out.println("Atmosphere checks passed: air/water/lava/snow crossings retain sky, fog and lighting; native liquid fog ranges, toggles and world reset.");
    }

    public static void main(String[] args) {
        checkLiquids();
        calls.clear(); submitSky(new Renderer3D(0), true, DATA);
        require(calls.equals(List.of("end", "end")));
        for (int frame = 0; frame < 300; frame++) {
            for (boolean sky : new boolean[]{true, false}) {
                calls.clear();
                int count = sky ? 3072 : 60000;
                submitSky(new Renderer3D(count), sky, sky ? DATA : null);
                require(!active && calls.contains("draw " + count));
                require(calls.indexOf("vertex upload") < calls.indexOf("open"));
                require(calls.indexOf("index upload") < calls.indexOf("open"));
                require(calls.contains("SkyData") == sky);
                require(calls.contains(sky ? "sky" : "stars"));
                require(calls.getLast().equals("close"));
            }
        }
        failDraw = true;
        try { submitSky(new Renderer3D(3), true, DATA); throw new AssertionError("Expected device failure"); }
        catch (UnsupportedOperationException expected) { require(!active); }
        System.out.println("Sky submission checks passed: cold/empty meshes, 300 sky + star frames, upload ordering and failure cleanup.");
    }
}
'''
java_home = os.environ.get("JAVA_HOME")
def java_tool(name):
    return str(Path(java_home) / "bin" / name) if java_home else shutil.which(name)

with tempfile.TemporaryDirectory(prefix="ember-sky-check-") as temporary:
    directory = Path(temporary)
    harness = directory / "SkySubmissionChecks.java"
    harness.write_text(HARNESS.replace("// PRODUCTION_METHOD", method)
                       .replace("// PRODUCTION_ENVIRONMENT", environment), encoding="utf-8")
    stub = directory / "com/mojang/blaze3d/buffers/GpuBufferSlice.java"
    stub.parent.mkdir(parents=True)
    stub.write_text("package com.mojang.blaze3d.buffers; public class GpuBufferSlice {}", encoding="utf-8")
    subprocess.run([java_tool("javac"), "-encoding", "UTF-8", "-d", temporary, str(harness), str(stub)], check=True)
    subprocess.run([java_tool("java"), "-ea", "-cp", temporary, "SkySubmissionChecks"], check=True)
