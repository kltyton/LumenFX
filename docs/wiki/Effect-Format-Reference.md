<p align="right">
  <strong>English</strong> | <a href="Effect-Format-Reference-zh">简体中文</a>
</p>

# Effect Format Reference

LumenFX loads effects from standard Minecraft resource packs.

## Resource Layout

An effect ID such as `my_mod:magic/fireball` maps to:

- **LumenFX JSON (Unity / Niagara / hand-authored):**
  `assets/my_mod/lumenfx/effects/magic/fireball.json`
- **Photon `.fx`:**
  `assets/my_mod/lumenfx/effects/magic/fireball.fx`
- **Custom GLSL Fragment Shaders (`my_mod:vfx/plasma`):**
  `assets/my_mod/shaders/vfx/plasma.fsh`
- **Vector Fields (`my_mod:curl_32`):**
  `assets/my_mod/lumenfx/vector_fields/curl_32.lfvf`
- **Textures:** Standard Minecraft texture paths, e.g. `my_mod:textures/vfx/spark.png`

---

## JSON Schema (`format_version: 1`)

Top-level JSON structure:

```json
{
  "format_version": 1,
  "materials": {
    "glow": {
      "fragment_shader": "lumenfx:core/effect",
      "textures": {
        "Sampler0": "my_mod:textures/vfx/spark.png"
      },
      "blend": "additive",
      "depth_test": true,
      "depth_write": false,
      "cull": false,
      "parameters": [1.0, 0.0, 0.0, 0.0]
    }
  },
  "emitters": [
    {
      "name": "embers",
      "renderer": "sprite",
      "material": "glow",
      "local_space": false,
      "delay": 0.0,
      "duration": 3.0,
      "loop": true,
      "max_particles": 512,
      "rate": 40.0,
      "lifetime": { "min": 0.6, "max": 1.2 },
      "width": 0.25,
      "height": 0.25,
      "start_speed": { "min": 1.5, "max": 3.0 },
      "shape": { "type": "sphere", "size": [0.3, 0.3, 0.3], "surface": false },
      "force_over_life": [0.0, -4.5, 0.0],
      "size_over_life": {
        "keys": [
          { "time": 0.0, "value": 0.4, "interpolation": "linear" },
          { "time": 0.3, "value": 1.0, "interpolation": "linear" },
          { "time": 1.0, "value": 0.0, "interpolation": "linear" }
        ]
      },
      "color": [
        1.0,
        { "keys": [{ "time": 0.0, "value": 0.85 }, { "time": 1.0, "value": 0.2 }] },
        0.1,
        { "keys": [{ "time": 0.0, "value": 1.0 }, { "time": 1.0, "value": 0.0 }] }
      ]
    }
  ]
}
```

---

## Emitter Fields

| Field | Description |
| :--- | :--- |
| `renderer` | `sprite`, `ribbon`, `beam`, or `mesh` |
| `material` | Key inside the effect's `materials` object |
| `local_space` | Whether particles simulate in emitter-local space (`true`) or world space (`false`) |
| `delay` / `duration` / `loop` | Start delay (seconds), cycle duration (seconds), and looping toggle |
| `rate` / `distance_spacing` / `bursts` | Time-based emission (particles/sec), world-distance spacing (blocks), and scheduled bursts |
| `lifetime` / `width` / `height` / `rotation` | Evaluated at particle birth by normalized emitter age (`0..1`); rotation in radians |
| `start_speed` / `start_direction` / `start_color` | Birth speed (blocks/s), initial direction vector, and birth RGBA tint |
| `velocity_min` / `velocity_max` / `acceleration` | Legacy initial velocity range (blocks/s) and constant acceleration (blocks/s²) |
| `velocity_over_life` / `force_over_life` | 3-axis scalar curves over normalized particle age (`0..1`) |
| `angular_velocity` | Angular velocity curve (radians/s) integrated over particle age |
| `drag` / `mass` / `integrator` | Drag coefficient/curve, birth mass, and integrator mode (`analytic` or `niagara`) |
| `shape` | `point`, `box`, `sphere`, `cylinder`, `cone` (`size` = half-extents; cone Y = height) |
| `size_over_life` / `color` / `custom_data` | Per-particle normalized age curves for size, RGBA color, and 4 custom shader floats |
| `atlas_columns` / `atlas_rows` / `atlas_frame` / `atlas_row` | Flipbook sprite-sheet grid dimensions, frame curve, and optional fixed row |
| `uv_mode` / `uv_distance` | Ribbon UV mapping (`stretch` or distance-repeating) |
| `subdivisions` / `width_segments` / `attach_head` | Ribbon longitudinal & transverse subdivision, and head-attachment toggle |
| `sort_order` / `sort_mode` | Renderer priority and sprite sorting (`none`, `distance`, `view_depth`) |
| `movement_threshold` | Distance jump threshold that starts a new ribbon segment instead of stretching |

---

## Scalar Curves & Expressions

Any scalar field accepts:

- **Constant number:** `1.5`
- **Random range (stable per particle):** `{ "min": 0.5, "max": 1.5 }`
- **Piecewise curve:** `{ "keys": [ { "time": 0.0, "value": 1.0, "arrive": 0.0, "leave": 0.0, "interpolation": "cubic" } ] }`
  - `interpolation`: `constant`, `linear`, or `cubic` (Hermite tangents in value-per-curve-time units)
- **Composite expressions:** `{ "multiply": [ ... ] }` (up to 5 factors) or `{ "add": [ ... ] }`

---

## Materials & Custom GLSL Shaders

Built-in fragment shaders:
- `lumenfx:core/effect` — Samples `Sampler0` multiplied by particle RGBA color.
- `lumenfx:core/color` — Outputs particle RGBA color directly without texture sampling.

### Writing a Custom Fragment Shader

Place your `.fsh` file at `assets/<namespace>/shaders/vfx/<name>.fsh` and reference it as `"fragment_shader": "<namespace>:vfx/<name>"`.

Available vertex-to-fragment inputs:
- `in vec2 effectUV;` — Primary UV coordinates
- `in vec2 effectUV1;` — Secondary UV coordinates (for dual-UV ribbons / Niagara materials)
- `in vec4 effectColor;` — Evaluated particle RGBA color
- `in vec4 effectParticle;` — `(normalizedAge, randomSeed01, lifetimeSeconds, birthTimeSeconds)`
- `in vec4 effectCustom;` — 4 custom per-particle floats from `custom_data`
- `in vec3 effectPosition;` — Particle world/view position

Uniform block `EffectFrame`:
- `vec4 EffectClock;` (`EffectClock.x` = elapsed world seconds)
- `vec4 MaterialParameters;` (4 floats from the material's `parameters` array)

Material state options:
- `blend`: `alpha`, `additive`, `add`, `premultiplied`, `multiply`, `opaque`
- `depth_test` / `depth_compare` / `depth_write`: Depth testing and write control (automatically adapted for Minecraft reverse-Z on 26.2)
- `cull`: Back-face culling toggle
- `fog`: `{ "enabled": false, "per_pixel": false }`
