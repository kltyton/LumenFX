<p align="right">
  <strong>English</strong> | <a href="Getting-Started-zh">简体中文</a>
</p>

# Getting Started

## Installation (Players & Modpacks)

1. Download the single unified LumenFX JAR matching your Minecraft version and mod loader (NeoForge, Fabric, or Forge).
2. Place the JAR into your `.minecraft/mods` directory.
3. Launch the game normally — LumenFX requires **no shader mod** and has **no mandatory external mod dependencies** (`LDLib2`, `KilaGraph`, `Iris`, `Sodium`, `GeckoLib`, and `KBR` are strictly optional).

> On **Minecraft 26.2 & 26.3**, there is no separate "Vulkan edition" or "OpenGL edition". One unified JAR dynamically selects Vulkan when supported and falls back to OpenGL automatically when needed.

---

## Supported Targets & Maven Coordinates

All official artifacts are published to **Sighs Maven** (`https://maven.sighs.cc/repository/maven-public/`) under group `com.kltyton.lumenfx`.

| Minecraft | Loader | Artifact ID | Version | Java | Backend |
| :---: | :---: | :--- | :---: | :---: | :--- |
| **26.3** | NeoForge | `lumenfx-neoforge-26.3` | `0.3.0` | Java 25 | Dynamic Vulkan-first / OpenGL |
| **26.3** | Fabric | `lumenfx-fabric-26.3` | `0.3.0` | Java 25 | Dynamic Vulkan-first / OpenGL |
| **26.2** | NeoForge | `lumenfx-neoforge-26.2` | `0.3.0` | Java 25 | Dynamic Vulkan-first / OpenGL |
| **26.2** | Fabric | `lumenfx-fabric-26.2` | `0.3.0` | Java 25 | Dynamic Vulkan-first / OpenGL |
| **26.1.2** | NeoForge | `lumenfx-neoforge-26.1.2` | `0.3.0` | Java 25 | Native OpenGL (Full Parity) |
| **26.1.2** | Fabric | `lumenfx-fabric-26.1.2` | `0.3.0` | Java 25 | Native OpenGL (Full Parity) |
| **1.21.1** | NeoForge | `lumenfx-neoforge-1.21.1` | `0.3.0` | Java 21 | Native OpenGL (Full Parity) |
| **1.21.1** | Fabric | `lumenfx-fabric-1.21.1` | `0.3.0` | Java 21 | Native OpenGL (Full Parity) |
| **1.20.1** | Forge | `lumenfx-forge-1.20.1` | `0.3.0` | Java 17 | Native OpenGL (Full Parity) |
| **1.20.1** | Fabric | `lumenfx-fabric-1.20.1` | `0.3.0` | Java 17 | Native OpenGL (Full Parity) |

---

## Gradle Setup (Mod Developers)

### NeoForge / Forge (`build.gradle`)

```groovy
repositories {
    maven {
        name = 'Sighs Maven'
        url = uri('https://maven.sighs.cc/repository/maven-public/')
    }
}

dependencies {
    implementation 'com.kltyton.lumenfx:lumenfx-neoforge-26.3:0.3.0'
    // Optional: bundle inside your mod using jarJar
    // jarJar('com.kltyton.lumenfx:lumenfx-neoforge-26.3:[0.3.0,)')
}
```

### Fabric (`build.gradle`)

```groovy
repositories {
    maven {
        name = 'Sighs Maven'
        url = uri('https://maven.sighs.cc/repository/maven-public/')
    }
}

dependencies {
    modImplementation 'com.kltyton.lumenfx:lumenfx-fabric-26.3:0.3.0'
}
```

---

## Java Client API Quick Start

All effect formats (LumenFX JSON, Unity exports, Niagara compilations, and Photon `.fx`) share the same client entry point: `com.kltyton.lumenfx.api.client.VfxEffects`.

### Spawning an Effect

```java
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.VfxEffects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier; // ResourceLocation on 1.20.1 / 1.21.1
import net.minecraft.world.phys.Vec3;

if (VfxEffects.hasEffect(Identifier.parse("my_mod:magic/rune_stream"))) {
    EffectHandle handle = VfxEffects.spawn(
            clientLevel,
            Identifier.parse("my_mod:magic/rune_stream"),
            new Vec3(x, y, z),
            seed
    );
}
```

### Updating Position, Pose, Color, and Beams

```java
// Simple world-space translation update
handle.moveTo(new Vec3(nextX, nextY, nextZ));

// Full transform: world position, normalized rotation quaternion, and uniform scale
handle.setPose(new EffectHandle.Pose(position, rotationQuat, 1.0f));

// Per-instance RGBA color tint multiplier
handle.setColor(1.0f, 0.85f, 0.4f, 1.0f);

// Beam world endpoint (for BEAM renderers)
handle.setBeamEnd(targetWorldPos);

// For Niagara effects that require actor velocity in source coordinates
handle.setPoseAndActorPosition(pose, actorPos, velocity);
```

### Render-Driven Attachment Tracking

If your effect is updated from an entity or block-entity renderer (rather than every client tick):

```java
handle.trackRenderSamples(); // Call once after spawn
```

While tracked, emitting pauses cleanly when the anchor stops receiving render samples (for example, when culled out of view), while already-spawned particles finish their remaining lifetime.

### Lifecycle & Cleanup

- **`handle.isAlive()`**: Returns `false` once the effect completes naturally, is explicitly closed and drained, or is invalidated by a level switch / resource reload (`F3+T`).
- **`handle.close()`**: Stops new particle emission and lets existing particles & trails finish gracefully before releasing resources.
- **Level switches & resource reloads**: LumenFX automatically clears stale world instances on level change and atomically publishes new immutable definitions on resource reload.
