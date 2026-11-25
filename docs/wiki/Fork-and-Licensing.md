<p align="right">
  <strong>English</strong> | <a href="Fork-and-Licensing-zh">简体中文</a>
</p>

# Fork Lineage & Licensing

## Fork Relationship to Photon

**LumenFX is an independent fork of [Photon](https://github.com/Low-Drag-MC/Photon) (Photon 26.2), originally created by [KilaBash](https://github.com/KilaBash) / Low-Drag-MC.**

### What LumenFX Preserves from Photon

- Core `.fx` effect loading, NBT migration, timelines, and nested sub-effect playback.
- Foundational particle emitter architecture, curves, emission shapes, collision & force solvers, beams, trails, model particles, and shader material hooks.
- Darius Bacon's expression evaluator (`expr`) used by function-based emission shapes.

### How LumenFX Differs from Upstream Photon

| Aspect | Upstream Photon | LumenFX (Fork) |
| :--- | :--- | :--- |
| **In-Game Editor** | Included inside the mod | **Removed from runtime JAR** — resource-driven runtime only |
| **External Engine Assets** | Photon `.fx` authoring | Adds offline **Unity ParticleSystem** exporter & **Unreal Engine Niagara** (CPU VectorVM + GPU SPIR-V + SimCache `.lfsc`) compiler |
| **Minecraft 26.2 Backends** | Target-specific pipeline | **Single unified JAR** with dynamic Vulkan-first / automatic OpenGL fallback |
| **Multi-Version Matrix** | Selected versions | Unified multi-target release across **1.20.1, 1.21.1, 26.1.2, and 26.2** (Forge / NeoForge / Fabric) |
| **Rendering Mod Dependencies** | Varies by version | Standalone-ready; Iris, Sodium, and KBR strictly optional |

---

## License Summary

LumenFX combines original code with Photon-derived runtime files and third-party components. Each component remains governed by its respective license:

1. **LumenFX Original Code & Offline Tools**
   - Copyright © 2026 kltyton
   - License: **[MIT License](https://github.com/kltyton/LumenFX/blob/main/LICENSE)**

2. **Photon-Derived Runtime Source Files**
   - Copyright © 2025 Photon by KilaBash
   - License: **[Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International (CC BY-NC-SA 4.0)](https://github.com/kltyton/LumenFX/blob/main/licenses/photon/LICENSE)**
   - Attribution to KilaBash / Photon is preserved in source headers and documentation. Please note the non-commercial and ShareAlike requirements of CC BY-NC-SA 4.0 when redistributing or deriving from Photon code.

3. **Expression Parser (`expr`)**
   - By Darius Bacon — governed by [`licenses/expr/COPYING`](https://github.com/kltyton/LumenFX/blob/main/licenses/expr/COPYING).

4. **Third-Party Libraries & Unreal Shader Components**
   - Retain their original licenses (see `tools/compiler/licenses/` and `gradle.properties`).

### Content Created Using LumenFX

Custom particle effects, resource packs, shaders, and mods authored *using* LumenFX's JSON format or public Java API are your own creations and are not bound by Photon's CC BY-NC-SA 4.0 code license (consistent with Photon's own "Content Created Using This Mod" clause).
