# LumenFX Custom GLSL Fragment Shader Contract

> Source-verified against `common/src/main/resources/assets/lumenfx/shaders/core/effect.fsh`, `instance_sprite.vsh`, `include/material_fog.glsl`, and `DirectGlCustomMaterialCompiler.java` (1.20.1–26.1.2).

---

## 1. The Cross-Version GLSL 330 Contract (Works on ALL 10 Targets: 1.20.1–26.3)

Always place custom fragment shaders at:
`assets/<namespace>/shaders/vfx/<name>.fsh`
and reference them in material JSON as:
`"fragment_shader": "<namespace>:vfx/<name>"`.

### Minimal Cross-Version Template

```glsl
#version 330
#moj_import <lumenfx:material_fog.glsl>

uniform sampler2D Sampler0; // Omit if material defines no textures

layout(std140) uniform EffectFrame {
    vec4 EffectClock;        // EffectClock.x = elapsed world seconds
    vec4 MaterialParameters; // 4 floats from material JSON "parameters": [p0, p1, p2, p3]
};

in vec2 effectUV;       // Primary UV (0..1)
in vec2 effectUV1;      // Secondary UV (dual-UV ribbons / Niagara materials)
in vec4 effectColor;    // Evaluated particle RGBA (start_color * color * handle tint)
in vec4 effectParticle; // (normalizedAge 0..1, randomSeed 0..1, lifetimeSeconds, birthTimeSeconds)
in vec4 effectCustom;   // 4 per-particle floats from emitter "custom_data"
out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, effectUV);
    fragColor = lfx_apply_material_fog(tex * effectColor);
}
```

### Why `#version 330` + `effect*` Works Everywhere

1. **Minecraft 26.2 & 26.3 (Vulkan & OpenGL via Blaze3D)**: Natively compiles `#version 330`, expands `#moj_import`, supplies `EffectFrame`, and matches `instance_sprite.vsh` / `instance_ribbon.vsh` varyings.
2. **Minecraft 1.20.1, 1.21.1, 26.1.2 (`DirectGlCustomMaterialCompiler.java`)**:
   - Detects leading `#version` and preserves the full shader source;
   - Emits `effectUV`, `effectUV1`, `effectColor`, `effectParticle`, `effectCustom`, `effectPosition` from its default vertex shader;
   - Expands `#moj_import <lumenfx:material_fog.glsl>`;
   - Binds `EffectFrame` UBO and all named `textures` samplers (`Sampler0`, `Sampler1`, ...);
   - Automatically downgrades `layout(...)` from GLSL 330 to strict GLSL 150 on OpenGL 3.2 Core contexts.

> **CRITICAL WARNING**: Do **NOT** write headerless snippet shaders using `vUv`, `vColor`, `uTime`, `uTex`, or `lfx_soft_particle_alpha()` unless you are explicitly targeting **only** `1.20.1–26.1.2`. Those convenience aliases are injected solely by `DirectGlCustomMaterialCompiler` and fail to compile on `26.2 / 26.3`!

---

## 2. Built-in Shaders & Available Inputs

### Built-in Fragment Shaders (No `.fsh` File Required)
- `lumenfx:core/effect`: Samples `Sampler0` × `effectColor`, applies material fog.
- `lumenfx:core/color`: Outputs `effectColor` directly (no texture), applies material fog.
- `lumenfx:core/effect_premultiplied` / `lumenfx:core/color_premultiplied`: Premultiplied-alpha variants.

### Vertex-to-Fragment Varyings
- `in vec2 effectUV;` — Primary UV coordinates (`[0, 1]` on quads/ribbons, atlas-remapped when `atlas_columns`/`atlas_rows` > 1).
- `in vec2 effectUV1;` — Secondary UV coordinates.
- `in vec4 effectColor;` — Combined particle RGBA color.
- `in vec4 effectParticle;` — `x` = normalized age `0..1`, `y` = stable per-particle random `0..1`, `z` = lifetime (s), `w` = birth time (s).
- `in vec4 effectCustom;` — 4 animated floats from emitter `custom_data`.
- `in vec3 effectPosition;` — Particle position (Note: already declared inside `#moj_import <lumenfx:material_fog.glsl>` — **do NOT redeclare `in vec3 effectPosition;`** if importing `material_fog.glsl`!).

### Uniforms
- `layout(std140) uniform EffectFrame { vec4 EffectClock; vec4 MaterialParameters; };`
  - `EffectClock.x`: Continuous world time in seconds.
  - `MaterialParameters`: Direct mapping from material JSON `"parameters": [p0, p1, p2, p3]`.
- `uniform sampler2D Sampler0;` (and `Sampler1`, `Sampler2`, ... matching keys in material JSON `"textures"`).

---

## 3. Procedural GLSL VFX Building Blocks (Zero Texture Dependency)

Procedural `.fsh` shaders let you deliver crisp, resolution-independent VFX without authoring PNGs. Always guard `pow()` / `sqrt()` / `length()` inputs against negative values or zero division so HDR bloom never explodes with `NaN`/`Inf` black squares!

### 3.1 Clean Radial Soft Mask & Sharp Geometric Ring (L2 Hero Shape)
```glsl
vec2 p = effectUV * 2.0 - 1.0;
float r = length(p);
if (r > 1.0) discard;

// Sharp 1-2px luminous ring with soft inner falloff
float radius = clamp(MaterialParameters.x, 0.1, 0.95);
float width  = max(MaterialParameters.y, 0.02);
float ring   = exp(-pow((r - radius) / width, 2.0));
float edge   = smoothstep(1.0, 0.85, r);
```

### 3.2 Procedural Sphere Fresnel Shield / Energy Orb
```glsl
vec2 p = effectUV * 2.0 - 1.0;
float r2 = dot(p, p);
if (r2 > 0.98) discard;
float z = sqrt(max(0.0, 1.0 - r2));
float fresnel = pow(max(0.0, 1.0 - z), 2.5);
```

### 3.3 Lightweight 2D Value Noise & Domain-Warped Dissolve (Flame / Smoke / Portal)
```glsl
float lfx_hash21(vec2 p) {
    p = fract(p * vec2(127.1, 311.7));
    p += dot(p, p + 19.19);
    return fract(p.x * p.y);
}

float lfx_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(lfx_hash21(i), lfx_hash21(i + vec2(1.0, 0.0)), f.x),
        mix(lfx_hash21(i + vec2(0.0, 1.0)), lfx_hash21(i + vec2(1.0, 1.0)), f.x),
        f.y
    );
}

// 2-octave fBm + age-driven dissolve (dissolve threshold driven by effectParticle.x)
float lfx_fbm2(vec2 p) {
    return lfx_noise(p) * 0.65 + lfx_noise(p * 2.13 + vec2(5.2, 1.3)) * 0.35;
}
```

### 3.4 Polar Coordinates for Runes, Swirls & Shockwaves
```glsl
vec2 p = effectUV * 2.0 - 1.0;
float r = length(p);
float angle = atan(p.y, p.x); // -PI..PI
float spin = angle + EffectClock.x * 2.0 + r * 3.0;
float spokes = 0.5 + 0.5 * cos(spin * 6.0);
```

---

## 4. GLSL Safety & Performance Rules for LumenFX

1. **No Duplicate `effectPosition` Declaration**: `#moj_import <lumenfx:material_fog.glsl>` already declares `in vec3 effectPosition;`. Redeclaring it causes a GLSL compile error.
2. **NaN / Inf Protection**: Always clamp inputs before `pow(max(x, 0.0), y)`, `sqrt(max(x, 0.0))`, and denominators `max(d, 1e-4)`. One `NaN` pixel in an HDR bloom buffer spreads into a giant black box across the screen!
3. **Output Conventions by Blend Mode**:
   - `additive` / `add`: Pre-multiply RGB by your fade/alpha (`vec4(rgb * alpha, alpha)`) or rely on `SRC_ALPHA, ONE`. Keeping RGB attenuated when `alpha -> 0` guarantees clean fade-outs across both OpenGL and Vulkan.
   - `alpha`: Straight alpha (`vec4(rgb, alpha)`), `discard` pixels below `0.004` to save fillrate on large transparent sprites.
   - `premultiplied`: Output `vec4(rgb * alpha, alpha)` — ideal for simultaneous bright fire + dark smoke in a single pass!
4. **Keep Fragment Cost Low for High-Count Emitters**: Avoid heavy raymarching loops (`> 8` steps) on ambient particles that spawn hundreds of overlapping billboards; reserve multi-octave procedural shaders for hero elements (L1/L2 shields, portals, beams, rings).
5. **Use `effectParticle.y` (Random Seed) for Phase Offset**: Offset noise UV or pulse phase by `effectParticle.y * 17.3` so neighboring particles never flicker in lockstep.
