<p align="right">
  <strong>English</strong> | <a href="Graphics-Backends-and-Compatibility-zh">简体中文</a>
</p>

# Graphics Backends & Compatibility

## Single Unified JAR Per Target

LumenFX delivers **one unified JAR** per Minecraft version and mod loader. Players never need to choose between separate "Vulkan" and "OpenGL" mod files.

---

## Dynamic Backend Selection (Minecraft 26.2)

On **Minecraft 26.2** (NeoForge & Fabric), LumenFX dynamically inspects the runtime environment at startup and selects the optimal graphics backend:

```mermaid
flowchart TD
    Start[Minecraft 26.2 Launch] --> Explicit{User set explicit<br/>--graphicsBackend or<br/>in-game setting?}
    Explicit -->|Yes| Respect[Honor User Choice &<br/>Vanilla Crash Recovery]
    Explicit -->|No| ModCheck{OpenGL-only rendering mod<br/>active? e.g. Iris}
    ModCheck -->|Yes| UseGL[Select OpenGL Backend]
    ModCheck -->|No| DriverCheck{System & GPU driver<br/>support Vulkan?}
    DriverCheck -->|Yes| TryVK[Select Vulkan Backend]
    TryVK -->|Device Init OK| RunningVK[Run Native Vulkan VFX Pipeline]
    TryVK -->|Init Failed| FallbackGL[Automatic Fallback to OpenGL]
    DriverCheck -->|No| UseGL
```

### Key Guarantees

1. **Vulkan by Default When Available**: Standalone Minecraft 26.2 and Vulkan-compatible co-installs (such as **Sodium**) run directly on the native Vulkan VFX pipeline.
2. **Automatic OpenGL Fallback**: If the system lacks a working Vulkan driver, if Vulkan device creation fails, or if an OpenGL-only shader mod (such as **Iris**) is installed, LumenFX seamlessly routes to its OpenGL VFX pipeline without crashing or requiring manual config edits.
3. **Explicit User Control Always Wins**: Command-line flags (`--graphicsBackend`), Minecraft's video backend setting, and vanilla crash-recovery logic are always respected.

---

## Legacy Targets (1.20.1, 1.21.1, 26.1.2)

Minecraft **1.20.1 (Forge / Fabric)**, **1.21.1 (NeoForge / Fabric)**, and **26.1.2 (NeoForge / Fabric)** use OpenGL natively. All six legacy targets ship the full LumenFX runtime (Unity ParticleSystem, Unreal Niagara CPU + GPU compute + SimCache handoff, and Photon `.fx`) on the direct OpenGL pipeline.

---

## Optional Mod Compatibility

LumenFX is completely **standalone** — it has zero required rendering mod dependencies.

| Mod | Dependency Type | Behavior When Installed |
| :--- | :---: | :--- |
| **Iris** | Optional | On 26.2, automatically selects OpenGL and integrates scene color, depth, and exposure with active shader packs. |
| **Sodium** | Optional | Compatible on both Vulkan (26.2) and OpenGL paths with full chunk-depth & scene compositing. |
| **KBR (KltytonBedrockRuntime)** | Optional | Full co-install compatibility with shared rendering hooks. |

All optional dependencies are declared with open compatible version ranges (never pinned to a single exact release).
