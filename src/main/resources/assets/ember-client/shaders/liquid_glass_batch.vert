#version 330 core

layout (location = 0) in vec2 pos;
layout (std140) uniform MeshData {
    mat4 u_Proj;
    mat4 u_ModelView;
};
struct Pane {
    vec4 rect;
    vec4 parameters; // radius, opacity, unused, unused
};
layout (std140) uniform GlassBatchData {
    vec4 surface; // seconds, density, refraction, tint
    vec4 accent;
    vec4 screen;
    Pane panes[64];
};
flat out vec4 v_Rect;
flat out vec4 v_Surface;
flat out vec4 v_Accent;
flat out vec4 v_Screen;
out vec2 v_TexCoord;
out vec4 v_Color;

void main() {
    Pane pane = panes[gl_InstanceID];
    vec2 corner = (pos + 1.0) * 0.5;
    vec2 pixel = pane.rect.xy - 1.0 + corner * (pane.rect.zw + 2.0);
    gl_Position = u_Proj * u_ModelView * vec4(pixel, 0.0, 1.0);
    v_Rect = pane.rect;
    v_Surface = vec4(pane.parameters.x, surface.xyz);
    v_Accent = vec4(accent.rgb, surface.w);
    v_Screen = screen;
    v_TexCoord = corner;
    v_Color = vec4(1.0, 1.0, 1.0, pane.parameters.y);
}
