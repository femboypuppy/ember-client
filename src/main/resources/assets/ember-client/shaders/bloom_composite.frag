#version 330 core
in vec2 uv;
out vec4 color;
uniform sampler2D u_Texture;
layout(std140) uniform BloomData { vec4 BloomSettings; };
void main() {
    color = vec4(texture(u_Texture, uv).rgb, BloomSettings.y);
}
