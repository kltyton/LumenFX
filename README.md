<p align="center">
  <img src="docs/publishing/artwork/lumenfx-icon-400.png" width="140" alt="LumenFX Logo" />
</p>

<h1 align="center">LumenFX</h1>

<p align="center">
  <strong>Resource-driven cross-engine VFX runtime for Minecraft — supporting Unity ParticleSystem, Unreal Engine Niagara, and Photon <code>.fx</code> with dynamic Vulkan / OpenGL rendering.</strong>
</p>

<p align="center">
  <strong>English</strong> | <a href="README_zh.md">简体中文</a> | <a href="https://github.com/kltyton/LumenFX/wiki">Wiki</a>
</p>

---

## Fork Relationship & Origin

**LumenFX is a fork of [Photon](https://github.com/Low-Drag-MC/Photon) (Photon 26.2 by [KilaBash](https://github.com/KilaBash) / Low-Drag-MC).**

Building upon Photon's particle simulation core, timelines, curves, and `.fx` playback foundation, LumenFX restructures and extends the runtime into a **lightweight, resource-driven client VFX library** with:

- **No in-game editor bundled in the runtime JAR** — authored effects are shipped cleanly as standard Minecraft resource-pack files (`assets/<namespace>/lumenfx/...`).
- **Multi-engine asset support** — runs native LumenFX JSON effects, legacy Photon `.fx` effects, offline-exported **Unity ParticleSystem (Shuriken)** assets, and offline-compiled **Unreal Engine Niagara** systems (CPU VectorVM, GPU SPIR-V compute, and SimCache `.lfsc`).
- **Unified multi-version & multi-loader architecture** — ships a single unified mod JAR per target across Minecraft **1.20.1, 1.21.1, 26.1.2, and 26.2** (Forge, NeoForge, and Fabric).
- **Dynamic Vulkan-first / OpenGL fallback on Minecraft 26.2** — automatically uses Vulkan when supported and falls back seamlessly to OpenGL when needed.

Photon-derived source files retain KilaBash's attribution and are governed by the bundled [Photon License (CC BY-NC-SA 4.0)](licenses/photon/LICENSE). Original LumenFX code and offline authoring tools are licensed under [MIT](LICENSE).

---

## Showcase

All screenshots below were captured live in-game using LumenFX's native runtime — **no shader pack required**.

| Arcane Rune Circle & Multi-Ribbon Trails | Astral Vortex Portal |
| :---: | :---: |
| ![Arcane Rune Circle & Multi-Ribbon Trails](docs/publishing/screenshots/01_rune_ribbon_stream.png) | ![Astral Vortex Portal](docs/publishing/screenshots/02_astral_vortex_portal.png) |

| Built-in Bloom Fireball & Rising Embers | Custom GLSL Shader Plasma Trail |
| :---: | :---: |
| ![Built-in Bloom Fireball & Rising Embers](docs/publishing/screenshots/03_blazing_ember_fireball.png) | ![Custom GLSL Shader Plasma Trail](docs/publishing/screenshots/04_emerald_plasma_comet.png) |

<p align="center">
  <img src="docs/publishing/screenshots/05_luminous_physics_fountain.png" width="760" alt="Luminous Physics Particle Fountain" /><br/>
  <em>Gravity-arcing luminous particle jet & ground splash</em>
</p>

---

## Key Features

- **Cross-Engine VFX Runtime**: Plays native LumenFX JSON effects, Photon `.fx` files, Unity ParticleSystem exports, and Unreal Engine Niagara CPU/GPU systems & SimCaches through one unified Java API (`VfxEffects`).
- **Rich Renderers & Built-in Bloom**: Supports Sprites (Billboard, Stretched, Horizontal/Vertical), Ribbons (Catmull–Rom & Hermite curves with adaptive subdivision), Beams, 3D Meshes, and Dynamic Particle Lights — with built-in additive/bloom visuals that shine even without shader mods.
- **Custom GLSL Materials**: Write custom fragment shaders (`assets/<namespace>/shaders/vfx/*.fsh`) with multi-texture samplers, per-particle age/random/custom attributes, alpha/additive/premultiplied/multiply blending, and full depth control.
- **Dynamic Graphics Backend (Minecraft 26.2)**:
  - Each target ships as a **single unified JAR** (no separate Vulkan vs. OpenGL downloads).
  - Defaults to **Vulkan** when the system driver and active rendering mods support it (including standalone and Sodium).
  - Automatically falls back to **OpenGL** when Vulkan is unavailable or when an OpenGL-only rendering mod (such as Iris) is active.
  - Always respects explicit user overrides (`--graphicsBackend`, in-game video settings, and vanilla crash recovery).
- **Optional Mod Compatibility**: Runs completely standalone with zero required rendering dependencies; offers optional compatibility with **Iris**, **Sodium**, and **KBR**.
- **Bounded Multi-Threaded Simulation**: Offloads particle simulation onto a bounded worker pool while keeping resource reloads atomic and the render thread focused on GPU submission.

---

## Supported Versions & Loaders

| Minecraft | Loader | Maven Artifact ID | Java | Graphics Backend |
| :---: | :---: | :--- | :---: | :--- |
| **26.2** | NeoForge | `lumenfx-neoforge-26.2` | Java 25 | Dynamic Vulkan-first / OpenGL fallback |
| **26.2** | Fabric | `lumenfx-fabric-26.2` | Java 25 | Dynamic Vulkan-first / OpenGL fallback |
| **26.1.2** | NeoForge | `lumenfx-neoforge-26.1.2` | Java 25 | OpenGL |
| **26.1.2** | Fabric | `lumenfx-fabric-26.1.2` | Java 25 | OpenGL |
| **1.21.1** | NeoForge | `lumenfx-neoforge-1.21.1` | Java 21 | OpenGL |
| **1.21.1** | Fabric | `lumenfx-fabric-1.21.1` | Java 21 | OpenGL |
| **1.20.1** | Forge | `lumenfx-forge-1.20.1` | Java 17 | OpenGL |
| **1.20.1** | Fabric | `lumenfx-fabric-1.20.1` | Java 17 | OpenGL |

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
    implementation 'com.kltyton.lumenfx:lumenfx-neoforge-26.2:0.1.3'
}
```

### 2. Spawn & Control Effects in Java

```java
EffectHandle effect = VfxEffects.spawn(
        clientLevel,
        Identifier.parse("my_mod:magic/trail"),
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

## Documentation & Offline Authoring Tools

- **[GitHub Wiki](https://github.com/kltyton/LumenFX/wiki)** (also mirrored in [`docs/wiki/`](docs/wiki/Home.md)):
  - [Getting Started & API](docs/wiki/Getting-Started.md)
  - [Effect JSON & GLSL Reference](docs/wiki/Effect-Format-Reference.md)
  - [Niagara, Unity & Photon Workflow](docs/wiki/Niagara-and-Unity-Workflow.md)
  - [Graphics Backends & Compatibility](docs/wiki/Graphics-Backends-and-Compatibility.md)
  - [Fork Relationship & Licensing](docs/wiki/Fork-and-Licensing.md)
- **Offline Authoring Tools** (never bundled into runtime mod JARs):
  - [Unity ParticleSystem Exporter](tools/unity/README.md) — Unity Editor tool exporting Shuriken hierarchies, URP Shader Graph materials, textures, and meshes.
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
