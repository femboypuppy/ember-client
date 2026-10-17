#version 330 core
#moj_import <meteor-client:materials.glsl>

in vec2 v_TexCoord;
in vec2 v_OneTexel;

uniform sampler2D u_Texture;
uniform sampler2D u_TextureI;

layout (std140) uniform ImageData {
    vec4 u_Color;
    vec4 u_Material;
};
layout (std140) uniform PostData { vec2 u_Size; float u_Time; };

out vec4 color;

void main() {
    float mask = texture(u_Texture, v_TexCoord).a;
    if (mask == 0.0) discard;
    if (u_Material.x < 0.0) color = texture(u_TextureI, v_TexCoord) * u_Color;
    else color = vec4(emberMaterial(v_TexCoord * vec2(u_Size.x / max(u_Size.y, 1.0), 1.0), u_Color.rgb,
        u_Time * u_Material.y, int(u_Material.x)), u_Color.a);
    color.a *= mask;
}
