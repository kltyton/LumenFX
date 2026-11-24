# Niagara, Unity & Photon Workflow

LumenFX separates **offline authoring/conversion** from the **lightweight runtime JAR**. None of the Unity, Unreal, or compiler binaries are shipped inside the Minecraft mod JAR.

---

## 1. Unity ParticleSystem (Shuriken)

The Unity Editor exporter lives in `tools/unity/Editor/` inside the repository and supports **Unity 2020.3 LTS and newer**.

### Exporting Steps

1. Copy `tools/unity/Editor` into your Unity project (for example `Assets/Editor/LumenFX/`).
2. Select the root `GameObject` of your particle hierarchy in the Unity Hierarchy window.
3. Open **Assets → LumenFX → Export Selected Particle Systems...**.
4. Configure your resource namespace, effect ID, and output directory, then click **Validate** or **Export**.

### Supported Unity Features

- **Main & Emission**: Duration, looping, prewarm, simulation speed, start delay, curves & two-curve ranges (with weighted tangents & wrap modes), rate over time, rate over distance, bursts with probability and cycles, ring buffer modes.
- **Shapes**: Point, Sphere, Hemisphere, Circle, Box, Rectangle, Donut, Cone, Single Sided Edge, Mesh, and Sprite emission shapes (with shape textures).
- **Motion & Physics**: Velocity over Lifetime (including orbital/radial), Force over Lifetime, Inherit Velocity, Limit Velocity, Size/Color/Rotation over Lifetime & by Speed, Curl Noise, External Force Fields, World & Plane Collisions, Triggers, and Sub-Emitters.
- **Renderers & Materials**: Billboard (including Stretched, Horizontal, Vertical, Align to Direction, Min/Max Viewport Size, Flip), Mesh renderers, Particle & Ribbon Trails, Texture Sheet Animation (Grid & Sprite List), Built-in & URP Particle shaders, plus **URP Shader Graph** (Unlit, Metallic Lit, Specular Lit, Complex Lit) and custom `LumenFxFragment` shader tags.

See [`tools/unity/README.md`](https://github.com/kltyton/LumenFX/blob/main/tools/unity/README.md) for the complete exporter specification.

---

## 2. Unreal Engine Niagara

The offline Niagara compiler lives in `tools/compiler/` (.NET 10) alongside helper export tools in `tools/unreal/`.

### Workflow Overview

1. Export the target Niagara System, Emitters, Materials, Functions, and Texture2D descriptions using `tools/unreal/Export-UnrealAssets.ps1` (UAssetAPI JSON) plus full-resolution source PNGs.
2. Run the offline compiler:
   ```powershell
   dotnet build tools/compiler/LumenFx.Compiler.csproj -o compiler-out
   dotnet compiler-out/LumenFx.Compiler.dll niagara <json-root> /Game/Path/To/NS_MyEffect.NS_MyEffect my_mod:effects/my_effect <png-root> <resources-root> <aliases.json> <render-profile.json> <vector-fields-root> <unreal-source-root>
   ```
3. Optional texture mip/compression baking via `./gradlew.bat bakeEffectTextures` for `.lftex.json` sidecars.
4. Package the generated `assets/<namespace>/lumenfx/...` files into your mod or resource pack.

### Supported Niagara Runtime Capabilities

- **CPU VectorVM**: Executes compiled system/emitter Spawn & Update bytecode with persistent ID tables, LUT/curve sampling, curl noise & 3D vector fields (`.lfvf`), and Niagara integration.
- **GPU Simulation (SPIR-V Compute)**: Compiles Niagara GPU compute stages and executes them natively on the active GPU backend (Vulkan or OpenGL compute) with SSBO state persistence and proper instance draining/reclamation.
- **SimCache Playback (`.lfsc`)**: Loads offline-recorded or runtime-recorded Niagara SimCaches (both CPU and GPU frames), supports seamless handoff back to live GPU simulation with full attribute restoration (`Float`, `Int`, `Half`, persistent IDs, and `AcquireTag`), and enforces per-device allocation limits safely.
- **Renderers**: Sprite (including cutout geometry & automatic pixel coverage), Ribbon (custom profiles, Hermite adaptive tessellation, dual UVs), Mesh (multi-LOD slots), Light, Decal, and Volume.

See [`tools/compiler/README.md`](https://github.com/kltyton/LumenFX/blob/main/tools/compiler/README.md) for CLI arguments and advanced options.

---

## 3. Photon `.fx` Compatibility

Because LumenFX is a fork of **Photon 26.2**, existing Photon `.fx` effects can be loaded directly through `VfxEffects.spawn(...)`:

- Place `.fx` files under `assets/<namespace>/lumenfx/effects/<path>.fx` (alongside their referenced textures/meshes).
- Supports Photon timelines, nested sub-effects, emitters, curves, emission shapes, collision & external forces, beams, trails, 3D model particles, custom shader materials, and asynchronous preloading.
- Clean lifecycle handling on natural completion, manual `close()` (running Release phases), level changes, and resource reloads.
