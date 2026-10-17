"""CPU regression check using an installed Exordium jar; no game files are changed.

Runs Exordium's real BufferInstance and PacingTracker with recording framebuffer
stubs. ASM applies the compiled production return-value handler to BufferInstance,
so the checks exercise the shipped guard rather than a second implementation.
Requires Java 21 and the project's cached ASM/Mixin dependencies.
"""
import argparse
import os
from pathlib import Path
import subprocess
import textwrap


SOURCES = {
    "net/minecraft/class_437.java": "package net.minecraft; public class class_437 {}",
    "com/mojang/blaze3d/textures/GpuTextureView.java": """
        package com.mojang.blaze3d.textures;
        public class GpuTextureView {
            public final java.util.Set<String> content = new java.util.HashSet<>();
        }
    """,
    "net/minecraft/class_276.java": """
        package net.minecraft;
        import com.mojang.blaze3d.textures.GpuTextureView;
        public class class_276 {
            public final GpuTextureView view = new GpuTextureView();
            public GpuTextureView method_71639() { return view; }
            public void method_68445(GpuTextureView target) { target.content.addAll(view.content); }
        }
    """,
    "net/minecraft/class_310.java": """
        package net.minecraft;
        public class class_310 {
            private static final class_310 INSTANCE = new class_310();
            public class_437 field_1755;
            public final class_276 main = new class_276();
            public class_276 temporary;
            public static class_310 method_1551() { return INSTANCE; }
            public class_276 method_1522() { return temporary == null ? main : temporary; }
        }
    """,
    "dev/tr7zw/exordium/ExordiumModBase.java": """
        package dev.tr7zw.exordium;
        import net.minecraft.class_310;
        import net.minecraft.class_276;
        public class ExordiumModBase {
            public static ExordiumModBase instance = new ExordiumModBase();
            public boolean isInitialized() { return true; }
            public void setTemporaryScreenOverwrite(class_276 target) { class_310.method_1551().temporary = target; }
        }
    """,
    "dev/tr7zw/exordium/components/BufferedComponent.java": """
        package dev.tr7zw.exordium.components;
        import dev.tr7zw.exordium.ExordiumModBase;
        import net.minecraft.class_276;
        public class BufferedComponent {
            public static int captures;
            private final class_276 target = new class_276();
            public void captureComponent() {
                captures++; target.view.content.clear();
                ExordiumModBase.instance.setTemporaryScreenOverwrite(target);
            }
            public void finishCapture() { ExordiumModBase.instance.setTemporaryScreenOverwrite(null); }
            public class_276 getGuiTarget() { return target; }
        }
    """,
    "checks/ExordiumChecks.java": """
        package checks;
        import java.lang.reflect.*;
        import java.nio.file.*;
        import java.util.*;
        import java.util.function.Supplier;
        import java.util.jar.*;
        import org.objectweb.asm.*;
        import org.objectweb.asm.tree.*;
        import meteordevelopment.meteorclient.utils.render.EmberGuiPass;
        import net.minecraft.class_310;
        import dev.tr7zw.exordium.components.BufferedComponent;
        import dev.tr7zw.exordium.versionless.config.Config;

        public final class ExordiumChecks {
            private static final String TARGET = "dev.tr7zw.exordium.components.BufferInstance";
            private static byte[] targetBytes, handlerBytes;

            private static Class<?> load(boolean patched) throws Exception {
                ClassNode node = new ClassNode(); new ClassReader(targetBytes).accept(node, 0);
                if (patched) {
                    ClassNode mixin = new ClassNode(); new ClassReader(handlerBytes).accept(mixin, 0);
                    MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("meteor$keepCacheForVanillaPass")).findFirst().orElseThrow();
                    require(handler.desc.equals("(Z)Z") && (handler.access & Opcodes.ACC_STATIC) == 0, "Handler must match the instance enabled() method");
                    handler.visibleAnnotations = handler.invisibleAnnotations = null;
                    MethodNode enabled = node.methods.stream().filter(m -> m.name.equals("enabled") && m.desc.equals("()Z")).findFirst().orElseThrow();
                    int returns = 0;
                    for (AbstractInsnNode insn : enabled.instructions.toArray()) if (insn.getOpcode() == Opcodes.IRETURN) {
                        InsnList call = new InsnList();
                        call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        call.add(new InsnNode(Opcodes.SWAP));
                        call.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, handler.name, handler.desc, false));
                        enabled.instructions.insertBefore(insn, call); returns++;
                    }
                    require(returns > 0, "Exordium enabled() must have a return injection point");
                    enabled.maxStack += 2; node.methods.add(handler);
                }
                ClassWriter writer = new ClassWriter(0); node.accept(writer); byte[] bytes = writer.toByteArray();
                ClassLoader loader = new ClassLoader(ExordiumChecks.class.getClassLoader()) {
                    @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                        if (!name.equals(TARGET)) return super.loadClass(name, resolve);
                        Class<?> result = findLoadedClass(name);
                        if (result == null) result = defineClass(name, bytes, 0, bytes.length);
                        if (resolve) resolveClass(result);
                        return result;
                    }
                };
                return Class.forName(TARGET, true, loader);
            }

            private static void pacing(Object buffer, boolean due) throws Exception {
                Field f = buffer.getClass().getDeclaredField("pacing"); f.setAccessible(true); Object pacing = f.get(buffer);
                Field flag = pacing.getClass().getDeclaredField("cooldownOver"); flag.setAccessible(true); flag.set(pacing, null);
                pacing.getClass().getMethod("setCooldown", long.class).invoke(pacing, due ? 0L : Long.MAX_VALUE);
            }
            private static boolean bool(Object object, String name) throws Exception {
                return (boolean) object.getClass().getMethod(name).invoke(object);
            }
            private static void flush(Object buffer, String... contents) throws Exception {
                if (!bool(buffer, "renderBuffer")) class_310.method_1551().method_1522().view.content.addAll(List.of(contents));
                buffer.getClass().getMethod("postRender").invoke(buffer);
            }

            private static int scenario(Class<?> type, boolean callerScheduling, int fps) throws Exception {
                Config.ComponentSettings settings = new Config.ComponentSettings(true, 30);
                boolean patched = Arrays.stream(type.getDeclaredMethods()).anyMatch(m -> m.getName().equals("meteor$keepCacheForVanillaPass"));
                Object buffer = type.getConstructor(Supplier.class).newInstance((Supplier<Config.ComponentSettings>) () -> settings);
                class_310 mc = class_310.method_1551(); mc.field_1755 = null; mc.temporary = null;
                BufferedComponent.captures = 0;
                int badFrames = 0; double nextUpdate = 0;
                for (int frame = 0; frame < fps * 3; frame++) {
                    mc.field_1755 = frame >= fps && frame < fps + fps / 4 ? new net.minecraft.class_437() : null;
                    mc.main.view.content.clear(); mc.main.view.content.add("world");
                    double now = frame * 1000.0 / fps;
                    pacing(buffer, now >= nextUpdate);
                    boolean extracted = !bool(buffer, "skipGuiRendering");
                    if (extracted) nextUpdate = now + 33;
                    if (extracted) flush(buffer, "scoreboard", "tab"); else flush(buffer, "placeholder");
                    if (callerScheduling || extracted) {
                        EmberGuiPass.begin();
                        try {
                            mc.main.view.content.add("ember");
                            // An expensive frame can cross Exordium's deadline between submissions.
                            pacing(buffer, frame % 11 == 0);
                            EmberGuiPass.begin();
                            try { flush(buffer, "hud-item"); } finally { EmberGuiPass.end(); }
                            flush(buffer, "overlay-tooltip");
                        } finally { EmberGuiPass.end(); }
                    }
                    require(mc.temporary == null && !EmberGuiPass.isActive(), "Framebuffer and overlay scopes must be restored");
                    if (!mc.main.view.content.containsAll(List.of("scoreboard", "tab", "ember", "hud-item", "overlay-tooltip"))) badFrames++;
                }
                require(BufferedComponent.captures > 0, "Vanilla HUD caching must remain enabled");
                if (fps > 60 && callerScheduling && patched) {
                    require(BufferedComponent.captures < fps, "HUD caching must still reduce vanilla redraws");
                }
                mc.field_1755 = null;
                require(bool(buffer, "enabled"), "The normal vanilla pass must keep Exordium enabled");
                EmberGuiPass.begin();
                try {
                    if (patched) {
                        require(!bool(buffer, "enabled"), "Ember passes must bypass the vanilla cache");
                    }
                } finally { EmberGuiPass.end(); }
                settings.setEnabled(false);
                require(!bool(buffer, "enabled"), "A user-disabled cache must stay disabled");
                return badFrames;
            }

            public static void main(String[] args) throws Exception {
                try (JarFile jar = new JarFile(args[0])) {
                    targetBytes = jar.getInputStream(jar.getJarEntry(TARGET.replace('.', '/') + ".class")).readAllBytes();
                }
                handlerBytes = Files.readAllBytes(Path.of(args[1]));
                Class<?> original = load(false), patched = load(true);
                require(scenario(original, false, 144) > 0, "Baseline must reproduce skipped-overlay flicker");
                require(scenario(original, true, 144) > 0, "Moving the HUD hook alone must still reproduce cache corruption");
                for (int fps : new int[]{20, 30, 60, 144, 360}) {
                    require(scenario(patched, true, fps) == 0, "Vanilla HUD and Ember must remain visible at " + fps + " FPS");
                }
                try {
                    EmberGuiPass.begin();
                    try { throw new IllegalArgumentException("synthetic render failure"); } finally { EmberGuiPass.end(); }
                } catch (IllegalArgumentException expected) { }
                require(!EmberGuiPass.isActive(), "A failed overlay must not disable caching on subsequent frames");
                System.out.println("Exordium checks passed: both original failures reproduced; mixed GUI submissions, cached frames, inventory, disabled caching and exception recovery pass at 20/30/60/144/360 FPS.");
            }
            private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
        }
    """,
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exordium-jar", required=True, type=Path)
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    args = parser.parse_args()
    if not args.java_home:
        parser.error("Pass --java-home pointing to Java 21")
    root = Path(__file__).resolve().parents[1]
    out = root / "build" / "exordium-checks"
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    dependencies = []
    for coordinate in ("org.ow2.asm/asm", "org.ow2.asm/asm-tree", "net.fabricmc/sponge-mixin", "io.github.llamalad7/mixinextras-fabric"):
        jars = sorted((cache / coordinate).rglob("*.jar"))
        if not jars:
            parser.error(f"Missing cached dependency: {coordinate}; configure the Gradle project first")
        dependencies.append(jars[-1])
    sources = []
    for name, source in SOURCES.items():
        path = out / "src" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(source), encoding="utf-8")
        sources.append(path)
    source_root = root / "src/main/java/meteordevelopment/meteorclient"
    sources += [source_root / "utils/render/EmberGuiPass.java", source_root / "mixin/exordium/BufferInstanceMixin.java"]
    classes = out / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    classpath = os.pathsep.join(map(str, [classes, args.exordium_jar.resolve(), *dependencies]))
    suffix = ".exe" if os.name == "nt" else ""
    subprocess.run([str(args.java_home / "bin" / f"javac{suffix}"), "-proc:none", "-cp", classpath, "-d", str(classes), *map(str, sources)], check=True)
    subprocess.run([str(args.java_home / "bin" / f"java{suffix}"), "-cp", classpath, "checks.ExordiumChecks", str(args.exordium_jar.resolve()), str(classes / "meteordevelopment/meteorclient/mixin/exordium/BufferInstanceMixin.class")], check=True)


if __name__ == "__main__":
    main()
