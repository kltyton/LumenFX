---
name: lumenfx-vfx
description: >-
  Author, validate, optimize, and integrate real-time Minecraft particle VFX for LumenFX
  (JSON v1 effects, cross-version GLSL 330 fragment shaders, Photon .fx, Unity Shuriken UPM
  exports, UE5 Niagara compilations, Blockbench ParticleCue Studio overrides, and Java
  VfxEffects / EffectHandle / LumenFxKeyframeBridge APIs across Minecraft 1.20.1–26.3).
  Use whenever creating, editing, debugging, or auditing LumenFX effects, materials, shaders,
  or animation keyframes to prevent 26.2/26.3 resource-reload crashes (such as invalid color
  stops syntax, unsupported blend enums, incomplete mesh blocks, or snippet-only GLSL),
  all-additive whiteout blobs, linear floaty motion, and high-concurrency frame drops.
---

# LumenFX VFX Skill

Dedicated authoring, validation, and optimization workflow for **LumenFX** across all 10 supported targets (**Minecraft 1.20.1, 1.21.1, 26.1.2, 26.2, and 26.3** on Forge, NeoForge, and Fabric).

> **Core Doctrine**: Great LumenFX effects combine **100% strict cross-version schema compliance** (zero reload crashes on 26.2/26.3 Vulkan/OpenGL and 1.20.1–26.1.2 OpenGL), **commercial VFX contrast & timing** (dark Alpha-Blend backing + small incandescent core + non-linear burst-and-drag), and **multi-instance batching discipline** (bounded particle counts, shared materials, and deterministic pre-flight validation).

---

## 0. Pre-Flight Decisions (Lock Before Writing Any File)

1. **Choose the Authoring Route**:
   - **Hand-authored / AI-authored Native JSON + Custom GLSL**: Fast, lightweight, version-control friendly. Put JSON at `assets/<ns>/lumenfx/effects/<path>.json` and custom shaders at `assets/<ns>/shaders/vfx/<name>.fsh`.
   - **Blockbench Visual Authoring (`ParticleCue Studio`)**: Interactive curves/gradients, KilaMaterial 6-module composer, KilaGraph GLSL node editor, and animation `Locator` preview.
   - **Unity Shuriken (`tools/unity/`) / UE5 Niagara (`tools/unreal/` + `tools/compiler/`)**: For complex simulations, GPU compute, SimCaches (`.lfsc`), or 3D vector fields (`.lfvf`).
   - **Photon `.fx`**: Legacy/existing Photon effects placed at `assets/<ns>/lumenfx/effects/<path>.fx`.
2. **Lock the Target Compatibility Profile**:
   - **Cross-Version Safe (`--target all`, Default & Recommended)**: Strictly valid on **both** `26.2 / 26.3` (`EffectJson.java` + GLSL 330) **and** `1.20.1–26.1.2` (`ProductionEffectParser.java` + `DirectGlCustomMaterialCompiler.java`).
   - **26.2 / 26.3 Only (`--target 26.2`)**: Strict `EffectJson` schema + full `#version 330` shaders.
   - **Legacy OpenGL Direct Only (`--target opengl_direct`)**: Permits 1.20.1–26.1.2 convenience shortcuts (top-level `lights`/`volumes`/`decals`, simplified `mesh` entries, snippet `.fsh` using `vUv`/`uTime`). Never ship these to 26.2/26.3!
3. **Lock the Concurrency Budget Tier**:
   - `ambient` (mounts, mob trails, 50–150+ concurrent instances): `<= 4` emitters, `<= 120` total `max_particles`, `0` dynamic lights, low overdraw.
   - `combat` (skills, hits, 10–20 concurrent instances): `<= 8` emitters, `<= 600` total `max_particles`, `<= 1` light.
   - `boss` (ultimates, 1–3 concurrent instances): `<= 16` emitters, `<= 1200` total `max_particles`, `<= 3` lights.
   - `showcase` (cinematic/gallery single instance): `<= 32` emitters, `<= 4000` total `max_particles`.
4. **Plan Visual Layers Before Emitters**: Split the effect into single-purpose layers from the 6-layer stack (L1 Incandescent Core, L2 Hero Shape, L3 Directional Sparks, L4 Dark Alpha-Blend Contrast Backing, L5 Ambient Embers, L6 Ground Anchoring) rather than cramming everything into one emitter.

---

## 1. Ten Non-Negotiable LumenFX Engineering Red Lines

| ID | Rule | Failure Mode If Violated | Reference |
| --- | --- | --- | --- |
| **L01** | **`color` & `start_color` MUST be a 4-channel `[R, G, B, A]` array** (each channel is a number, `{min, max}`, `{keys: [...]}`, or `{add/multiply: [...]}`), or `{ "components": [R, G, B, A] }`. **NEVER use `{ "stops": [...] }`**. | `JsonParseException: Expected four color components for color` → **Minecraft 26.2/26.3 black-screen resource reload crash**. | [effect-json-schema.md](references/effect-json-schema.md) |
| **L02** | **Use only valid `MaterialDefinition.Blend` values**: `alpha`, `additive`, `add`, `soft_additive`, `premultiplied`, `opaque`, `holdout`, `modulate`, `colored_transmittance`. **NEVER write `"multiply"`** (use `"modulate"`). | `IllegalArgumentException: No enum constant Blend.MULTIPLY` on 26.2/26.3 reload. | [effect-json-schema.md](references/effect-json-schema.md) |
| **L03** | **Always include `"format_version": 1`, `"materials": {...}`, and `"emitters": [...]`** at JSON root; every emitter needs `"name"`, `"renderer"`, and a `"material"` key present in `"materials"` (except `light`/`component`/`simulation`/`splat`). | `NullPointerException` / missing material binding during `EffectJson.read`. | [effect-json-schema.md](references/effect-json-schema.md) |
| **L04** | **Custom `.fsh` shaders MUST be full `#version 330` shaders** using `effectUV`, `effectColor`, `effectParticle`, `effectCustom`, `EffectFrame` UBO (`EffectClock`, `MaterialParameters`), `Sampler0`, and `out vec4 fragColor`. Do NOT write headerless `vUv`/`uTime` snippets for 26.2/26.3. | Shader compilation failure on 26.2/26.3 Vulkan & OpenGL (works automatically on 1.20.1–26.1.2 too because `DirectGlCustomMaterialCompiler` emits all `effect*` varyings and binds `EffectFrame`). | [custom-glsl-shaders.md](references/custom-glsl-shaders.md) |
| **L05** | **Enable HDR via `"render": { "hdr": true }`** (root or per-material) for 26.2/26.3; optional `emissive_scale`/`bloom_intensity` only affect 1.20.1–26.1.2 `opengl_direct`. | HDR bloom never activates on 26.2/26.3 if only `emissive_scale` is set. | [effect-json-schema.md](references/effect-json-schema.md) |
| **L06** | **Respect strict parser invariants**: `bursts` cannot mix `count` with `min`/`max` (and `cycles == 0 && interval == 0` throws); `noise.mode == "vector_field"` requires `noise.field` while `"displacement"` forbids it; `shape.type == "donut"/"mesh"` requires `shape.unity`. | Hard reload exception in `EffectEmitterJson`. | [effect-json-schema.md](references/effect-json-schema.md) |
| **L07** | **Prevent All-Additive Whiteout**: Multi-emitter combat bursts (`>= 3` emitters) must include at least one dark `alpha` / `premultiplied` / `modulate` backing layer (L4), keeping the pure-white L1 core to 3%–8% of screen area. | Overexposed flat white blob with zero silhouette depth. | [aesthetics-timing-and-performance.md](references/aesthetics-timing-and-performance.md) |
| **L08** | **Ban Lifeless Linear Drift**: Use non-linear 3-phase timing — slow anticipation → 2–6 frame high-speed burst braked by `drag` (`2.0–3.5`) or cubic ease-out `size_over_life` → bright core dies first (`0.15–0.3s`), dark smoke & embers linger. | Floaty, weightless particles that obscure combat readability. | [aesthetics-timing-and-performance.md](references/aesthetics-timing-and-performance.md) |
| **L09** | **Batch & Budget for Multi-Instance Stress**: Share materials across emitters when possible, cap `max_particles` tightly (never leave default `4096`), keep ribbon `subdivisions <= 6`, and use `local_space: false` + `distance_spacing`/`rate_over_distance` for movement trails. | Severe vertex upload & draw-call stalls when 50–150+ entities spawn the effect simultaneously. | [aesthetics-timing-and-performance.md](references/aesthetics-timing-and-performance.md) |
| **L10** | **Never Destroy Compiled Engine Payloads**: When tuning Unity/UE5-exported LumenFX JSONs, patch non-destructively (preserving `niagara`, `spawn_program`, `update_program`, `gpu_program`, `sim_cache`, `.lfvf`, `.lfmesh`, and full `MeshRendererJson` fields). | Silently breaks compiled VectorVM/SPIR-V/SimCache behavior or crashes `MeshRendererJson`. | [java-api-and-authoring-pipelines.md](references/java-api-and-authoring-pipelines.md) |

---

## 2. Six-Stage LumenFX Production Workflow

1. **Stage 1 · Scope & Layer Blueprint**: Lock target version (`all` vs `26.2`), concurrency tier (`ambient` / `combat` / `boss`), and list L1–L6 emitters with blend modes and `max_particles`.
2. **Stage 2 · Silhouette & Motion Pass**: Author emitter shapes, initial speed/direction, `drag`, `force_over_life`, `size_over_life`, and `local_space` (world space for trails; local space for attached auras).
3. **Stage 3 · Value Contrast & Color Curves**: Build 4-channel `[R, G, B, A]` curves implementing the blackbody value ladder (95–100% white core → 75–92% highlight → 40–70% midtone → 5–25% dark alpha backing) with bright-first / dark-slow alpha fade.
4. **Stage 4 · Materials & Custom GLSL**: Prefer built-in `lumenfx:core/effect` / `lumenfx:core/color` when textures suffice; write `#version 330` `.fsh` shaders under `assets/<ns>/shaders/vfx/` for procedural rings, Fresnel shields, scanlines, or domain-warped flames.
5. **Stage 5 · Mandatory Deterministic Validation (Zero Errors)**: Run the bundled validator before touching Minecraft:
   ```powershell
   python scripts/validate_lumenfx_effect.py <path-to-effect.json-or-dir> --target all --tier combat --assets-root <path-to-assets-dir> --strict
   ```
6. **Stage 6 · Runtime Hookup & Multi-Instance Check**: Wire via `VfxEffects.spawn(...)` + `EffectHandle` (`moveTo`/`setPose`/`trackRenderSamples`/`close`) or `LumenFxKeyframeBridge.trigger(...)` for GeckoLib/KBR/PAL/AnimCue; verify resource reload (`F3+T`) produces zero errors and check multi-instance frame time.

---

## 3. Progressive-Disclosure Reference Manual Index

Load only the reference needed for the current task:

| Task Need | Reference File |
| --- | --- |
| Complete `format_version: 1` JSON schema, curves, shapes, bursts, ribbons, meshes, lights, 26.2 vs `opengl_direct` differences, and error-to-fix table | [references/effect-json-schema.md](references/effect-json-schema.md) |
| Writing cross-version `#version 330` `.fsh` shaders, vertex varyings, `EffectFrame` UBO, `#moj_import`, procedural SDF/Fresnel/noise idioms, HDR/fog | [references/custom-glsl-shaders.md](references/custom-glsl-shaders.md) |
| 6-layer VFX visual stack, blackbody value ladder, 3-phase timing, batch coalescing, and multi-instance concurrency budgets | [references/aesthetics-timing-and-performance.md](references/aesthetics-timing-and-performance.md) |
| Java `VfxEffects` / `EffectHandle` / `LumenFxKeyframeBridge` APIs, GeckoLib/KBR/PAL/AnimCue integration, Unity UPM, UE5 Niagara, Photon `.fx`, ParticleCue Studio | [references/java-api-and-authoring-pipelines.md](references/java-api-and-authoring-pipelines.md) |

### Verified Starter Templates (`examples/`)

- [examples/arcane_impact_burst.json](examples/arcane_impact_burst.json): Cross-version (`1.20.1–26.3`) 3-layer combat impact (L4 dark alpha smoke + L1 incandescent core + L3 drag-braked sparks). Passes `--target all --tier combat --strict`.
- [examples/procedural_fresnel_ring.fsh](examples/procedural_fresnel_ring.fsh): Cross-version `#version 330` procedural Fresnel shockwave ring fragment shader compatible with 26.2/26.3 Vulkan/OpenGL and 1.20.1–26.1.2 OpenGL.

---

## 4. Bundled Deterministic Validator (`scripts/validate_lumenfx_effect.py`)

Zero external Python dependencies (Python 3.9+ stdlib only). Checks:
- Exact `EffectJson` / `EffectEmitterJson` / `EffectJsonFields` / `MeshRendererJson` parser rules (catches `color.stops`, invalid `blend` like `"multiply"`, mixed `burst` fields, mismatched `noise.mode`/`field`, missing `mesh` fields, invalid curve/vector shapes).
- Asset existence & shader compatibility when `--assets-root <assets-dir>` is provided (verifies referenced `.fsh` & textures exist, flags headerless `vUv`/`uTime` shaders when targeting `26.2` or `all`).
- Visual contrast & performance budgets (`--tier ambient | combat | boss | showcase`): particle cap, emitter cap, overdraw score, dynamic light cap, ribbon tessellation, and All-Additive Whiteout detection.

```powershell
# Single file strict cross-version check
python scripts/validate_lumenfx_effect.py assets/my_mod/lumenfx/effects/slash.json --target all --tier combat --assets-root assets --strict

# Audit an entire mod's effects folder as JSON
python scripts/validate_lumenfx_effect.py assets/my_mod/lumenfx/effects --target 26.2 --tier ambient --assets-root assets --json
```
