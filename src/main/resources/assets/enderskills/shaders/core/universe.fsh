#version 330

#moj_import <minecraft:globals.glsl>

// Ender Skills (1.12.2) "universe" shader: volumetric star field tinted with the vertex color.
#define iterations 17
#define formuparam 0.53

#define volsteps 20
#define stepsize 0.1

#define tile 0.850
#define speed 0.00005

#define saturation 0.0
#define brightness 0.0015
#define darkmatter 0.300
#define distfading 0.730

uniform sampler2D Sampler0;

in vec3 position;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec3 dir = normalize(-position);
    vec4 base = texture(Sampler0, vec2(dir.x, dir.y));
    float time = GameTime * 24000.0 * speed + 0.25;
    vec2 dimensions = max(ScreenSize, vec2(1.0));

    float a1 = 111.5 + time / dimensions.x * 2.0;
    float a2 = 111.8 + time / dimensions.y * 2.0;
    mat2 rot1 = mat2(cos(a1), sin(a1), -sin(a1), cos(a1));
    mat2 rot2 = mat2(cos(a2), sin(a2), -sin(a2), cos(a2));
    dir.xz *= rot1;
    dir.xy *= rot2;
    vec3 from = vec3(1.0, 0.5, 0.5);
    from += vec3(time * 2.0, time, -2.0);
    from.xz *= rot1;
    from.xy *= rot2;

    float s = 0.1, fade = 1.0;
    vec4 v = vec4(0.0);
    for (int r = 0; r < volsteps; r++) {
        vec3 p = from + s * dir * 0.5;
        p = abs(vec3(tile) - mod(p, vec3(tile * 2.0)));
        float pa, a = pa = 0.0;
        for (int i = 0; i < iterations; i++) {
            p = abs(p) / dot(p, p) - formuparam;
            a += abs(length(p) - pa);
            pa = length(p);
        }
        float dm = max(0.0, darkmatter - a * a * 0.001);
        a *= a * a;
        if (r > 6) fade *= 1.0 - dm;
        v += fade;
        v += vec4(s, s * s, s * s * s * s, s * s * s * s * s * s) * a * brightness * fade;
        fade *= distfading;
        s += stepsize;
    }
    v = mix(vec4(length(v)), v, saturation);
    vec4 stars = v * 0.01;
    fragColor = base;
    fragColor.rgb = mix(base.rgb, stars.rgb * vertexColor.rgb, clamp(stars.a, 0.0, 1.0));
    fragColor.a *= vertexColor.a;
}
