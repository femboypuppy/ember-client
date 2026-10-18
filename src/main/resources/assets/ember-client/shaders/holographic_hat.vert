#version 330 core
layout (location = 0) in vec3 pos;
layout (location = 1) in vec4 color;
layout (std140) uniform Projection { mat4 ProjMat; };
layout (std140) uniform DynamicTransforms { mat4 ModelViewMat; };
out vec4 v_Color;
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    v_Color = color;
}
