#version 330 core
layout (location = 0) in vec3 pos;
layout (location = 1) in vec4 color;
layout (std140) uniform Projection { mat4 ProjMat; };
layout (std140) uniform DynamicTransforms { mat4 ModelViewMat; };
out vec3 v_Direction;
out vec4 v_Color;
void main() {
    // Discard camera translation and pin the dome to the far depth plane.
    // Terrain still occludes it, including at very short view distances.
    vec4 clip = ProjMat * vec4(mat3(ModelViewMat) * pos, 1.0);
    gl_Position = clip.xyww;
    v_Direction = pos;
    v_Color = color;
}
