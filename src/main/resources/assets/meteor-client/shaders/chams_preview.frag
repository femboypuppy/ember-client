#version 330 core
#moj_import <meteor-client:materials.glsl>
in vec2 v_TexCoord;
in vec4 v_Color;
layout(std140) uniform MaterialData { vec4 material; vec4 tint; };
out vec4 color;
void main() {
    if (material.y < 0.0) {
        vec2 p = v_TexCoord * 5.0;
        vec2 edge = abs(fract(p) - 0.5);
        vec2 aa = max(fwidth(p), vec2(0.01));
        vec2 etched = smoothstep(vec2(0.5) - aa * 1.3, vec2(0.5), edge);
        float detail = material.z < 0.5 ? 0.0 : (material.z < 1.5 ? max(etched.x, etched.y) : etched.y);
        color = vec4(tint.rgb * v_Color.rgb * (1.0 + detail * material.w * 1.5), v_Color.a);
    } else color = vec4(emberMaterial(v_TexCoord, tint.rgb, material.x, int(material.y)) * v_Color.rgb, v_Color.a);
}
