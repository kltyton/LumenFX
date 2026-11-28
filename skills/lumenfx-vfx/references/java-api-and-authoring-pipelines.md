# LumenFX Java API, Animation Keyframes & Cross-Engine Pipelines

> Source-verified against `VfxEffects.java`, `EffectHandle.java`, `LumenFxKeyframeBridge.java`, and LumenFX offline tooling (`tools/unity/`, `tools/unreal/`, `tools/compiler/`, `ParticleCue Studio`).

---

## 1. Client Java API (`VfxEffects` & `EffectHandle`)

All effect formats (LumenFX JSON v1, Unity exports, UE5 Niagara compilations, and Photon `.fx`) share one uniform entry point on the client.

> **Version Import Note**: On Minecraft `26.1.2 / 26.2 / 26.3`, use `net.minecraft.resources.Identifier`; on `1.20.1 / 1.21.1`, use `net.minecraft.resources.ResourceLocation`.

### 1.1 Spawning & Lifecycle Control

```java
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.VfxEffects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

Identifier id = Identifier.parse("my_mod:combat/arcane_impact_burst");
if (VfxEffects.hasEffect(id)) {
    EffectHandle handle = VfxEffects.spawn(clientLevel, id, worldPos, seed);

    // Optional: call once if pose updates come from a render-thread locator/bone pass
    // handle.trackRenderSamples();

    // Update world position or full 3D pose (position, quaternion, scale)
    handle.moveTo(nextPos);
    // handle.setPose(nextPos, rotationQuat, 1.0f);

    // Optional: multiply RGBA tint at runtime
    // handle.setColor(0.5f, 0.9f, 1.0f, 1.0f);

    // Optional: set beam endpoint for "beam" renderers
    // handle.setBeamEnd(targetPos);

    // Check liveness (false when finished, closed & drained, level changed, or resources reloaded)
    if (!handle.isAlive()) {
        // release reference
    }

    // Smoothly stop emitting new particles and let living particles finish naturally
    handle.close();
}
```

### 1.2 Dynamic Niagara User Parameters

For UE5 Niagara effects exposing `User.*` parameters, push values lock-free via `EffectHandle.ParameterValue`:
- `ParameterValue.ofFloat(float)` / `ofFloats(float...)` (1–4 components)
- `ParameterValue.ofInts(int...)` / `ofBooleans(boolean...)`
- `ParameterValue.ofSourcePosition(double x, double y, double z)` (in Unreal cm; converted per frame)

### 1.3 Ambient / Entity-Attached Effect Hygiene

- **Check `handle != null && handle.isAlive()`** before updating; re-spawn only when needed so you never leak duplicate stacked handles on the same entity!
- **Always call `handle.close()`** when the entity dies, dismounts, leaves render distance, or transitions state.
- **Avoid spawning a new handle every tick** for continuous trails: spawn **one** looping handle with `local_space: false` + `rate_over_distance` (or `distance_spacing`) and update it via `handle.moveTo(pos)` / `handle.setPose(...)`.

---

## 2. Animation Particle Keyframes (`LumenFxKeyframeBridge`)

LumenFX provides zero-hard-dependency soft integration with **GeckoLib (4.x / 5.x)**, **KBR / KBR-Lite**, **PAL / PALMore**, and **AnimCue (`interactive_animation` 0.1.2+)**:

### 2.1 Keyframe Effect ID Resolution (`LumenFxKeyframeBridge.parseEffectId`)
- Explicit prefix `lumenfx:<ns>:<path>` or `lumenfx/<ns>:<path>` → forces LumenFX dispatch.
- Standard `<ns>:<path>` → plays via LumenFX when loaded (on KBR / AnimCue, priority is Vanilla → KBR → LumenFX).
- Bare `<path>` → resolves against `lumenfx:<path>`.

### 2.2 Manual Trigger Inside GeckoLib / PAL / PALMore Callbacks
Standalone GeckoLib and PAL/PALMore preserve their native callback-only model. Call `LumenFxKeyframeBridge.trigger(...)` inside your particle keyframe handler to gain automatic 3D locator/bone/bend pose tracking and stop/reset cleanup:

```java
LumenFxKeyframeBridge.trigger(
        clientLevel,
        animatableHost,
        entity,          // nullable
        controllerKey,   // nullable
        controllerName,
        rawEffectId,     // e.g. "lumenfx:my_mod:combat/slash"
        locatorName,     // nullable Blockbench locator name
        preEffectScript, // nullable
        bindToActor,     // nullable Boolean
        x, y, z
);
```

Lifecycle cleanup helpers:
- `LumenFxKeyframeBridge.stopForHost(host)`
- `LumenFxKeyframeBridge.stopForController(host, controllerKey)`
- `LumenFxKeyframeBridge.stopEffect(host, effectId, locator)`
- Global hooks: `addFilter(...)`, `addSpawnListener(...)`, `setAutoInterceptEnabled(boolean)`.

---

## 3. Cross-Engine Offline Authoring Pipelines

| Pipeline | Tool Location | Coordinate / Scale Rule | Key Constraints |
| --- | --- | --- | --- |
| **Blockbench (`ParticleCue Studio`)** | `https://github.com/kltyton/ParticleCue-Studio` | Right-handed, `1 unit = 1 block` | Direct LumenFX JSON v1, KilaMaterial/KilaGraph GLSL, Photon `.fx`, and lossless overrides (`EngineOverride.applyOverridesLossless`) for Unity/UE5 exports. |
| **Unity Shuriken (UPM)** | `tools/unity/` (`com.kltyton.lumenfx.exporter`) | `1 Unity unit = 1 block`; left→right handed `(x, y, -z)` (Cone Volume rotates `(x, z, -y)` to align `+Z` → `+Y`) | Zero silent skip on Validate/Export. Rejects 3D Start Rotation X/Y, `_DISTORTION_ON`, `_NORMALMAP`, `_REQUIRE_UV2`, non-readable meshes. Supports URP Shader Graph & `"LumenFxFragment"` tag. |
| **UE5 Niagara** | `tools/unreal/LumenFxExporter` + `tools/compiler/` (.NET 10) | **`100 Unreal cm = 1 block`** (1.8-block player = 180 cm) | GPU emitters MUST have **Fixed Bounds** enabled in Niagara to prevent frustum pop-out. `GPUComputeSim` requires `UnrealSourceRoot` configured in Project Settings. |
| **Photon `.fx`** | `assets/<ns>/lumenfx/effects/<path>.fx` | Native Photon coordinates | Loaded directly by `VfxEffects.spawn(...)`; supports timelines, sub-effects, beams, trails, and 3D models. |

### Lossless Override Rule for Exported Unity / UE5 Assets
Exported Unity/Niagara JSONs contain compiled binary/bytecode payloads (`niagara`, `spawn_program`, `update_program`, `gpu_program`, `sim_cache`, `.lfvf`, `.lfmesh`, and strict `MeshRendererJson` entries). When tweaking visual parameters (scale, speed, color tint, blend, HDR, sort order), **patch only the targeted fields in place** — never regenerate or rewrite the whole file from scratch, or you will silently strip compiled simulation programs!
