#version 330 core

uniform sampler2D u_Texture;
layout (std140) uniform GlassData {
    vec4 u_Rect;
    vec4 u_Surface; // radius, seconds, density, refraction
    vec4 u_Accent;  // RGB, light/dark tint
    vec4 u_Screen;  // framebuffer size, pointer position
};
in vec2 v_TexCoord;
in vec4 v_Color;
out vec4 color;

float roundedBox(vec2 p, vec2 halfSize, float radius) {
    vec2 q = abs(p) - halfSize + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

void main() {
    vec2 pixel = vec2(gl_FragCoord.x, u_Screen.y - gl_FragCoord.y);
    vec2 halfSize = u_Rect.zw * 0.5;
    vec2 p = pixel - u_Rect.xy - halfSize;
    float radius = u_Surface.x;
    float sd = roundedBox(p, halfSize, radius);
    float aa = max(fwidth(sd), 0.7);
    float coverage = 1.0 - smoothstep(-aa, aa, sd);
    if (coverage <= 0.001) discard;

    // Lens curvature follows every edge, including the rounded corners.
    vec2 q = abs(p) - halfSize + radius;
    vec2 normal = normalize(max(q, vec2(0.001))) * sign(p);
    if (q.x > q.y && q.y < 0.0) normal = vec2(sign(p.x), 0.0);
    if (q.y > q.x && q.x < 0.0) normal = vec2(0.0, sign(p.y));
    float depth = max(-sd, 0.0);
    float bevel = exp(-depth / max(3.0, min(halfSize.y * 0.55, 12.0)));
    float lens = (1.5 + 12.0 * u_Surface.w) * bevel * (1.0 - exp(-depth * 0.5));
    vec2 uv = (pixel + normal * lens) / u_Screen.xy;
    uv.y = 1.0 - uv.y;
    vec2 fringe = normal * (0.25 + 0.7 * u_Surface.w) * bevel / u_Screen.xy;
    fringe.y = -fringe.y;
    vec2 lo = 0.5 / u_Screen.xy;
    vec2 hi = 1.0 - lo;
    vec3 scene = vec3(
        texture(u_Texture, clamp(uv + fringe, lo, hi)).r,
        texture(u_Texture, clamp(uv, lo, hi)).g,
        texture(u_Texture, clamp(uv - fringe, lo, hi)).b);

    // The scene supplies the pane's color. Charcoal keeps labels readable.
    vec3 charcoal = mix(vec3(0.028, 0.032, 0.041), vec3(0.78), u_Accent.a);
    float density = mix(0.48, 0.83, u_Surface.z);
    vec3 pane = mix(scene, charcoal, density);
    pane += scene * 0.07;

    float topLight = pow(max(dot(normal, normalize(vec2(-0.4, -1.0))), 0.0), 2.0);
    float rim = exp(-abs(sd + 0.65) * 1.7);
    float sweep = 0.5 + 0.5 * sin(u_Surface.y * 0.65 + p.x / max(halfSize.x, 1.0) * 1.2);
    vec2 mouseDelta = (pixel - u_Screen.zw) / vec2(140.0, 100.0);
    float pointer = exp(-dot(mouseDelta, mouseDelta));
    pane += vec3(1.0) * rim * (0.07 + topLight * 0.27 + pointer * 0.16);
    pane += u_Accent.rgb * rim * (0.025 + 0.035 * sweep);
    pane += vec3(1.0) * pointer * 0.025;
    pane += vec3(1.0) * (1.0 - clamp((p.y + halfSize.y) / max(u_Rect.w, 1.0), 0.0, 1.0)) * 0.02;
    color = vec4(pane, coverage * v_Color.a);
}
