<p align="center">
  <img src="docs/publishing/artwork/lumenfx-icon-400.png" width="140" alt="LumenFX Logo" />
</p>

<h1 align="center">LumenFX</h1>

<p align="center">
  <strong>Resource-driven cross-engine VFX runtime for Minecraft — supporting Unity ParticleSystem, Unreal Engine Niagara, Photon <code>.fx</code>, dynamic lights, volumetric fog, custom GLSL shaders, and 3D meshes with automatic Vulkan / OpenGL rendering.</strong>
</p>

<p align="center">
  <strong>English</strong> | <a href="README_zh.md">简体中文</a> | <a href="https://github.com/kltyton/LumenFX/wiki">Wiki</a> | <a href="examples/showcase-pack/README.md">Showcase Pack</a>
</p>

---

## Fork Relationship & Origin

**LumenFX is a fork of [Photon](https://github.com/Low-Drag-MC/Photon) (Photon 26.2 by [KilaBash](https://github.com/KilaBash) / Low-Drag-MC).**

Building upon Photon's particle simulation core, timelines, curves, and `.fx` playback foundation, LumenFX restructures and extends the runtime into a **lightweight, resource-driven client VFX library** with:

- **Zero heavy external dependencies & no in-game editor in the runtime JAR** — runs without `LDLib2` or `KilaGraph` (KilaGraph shader-graph playback remains available as optional soft-compat), and ships no demo assets or editor UI inside runtime JARs.
- **Multi-engine asset support & creator editor plugins** — runs native LumenFX JSON effects, Photon `.fx` effects, offline-exported **Unity ParticleSystem (Shuriken)** assets (via the included **Unity UPM Editor Plugin**), and offline-compiled **Unreal Engine Niagara** systems (CPU VectorVM, GPU SPIR-V compute, and SimCache `.lfsc`, via the included **UE5 Editor Plugin** & CLI compiler).
- **Optional GeckoLib (4.x / 5.x), KBR / KBR-Lite, PAL / PALMore, and AnimCue particle keyframe compatibility** — pure zero-dependency soft-compat across all 10 targets: preserves native callback-only semantics on standalone GeckoLib and PAL/PALMore (with automatic 3D locator/bone/bend pose tracking and reset cleanup when triggered via `LumenFxKeyframeBridge.trigger(...)`), automatically coexists with KBR / KBR-Lite keyframe playback without unknown-ID conflicts, and integrates with AnimCue (0.1.2+) local & server-synced scenes following Vanilla → KBR → LumenFX priority.
- **Unified multi-version & multi-loader architecture** — ships a single unified mod JAR per target across Minecraft **1.20.1, 1.21.1, 26.1.2, 26.2, and 26.3** (Forge, NeoForge, and Fabric), with **dynamic Vulkan-first / OpenGL fallback on 26.2 & 26.3** and a full-feature native OpenGL runtime on 1.20.1, 1.21.1, and 26.1.2.

Photon-derived source files retain KilaBash's attribution and are governed by the bundled [Photon License (CC BY-NC-SA 4.0)](licenses/photon/LICENSE). Original LumenFX code and offline authoring tools are licensed under [MIT](LICENSE).

---

## Visual Showcase

All 10 live animated captures below were captured live in-game using LumenFX's native runtime — **no shader pack required**. Every effect shown is included in the ready-to-load [`examples/showcase-pack/`](examples/showcase-pack/README.md) resource pack.

### Signature VFX Capabilities (Photon-Style Showcase)

<table>
<tr>
<td width="50%">
<img src="docs/publishing/screenshots/01_dynamic_lights.gif" alt="Dynamic Lights Plaza" /><br/>
<strong>Dynamic Point &amp; Spot Lights</strong><br/>
Real-time colored point and spot lights that illuminate blocks, pillars, and world geometry — paired with glowing Fresnel orbs, vertical light beams, and pentagram magic seals.
</td>
<td width="50%">
<img src="docs/publishing/screenshots/02_volumetric_light_shafts.gif" alt="Volumetric Lights and Fog Volumes" /><br/>
<strong>Volumetric Lights &amp; Fog Volumes</strong><br/>
3D raymarched fog volumes, warm interior point/spot lights, and depth-soft volumetric light shafts streaming through temple doorways with illuminated dust motes.
</td>
</tr>
<tr>
<td width="50%">
<img src="docs/publishing/screenshots/03_kila_material_gallery.gif" alt="Procedural VFX Materials Gallery" /><br/>
<strong>All-in-One Procedural VFX Materials (Custom GLSL)</strong><br/>
Cel-shaded toon fireballs, crisp HDR energy rings, crackling lightning arcs, Fresnel forcefield orbs, polar astral vortices, cellular dissolve cores, organic flames, and neon ground sigils.
</td>
<td width="50%">
<img src="docs/publishing/screenshots/04_particle_collision.gif" alt="Real-Time Particle Collision" /><br/>
<strong>Real-Time Particle Collision</strong><br/>
Gravity-driven sparkle showers colliding against real Minecraft stairs, slabs, and walls — bouncing, damping, cascading across every step, and spilling onto the terrain.
</td>
</tr>
<tr>
<td colspan="2" align="center">
<img src="docs/publishing/screenshots/05_hologram_fox_swarm.gif" width="780" alt="3D Hologram Mesh Swarm" /><br/>
<strong>3D Hologram Mesh Swarms</strong><br/>
Custom 3D <code>.lfmesh</code> &amp; glTF models rendered as particle swarms with animated horizontal hologram scanlines, edge glow, and dynamic ground lighting.
</td>
</tr>
</table>

### Extended Next-Gen Showcase

<table>
<tr>
<td width="50%">
<img src="docs/publishing/screenshots/06_supernova_black_hole.gif" alt="Supernova Black Hole" /><br/>
<strong>Black Hole &amp; Relativistic Accretion Disk</strong><br/>
Pitch-black event horizon, blazing Einstein photon ring, tilted swirling accretion disk shader, twin relativistic polar jets, and orbiting star dust.
</td>
<td width="50%">
<img src="docs/publishing/screenshots/07_arcane_prism_ribbons.gif" alt="Arcane Mandala and Helical Prism Ribbons" /><br/>
<strong>Arcane Mandala &amp; Multi-Ribbon Trails</strong><br/>
Double-layered rotating golden-cyan arcane hexagram seals weaving intertwined tri-color helical ribbons and shimmering arcane star motes.
</td>
</tr>
<tr>
<td width="50%">
<img src="docs/publishing/screenshots/08_orbital_plasma_beam.gif" alt="Orbital Plasma Cannon Beam" /><br/>
<strong>Orbital Plasma Cannon &amp; Shockwave Impact</strong><br/>
Sky-to-ground HDR plasma pillar, rising violet charge rings, expanding shockwave rings, deferred ground scorch decal, volumetric mist, and bouncing sparks.
</td>
<td width="50%">
<img src="docs/publishing/screenshots/09_frost_nova_shatter.gif" alt="Crystalline 3D Frost Nova" /><br/>
<strong>Crystalline 3D Frost Nova</strong><br/>
Erupting faceted 3D hexagonal ice crystal spires with translucent glacial Fresnel shading, snowflake mandala ground seal, cryogenic fog, and snowflakes.
</td>
</tr>
<tr>
<td colspan="2" align="center">
<img src="docs/publishing/screenshots/10_celestial_sakura_sanctuary.gif" width="780" alt="Celestial Sakura and Firefly Sanctuary" /><br/>
<strong>Celestial Sakura &amp; Golden Firefly Sanctuary</strong><br/>
Noise-driven drifting cherry blossom petals, HDR glowing golden fireflies, multi-color localized dynamic lighting, and ethereal moonlit mist.
</td>
</tr>
</table>

---

## Key Features

- **Cross-Engine VFX Runtime**: Plays native LumenFX JSON effects, Photon `.fx` files, Unity ParticleSystem exports, and Unreal Engine Niagara CPU/GPU systems & SimCaches through one unified Java API (`VfxEffects`).
- **Rich Renderers, Built-in HDR Bloom & Full Native OpenGL Parity**: Supports Sprites (Billboard, Stretched, Horizontal/Vertical), Ribbons (Catmull–Rom & Hermite curves with adaptive subdivision), Beams, 3D Meshes, Nanite cluster LOD meshes, 3D Gaussian Splatting, Runtime Virtual Textures (RVT), Groom hair strands, Volumetric Fog/Materials, Deferred Decals, Dynamic Point/Spot Lights, Depth Soft Particles, and 3D OpenAL Spatial Audio — with built-in HDR Bloom across every supported Minecraft version (1.20.1 through 26.3).
- **Zero-Readback GPU Niagara & Custom GLSL Materials**: Renders GPU-simulated Niagara emitters directly from OpenGL/Vulkan storage buffers with GPU distance sorting and no CPU readback cap, alongside custom GLSL fragment/vertex shaders (`assets/<namespace>/shaders/vfx/*.fsh`) with multi-texture samplers, scene depth/color access, and full blend/depth control.
- **Dynamic Graphics Backend (Minecraft 26.2 & 26.3)**:
  - Each target ships as a **single unified JAR** (no separate Vulkan vs. OpenGL downloads) with lossless compute-shader packaging (`compute.lfpack`) that keeps download size compact while preserving resource-pack overrides.
  - Defaults to **Vulkan** when the system driver and active rendering mods support it (including standalone and Sodium).
  - Automatically falls back to **OpenGL** when Vulkan is unavailable or when an OpenGL-only rendering mod (such as Iris) is active.
  - Always respects explicit user overrides (`--graphicsBackend`, in-game video settings, and vanilla crash recovery).
- **Optional Mod Compatibility**: Fabric builds require Fabric API for the matching Minecraft version. LumenFX automatically enables optional integrations with **Iris**, **Sodium**, **GeckoLib 4.x / 5.x**, **KBR / KBR-Lite (KltytonBedrockRuntime)**, **PAL / PALMore**, **AnimCue**, and **KilaGraph**.
- **Animation Particle Keyframes (GeckoLib, KBR / KBR-Lite, PAL / PALMore, AnimCue)**: Preserves callback-only semantics on standalone GeckoLib and PAL/PALMore while automatically providing 3D locator, bone, and bent-limb pose tracking (`PlayerBonePoseSampler`) plus controller-reset cleanup when triggered via `LumenFxKeyframeBridge.trigger(...)`; automatically coexists with KBR / KBR-Lite without Mixin order conflicts, and integrates with AnimCue (0.1.2+) local and multiplayer scenes following Vanilla → KBR → LumenFX priority.
- **Lock-Free Work-Stealing Multi-Threaded Simulation**: Runs emitter simulation on persistent lock-free worker pools with workload-balanced LPT scheduling, owner-thread overlap, pre-compiled Niagara parameter bindings, hoisted VectorVM execution, and ring-buffered GPU uploads.

---

## Supported Versions & Loaders

| Minecraft | Loader | Maven Artifact ID | Java | Graphics Backend |
| :---: | :---: | :--- | :---: | :--- |
| **26.3** | NeoForge | `lumenfx-neoforge-26.3` | Java 25 | Dynamic Vulkan-first / OpenGL fallback |
| **26.3** | Fabric | `lumenfx-fabric-26.3` | Java 25 | Dynamic Vulkan-first / OpenGL fallback |
| **26.2** | NeoForge | `lumenfx-neoforge-26.2` | Java 25 | Dynamic Vulkan-first / OpenGL fallback |
| **26.2** | Fabric | `lumenfx-fabric-26.2` | Java 25 | Dynamic Vulkan-first / OpenGL fallback |
| **26.1.2** | NeoForge | `lumenfx-neoforge-26.1.2` | Java 25 | Native OpenGL (Full Feature Parity) |
| **26.1.2** | Fabric | `lumenfx-fabric-26.1.2` | Java 25 | Native OpenGL (Full Feature Parity) |
| **1.21.1** | NeoForge | `lumenfx-neoforge-1.21.1` | Java 21 | Native OpenGL (Full Feature Parity) |
| **1.21.1** | Fabric | `lumenfx-fabric-1.21.1` | Java 21 | Native OpenGL (Full Feature Parity) |
| **1.20.1** | Forge | `lumenfx-forge-1.20.1` | Java 17 | Native OpenGL (Full Feature Parity) |
| **1.20.1** | Fabric | `lumenfx-fabric-1.20.1` | Java 17 | Native OpenGL (Full Feature Parity) |

---

## Developer Quick Start

### 1. Add Maven Dependency

LumenFX artifacts are hosted on **Sighs Maven**:

```groovy
repositories {
    maven {
        name = 'Sighs Maven'
        url = uri('https://maven.sighs.cc/repository/maven-public/')
    }
}

dependencies {
    // Replace with your target artifact ID from the table above
    implementation 'com.kltyton.lumenfx:lumenfx-neoforge-26.3:0.3.3'
}
```

### 2. Spawn & Control Effects in Java

```java
EffectHandle effect = VfxEffects.spawn(
        clientLevel,
        Identifier.parse("lumenfx_showcase:photon/kila_material_gallery"),
        position,
        seed
);

effect.moveTo(nextPosition);
// Optional: use setPose(position, quaternion, scale) or setColor(r, g, b, a)
// Optional: call trackRenderSamples() once for render-driven anchor updates
// Optional: setBeamEnd(targetPos) for Beam emitters

if (!effect.isAlive()) {
    // Effect finished naturally or was invalidated by resource reload / level change
}

effect.close(); // Stops new emission and lets existing particles finish gracefully
```

Effect ID `my_mod:magic/trail` resolves to `assets/my_mod/lumenfx/effects/magic/trail.json` (or `.fx` for Photon assets).

---

## Repository Structure

```text
LumenFX/
├── common/                 # Shared multi-version VFX engine, renderers, shaders & native math/audio
├── targets/                # 10 loader/version targets (1.20.1–26.3 × Forge/NeoForge/Fabric)
├── examples/
│   └── showcase-pack/      # Ready-to-load showcase resource pack (all 10 README/CurseForge effects)
├── skills/
│   └── lumenfx-vfx/        # AI Agent Skill (Experimental): SKILL.md, schema/GLSL refs & deterministic validator
├── tools/
│   ├── unity/              # Unity UPM Editor Plugin (com.kltyton.lumenfx.exporter)
│   ├── unreal/             # Unreal Engine 5 Editor Plugin (LumenFxExporter.uplugin) & exporters
│   ├── compiler/           # Offline UE5 Niagara CLI compiler (C#)
│   └── shaders/            # Offline SPIR-V / compute shader baking manifests
├── docs/
│   ├── wiki/               # Bilingual GitHub Wiki mirror (English & 简体中文)
│   └── publishing/         # CurseForge / MCMod descriptions, icon & showcase screenshots
├── buildSrc/ & gradle/     # Multi-target Gradle convention plugins & build definitions
└── licenses/               # Bundled third-party attribution & licenses (Photon CC BY-NC-SA 4.0, expr)
```

---

## Documentation & Offline Authoring Tools

- **[Official Showcase Pack](examples/showcase-pack/README.md)** — Ready-to-load resource pack with all 10 showcase effects, custom GLSL shaders, procedural textures, and `.lfmesh` 3D models.
- **[GitHub Wiki](https://github.com/kltyton/LumenFX/wiki)** (also mirrored in [`docs/wiki/`](docs/wiki/Home.md)):
  - [Getting Started & API](docs/wiki/Getting-Started.md)
  - [Effect JSON & GLSL Reference](docs/wiki/Effect-Format-Reference.md)
  - [Niagara, Unity & Photon Workflow](docs/wiki/Niagara-and-Unity-Workflow.md)
  - [GeckoLib & KBR Particle Keyframes](docs/wiki/GeckoLib-and-KBR-Keyframes.md)
  - [Graphics Backends & Compatibility](docs/wiki/Graphics-Backends-and-Compatibility.md)
  - [Fork Relationship & Licensing](docs/wiki/Fork-and-Licensing.md)
  - [AI Agent Skill (Experimental)](docs/wiki/AI-Agent-Skill.md)
- **AI Agent Skill (Experimental)**:
  - [`skills/lumenfx-vfx/`](skills/lumenfx-vfx/SKILL.md) — Open-standard Agent Skill (`SKILL.md`, source-verified JSON v1 & GLSL 330 references, starter templates, and a zero-dependency Python validator `validate_lumenfx_effect.py`) for authoring, validating, and optimizing LumenFX effects with Codex, Antigravity, Claude Code, or Cursor (`npx skills add kltyton/LumenFX --skill lumenfx-vfx`).
- **Offline Creator Plugins & Compilers** (never bundled into runtime mod JARs):
  - **[ParticleCue Studio](https://github.com/kltyton/ParticleCue-Studio)** (Blockbench Desktop Plugin) — Author, preview, and edit LumenFX JSON v1 (`sprite`/`ribbon`/`beam`/`mesh`, `.lfmesh`, custom `.fsh` shaders, curves/gradients, KilaMaterial 6-module composer, and **KilaGraph** visual GLSL node editor), Photon `.fx`, Snowstorm, KBR 3D, and Unity/Niagara overrides directly inside Blockbench with Portable ZIP & Self-Contained `.bbmodel` sharing.
  - [Unity UPM Editor Plugin](tools/unity/README.md) (`com.kltyton.lumenfx.exporter`) — Install via Unity Package Manager; includes creator presets, Minecraft 1-block grid guides, inline validation, batch export, URP Shader Graph compilation, and optional `pack.mcmeta` export.
  - [Unreal Engine 5 Editor Plugin](tools/unreal/LumenFxExporter/README.md) (`LumenFxExporter.uplugin`) — Content Browser right-click export on `UNiagaraSystem` assets with Project Settings, pre-export validation, and async compiler runner.
  - [Unreal Engine Niagara Compiler](tools/compiler/README.md) — Offline compiler for Niagara CPU VectorVM, GPU compute shaders, materials, vector fields, and SimCaches.

---

## Building from Source

```powershell
# Build the default target (neoforge-26.2)
.\gradlew.bat --no-daemon --console=plain build

# Or build a specific target directory (e.g. fabric-26.2, neoforge-1.21.1, forge-1.20.1)
cd targets\fabric-26.2
.\gradlew.bat --no-daemon --console=plain build
```

---

## Licensing & Attribution

- **LumenFX Original Code & Offline Tools**: Licensed under the [MIT License](LICENSE).
- **Photon-Derived Runtime Code**: Forked from [Photon](https://github.com/Low-Drag-MC/Photon) (Photon 26.2 by **KilaBash** / Low-Drag-MC) and distributed under the [Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License (CC BY-NC-SA 4.0)](licenses/photon/LICENSE). Please review the Photon license terms regarding attribution and non-commercial redistribution.
- **Expression Parser (`expr`)**: Uses Darius Bacon's expression implementation under [licenses/expr/COPYING](licenses/expr/COPYING).
- **Third-Party Assets & Engine Components**: Retain their respective original licenses.
