#version 330 core

in vec2 v_TexCoord;
in vec2 v_OneTexel;

uniform sampler2D u_Texture;

layout (std140) uniform OutlineData {
    int width;
    float fillOpacity;
    int shapeMode;
    float glowMultiplier;
    vec4 surface;
} u_Outline;

out vec4 color;

void main() {
    vec4 center = texture(u_Texture, v_TexCoord);

    if (center.a != 0.0) {
        if (u_Outline.shapeMode == 0) discard;
        center = vec4(center.rgb, center.a * u_Outline.fillOpacity);
        vec2 grid = abs(fract(gl_FragCoord.xy / 9.0) - 0.5);
        float pattern = u_Outline.surface.x < 0.5 ? 0.0 : (u_Outline.surface.x < 1.5 ? max(grid.x, grid.y) : grid.y);
        center.rgb *= 1.0 + smoothstep(0.35, 0.5, pattern) * u_Outline.surface.y * 0.45;
    }
    else {
        if (u_Outline.shapeMode == 1) discard;

        float dist = u_Outline.width * u_Outline.width * 4.0;

        for (int x = -u_Outline.width; x <= u_Outline.width; x++) {
            for (int y = -u_Outline.width; y <= u_Outline.width; y++) {
                vec4 offset = texture(u_Texture, v_TexCoord + v_OneTexel * vec2(x, y));

                if (offset.a != 0) {
                    float ndist = max(0.0, float(x * x + y * y) - 0.5);
                    dist = min(ndist, dist);
                    center = offset;
                }
            }
        }

        float minDist = u_Outline.width * u_Outline.width;

        float coverage = 1.0 - smoothstep(max(0.0, float(u_Outline.width) - 0.75), float(u_Outline.width) + 0.5, sqrt(dist));
        center.a *= coverage * min(u_Outline.glowMultiplier, 1.0 + u_Outline.glowMultiplier * 0.12);
    }

    color = center;
}
