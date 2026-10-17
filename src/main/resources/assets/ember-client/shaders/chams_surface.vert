#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:fog.glsl>
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec3 Normal;
out vec2 surfaceUV;
out vec4 surfaceColor;
out float surfaceLight;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    surfaceUV = UV0;
    surfaceColor = Color * ColorModulator;
    vec3 n = normalize(mat3(ModelViewMat) * Normal);
    surfaceLight = 0.68 + 0.20 * max(n.y, 0.0) + 0.12 * abs(n.z);
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
}
