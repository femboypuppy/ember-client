#version 330 core
in vec2 uv;
out vec4 color;
uniform sampler2D u_Depth;
layout(std140) uniform SceneFogData {
    mat4 InverseProjection;
    vec4 FogTint;
    vec4 FogRange;
};
void main() {
    float depth = texture(u_Depth, uv).r;
    // Preserve the sky; sky color already comes from the atmosphere shader.
    if (depth >= 0.9999999) { color = vec4(0); return; }
    vec4 position = InverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    float distance = length(position.xyz / max(abs(position.w), 0.000001));
    float haze = smoothstep(FogRange.x, FogRange.y, distance) * FogRange.z;
    color = vec4(FogTint.rgb, clamp(haze, 0.0, 1.0));
}
