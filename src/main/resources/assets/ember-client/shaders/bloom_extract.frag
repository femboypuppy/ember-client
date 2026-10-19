#version 330 core
in vec2 uv;
out vec4 color;
uniform sampler2D u_Texture;
layout(std140) uniform BloomData { vec4 BloomSettings; };
vec3 bright(vec2 p) {
    vec3 value = texture(u_Texture, p).rgb;
    float luminance = max(value.r, max(value.g, value.b));
    return value * smoothstep(BloomSettings.x - 0.1, BloomSettings.x + 0.1, luminance);
}
void main() {
    vec2 texel = 0.5 / vec2(textureSize(u_Texture, 0));
    vec3 result = bright(uv) * 4.0;
    result += bright(uv + texel) + bright(uv - texel);
    result += bright(uv + vec2(texel.x, -texel.y)) + bright(uv + vec2(-texel.x, texel.y));
    color = vec4(result / 8.0, 1.0);
}
