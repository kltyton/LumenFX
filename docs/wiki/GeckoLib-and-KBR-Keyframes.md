<p align="right">
  <strong>English</strong> | <a href="GeckoLib-and-KBR-Keyframes-zh">简体中文</a>
</p>

# Animation Particle Keyframes (GeckoLib, KBR / KBR-Lite, PAL / PALMore, AnimCue)

LumenFX provides zero-dependency, pure optional soft-compatibility across all 10 targets (no classes are loaded and no mixins are applied when an optional animation mod is absent):

- **GeckoLib 4.x** (Minecraft 1.20.1 & 1.21.1) and **GeckoLib 5.x** (Minecraft 26.1.2, 26.2 & 26.3)
- **KBR / KBR-Lite** (`kltyton_bedrock_runtime`, both full and Lite builds)
- **Player Animation Library (PAL)** (1.21.1 / 26.1.2 / 26.2 / 26.3) and **PlayerAnimationLibraryMoreRotation (PALMore)** (including 1.20.1 `PalMoreKeyframePlayer` and 3-axis bend deformation)
- **AnimCue (`interactive_animation` 0.1.2+)** (both client-local scenes and server-synced multiplayer scenes, with per-participant locator isolation and automatic cleanup on scene finish/cancel)

---

## Consistent Dispatch Semantics Per Animation Library

| Animation Library | Auto-Spawns Particles? | Integration Behavior |
| :--- | :---: | :--- |
| **Standalone GeckoLib (without KBR)** | No (preserves callback-only contract) | Matches GeckoLib's native callback-only design. Calling `LumenFxKeyframeBridge.trigger(...)` inside `setParticleKeyframeHandler(...)` automatically enables **3D locator/bone tracking** and **controller-reset cleanup**. |
| **KBR / KBR-Lite Coexistence** | Yes (follows KBR's auto-playback) | Intercepts KBR's `GeckoLibHelper.processParticleEffect` (and full KBR `BedrockAnimationParticles.create`): routes loaded LumenFX effects (or `lumenfx:` prefixes) to LumenFX and cancels KBR before KBR can throw `Unknown particle id` or Molang errors; leaves KBR-registered particles untouched; gracefully skips unregistered IDs. Completely immune to Mixin apply order across 1.20.1–26.3. |
| **PAL / PALMore** | No (preserves callback-only contract) | Matches PAL / PALMore's native callback-only design. Calling `LumenFxKeyframeBridge.trigger(...)` inside a PAL / PALMore particle callback automatically samples **3D world positions & rotations of animated player bones (including native X-bend and PALMore 3-axis bend/position/scale)** and cleans up on animation stop/reset. |
| **AnimCue (0.1.2+)** | Yes (follows AnimCue's auto-playback) | Preserves AnimCue's built-in priority (**Vanilla `ParticleType` → KBR → LumenFX**): local client scenes fall back to LumenFX at `KbrParticleCompat#spawn` when neither vanilla nor KBR handled the keyframe; server-synced remote scenes advance LumenFX-only keyframes directly on `ClientScene` (even when the dedicated server does not install LumenFX), isolate identical locator names across participants with 3D bone/bend tracking, and clean up automatically on `ClientScene#finish`. |

---

## KBR / KBR-Lite Automatic Effect Routing

Add standard `particle_effects` keyframes in your Blockbench `.animation.json`:

```json
"particle_effects": {
  "0.25": {
    "effect": "my_mod:fire_slash",
    "locator": "sword_tip",
    "pre_effect_script": "scale=1.25; color=#FFAA33; follow=true; rotate=true"
  }
}
```

| Effect ID Format | Routing Behavior with KBR / KBR-Lite |
| :--- | :--- |
| `my_mod:fire_slash` | If loaded in LumenFX, LumenFX plays it and skips KBR; otherwise if registered in KBR `MolangParticleEngine` / `BedrockParticleBindings`, KBR plays it untouched; if registered in neither, KBR is skipped cleanly so custom callbacks work without errors. |
| `lumenfx:my_mod/fire_slash` *(recommended single-colon format)* or `lumenfx:my_mod:fire_slash` / `lumenfx/my_mod:fire_slash` | Explicitly forces routing to LumenFX (never passed to KBR even if missing). |
| `fire_slash` *(bare path)* | Checks `lumenfx:fire_slash`, then `minecraft:fire_slash`; falls back using the rule above if not loaded. |

---

## 3D Locator, Bone & Player Bend Tracking

- **GeckoLib 5.x (26.1.2 / 26.2 / 26.3)**: Attaches automatically to `RenderPassInfo` locator and bone listeners (`addLocatorPositionListener` + `addBonePositionListener`) and captures full 3D orientation quaternions from `GeoLocator` poses, supporting both `AnimatableManager` and `Entity` hosts.
- **GeckoLib 4.x (1.20.1 / 1.21.1)**: Automatically enables world-space matrix tracking on actively bound `GeoBone`s and forwards both bone transforms and (when KBR is installed) KBR Blockbench `LocatorValue` sub-locators.
- **PAL / PALMore (1.20.1–26.3)**:
  - Supports standard player bones (`body`, `head`, `torso`, `right_arm`/`rightArm`, `left_arm`/`leftArm`, `right_leg`/`rightLeg`, `left_leg`/`leftLeg`, `right_item`/`rightItem`/`right_hand`, `left_item`/`leftItem`/`left_hand`, `cape`, `elytra`), custom `pivotBones`, and PAL Bend Player Tools / DragonCore bend helpers (`torso_bend`, `right_arm_bend`/`Right_Arm_Lower`, `left_arm_bend`/`Left_Arm_Lower`, `right_leg_bend`/`Right_Leg_Lower`, `left_leg_bend`/`Left_Leg_Lower`);
  - Applies PAL native X-bend as well as PALMore 3-axis `bend.rotation` (X/Y/Z), `bend.position`, and `bend.scale`.

---

## Supported `pre_effect_script` Directives

Separate directives with `;` or newlines. Optional Molang prefixes (`v.`, `variable.`, `lumenfx_`, `lumenfx.`) are automatically stripped (e.g. `v.lumenfx_scale=1.5`).

| Directive | Values / Example | Description |
| :--- | :--- | :--- |
| `follow` / `bind_to_actor` / `attach` | `true` \| `false` | Follow the target locator or entity every frame (defaults to `bind_to_actor`, or `true` when a `locator` is named). |
| `rotate` / `inherit_rotation` | `true` \| `false` | Inherit 3D locator/bone rotation quaternion (default `true`). |
| `scale` / `size` | `1.5` | Uniform scale multiplier multiplied with entity scale (default `1.0`). |
| `color` / `tint` | `#FF8040`, `#FF8040CC`, `1,0.5,0.25,1` | Per-instance RGBA color multiplier. |
| `offset` / `pos` | `0,0.5,-0.25` | World offset in blocks added to the locator/entity anchor. |
| `beam_end` / `target_locator` | `hand_left` | Second locator driving `EffectHandle.setBeamEnd(...)`. |
| `stop` / `close` / `end` | `true` | Smoothly stops and drains matching active effects on this actor/controller/locator. |
| `restart` / `respawn` | `true` | Closes any existing bound instance in the same slot before spawning a fresh one. |
| `seed` | `4242` | Deterministic seed override. |

---

## Lifecycle Cleanup

Bound keyframe effects are automatically closed and smoothly drained when:
- The animation controller resets or stops (GeckoLib 5 `reset()`, GeckoLib 4 `resetEventKeyFrames()`, PAL `resetEventKeyFrames()` / `stop()`, PALMore 1.20.1 `PalMoreKeyframePlayer` end of playback);
- A `stop=true` keyframe fires;
- The entity is removed or switches dimensions;
- The client reloads resources (`F3+T`) or disconnects.

---

## Public Java API (`LumenFxKeyframeBridge`)

### Triggering from a PAL / PALMore Particle Callback (with Bent Bone Tracking)

```java
import com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge;
import net.minecraft.client.multiplayer.ClientLevel;

controller.setParticleKeyframeHandler(ctx -> {
    if (ctx.avatar().level() instanceof ClientLevel clientLevel) {
        LumenFxKeyframeBridge.trigger(
                clientLevel,
                ctx.avatar(),
                ctx.avatar(),
                ctx.animationController(),
                "pal",
                ctx.effect(),
                ctx.locator(),
                ctx.script(),
                null,
                ctx.avatar().getX(), ctx.avatar().getY(), ctx.avatar().getZ()
        );
    }
});
```

### Filtering, Listening & Manual Control

```java
// 1. Optional: filter or redirect specific keyframes
LumenFxKeyframeBridge.addFilter(request -> {
    if (request.entity() != null && request.entity().isInvisible()) {
        return false; // Skip spawning
    }
    return true;
});

// 2. Optional: customize spawned EffectHandle instances (e.g. set Niagara user parameters)
LumenFxKeyframeBridge.addSpawnListener((request, rawHandle) -> {
    // Cast rawHandle to com.kltyton.lumenfx.api.client.EffectHandle
});

// 3. Optional: manually stop all keyframe-bound effects on an entity
LumenFxKeyframeBridge.stopForHost(entity);
```
