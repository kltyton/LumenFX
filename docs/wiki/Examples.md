<p align="right">
  <strong>English</strong> | <a href="Examples-zh">简体中文</a>
</p>

# Example Effects & Showcase Resource Pack

LumenFX ships an official, ready-to-load developer example resource pack in the repository under **[`examples/showcase-pack/`](https://github.com/kltyton/LumenFX/tree/main/examples/showcase-pack)**.

> **Clean Runtime Separation:** All example effects, `.lfmesh` models, procedural textures, and custom GLSL fragment shaders live strictly under `examples/` and in this Wiki. They are **never included** in the core library source tree (`common/`, `targets/`) and **never packaged** into any released LumenFX `.jar` file.

---

## Resource Pack Layout

```text
examples/showcase-pack/
├── pack.mcmeta
├── README.md
└── assets/lumenfx_showcase/
    ├── lumenfx/
    │   ├── effects/
    │   │   ├── classic/          # 5 focused single-feature starter effects
    │   │   ├── photon/           # 5 Photon-parity architectural, lighting, shader & mesh scenes
    │   │   └── bonus/            # 5 advanced multi-layer next-gen showcase scenes
    │   └── meshes/               # 6 custom 3D .lfmesh models (quads, discs, beam, fan, fox, crystal)
    ├── shaders/vfx/              # 16 GLSL 330 Core custom fragment shaders (.fsh)
    └── textures/vfx/             # 6 procedural PNG textures (glow, sparkle, pentagram, frost, scorch, petal)
```

---

## Complete Example Catalog (15 Effects)

### 1. Focused Starter Examples (`lumenfx_showcase:classic/*`)
Minimal, easy-to-read JSON templates ideal for copying into your own mod or resource pack:

| Effect ID | Primary Techniques Demonstrated | Source File |
| :--- | :--- | :--- |
| `lumenfx_showcase:classic/rune_ribbon_stream` | Ground mesh sigil + `ribbon` renderer with `orbital_velocity` and color-gradient stops | [`rune_ribbon_stream.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/classic/rune_ribbon_stream.json) |
| `lumenfx_showcase:classic/astral_vortex_portal` | Polar-swirl GLSL shader + `volumes` fog box + inward `radial_velocity` + `orbital_velocity` | [`astral_vortex_portal.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/classic/astral_vortex_portal.json) |
| `lumenfx_showcase:classic/blazing_ember_fireball` | Native HDR bloom (`hdr`, `emissive_scale`, `bloom_intensity`), flame shader, and curl-noise rising embers | [`blazing_ember_fireball.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/classic/blazing_ember_fireball.json) |
| `lumenfx_showcase:classic/emerald_plasma_comet` | Cellular dissolve shader core paired with noise-modulated emerald `ribbon` trail | [`emerald_plasma_comet.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/classic/emerald_plasma_comet.json) |
| `lumenfx_showcase:classic/luminous_physics_fountain` | Cone emitter with `gravity`, `drag`, and real-time block `collision` (`bounce`, `dampen`, `lifetime_loss`) | [`luminous_physics_fountain.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/classic/luminous_physics_fountain.json) |

### 2. Photon-Parity Showcase (`lumenfx_showcase:photon/*`)
Full scenes demonstrating dynamic lights, volumetric fog volumes, procedural GLSL materials, stair/slab collision, and 3D hologram mesh swarms:

| Effect ID | Highlights | Source File |
| :--- | :--- | :--- |
| `lumenfx_showcase:photon/dynamic_lights_plaza` | Multi-color `lights` (`point`), `volumes` halos, Fresnel orb, vertical pillar beam, and rotating pentagram sigil | [`dynamic_lights_plaza.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/photon/dynamic_lights_plaza.json) |
| `lumenfx_showcase:photon/volumetric_temple_shafts` | Raymarched `volumes` fog, directional `spot` + `point` lights, depth-soft (`soft_particles`) volumetric god-rays, and floating dust motes | [`volumetric_temple_shafts.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/photon/volumetric_temple_shafts.json) |
| `lumenfx_showcase:photon/kila_material_gallery` | 7 procedural GLSL fragment shaders (toon fire, HDR ring, lightning arcs, Fresnel orb, polar swirl, cellular dissolve, organic flame) + ground pentagram | [`kila_material_gallery.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/photon/kila_material_gallery.json) |
| `lumenfx_showcase:photon/particle_collision_stairs` | Real-time world collision with gravity, restitution, and velocity damping across stairs, slabs, and walls | [`particle_collision_stairs.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/photon/particle_collision_stairs.json) |
| `lumenfx_showcase:photon/hologram_fox_swarm` | Custom 3D `.lfmesh` low-poly fox pack rendered with animated horizontal scanlines (`hologram_scanline.fsh`) and localized ground light | [`hologram_fox_swarm.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/photon/hologram_fox_swarm.json) |

### 3. Next-Gen Multi-Layer Showcase (`lumenfx_showcase:bonus/*`)
Production-scale composite VFX combining multiple renderers, shaders, decals, fog volumes, and dynamic lighting:

| Effect ID | Highlights | Source File |
| :--- | :--- | :--- |
| `lumenfx_showcase:bonus/supernova_black_hole` | Opaque pitch-black event horizon (`blend: "alpha"`), Einstein photon ring, tilted swirling accretion disk mesh, polar jets, and orbiting stardust | [`supernova_black_hole.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/bonus/supernova_black_hole.json) |
| `lumenfx_showcase:bonus/arcane_prism_ribbons` | Double-layered counter-rotating golden-cyan arcane mandala with intertwined tri-color helical ribbon trails | [`arcane_prism_ribbons.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/bonus/arcane_prism_ribbons.json) |
| `lumenfx_showcase:bonus/orbital_plasma_beam` | Sky-to-ground HDR plasma column, rising charge rings, expanding ground shockwaves, deferred ground scorch `decals`, volumetric mist, and bouncing sparks | [`orbital_plasma_beam.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/bonus/orbital_plasma_beam.json) |
| `lumenfx_showcase:bonus/frost_nova_shatter` | Faceted 3D hexagonal `ice_crystal.lfmesh` spires with glacial Fresnel shader, glowing frost mandala, cryogenic fog volume, and drifting snowflakes | [`frost_nova_shatter.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/bonus/frost_nova_shatter.json) |
| `lumenfx_showcase:bonus/celestial_sakura_sanctuary` | Curl-noise drifting cherry blossom petals, HDR golden fireflies, multi-color point lighting, and moonlit volumetric mist | [`celestial_sakura_sanctuary.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/bonus/celestial_sakura_sanctuary.json) |

---

## Quick Developer Recipes

### 1. Load & Spawn an Example Effect in Java

Copy the desired JSON, shader, texture, and `.lfmesh` files into your own mod's `src/main/resources/assets/<your_mod_id>/` (or drop `examples/showcase-pack/` into `.minecraft/resourcepacks/`), then spawn on the client:

```java
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.VfxEffects;
import net.minecraft.resources.ResourceLocation; // Identifier on 26.x
import net.minecraft.world.phys.Vec3;

ResourceLocation effectId = ResourceLocation.parse("lumenfx_showcase:bonus/supernova_black_hole");
if (VfxEffects.hasEffect(effectId)) {
    EffectHandle handle = VfxEffects.spawn(clientLevel, effectId, new Vec3(x, y, z), 2026L);
    // Optional: update position/scale every tick, or call handle.close() to stop early
}
```

### 2. Trigger from a GeckoLib / KBR Blockbench Animation Keyframe

In Blockbench's `particle_effects` timeline track:
- **Effect:** `lumenfx_showcase:bonus/frost_nova_shatter`
- **Locator:** `cast_origin` (optional 3D bone locator)
- **Pre-Effect Script:** `scale=1.2; offset=0,0.1,0`

See **[[GeckoLib & KBR Particle Keyframes|GeckoLib-and-KBR-Keyframes]]** for full keyframe bridge options.

### 3. Authoring Best Practices Learned from the Showcase Pack

- **Single Static Visuals (Sigils, Orbs, Beams):** Use a one-shot burst (`"rate": 0.0`, `"bursts": [{"time": 0.0, "min": 1, "max": 1, "cycles": 1}]`, `"max_particles": 1`, `"lifetime": 20.0`) instead of continuous emission so additive shaders never stack and blow out to white.
- **Color Over Lifetime:** Use explicit `stops` arrays: `{"stops": [{"time": 0.0, "color": [r, g, b, a]}, ...]}` rather than mixing scalar floats and nested curve objects inside `[r, g, b, a]`.
- **HDR & Bloom Calibration:** Start with `"emissive_scale": 1.1`–`1.25` and `"bloom_intensity": 0.35`–`0.55` on additive layers; keep solid cores (like black hole horizons or toon fire) on `"blend": "alpha"` so they retain crisp silhouettes against bright additive glow.
- **Custom GLSL Fragment Shaders:** Place `.fsh` files under `assets/<namespace>/shaders/vfx/<name>.fsh` and reference them as `"<namespace>:vfx/<name>"` in material definitions. Built-in inputs `vUv`, `vColor`, `uTime`, and `Sampler0` are automatically bound by LumenFX.
