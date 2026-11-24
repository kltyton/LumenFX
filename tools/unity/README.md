# LumenFX Unity ParticleSystem exporter

Offline Unity Editor tool that converts a selected Unity ParticleSystem hierarchy (Shuriken) into a
LumenFX effect JSON, PNG textures and static mesh resources. The exporter runs in the Unity Editor.

## Install and run

Requires Unity 2020.3 LTS or newer.

1. Copy `tools/unity/Editor` into a Unity project, for example `Assets/Editor/LumenFX/`.
   The folder name `Editor` is required so Unity compiles the scripts into the editor assembly.
2. Select the root GameObject of the effect hierarchy in the Hierarchy window.
3. Open `Assets > LumenFX > Export Selected Particle Systems...`.
4. Set the namespace, effect name and output folder, then use `Validate` or `Export`.

`Validate` performs the same complete pass as `Export`. If any enabled module or shader is outside
the supported set, **all errors are reported in the Console and no file is written**. `Export` never
skips an enabled module silently.

The selected root is the LumenFX effect anchor and accepts any finite, positive, unsheared `lossyScale`. Child systems can keep
their local translation and rotation. Hierarchy uses positive, unsheared cumulative scale,
Local uses the emitter's own scale, and Shape applies positive, unsheared cumulative scale
at particle birth only. A sub-emitter starts at the event particle and uses its authored system orientation
and scale. Exported collision planes, trigger colliders and force fields stay relative to the
effect root.

## Output layout

The output folder is a resource-pack fragment:

```text
assets/<namespace>/lumenfx/effects/<effect>.json
assets/<namespace>/textures/effects/<effect>/<material>_<texture>.png
assets/<namespace>/lumenfx/meshes/<effect>/<emitter>.json
assets/<namespace>/lumenfx/meshes/<effect>/<emitter>.lfmesh
```

With the default namespace `lumenfx`, place the generated `assets` folder under a resource pack
root. The generated effect is loaded from `lumenfx/effects/<effect>.json`.

Material texture entries are sampler identifiers, not sprite ids: every `Sampler0` value is the
actual texture resource path, `namespace:textures/effects/<effect>/<file>.png`, and is exactly the
same file as `TextureRelativePath` with `assets/<namespace>/` removed.

## Output contract

The exporter writes the format consumed by
`src/main/java/com/kltyton/lumenfx/client/resource/EffectJson.java`:

```json
{
  "format_version": 1,
  "materials": {
    "mat": {
      "fragment_shader": "lumenfx:core/effect",
      "textures": { "Sampler0": "lumenfx:textures/effects/effect/mat_main.png" },
      "blend": "alpha",
      "depth_test": true,
      "cull": false,
      "parameters": [0, 0, 0, 0]
    }
  },
  "emitters": [
    {
      "name": "spark",
      "renderer": "SPRITE",
      "material": "mat",
      "local_space": false,
      "delay": 0,
      "duration": 2,
      "loop": true,
      "max_particles": 512,
      "rate": 20,
      "distance_spacing": 0,
      "bursts": [ { "time": 0.5, "min": 8, "max": 12, "cycles": 1, "interval": 0 } ],
      "lifetime": { "min": 0.4, "max": 0.9 },
      "width": 0.2,
      "height": 0.2,
      "rotation": 0,
      "angular_velocity": 0,
      "start_speed": { "min": 1, "max": 2 },
      "start_direction": [0, 0, -1],
      "velocity_over_life": [0, 0, 0],
      "force_over_life": [0, -9.8, 0],
      "velocity_min": [0, 0, 0],
      "velocity_max": [0, 0, 0],
      "acceleration": [0, 0, 0],
      "drag": 0,
      "shape": { "type": "sphere", "size": [0.5, 0.5, 0.5], "surface": false, "angle": 0 },
      "size_over_life": 1,
      "start_color": [1, 1, 1, 1],
      "color": [1, 1, 1, 1],
      "custom_data": [0, 0, 0, 0],
      "noise": { "strength": [0, 0, 0], "frequency": 1, "scroll": [0, 0, 0] },
      "uv_mode": "stretch",
      "uv_distance": 1,
      "atlas_columns": 1,
      "atlas_rows": 1,
      "atlas_frame": 0,
      "subdivisions": 1,
      "attach_head": false
    }
  ]
}
```

`start_color` is sampled at emitter age at birth; `color` remains the particle-age multiplier.
`velocity_over_life` and `force_over_life` are arrays of three scalar functions in emitter
simulation axes. `start_speed` is the birth speed and `start_direction` is a single direction
vector used by Point/Box/Cylinder (Sphere and Cone use their shape rules). The older
`velocity_min`, `velocity_max` and `acceleration` fields are written as zero because the new fields
carry those semantics.

Curve encoding accepted by LumenFX:

* a number: constant minimum and maximum;
* `{ "min": ..., "max": ... }`: independent minimum/maximum values or curves;
* `{ "keys": [ { "time": 0, "value": 1, "arrive": 0, "leave": 0,
  "interpolation": "linear" } ] }`: piecewise keys.

`interpolation` is `constant`, `linear` or `cubic`. For cubic segments, `leave` of the previous key
and `arrive` of the next key are Hermite slopes in value-per-curve-time units. Unity `inTangent`
maps to `arrive`, Unity `outTangent` maps to `leave`. Curve and Two Curves use Unity's
`curveMultiplier` on values and slopes before export. Constant and Two Constants carry their
authored values directly. Weighted tangents keep their incoming and outgoing weights. Loop and
Ping Pong wrap modes are stored for the curve's leading and trailing ranges separately.

Times are normalized where the LumenFX field expects a normalized age:

* emission rate, start lifetime, start size, start rotation, start speed, start color:
  normalized emitter age `0..1`;
* size over lifetime, color over lifetime, velocity over life, force over life, texture sheet frame,
  angular velocity: normalized particle age `0..1`;
* delay, duration, burst time, burst interval and lifetime values: seconds.

LumenFX units are Minecraft units: one Unity world unit is one block/meter. Rotations are radians,
angular velocity is radians per second, velocity is blocks per second, and force/acceleration is
blocks per second squared.

### Axis conversion

The default conversion is Unity `(x, y, z) -> LumenFX (x, y, -z)`, preserving Unity up as Minecraft
up. Rotation around the view axis is negated because Unity is left-handed and Minecraft is
right-handed.

If any emitter uses `Cone Volume`, the whole exported effect uses the rigid rotation
Unity `(x, y, z) -> LumenFX (x, z, -y)` so Unity's forward `+Z` cone axis becomes LumenFX's `+Y`
cone axis. Choose the spawn pose accordingly when using cone effects.

## Supported modules

### Main

Looping systems can prewarm one cycle at the first emitting attachment pose. The warmup runs through
the normal emitter, motion and trail modules and carries the resulting particles into the first frame.

* `Duration`, `Looping`.
* `Simulation Speed` advances the primary emitter, particles, flipbook, and trails on one clock.
  Linked emitters start at the event's host time and then advance at their own speed.
  A zero rate freezes the initial simulation state. Closing a frozen emitter clears its particles
  and trails. [Unity 2020.3 simulation speed](https://docs.unity3d.com/kr/2020.3/ScriptReference/ParticleSystem.MainModule-simulationSpeed.html)
* `Start Delay` accepts a non-negative constant or two constants. The emitter samples the range once
  per playback and uses that result for both continuous and burst emission. Looping prewarm begins
  without a delay. [Unity 2020.3 Main module](https://docs.unity3d.com/2020.3/Documentation/Manual/PartSysMainModule.html)
* `Start Lifetime` curves and random ranges, in seconds. The shared scalar curve format preserves
  per-side weighted tangents and independent Clamp, Loop and PingPong wrapping. Weight is a fraction
  of the horizontal key interval. [Unity curve editor](https://github.com/Unity-Technologies/UnityCsReference/blob/2020.3/Editor/Mono/Animation/AnimationWindow/CurveEditor.cs).
* `Start Speed` curves and random ranges, exported as `start_speed` (sampled at emitter age at
  birth). `start_direction` is Unity forward mapped through the effect axis conversion. LumenFX
  applies it radially for Sphere shapes, along the cone for Cone Volume, and along the supplied
  direction for Point/Box/Cylinder.
* `Start Size`; `3D Start Size` uses X/Y for billboard width/height (Z is not used by a billboard).
* `Start Color` uses `start_color` at birth. Gradients sample emitter age, Two Colors and Two
  Gradients share one interpolation factor across RGBA, and Random Color uses one particle-specific
  position in its gradient. Blend and Fixed use the authored color and alpha keys.
* `Start Rotation` around Z (radians), including random ranges.
* `Flip Rotation` assigns a spin direction at birth and applies it to Rotation over Lifetime and
  Rotation by Speed on the exported axes. Start Rotation, inherited angles and Noise keep their own
  contributions. [Unity 2020.3 Flip Rotation](https://docs.unity3d.com/kr/2020.3/ScriptReference/ParticleSystem.MainModule-flipRotation.html)
* `Max Particles` (`1..262144`).
* A fixed `Random Seed` uses the full unsigned 32-bit value from the Unity system. Auto Random
  Seed derives the emitter seed from the seed supplied by the Minecraft caller at playback.
  Linked child occurrences draw independently from the child system's playback seed.
  [Unity 2020.3 Auto Random Seed](https://docs.unity3d.com/kr/2020.3/ScriptReference/ParticleSystem-useAutoRandomSeed.html)
* `Simulation Space`: Local, World, or Custom. Custom uses `customSimulationSpace` as the simulation
  transform (`unity_renderer.transform`) with `local_space: true` and exports the emitter transform
  relative to that custom space in `unity_renderer.emission_transform`.
* `Gravity Modifier` curves/ranges in any simulation space; its `MinMaxCurve` is combined with
  `force_over_life` or evaluated in `space_motion` when `local_space` is true.

* `Ring Buffer / Pause Until Replaced` holds an autonomous system's particles at their end state.
  Motion continues while lifetime curves hold their final values. A new birth at capacity replaces the oldest particle. Closing the effect drains the ring.
  [Unity 2020.3 Main module](https://docs.unity3d.com/2020.3/Documentation/Manual/PartSysMainModule.html)
* `Ring Buffer / Loop Until Replaced` preserves the authored lifetime range. Lifetime ramps repeat within that range while motion continues. A replaced particle continues from its current lifetime phase to the end and releases its own trail according to `Die With Particles`.
  [Unity 2020.3 lifetime loop API](https://docs.unity3d.com/kr/2020.3/ScriptReference/ParticleSystem.MainModule-ringBufferLoopRange.html)

Rejected Main settings: shared rings on linked targets and `3D Start Rotation` with X/Y rotation.

### Emission

* `Rate over Time` curves and random ranges.
* Burst count curves use the emitter's normalized cycle time at each scheduled repeat.
  A two-curve count samples between the authored curves once per burst. Constant and
  two-constant integer counts keep the integer emission path.
  [Unity 2020.3 Burst API](https://docs.unity3d.com/2020.3/Documentation/ScriptReference/ParticleSystem.Burst-ctor.html)
* `Rate over Distance` supports constants, curves and two-curve random ranges. Constant values emit
  `distance_spacing` (the reciprocal); curved or random ranges emit `rate_over_distance` as a
  non-negative scalar function integrated dynamically along emitter travel.
* Bursts: time, integer min/max count, repeat interval and trigger probability. A cycle count of zero
  repeats through the active emitter cycle and requires a positive interval.

### Shape

* Disabled shape -> `point`.
* `Sphere`, `Hemisphere`, and `Circle` support radius thickness from the outer surface through the full interior.
* `Box` volume, surfaces and edges with optional per-axis thickness, plus `Rectangle`.
* `Donut` preserves the ring, tube, angular range, scale and radial thickness.
* `Cone` base and volume use independent shape-axis scales and accept 0 through 90 degrees.
* Radial shapes support partial arcs, spread, Random, Loop, Ping-Pong and Burst Spread. Shape
  translation and rotation, randomized direction, spherized direction and randomized position
  apply during particle birth. Native sample sequences and randomization distributions require
  an editor comparison.
* `Single Sided Edge` spans the authored local X segment and emits along local Y. Its separate
  radius traversal uses the same mode and spread sampling as the arc shapes.
* `Align to Direction` is supported for Local and World Mesh renderers. It uses the initial
  travel direction and retains the authored Start Rotation. Camera-facing and velocity-facing
  alignments still report an export error.
* A readable Mesh asset can supply emission vertices, unique edges or triangles. Vertex and edge
  sampling support Random, Loop and Ping-Pong. Material-index selection, interpolated normals and
  vertex colors, and normal offset use the exported mesh's source sections and attributes.
* Sprite emission uses the Sprite asset's original vertices, triangle indices, and UVs with the
  same sampler. Readable Mesh and Sprite emission textures support the selected clip channel,
  threshold, independent RGB/alpha modulation, nearest/bilinear sampling and per-axis wrapping.
  Mesh emission retains the selected source UV channel. CPU pixel values are saved as floats in
  the offline resource. Primitive shape textures, MeshRenderer,
  SkinnedMeshRenderer and SpriteRenderer still report an export error.

Sphere uses radius components and Box uses half of Unity's full shape scale. Unity Cone stores
its radius and axial length independently of the shape scales. The runtime uses the axis-relative
angle and handles the flat 90-degree limit separately.
[Unity 2020.3 Shape module](https://docs.unity3d.com/2020.3/Documentation/Manual/PartSysShapeModule.html)

### Motion modules

* `Velocity over Lifetime`: X/Y/Z curves and random ranges in Local, World, or Custom module space,
  exported as `velocity_over_life` (blocks/s) or `unity_renderer.space_motion.velocity` with
  `velocity_space` (`{ "fixed": ..., "rotation": ... }`) when module space differs from Simulation Space.
  Orbital axes, center offset, radial velocity, and speed multiplier share the motion step.
  [Unity Velocity over Lifetime](https://docs.unity3d.com/2020.3/Documentation/Manual/PartSysVelOverLifeModule.html).
* `Force over Lifetime`: X/Y/Z curves and random ranges (including `randomized` per-step per-axis sampling
  via `space_motion.randomized_force`) in Local, World, or Custom module space, exported as
  `force_over_life` (blocks/s^2) or `space_motion.force` with `force_space`. Gravity stays in world axes.
* `Inherit Velocity`: Initial or Current for Local, Custom, or World-space emitter movement (when `!local_space`,
  via host velocity). Rigidbody mode uses attached host physics velocity and falls back to Transform motion.
* `Limit Velocity over Lifetime`: magnitude or per-axis speed curves, Local, World, or Custom limit axes
  (`local_space` + `space_transform`), dampen and drag, including size and speed scaling.
* `Size over Lifetime`: uniform or separate curves. Billboard uses X/Y and Mesh uses X/Y/Z.
* `Color over Lifetime` applies a particle-age `color` multiplier. A Random Color gradient uses a
  stable particle-specific position instead of age. Fixed gradients use discrete color and alpha
  transitions as evaluated by the editor. [Unity Fixed](https://docs.unity3d.com/2020.3/Documentation/ScriptReference/GradientMode.Fixed.html)
  and [Unity MinMaxGradient](https://github.com/Unity-Technologies/UnityCsReference/blob/2020.3/Modules/ParticleSystem/Managed/ParticleSystemStructs.cs#L130-L146).
* `Size by Speed`, `Color by Speed`, and `Rotation by Speed` sample each authored speed range
  using simulated particle speed. Separate size axes reach Billboard and Mesh, and separate rotation
  axes reach Mesh. Speed-based rotation accumulates in radians over simulation time.
* `Lifetime by Emitter Speed` scales the initial lifetime using the selected emitter velocity
  source. It applies to particles born while that velocity is current.
* `Rotation over Lifetime`: Z curves and random ranges, exported as `angular_velocity` over
  particle age. X/Y axes are rejected for billboards.

* `Sub Emitters` links systems by component identity under the exported root. Birth links follow
  the parent particle and use child rate, distance and bursts. Collision, Death and Manual links
  use the child bursts. A simulation-only source can spawn visible children. Use
  `EffectHandle.triggerUnitySubEmitters` with the exported emitter name for Manual links.
  Trigger links use overlap events from the Trigger module and run the child bursts.
  [Unity Sub Emitters](https://docs.unity3d.com/2020.3/Documentation/Manual/PartSysSubEmitModule.html).
* `Triggers` supports effect-local `SphereCollider`, `BoxCollider`, `CapsuleCollider`, `CharacterController`, and readable `MeshCollider` (both concave triangle distance and 3D convex hull overlap) entries, as well as out-of-hierarchy `host` trigger slots bound at runtime via `EffectHandle.setUnityTriggerColliders`. A callback registered through `EffectHandle.setUnityTriggerListener` receives immutable event data after simulation.
  Translating Unity C# scripts or editing individual particles inside C# trigger callbacks remains outside the offline JSON format.
  [Unity Triggers](https://docs.unity3d.com/2020.3/Documentation/Manual/PartSysTriggersModule.html).

* `Lights` accepts a point light with lifetime range and intensity curves, periodic or seeded random
  assignment, a maximum live-light count, and optional particle color, size and alpha. It uses the
  existing particle light path. Spot lights, cookies, shadows and custom layer masks require more
  source and host inputs. [Unity Lights](https://docs.unity3d.com/2020.3/Documentation/Manual/PartSysLightsModule.html).
* `Custom Data` exports the authored vector curves or color gradient for each active Custom1 or
  Custom2 vertex stream on Sprite and Mesh. The renderer's declared stream width controls which
  components reach the fragment shader. The shader reads Custom1 as `effectCustom` and Custom2 as
  `effectCustom1` when `LFX_EXTRA_MATERIAL` is enabled. Script-written particle data and trail vertex
  layouts need separate support.
  [Unity particle vertex streams](https://docs.unity3d.com/2020.3/Documentation/Manual/PartSysVertexStreams.html).

* `Noise` reads strength, remap, frequency, scrolling, octaves and the position, rotation and size
  amounts. The shared simulation applies a curl field using the host Perlin gradient and includes
  its motion in collision and trails. Its field and quality levels approximate Shuriken's native
  sampler. [Unity Noise](https://docs.unity3d.com/2020.3/Documentation/Manual/PartSysNoiseModule.html).

* `External Forces` resolves Particle System Force Fields through the source list and layer
  filter and saves their initial enabled state. Fields inside the selected root retain their transforms, shape, directional forces, gravity,
  vortex, drag and readable vector Texture3D. Unity's Start Range excludes the inner volume, and a
  cylinder uses the same axial length for both radial boundaries. The force response reuses Photon
  direction, gravity, vortex and drag. The strength profile inside the influence volume needs
  comparison against the source editor. Linked sub-emitters share the effect-root placement of the
  exported force field, collision planes and trigger volumes.
  To move or enable an exported field independently, publish its state by `external_forces.fields[].source_id`
  using `EffectHandle.setUnityForceFields`. Supply the full basis relative to the effect root in
  LumenFX coordinates and block units. Omit the basis to use the saved transform and omit the ID to restore
  the saved enabled state and transform. Each instance samples its own states once per simulation frame.
  The server-authoritative collision path accepts the same states through
  `LumenFxServerEffects.CollisionInput`. Previous exports retain their saved static behavior.
  Runtime addition of fields outside the exported selection and WindZone inputs remain unsupported.
  [Unity Force Fields](https://docs.unity3d.com/2020.3/Documentation/Manual/class-ParticleSystemForceField.html).

World Collision exports 3D and 2D collisions (`mode_2d` / `plane_2d`) across `High`, `Medium`, and `Low` quality levels (using static block/voxel caches for `Medium`/`Low`) alongside moving entity bounds (`dynamic_colliders` up to `max_dynamic_shapes`) and server-authoritative `collider_force` impulses from the same emitter simulation started through `LumenFxServerEffects`. For a selective mask, set `EffectHandle.setUnityCollisionLayers` with block and entity IDs or `#` tags for the Unity layer indices (unmapped indices contribute no geometry). Provide the same mapping to the server through `LumenFxServerEffects.CollisionInput`.

`Send Collision Messages` on Planes or World delivers a batch through
`EffectHandle.setUnityCollisionListener` for each emitter instance and collider in a simulation frame.
Each hit includes its world-space intersection, surface normal, incident velocity and time. Plane IDs
retain the authored slot even when earlier slots are empty. Collision sub-emitters still spawn from
individual impacts. [Unity OnParticleCollision](https://docs.unity3d.com/jp/current/ScriptReference/MonoBehaviour.OnParticleCollision.html).

### Texture sheet animation

Supported when the module is enabled:

* Grid and ordered Sprite-list modes with Lifetime, Speed, or FPS playback.
* Whole Sheet or Single Row with Custom, Random, or Mesh Index selection.
* `numTilesX`, `numTilesY`, and constant or random `startFrame`. Lifetime playback uses
  `frameOverTime` and `cycleCount`. Speed playback uses `speedRange` and `cycleCount`.
  FPS playback uses `fps`.
* UV channel mask must include UV0.
* Sprite-list frames must use the same texture with unrotated rectangular regions. Frames retain
  their authored order and UV rectangles, and the complete sheet is exported once per material.
  Tight-packed, rotated or split-texture sprite atlases are rejected.

For Lifetime playback, LumenFX `atlas_frame` combines the frame curve, starting phase and cycle count.
FPS playback stores the starting phase in `atlas_frame` and advances by `atlas_fps` per second of
elapsed particle lifetime. Speed playback samples the current particle velocity and maps its magnitude
through the authored range and cycles. Single Row uses the custom row, selects a random row at birth, or uses the
particle's mesh slot. The frame wraps within that row's columns. Whole Sheet and legacy files
without a row setting wrap over the full grid.

### Renderer, material and textures

Transparent Sprite, Mesh and trail streams export the renderer's Sorting Layer value, Order in Layer,
Sorting Fudge and the actual material Render Queue. The runtime applies the bias to its bounds-center
camera distance before sorting whole streams. Particle Sort Mode still orders individual particles
within the renderer. Multiply affects both the previously accumulated particle color and the backdrop.
The source camera's custom transparency axis and Sorting Group hierarchy are currently outside the
exported sorting data. [URP transparent draw order](https://github.com/Unity-Technologies/Graphics/blob/2022.3/staging/Packages/com.unity.render-pipelines.universal/Runtime/Passes/DrawObjectsPass.cs#L190-L203)

* Renderer mode `Billboard` -> LumenFX `SPRITE`.
* Renderer mode `None` with Ribbon trails -> LumenFX `RIBBON`.
* Billboard, Horizontal Billboard and Vertical Billboard use the same sprite geometry. The
  exporter preserves alignment, X/Y pivot, camera roll and the minimum and maximum billboard
  size as fractions of the viewport. Adding trails shares the billboard's particle simulation
  with the trail renderer.
* Billboard and Mesh support `Depth` and `Distance` ordering. Orthographic views measure both from
  the camera plane. Editors with `DepthReverse` and `DistanceReverse` can export them for front-to-back
  drawing. [Unity particle sorting](https://docs.unity3d.com/ja/current/ScriptReference/ParticleSystemSortMode.html)
* Billboard Flip uses the authored X and Y probabilities to mirror a particle's sampling
  within its selected texture frame. Each particle keeps the same choice throughout its life.
  [Unity 2020.3 ParticleSystemRenderer.flip](https://docs.unity3d.com/kr/2020.3/ScriptReference/ParticleSystemRenderer-flip.html)
* Stretched Billboard uses the shared Photon velocity-aligned basis and preserves length,
  particle velocity and camera velocity scales. Camera velocity comes from consecutive
  rendered world views and restarts when the view changes.
  [Unity 2020.3 ParticleSystemRenderer.cameraVelocityScale](https://docs.unity3d.com/2020.3/Documentation/ScriptReference/ParticleSystemRenderer-cameraVelocityScale.html)
* Mesh exports the renderer's ordered list of readable triangle meshes, the particle material
  and the shared static mesh payload. Each particle picks a mesh at birth using its emitter's
  seeded mesh selection stream. Geometry retains its origin, vertex colors and UV0.
  The particle controls 3D size, rotation, alignment, pivot and draw order. Trails can draw
  from the same particle population. Mesh UV0 supports Lifetime grid animation, including mesh
  slots mapped to rows. Material tiling and offset apply to the selected frame's UVs.
  Mesh Flip applies independently to the three authored axes. Reflected instances share the
  original vertex buffer and use reversed triangle indices where needed for back-face culling.

The built-in material translations recognize these shaders:

* `Particles/Standard Unlit`
* `Particles/Additive`, `Particles/Additive (Soft)`, `Particles/Alpha Blended`, `Particles/Alpha Blended Premultiply`, `Particles/Multiply`
* `Legacy Shaders/Particles/Additive`, `Legacy Shaders/Particles/Additive (Soft)`, `Legacy Shaders/Particles/Alpha Blended`, `Legacy Shaders/Particles/Alpha Blended Premultiply`, `Legacy Shaders/Particles/Multiply`
* `Mobile/Particles/Additive`, `Mobile/Particles/Alpha Blended`, `Mobile/Particles/Multiply`
* `Universal Render Pipeline/Particles/Unlit`, `Universal Render Pipeline/Unlit`, `HDRP/Unlit`
* `Sprites/Default`, `Unlit/Texture`, `Unlit/Transparent`, `Unlit/Transparent Cutout`, `Unlit/Color`, Unlit Shader Graph assets (`.shadergraph`), and materials tagged with `LumenFxFragment`.

To use an authored fragment shader, add a `"LumenFxFragment" = "your_namespace:core/particle"` SubShader
tag and supply the corresponding `assets/your_namespace/shaders/core/particle.fsh` in the destination
resource pack. The GLSL shader implements the effect's material logic with the shared LumenFX vertex
outputs and receives the exported primary texture as `Sampler0`. Declare `_SrcBlend`, `_DstBlend`,
`_ZTest`, `_ZWrite` and `_Cull` on the Unity material when needed to convey the actual render state.
An unrecognized shader without a mapping reports its exact name.

Distortion, normal maps, UV2 requirements and lit modes still abort the export (`_DISTORTION_ON`,
`DISTORTION`, `_DistortionEnabled`, `_NORMALMAP`, `_REQUIRE_UV2`, `_LightingEnabled`, `EFFECT_BUMP`).
Plain unlit materials export to `lumenfx:core/effect(_premultiplied)` or `lumenfx:core/color(_premultiplied)`;
extended unlit features (`_BlendOp` Add/Subtract/ReverseSubtract/Min/Max, `Soft Additive` / `Modulate`,
sprite `_MainTex_ST` tiling/offset, `_ColorMode` 0..5, `_ALPHATEST_ON`/`_AlphaClip` cutoff,
`_EMISSION` + `_EmissionColor` + `_EmissionMap` as `Sampler1`, `SOFTPARTICLES_ON`/`_SoftParticlesEnabled`,
`_FADING_ON`/`_CameraFadingEnabled`, `FLIPBOOKBLENDING_ON`/`_FlipbookBlending`, `_Cull = Front`)
automatically compile to `lumenfx:core/unity_particle` with `runtime_parameters` (`uvST`,
`emissionAndCutoff`, `fadeParams`, `config`), `blend_op`, `depth_projection`, and `scene_depth`.

Blend mode comes from `_SrcBlend`/`_DstBlend` (`Opaque`, `Alpha`, `Additive`, `Add`, `Soft Additive`,
`Premultiplied`, `Modulate`) or falls back to `_Surface` and the shader name. Depth test is read from
`_ZTest` (`disabled`/`always` disable depth test); `_ZWrite` controls depth write; `_Cull` supports
Off, Back and Front (`cullFront` discard in `lumenfx:core/unity_particle`). Material tint is read
from `_Color`, `_TintColor` and/or `_BaseColor`.

Main texture (`_MainTex`, `_BaseMap`, `_BaseColorMap` or `_MainTexture`) becomes `Sampler0`, and
optional `_EmissionMap` becomes `Sampler1`. Tiling and offset are supported on Sprite (`uvST`), Mesh,
and Ribbon materials; flipbook blending works on Sprite and Mesh sheets.

PNG export uses Unity's public texture API. Readable textures use `GetPixels32`; non-readable
textures are copied through a temporary sRGB `RenderTexture` and `Texture2D.ReadPixels` before
`EncodeToPNG`. The destination has exactly the source texture's width and height. Source texture
assets are only read and are never modified, re-imported or resized.

### Trails

The exporter records Particle and Ribbon as separate modes in `unity_renderer.particle_trail`. Particle
mode stores a vertex history for each participating particle. Ratio, minimum vertex distance, world
space, particle-death behavior and trail lifetime apply to that history. Ribbon mode connects every
Nth live particle in each continuous emission span with `unity_renderer.ribbon_count`. With
`attachRibbonsToTransform` enabled, the current ribbons also connect to the emitter position.

A Billboard or Mesh with a Ribbon shares its live particle simulation with the Ribbon. The Ribbon's
`unity_renderer.source` identifies the primary emitter. Each renderer applies its own `unity_renderer.tint`.
The same format supports Ribbon-only systems and older assets with no `particle_trail.mode`.

Both modes support Stretch, Tile, DistributePerSegment and RepeatPerSegment through
`unity_renderer.trail_texture`. On Unity 2022.1 and newer, the exporter combines the Trails module's
texture scale with the material transform. The module scales U from the strip origin and V from the
center. [Unity Trails module](https://docs.unity3d.com/6000.0/Documentation/Manual/PartSysTrailsModule.html)

## Known limitations

`ONE/ONE` is exported as `add` and `SRC_ALPHA/ONE` as `additive`. The exporter follows the
source shader's RGB conversion before blending. Standard Unlit Transparent passes its RGB to
`ONE/ONE_MINUS_SRC_ALPHA`. Standard Unlit Modulate uses the texture and alpha to modulate the receiver.
For a Gamma project, Built-in Standard Unlit transparencies retain encoded texture samples and
blend in the camera's transparent queue before image effects. The default target uses normalized
8-bit blending. Setting the Gamma material's existing `render.hdr` option uses the floating-point
target for source captures that preserve signed or over-range color. Depth tests and writes
take effect in draw order, and the result is converted back to the host's linear scene.

Each exported texture slot writes its source filter mode (`point` / `linear` / `aniso` + mip filter + anisotropy) and wrap mode (`wrap` / `clamp` / `mirror` / `mirror_once`) into `texture_samplers` and `.lftex.json` metadata.

* Start Speed uses the direction described for each supported shape. Native randomization
  distributions and the exact cone travel angles still require source-engine comparison.
* LumenFX uses one random stream per particle for its random ranges. Independent per-property
  random correlations from Unity are preserved as ranges, not as identical random sequences.
* Unconnected Shader Graph color slots in the default color mode use the source project's color
  space. HDR slot values stay in the authored space. `ColorspaceConversionNode` applies the explicit
  conversion. `Sample Gradient` uses the saved node version, separate RGB and alpha keys, and OKLab
  interpolation for the V1 perceptual mode.
* Material depth comparison and depth writing are exported independently of blending. URP Shader
  Graph uses the target's Render Face, Depth Test and Depth Write states, with material properties
  applied when the graph permits overrides.
* URP Particles/Lit writes the authored normal, metallic and smoothness into the particle lighting
  pass. Linear projects convert the scalar metallic property according to its ShaderLab `[Gamma]` flag.
  The metallic map uses its red and alpha channels and follows the particle flipbook. The URP
  `_EMISSION` keyword controls whether the source emission color and map are exported.
  The particle shader samples `_BaseMap` and `_BaseColor` once.
  Alpha, premultiplied, additive and Multiply materials use the forward particle lighting pass.
* Plane collisions export planes under the selected root. Photon supplies swept contacts and
  response. The Unity curves control bounce, damping and lifetime loss, and kill speeds apply
  to the exit velocity.
* Supported `.shadergraph` nodes compile to GLSL with Custom1/Custom2 and SamplerState support.
  For a graph with multiple targets, export uses the pipeline asset selected by the current
  Unity quality level. The official Particle Color, Particle Texcoords and particle transform
  providers resolve through the shipped Unity package resources.
  URP Metallic and Specular Lit graphs use the particle lighting passes for opaque,
  alpha, premultiplied, additive and Multiply materials. Specular graphs supply RGB reflectance and apply the
  source reflectivity to diffuse. The forward fragment preserves specular independently of diffuse
  alpha when the source requests it. Multiply produces an RGB factor and applies the source alpha
  modulation to albedo before lighting. Complex Lit graphs retain their Coat Mask and Coat Smoothness.
  The coat evaluates the base refraction, dielectric highlight and indirect reflectance using the
  active particle lighting inputs. [URP Clear Coat BRDF](https://github.com/Unity-Technologies/Graphics/blob/2022.3/staging/Packages/com.unity.render-pipelines.universal/ShaderLibrary/BRDF.hlsl#L96-L158)
  Other custom HLSL `CustomFunctionNode`s report an export error.
* The exporter does not write `pack.mcmeta`; merge the generated `assets` tree into your resource
  pack.
* Newer optional LumenFX fields (`multiply` curve factors, `movement_threshold`,
  `width_segments`, `noise.mode`) are not emitted. The Unity limit module stores its drag
  in `unity_renderer.velocity_limit`, alongside the magnitude or axis limits and drag multipliers.
