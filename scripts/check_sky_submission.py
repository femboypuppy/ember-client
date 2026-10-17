"""Exercise the production sky submission method with a strict recording GPU device.

No OpenGL context or game launch is needed. The method is compiled unchanged from
its source; collaborators reject uploads inside a pass just like Minecraft does.
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
    static class Client { Target getFramebuffer() { return new Target(); } }
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

    public static void main(String[] args) {
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
    harness.write_text(HARNESS.replace("// PRODUCTION_METHOD", method), encoding="utf-8")
    stub = directory / "com/mojang/blaze3d/buffers/GpuBufferSlice.java"
    stub.parent.mkdir(parents=True)
    stub.write_text("package com.mojang.blaze3d.buffers; public class GpuBufferSlice {}", encoding="utf-8")
    subprocess.run([java_tool("javac"), "-encoding", "UTF-8", "-d", temporary, str(harness), str(stub)], check=True)
    subprocess.run([java_tool("java"), "-ea", "-cp", temporary, "SkySubmissionChecks"], check=True)
