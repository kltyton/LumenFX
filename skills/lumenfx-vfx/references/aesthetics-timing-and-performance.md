# VFX Aesthetics, Timing & High-Concurrency Performance Budgets for LumenFX

> Distills proven real-time VFX design grammar (Jason Keyser / Gabriel Aguiar / ARPG 6-layer combat contrast) alongside real LumenFX GPU/CPU runtime lessons from 100+ concurrent entity stress tests.

---

## 1. Gameplay Readability First

1. **The Boundary Is the Shape**: AOE rings, slash arcs, and beams must match actual gameplay hitboxes cleanly — avoid bloated blurry smoke that hides edges or misleads players.
2. **Duration Matches State**: Impact hits should peak in `0.05s–0.20s` (2–6 frames) and clear the core quickly so player visibility stays sharp.
3. **Brightness Is a Scarce Budget**:
   - Ambient / Mount / Footstep effects: low-to-medium luminance, delicate silhouettes, zero screen-blinding bloom.
   - Basic Attacks: medium contrast with unmistakable motion direction.
   - Ultimates / Boss Skills: reserve full HDR bloom, dark contrast backing, and wide radial shockwaves.

---

## 2. The Six-Layer Commercial VFX Stack (Mapped to LumenFX)

Never pile 500 additive white spheres into one emitter. Split complex effects into focused layers using `sort_order` and intentional blend modes:

| Layer | Role & Screen Share | LumenFX Blend & `sort_order` | Typical Geometry / Setup |
| --- | --- | --- | --- |
| **L4** | **Dark Contrast Backing** (Secret weapon against whiteout!) | `alpha` or `premultiplied`, `sort_order: -2` | Deep navy / dark violet / charcoal smoke or dark shock ring. Darkens the background locally so foreground gold/cyan lines read razor-sharp in daylight and dark caves alike. |
| **L6** | **Ground Anchoring** | `alpha` / `decal` / subtle `light`, `sort_order: -1` | Flat ring, scorch mark, or brief low-radius light pinning the effect to the world floor instead of floating like a 2D sticker. |
| **L2** | **Hero Geometric Shape** | `additive` or `alpha` custom `.fsh`, `sort_order: 0` | Crisp 1–2px ring, rune circle, Fresnel dome, ribbon slash arc, or `.lfmesh` crystal/beam establishing theme identity. |
| **L3** | **Directional Kinetic Sparks** | `additive`, `sort_order: 1`, `sort_mode: "none"` | Fast narrow needle sparks (`width < height`) or small streaks propelled radially/tangentially with high `drag` (`2.0–3.5`) + gravity (`force_over_life: [0, -6.5, 0]`). |
| **L1** | **Incandescent Core Anchor** (3%–8% area only!) | `additive` + HDR, `sort_order: 2` | Pure white / pale gold cross-star or bright core lasting `0.10–0.22s` to lock the eye instantly, then vanishing first. |
| **L5** | **Lingering Ambient Embers** | `additive` or `alpha`, `sort_order: 1`, `sort_mode: "none"` | Tiny, slow, noise-drifted motes with longer lifetime (`0.8–1.8s`) that taper cleanly so the effect never hard-cuts. |

---

## 3. Blackbody Value Ladder & Color Contrast

Run a mental **Grayscale Test** before finalizing colors — even stripped of hue, L1 must be brightest, L2/L3 second, and L4 darkest:

| Luminance Band | Example Palette (Arcane Violet-Gold) | Example Palette (Frost Cyan) | Visual Role |
| --- | --- | --- | --- |
| **95%–100%** | `[1.0, 0.98, 0.90]` | `[0.95, 1.0, 1.0]` | L1 white-hot core, blade tip, cross-star center |
| **75%–92%** | `[1.0, 0.78, 0.30]` | `[0.45, 0.92, 1.0]` | L2 crisp rings, L3 high-speed sparks |
| **40%–70%** | `[0.58, 0.25, 0.96]` | `[0.15, 0.52, 0.92]` | Midtone Fresnel rim, ribbon body, ground glow |
| **5%–25%** | `[0.12, 0.04, 0.20]` | `[0.04, 0.10, 0.18]` | L4 dark `alpha` smoke/ring contrast backing |

---

## 4. Non-Linear 3-Phase Timing Rule

Linear constant-speed drift looks synthetic and weightless. Structure bursts in 3 asymmetric phases:

1. **Anticipation (`0.15s–0.8s`, slow, tight, inward)**: Use `delay` on impact emitters while an inward ring or dim glow charges.
2. **Impact Peak (`0.05s–0.20s`, instant, wide, bright)**: Fire bursts at impact time with high `start_speed` (`3.5–7.5`) + high `drag` (`2.0–3.5`), and a steep ease-out `size_over_life` curve (`0.0 -> 0.15s` jumps `0.25 -> 1.0`, then tapers slowly).
3. **Dissipation (`0.5s–2.0s`, "Bright Dies First, Dark Lingers Slow")**:
   - L1 core fades to `alpha = 0` by `0.15s–0.25s`;
   - L2 hero ring expands & dissolves by `0.4s–0.8s`;
   - L4 dark smoke & L5 tiny embers drift upward/outward slowly for `0.8s–1.8s`.

---

## 5. High-Concurrency Performance & Batching Rules (LumenFX Runtime)

Real-world profiling on Minecraft `26.2` with **85–130 simultaneous mounts** revealed the exact runtime bottlenecks to design around:

### 5.1 Budget Tiers (`validate_lumenfx_effect.py --tier <tier>`)

| Metric | `ambient` (50–150+ entities) | `combat` (10–20 instances) | `boss` (1–3 instances) | `showcase` (single) |
| --- | --- | --- | --- | --- |
| Total `max_particles` (sum across emitters) | `<= 120` | `<= 600` | `<= 1200` | `<= 4000` |
| Emitter count | `<= 4` | `<= 8` | `<= 16` | `<= 32` |
| Dynamic Lights | `0` | `<= 1` | `<= 3` | `<= 8` |
| Overdraw Score | `<= 80` | `<= 350` | `<= 700` | `<= 2500` |
| Ribbon `subdivisions` | `1–2` | `2–4` | `2–6` | `<= 8` |

### 5.2 Concrete LumenFX Engine Optimizations While Authoring

1. **Always Set Explicit `max_particles`**: Leaving `max_particles` omitted defaults to `4096` per emitter, inflating pre-allocation and budget estimates. Set the real steady-state ceiling: `ceil(rate * max_lifetime * 1.25) + burst_peak`.
2. **Share Materials to Maximize Draw-Batch Coalescing**: LumenFX coalesces consecutive/non-overlapping batches that share identical `MaterialDefinition` + pass + vertex format. Reuse the same material across similar additive spark/mote emitters instead of cloning 5 slightly different materials.
3. **Use `sort_mode: "none"` for Additive Particles**: Pure `additive` blending is order-independent (`A + B == B + A`). Setting `"sort_mode": "none"` skips per-frame CPU distance sorting on additive sparks and embers! Keep `"distance"` / `"view_depth"` only for `alpha` / `premultiplied` layers.
4. **Keep Ambient/Mount Trails Featherweight**:
   - Use `"local_space": false` with `"rate_over_distance"` or `"distance_spacing"` so stationary entities spawn **zero** trail particles instead of piling up vertices in place.
   - Keep `width`/`height` compact (`0.12–0.35`) to minimize fragment overdraw when dozens of entities cluster on screen.
   - For ribbons on mounts/projectiles, keep `subdivisions: 1–2`, `width_segments: 1`, and set `movement_threshold` (e.g. `3.0`) so teleports break the strip cleanly.
5. **Avoid Unnecessary Custom Shaders on High-Count Tiny Particles**: Built-in `lumenfx:core/effect` and `lumenfx:core/color` share canonical GPU pipelines across all effects in the world. Reserve custom `.fsh` shaders for hero shapes (rings, beams, shields, portals) where particle counts are small (`1–16`).
6. **Never Attach Lights to High-Frequency Particles**: Use at most 1–3 stationary/system-level lights per boss skill; never emit dynamic lights per spark or per mob footstep.
