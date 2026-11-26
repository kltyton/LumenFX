<p align="right">
  <strong>English</strong> | <a href="GeckoLib-and-KBR-Keyframes-zh">简体中文</a>
</p>

# GeckoLib & KBR Particle Keyframes

LumenFX includes zero-dependency, pure optional soft-compatibility with **GeckoLib 4.x** (Minecraft 1.20.1 & 1.21.1), **GeckoLib 5.x** (Minecraft 26.1.2 & 26.2), and **KBR (`kltyton_bedrock_runtime`)** animation particle keyframes across all 8 targets.

When GeckoLib or KBR is absent, none of their classes are loaded and no keyframe mixins are applied.

---

## Automatic Effect Routing (Coexisting with KBR & Custom Handlers)

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

| Effect ID Format | Routing Behavior |
| :--- | :--- |
| `my_mod:fire_slash` | If `my_mod:fire_slash` is a loaded LumenFX JSON / Photon `.fx` / Unity / Niagara effect, LumenFX plays it automatically. Otherwise, LumenFX returns control untouched to **KBR SnowStorm / Bedrock particles** or your custom `AnimationController` handler. |
| `lumenfx:my_mod/fire_slash` *(Identifier-safe)* or `lumenfx:my_mod:fire_slash` or `lumenfx/my_mod:fire_slash` | Explicitly forces routing to LumenFX (never passed to KBR even if missing). Prefer `lumenfx:my_mod/fire_slash` when KBR is also installed so strict `Identifier.parse(...)` checks stay valid. |
| `fire_slash` *(bare path)* | Checks `lumenfx:fire_slash`, then `minecraft:fire_slash`; if neither is loaded in LumenFX, passes through untouched. |

---

## 3D Locator & Bone Tracking

- **GeckoLib 5.x (26.1.2 / 26.2)**: Attaches automatically to GeckoLib's `RenderPassInfo` locator and bone position listeners (`addLocatorPositionListener` + `addBonePositionListener`) and captures full 3D locator orientation quaternions from `GeoLocator` poses.
- **GeckoLib 4.x (1.20.1 / 1.21.1)**: Automatically enables world-space matrix tracking on actively bound `GeoBone`s and forwards both bone transforms and (when KBR is installed) KBR Blockbench `LocatorValue` sub-locator world positions & rotations.
- **KBR Native Bedrock Animations**: Hooks `BedrockAnimationParticles.create(...)` fallback so KBR addon animations whose effect IDs are defined in LumenFX instead of KBR's `MolangParticleEngine` play smoothly in LumenFX.

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
- The animation controller resets or changes animation (`reset()` on GeckoLib 5 / `resetEventKeyFrames()` on GeckoLib 4);
- A `stop=true` keyframe fires;
- The entity is removed or switches dimensions;
- The client reloads resources (`F3+T`) or disconnects.

---

## Public Java API (`LumenFxKeyframeBridge`)

Mod developers can inspect, filter, override, listen to, or manually trigger keyframe effects via `com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge`:

```java
import com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge;

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

// 3. Optional: check whether an effect is loaded in LumenFX
boolean loaded = VfxEffects.hasEffect(effectId);

// 4. Optional: manually trigger or stop keyframe-bound effects
LumenFxKeyframeBridge.stopForHost(entity);
```
