#version 330 core
layout (location = 0) in vec3 pos;
layout (location = 1) in vec4 color;
layout (std140) uniform MeshData { mat4 u_Proj; mat4 u_ModelView; };
out vec3 v_Direction;
out vec4 v_Color;
void main() {
    // Discard camera translation and pin the dome to the far depth plane.
    // Terrain still occludes it, including at very short view distances.
    vec4 clip = u_Proj * vec4(mat3(u_ModelView) * pos, 1.0);
    gl_Position = clip.xyww;
    v_Direction = pos;
    v_Color = color;
}
