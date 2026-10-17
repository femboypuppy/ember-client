#version 330
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <ember-client:materials.glsl>
in vec2 surfaceUV;
in vec4 surfaceColor;
in float surfaceLight;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
out vec4 fragColor;
void main() {
    float time = 0.0;
#ifdef ANIMATED
    time = GameTime * 1200.0;
#endif
    vec3 rgb = emberMaterial(surfaceUV, surfaceColor.rgb, time, MATERIAL);
    vec4 color = vec4(rgb * surfaceLight, surfaceColor.a);
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
