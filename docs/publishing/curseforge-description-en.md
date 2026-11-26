<!-- ====================================================================== -->
<!-- ONE-SENTENCE ABOUT / SUMMARY (Paste into CurseForge Summary field)     -->
<!-- ====================================================================== -->
<!--
A lightweight Photon fork bringing Unity, Unreal Niagara, and custom GLSL particle VFX to Minecraft with automatic Vulkan/OpenGL support.
-->

# ✨ LumenFX

**A lightweight, resource-driven next-gen VFX runtime for Minecraft — bringing Unity ParticleSystem, Unreal Engine Niagara, and Photon `.fx` effects to life with built-in bloom, custom shaders, and automatic Vulkan / OpenGL rendering.**

> **About the Fork:** LumenFX is a fork of **[Photon](https://github.com/Low-Drag-MC/Photon)** (Photon 26.2 by **KilaBash** / Low-Drag-MC), rebuilt into a clean, editor-free client VFX runtime supporting Minecraft **1.20.1, 1.21.1, 26.1.2, 26.2, and 26.3** across **Forge, NeoForge, and Fabric**.

---

## 🌟 Visual Showcase

*Every screenshot below was captured directly in-game using LumenFX's native renderer — **no shader mod required!***

### 🔮 Arcane Rune Circles & Multi-Ribbon Trails
Weave smooth, multi-layered glowing ribbons, orbital curves, and intricate rune rings directly in the world.

![Arcane Rune Circle & Multi-Ribbon Trails](https://raw.githubusercontent.com/kltyton/LumenFX/main/docs/publishing/screenshots/01_rune_ribbon_stream.png)

### 🌌 Volumetric Astral Portals & Energy Vortices
Bring boss arenas, portals, and magical altars alive with layered additive glow, radial energy rings, and dynamic light.

![Astral Vortex Portal](https://raw.githubusercontent.com/kltyton/LumenFX/main/docs/publishing/screenshots/02_astral_vortex_portal.png)

### 🔥 Built-in Bloom Effects (No Shader Mod Required)
Incandescent fireballs, rising ember plumes, and soft HDR-style glow work out of the box on vanilla graphics — and stay compatible when you do install shaders!

![Built-in Bloom Fireball & Embers](https://raw.githubusercontent.com/kltyton/LumenFX/main/docs/publishing/screenshots/03_blazing_ember_fireball.png)

### 🎨 Custom GLSL Shader Materials
Mod authors and resource-pack creators can drive fully custom fragment shaders for animated plasma trails, energy distortions, and procedural patterns.

![Custom GLSL Shader Plasma Trail](https://raw.githubusercontent.com/kltyton/LumenFX/main/docs/publishing/screenshots/04_emerald_plasma_comet.png)

### 💧 Real-Time Particle Physics & Gravity Simulation
Support for gravity arcs, velocity/force curves, drag, curl noise, and ground splashes powered by bounded multi-threaded simulation.

![Luminous Physics Particle Fountain](https://raw.githubusercontent.com/kltyton/LumenFX/main/docs/publishing/screenshots/05_luminous_physics_fountain.png)

---

## ⚡ Why Players & Modpack Makers Love LumenFX

- **One Unified Download — Zero Backend Headaches (26.2 & 26.3):** No separate "Vulkan edition" or "OpenGL edition" to worry about! On Minecraft 26.2 and 26.3, LumenFX automatically uses **Vulkan** when your GPU and mod list support it, and seamlessly falls back to **OpenGL** when needed (such as on older drivers or with OpenGL-only shader mods), while lossless compute-shader packaging keeps JAR sizes compact.
- **Full Native OpenGL Feature Parity on 1.20.1, 1.21.1 & 26.1.2:** Every supported Minecraft version ships with built-in HDR Bloom, custom GLSL material shaders, depth soft particles, complete Photon `.fx` timelines/forcefields/collisions/sub-emitters, zero-readback GPU Niagara rendering (no particle count readback cap), Nanite cluster LOD meshes, 3D Gaussian Splatting, Runtime Virtual Textures (RVT), Groom hair strands, volumetric fog/decals/lights, and 3D OpenAL spatial audio.
- **High-Throughput Multi-Threaded Simulation:** Lock-free work-stealing worker pools with workload-balanced scheduling, pre-compiled Niagara parameter bindings, hoisted VectorVM execution, and ring-buffered GPU uploads keep frame pacing smooth across all 10 targets.
- **Ultra-Lightweight Standalone Runtime (Zero Heavy Dependencies):** No mandatory `LDLib2`, `KilaGraph`, or rendering mod required, and no in-game editor UI or demo bloat inside the JAR. Optional soft-compatibility with **Iris**, **Sodium**, **GeckoLib (4.x / 5.x)**, **KBR**, and **KilaGraph** activates automatically when those mods are present.
- **GeckoLib & KBR Particle Keyframe Support:** Trigger LumenFX particles, ribbons, and trails directly from Blockbench `particle_effects` animation keyframes across all supported Minecraft versions — with 3D locator position & rotation tracking, `pre_effect_script` controls (`scale`, `color`, `offset`, `rotate`, `beam_end`, `stop`, etc.), and seamless coexistence with KBR Bedrock / SnowStorm particles.
- **Cross-Engine Power & Official Unity / UE5 Editor Plugins:** Runs native LumenFX JSON effects, **Photon `.fx`** assets, offline-exported **Unity ParticleSystem (Shuriken)** hierarchies (with a dedicated **Unity UPM Editor Plugin** featuring presets, MC 1-block guides, and batch export), and offline-compiled **Unreal Engine Niagara** systems (CPU VectorVM, GPU compute, and SimCache, with a dedicated **UE5 `.uplugin` Editor Plugin** & CLI compiler).

---

## 📦 Supported Versions & Loaders

| Minecraft | Mod Loaders | Rendering Backend |
| :---: | :---: | :--- |
| **26.3** | NeoForge, Fabric | Dynamic Vulkan-first / Automatic OpenGL fallback |
| **26.2** | NeoForge, Fabric | Dynamic Vulkan-first / Automatic OpenGL fallback |
| **26.1.2** | NeoForge, Fabric | Native OpenGL (Full Feature Parity) |
| **1.21.1** | NeoForge, Fabric | Native OpenGL (Full Feature Parity) |
| **1.20.1** | Forge, Fabric | Native OpenGL (Full Feature Parity) |

---

## 🛠️ For Mod Developers & Creators

- **GitHub Source, Unity UPM & UE5 Editor Plugins:** [github.com/kltyton/LumenFX](https://github.com/kltyton/LumenFX)
- **Full Wiki, Keyframe Bridge & Java API Documentation:** [github.com/kltyton/LumenFX/wiki](https://github.com/kltyton/LumenFX/wiki)
- **Maven Repository (Sighs Maven):** `https://maven.sighs.cc/repository/maven-public/` (`com.kltyton.lumenfx:lumenfx-<loader>-<mc_version>:0.3.0`)

---

## 📜 Credits & Licensing

- **Forked from [Photon](https://github.com/Low-Drag-MC/Photon)** (Photon 26.2) by **KilaBash** / Low-Drag-MC.
- Photon-derived runtime code is distributed under **CC BY-NC-SA 4.0** (see bundled `licenses/photon/LICENSE`). Original LumenFX code and offline tools are licensed under **MIT**.
- Feel free to include LumenFX in non-commercial modpacks!
