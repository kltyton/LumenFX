# LumenFX JSON v1 Schema & Cross-Version Compatibility Reference

> Source-verified against LumenFX `26.2 / 26.3` (`EffectJson.java`, `EffectEmitterJson.java`, `EffectJsonFields.java`, `EffectResources.java`, `MaterialDefinition.java`, `EmitterDefinition.java`, `MeshRendererJson.java`, `LightRendererJson.java`, `DecalRendererJson.java`, `VolumeRendererJson.java`) and `1.20.1–26.1.2` (`ProductionEffectParser.java`, `MaterialModel.java`, `UnityEmitterModel.java`).

---

## 1. Resource Path Contract

For an effect ID `<namespace>:<path>` (e.g. `my_mod:magic/frost_step`):

| Asset Type | Exact Resource Path | JSON Reference Form |
| --- | --- | --- |
| LumenFX JSON v1 | `assets/<ns>/lumenfx/effects/<path>.json` | Effect ID `<ns>:<path>` |
| Photon `.fx` | `assets/<ns>/lumenfx/effects/<path>.fx` | Effect ID `<ns>:<path>` |
| Custom Fragment Shader | `assets/<ns>/shaders/<shader_path>.fsh` | `"fragment_shader": "<ns>:<shader_path>"` (e.g. `my_mod:vfx/ring`) |
| Custom Vertex Material | `assets/<ns>/shaders/<shader_path>.vsh` | `"vertex_material": "<ns>:<shader_path>"` |
| Texture | `assets/<ns>/textures/<tex_path>.png` | `"Sampler0": "<ns>:textures/<tex_path>.png"` |
| Static 3D Mesh | `assets/<ns>/lumenfx/meshes/<name>.lfmesh` | `"asset": "<ns>:<name>"` |
| 3D Vector Field | `assets/<ns>/lumenfx/vector_fields/<name>.lfvf` | `"field": "<ns>:<name>"` |
| Niagara SimCache | `assets/<ns>/lumenfx/sim_caches/<name>.lfsc` | Compiler-emitted reference |

> **Resource Reload Rule**: On Minecraft `26.2 / 26.3`, `EffectResources.prepare(...)` throws `IllegalStateException` and aborts the **entire resource reload** (potentially leaving a black screen on boot) if **any** JSON under `assets/*/lumenfx/effects/*.json` fails to parse or references a missing texture/shader. Always validate before launching!

---

## 2. Two Runtime Parsers: 26.2/26.3 Strict vs 1.20.1–26.1.2 `opengl_direct`

LumenFX ships two JSON parsers across its 10 targets. **Always author in the Cross-Version Safe Subset** unless explicitly targeting only `1.20.1–26.1.2`:

| Feature | 26.2 / 26.3 (`EffectJson.java`) | 1.20.1–26.1.2 (`ProductionEffectParser.java`) | Cross-Version Safe Rule |
| --- | --- | --- | --- |
| `format_version` | **Required** integer `1` | Optional (checked `== 1` if present) | Always set `"format_version": 1` |
| `materials` / `emitters` | **Required** JSON object / JSON array | Optional | Always include both `"materials": {}` and `"emitters": []` |
| `color` / `start_color` | **Strictly** 4-element `[R, G, B, A]` array (or `{"components": [R,G,B,A]}` / `{"random_time": [R,G,B,A]}`). **`{"stops": [...]}` CRASHES!** | Accepts `[R,G,B,A]` AND `{"stops": [...]}` | **ALWAYS use `[R, G, B, A]`** with per-channel `{ "keys": [...] }` curves! |
| `blend` | Strict enum: `add`, `additive`, `soft_additive`, `alpha`, `premultiplied`, `opaque`, `holdout`, `modulate`, `colored_transmittance`. **`"multiply"` CRASHES!** | Tolerates `"multiply"` as `MODULATE` | **ALWAYS use `"modulate"`** instead of `"multiply"` |
| HDR / Bloom | `"render": { "hdr": true }` (root or per-material); top-level `hdr`/`emissive_scale`/`bloom_intensity` ignored | Reads top-level `hdr`, `emissive_scale`, `bloom_intensity` AND `render.hdr` | Set `"render": { "hdr": true }` (may optionally pair with `emissive_scale`/`bloom_intensity` if targeting both) |
| Top-level `lights` / `volumes` / `decals` | Ignored at root (26.2 uses emitter-level `renderer: "light"|"volume"|"decal"` + settings blocks) | Parsed at root as convenience arrays | Use emitter-based renderers for 26.2/26.3; only use root arrays if targeting `opengl_direct` |
| `mesh` emitter block | Strict `MeshRendererJson` (all entry/LOD/facing fields required) | Accepts minimal `{ "entries": [{ "asset": "..." }] }` | Export full `MeshRendererJson` via Unity/UE5 plugin or fill all required fields |
| Custom `.fsh` shaders | Must be full `#version 330` using `effectUV`, `effectColor`, `EffectFrame`, `Sampler0` | Supports BOTH full `#version 330` (`effect*` + `EffectFrame`) AND headerless `vUv`/`uTime` snippets | **ALWAYS write full `#version 330`** with `effect*` varyings and `EffectFrame` UBO |

---

## 3. Root Object Schema

```json
{
  "format_version": 1,
  "render": {
    "hdr": true,
    "blend_color_space": "linear"
  },
  "materials": {
    "<material_key>": { "...": "..." }
  },
  "emitters": [
    { "...": "..." }
  ]
}
```

- `format_version` (required `int`): Must be `1`.
- `render` (optional `object`):
  - `hdr` (`bool`, default `false`): Enables linear HDR particle target & bloom pipeline on 26.2/26.3.
  - `blend_color_space` (`string`, default `"linear"`): `"linear"` or `"unity_gamma"`.
- `materials` (required `object`): Map of `<material_key>` → Material Definition.
- `emitters` (required `array`): List of Emitter Definitions.
- Compiled-engine optional root keys (preserve untouched during overrides!): `niagara`, `renderer_objects`, `physics_field_components`, `groom_assets`, `material_fonts`, `primitive_lighting`, `receives_decals`, `custom_depth`.

---

## 4. Material Definition Schema

```json
"glow_ring": {
  "fragment_shader": "lumenfx:core/effect",
  "textures": {
    "Sampler0": "my_mod:textures/vfx/spark.png"
  },
  "blend": "additive",
  "blend_op": "add",
  "depth_test": true,
  "depth_compare": "less_equal",
  "depth_write": false,
  "cull": false,
  "translucency_pass": "after_dof",
  "parameters": [1.0, 0.5, 0.0, 0.0],
  "render": { "hdr": true },
  "fog": { "enabled": false, "per_pixel": false, "sky": false }
}
```

| Field | Type / Valid Values | Default | Notes |
| --- | --- | --- | --- |
| `fragment_shader` | `string` (`ns:path`) | `lumenfx:core/color` (if no textures) else `lumenfx:core/effect` | Built-ins: `lumenfx:core/effect`, `lumenfx:core/color`, `lumenfx:core/effect_premultiplied`, `lumenfx:core/color_premultiplied`. Custom resolves to `assets/<ns>/shaders/<path>.fsh`. |
| `textures` | `object` (`SamplerName` → `ns:path`) | `{}` | Primary sprite texture is `"Sampler0"`. Must exist on disk (except built-in Perlin noise textures). |
| `blend` | `alpha`, `additive`, `add`, `soft_additive`, `premultiplied`, `opaque`, `holdout`, `modulate`, `colored_transmittance` | `"alpha"` | Case-insensitive. **Never `"multiply"`** — use `"modulate"`. |
| `blend_op` | `add`, `subtract`, `reverse_subtract`, `min`, `max` | `"add"` | Hardware blend operation. |
| `depth_test` | `bool` | `true` | Legacy shorthand; sets default `depth_compare` to `less_equal` (`true`) or `always` (`false`). |
| `depth_compare` | `disabled`, `never`, `less`, `equal`, `less_equal`, `greater`, `not_equal`, `greater_equal`, `always` | from `depth_test` | Automatically adapted to Minecraft 26.2 reverse-Z. |
| `depth_write` | `bool` | `depth_test && blend == opaque` | Set `false` for translucent/additive particles to avoid sorting cutouts. |
| `cull` | `bool` | `false` | Back-face culling (`false` recommended for sprites, ribbons, double-sided rings). |
| `parameters` | `number[4]` | `[0, 0, 0, 0]` | Must have **exact length 4**; exposed in GLSL as `MaterialParameters` (`vec4`). |
| `translucency_pass` | `before_dof`, `after_dof`, `after_motion_blur` | `"after_dof"` | Compositing pass order. |
| `fog` | `{ "enabled": bool, "per_pixel": bool, "sky": bool }` | all `false` | Enables `lfx_apply_material_fog(...)` integration. |
| `render` | `{ "hdr": bool, "blend_color_space": string }` | inherits root `render` | Per-material HDR override. |
| `scene_depth` / `depth_projection` / `scene_color` | `bool` | `false` | Enables depth/scene-color sampling uniforms for soft particles & refraction shaders. |
| `vertex_material` | `string` (`ns:path`) | `null` | Optional custom `.vsh` shader under `assets/<ns>/shaders/<path>.vsh`. |

---

## 5. Scalar Curves, Color Functions & Vectors (`EffectJsonFields.java`)

### 5.1 ScalarFunction (used by `rate`, `lifetime`, `mass`, `width`, `height`, `rotation`, `angular_velocity`, `start_speed`, `drag`, `size_over_life`, `atlas_frame`, `velocity_over_life[i]`, `force_over_life[i]`, `custom_data[i]`, `rate_over_distance`, and each `color[i]` channel)

1. **Constant**: `1.5`
2. **Random Range (stable per particle)**: `{ "min": 0.4, "max": 1.2 }` (each of `min`/`max` may itself be a constant or curve!)
3. **Keyframe Curve**:
   ```json
   {
     "pre_wrap": "clamp",
     "post_wrap": "clamp",
     "keys": [
       { "time": 0.0, "value": 0.2, "interpolation": "linear" },
       { "time": 0.2, "value": 1.0, "arrive": 0.0, "leave": -1.5, "interpolation": "cubic" },
       { "time": 1.0, "value": 0.0, "interpolation": "linear" }
     ]
   }
   ```
   - `interpolation`: `"constant"`, `"linear"`, or `"cubic"` (optional `arrive`, `leave`, `arrive_weight`, `leave_weight`).
   - `pre_wrap` / `post_wrap`: `"clamp"`, `"loop"`, or `"ping_pong"`.
4. **Lookup Table**: `{ "lookup": { "minimum_time": 0.0, "inverse_time_range": 1.0, "values": [0.0, 0.5, 1.0] } }`
5. **Composite Math**: `{ "multiply": [ <scalar>, <scalar>, ... ] }` or `{ "add": [ <scalar>, <scalar>, ... ] }`

### 5.2 ColorFunction (`start_color`, `color`)

Must resolve to **4 `ScalarFunction` channels `[R, G, B, A]`**:

```json
"color": [
  { "keys": [{ "time": 0.0, "value": 1.0 }, { "time": 0.5, "value": 0.65 }, { "time": 1.0, "value": 0.25 }] },
  { "keys": [{ "time": 0.0, "value": 0.96 }, { "time": 0.5, "value": 0.85 }, { "time": 1.0, "value": 0.60 }] },
  1.0,
  { "keys": [{ "time": 0.0, "value": 0.0 }, { "time": 0.12, "value": 0.95 }, { "time": 0.75, "value": 0.55 }, { "time": 1.0, "value": 0.0 }] }
]
```

Optional object wrapper (supported on 26.2/26.3):
```json
"color": {
  "random_time": true,
  "components": [1.0, 0.8, 0.3, 1.0]
}
```
> **NEVER write `"color": { "stops": [...] }`** — that is a non-standard gradient shorthand rejected by `EffectJsonFields.color(...)` on 26.2/26.3!

### 5.3 Vectors

- Fixed `Vec3` (`start_direction`, `velocity_min`, `velocity_max`, `acceleration`, `shape.size`, `noise.strength`, `noise.scroll`): Must be `[x, y, z]` (exact length 3 numbers).
- Animated `Vec3` (`velocity_over_life`, `force_over_life`): Must be `[scalarX, scalarY, scalarZ]` (exact length 3 `ScalarFunction` items).

---

## 6. Emitter Definition Schema (`EffectEmitterJson.java`)

| Field | Type / Valid Values | Default | Notes |
| --- | --- | --- | --- |
| `name` | `string` (**required**) | — | Unique emitter name within the effect. |
| `renderer` | `sprite`, `ribbon`, `beam`, `mesh`, `light`, `decal`, `volume`, `splat`, `component`, `simulation` (**required**) | — | Case-insensitive. |
| `material` | `string` | `""` | Must match a key in root `materials` when `renderer` is `sprite`, `ribbon`, `beam`, `mesh`, `decal`, or `volume`. |
| `local_space` | `bool` | `false` | `false` = world space (required for movement trails/sparks); `true` = attached to emitter pose (auras/cores). |
| `delay` / `duration` / `loop` | `number` / `number` / `bool` | `0` / `1` / `true` | Seconds. Set `loop: false` for one-shot hits/bursts. |
| `max_particles` | `int` | `4096` | **Always set an explicit tight cap** (e.g. `4–120`) to control memory & GPU upload size. |
| `rate` | `ScalarFunction` | `0` | Particles per second. |
| `distance_spacing` / `rate_over_distance` | `number` / `ScalarFunction` | `0` / `null` | Spawns particles by world travel distance (ideal for trails). |
| `movement_threshold` | `number` | `Double.MAX_VALUE` | Distance jump threshold that breaks ribbon continuity instead of stretching across teleports. |
| `bursts` | `array` of Burst objects | `[]` | Each burst: `{ "time": 0.0, "min": 12, "max": 16, "cycles": 1, "interval": 0.0, "probability": 1.0 }` OR `{ "time": 0.0, "count": <scalar>, ... }`. **Do not mix `count` with `min`/`max`. Never set `cycles: 0` with `interval: 0`.** |
| `lifetime` / `mass` | `ScalarFunction` | `1` / `1` | Lifetime in seconds. |
| `width` / `height` | `ScalarFunction` | `1` / `1` | World-space block size (`1.0` = 1 Minecraft block). |
| `rotation` / `angular_velocity` | `ScalarFunction` | `0` / `0` | **Radians** and **radians/second** (not degrees!). |
| `start_speed` / `start_direction` | `ScalarFunction` / `Vec3` | `0` / `[0,1,0]` | Speed in blocks/second. |
| `velocity_min` / `velocity_max` / `acceleration` | `Vec3` | `[0,0,0]` | Legacy random initial velocity & constant acceleration. |
| `velocity_over_life` / `force_over_life` | `ScalarFunction[3]` | `[0,0,0]` | Per-axis velocity/force over normalized particle age `0..1`. |
| `drag` | `ScalarFunction` | `0` | Exponential air drag (`2.0–3.5` gives punchy burst-and-brake impact feel). |
| `size_over_life` | `ScalarFunction` | `1` | Multiplier over normalized age `0..1`. |
| `start_color` / `color` | `ColorFunction` | `[1,1,1,1]` | Birth tint × over-life RGBA color. |
| `custom_data` | `ScalarFunction[4]` | `[0,0,0,0]` | Exposed in GLSL as `in vec4 effectCustom;`. |
| `shape` | `{ "type": ..., "size": [x,y,z], "surface": bool, "angle": num }` | `point` | Types: `point`, `box`, `sphere`, `cylinder`, `cone`, `circle`, `hemisphere`, `edge`, `box_edge`, `donut` (needs `unity.donut`), `mesh` (needs `unity.mesh`). |
| `noise` | `{ "strength": [x,y,z], "frequency": num, "scroll": [x,y,z], "mode": "displacement"\|"vector_field", "field": "ns:id" }` | zero | `mode: "vector_field"` **requires** `field`; `mode: "displacement"` **forbids** `field`. |
| `integrator` | `analytic`, `niagara` | `"analytic"` | Default `"analytic"` for native effects. |
| `atlas_columns` / `atlas_rows` / `atlas_frame` / `atlas_row` | `int` / `int` / `ScalarFunction` / `int` | `1` / `1` / `0` / `-1` | Flipbook animation; `atlas_frame` ranges `0 .. (cols * rows - 1)`. |
| `uv_mode` / `uv_distance` | `stretch` \| `distance` / `number` | `"stretch"` / `1` | Ribbon/beam UV tiling. |
| `subdivisions` / `width_segments` / `attach_head` | `int` / `int` / `bool` | `1` / `1` / `false` | Ribbon tessellation (keep `subdivisions <= 6` for performance). |
| `sort_order` / `sort_mode` | `int` / `none`, `distance`, `view_depth`, `custom_ascending`, `custom_descending` | `0` / `"distance"` | Use `sort_order` to guarantee L4 dark backing renders behind L1 bright core (`sort_order: -1` vs `+2`). Use `"none"` for additive sparks when depth order doesn't matter! |
| `scale_size_with_owner` | `bool` | `true` | Scales particle dimensions with `EffectHandle.setPose(...)` scale. |

### 6.1 Optional `ribbon` Sub-Object (Exact Case-Sensitive Enums!)

When using `"ribbon": { ... }`, note that several strings are **case-sensitive** in `EffectEmitterJson.java`:
- `shape`: `"Plane"`, `"MultiPlane"`, `"Tube"`, `"Custom"`
- `facing`: `"Screen"`, `"Custom"`, `"CustomSideVector"`
- `draw_direction`: `"FrontToBack"`, `"BackToFront"`
- `tessellation_mode`: `"Automatic"`, `"Custom"`, `"Disabled"` (case-insensitive)
- `uv0` / `uv1`: `distribution` (`length` / `index` / etc.), `leading_edge` & `trailing_edge` (`"Locked"` or `"SmoothTransition"` — case-sensitive!)

### 6.2 Strict `mesh` Sub-Object (`MeshRendererJson.java` on 26.2/26.3)

On `26.2 / 26.3`, a `mesh` emitter requires all fields in `mesh` and each item in `mesh.entries`:
```json
"mesh": {
  "entries": [
    {
      "asset": "my_mod:ice_shard",
      "materials": [{ "default_material": "shard_mat" }],
      "scale": [1.0, 1.0, 1.0],
      "rotation": [0.0, 0.0, 0.0, 1.0],
      "pivot": [0.0, 0.0, 0.0],
      "pivot_space": "local",
      "lod_mode": "explicit",
      "lod_level": { "value": 0 },
      "lod_bias": { "value": 0 },
      "lod_distance_factor": 1.0,
      "use_lod_range": false,
      "lod_range_min": 0,
      "lod_range_max": 0
    }
  ],
  "facing_mode": "default",
  "locked_axis_enabled": false,
  "locked_axis": [0.0, 0.0, 1.0],
  "locked_axis_space": "simulation",
  "bounds_scale": [1.0, 1.0, 1.0],
  "frustum_culling": true,
  "cast_shadows": false
}
```

---

## 7. Common Reload Crash Errors → Instant Fixes

| Crash / Log Message | Root Cause | Exact Fix |
| --- | --- | --- |
| `JsonParseException: Expected four color components for color` | Used `"color": { "stops": [...] }` or 3-element `[R, G, B]` | Replace with 4-element `[R, G, B, A]` array using per-channel `{ "keys": [...] }`. |
| `IllegalArgumentException: No enum constant MaterialDefinition.Blend.MULTIPLY` | Used `"blend": "multiply"` | Change to `"blend": "modulate"`. |
| `IllegalArgumentException: Missing effect fragment shader ...` | File path mismatch or typo in `fragment_shader` | Place shader at `assets/<ns>/shaders/<path>.fsh` matching `"<ns>:<path>"`. |
| `IllegalArgumentException: Missing effect texture ...` | Texture missing or omitted `textures/` prefix | Use full `<ns>:textures/<subpath>.png` and verify file exists. |
| `JsonParseException: Material parameters must have four components` | `parameters` array length != 4 | Pad/trim to exactly 4 floats: `[p0, p1, p2, p3]`. |
| `JsonParseException: Emission burst mixes a count curve with a constant range` | Burst object has both `count` and `min`/`max` | Keep either `min`+`max` OR `count`, never both. |
| `IllegalArgumentException: Vector field reference does not match noise mode` | Set `noise.field` without `mode: "vector_field"` (or vice versa) | Match `mode: "vector_field"` with `field`, or remove `field` for `"displacement"`. |
| `NullPointerException` inside `MeshRendererJson.read` | Simplified `mesh` block missing `facing_mode`/`rotation`/`lod_*` on 26.2/26.3 | Fill full `MeshRendererJson` structure from Section 6.2. |
