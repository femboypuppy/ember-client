// The same procedural surface is used on world models and the settings mannequin.
float emberHash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
float emberNoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(emberHash(i), emberHash(i + vec2(1, 0)), u.x),
               mix(emberHash(i + vec2(0, 1)), emberHash(i + 1.0), u.x), u.y);
}
float emberCloud(vec2 p) {
    float n = 0.0, a = 0.55;
    for (int i = 0; i < 4; i++) { n += a * emberNoise(p); p = mat2(1.6, 1.2, -1.2, 1.6) * p + 3.7; a *= 0.5; }
    return n;
}
vec3 emberMaterial(vec2 uv, vec3 accent, float time, int material) {
    vec2 p = uv * 7.0;
    if (material == 0) return accent;
    if (material == 1) {
        vec2 drift = vec2(time * 0.035, -time * 0.018);
        float mist = emberCloud(p + drift);
        float filaments = emberCloud(p * 1.7 + vec2(mist * 2.2, -mist) - drift);
        vec3 secondary = mix(accent.brg, vec3(0.58, 0.22, 0.90), 0.48);
        vec3 nebula = mix(accent * 0.10, secondary * 0.70, smoothstep(0.25, 0.82, mist));
        nebula += accent * pow(filaments, 3.0) * 1.65;
        vec2 stars = uv * 145.0;
        vec2 cell = floor(stars), q = fract(stars) - 0.5;
        float seed = emberHash(cell);
        float star = (1.0 - smoothstep(0.035, 0.19, length(q))) * step(0.973, seed);
        star *= 0.75 + 0.25 * sin(time * 0.7 + seed * 90.0);
        return nebula + mix(accent, vec3(1.0), 0.82) * star;
    }
    if (material == 2) {
        float bend = sin(p.x * 0.8 + time * 0.12) * 0.9 + emberNoise(p * 0.6) * 1.6;
        float ribbons = pow(0.5 + 0.5 * sin(p.y * 2.2 + bend + time * 0.15), 5.0);
        vec3 secondary = mix(accent, accent.gbr, 0.22);
        return accent * 0.32 + mix(accent, secondary, smoothstep(0.1, 0.9, uv.y)) * ribbons * 0.84;
    }
    float wave = sin(p.x * 1.8 + sin(p.y * 1.2 + time * 0.2) * 1.7 + time * 0.16);
    float vein = pow(1.0 - abs(wave), 6.0);
    vec3 pearl = mix(accent * 0.48, mix(accent, vec3(1.0), 0.7), wave * 0.5 + 0.5);
    return pearl + mix(accent.brg, vec3(1.0), 0.6) * vein * 0.34;
}
