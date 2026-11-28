<p align="right">
  <strong>English</strong> | <a href="AI-Agent-Skill-zh">简体中文</a>
</p>

# AI Agent Skill (`lumenfx-vfx`, Experimental)

> **Experimental Feature:** The bundled LumenFX Agent Skill is currently **experimental**. Schema rules, GLSL guidelines, and validator diagnostics are actively evolving alongside LumenFX. Always review generated effects and test them in-game (`F3+T` resource reload) before shipping.

LumenFX includes a dedicated, open-standard Agent Skill at [`skills/lumenfx-vfx/`](https://github.com/kltyton/LumenFX/tree/main/skills/lumenfx-vfx) designed for AI coding agents (**Codex**, **Antigravity**, **Claude Code**, **Cursor**, and any Agent Skills-compatible client).

---

## What It Includes

```text
skills/lumenfx-vfx/
├── SKILL.md                                     # Core routing, 10 LumenFX red lines & 6-stage authoring workflow
├── references/
│   ├── effect-json-schema.md                    # Source-verified JSON v1 schema + 26.2/26.3 vs 1.20.1–26.1.2 matrix
│   ├── custom-glsl-shaders.md                   # Cross-version #version 330 .fsh contract, EffectFrame UBO & procedural idioms
│   ├── aesthetics-timing-and-performance.md     # 6-layer VFX contrast stack, 3-phase timing & multi-instance budgets
│   └── java-api-and-authoring-pipelines.md      # VfxEffects, EffectHandle, LumenFxKeyframeBridge, Unity/UE5/Photon/ParticleCue
├── scripts/
│   └── validate_lumenfx_effect.py               # Zero-dependency strict schema, GLSL, asset & budget validator (Python 3)
└── examples/
    ├── arcane_impact_burst.json                 # Cross-version verified 3-layer combat impact template
    └── procedural_fresnel_ring.fsh              # Cross-version #version 330 procedural Fresnel ring shader
```

---

## Key Problems It Prevents

- **26.2 / 26.3 Resource-Reload Crashes**: Catches invalid `{"stops": [...]}` color syntax (enforces 4-channel `[R, G, B, A]` curves), unsupported `"blend": "multiply"` (enforces `"modulate"`), mixed burst definitions, mismatched `noise.mode`/`field`, and incomplete `MeshRendererJson` blocks before launching Minecraft.
- **Cross-Version GLSL Mismatches**: Standardizes custom fragment shaders on `#version 330` with `effectUV`, `effectColor`, `effectParticle`, `EffectFrame`, and `#moj_import <lumenfx:material_fog.glsl>` so one `.fsh` runs cleanly on **both** `26.2 / 26.3` (Vulkan & OpenGL) and `1.20.1–26.1.2` (`opengl_direct`).
- **All-Additive Whiteout & Floaty Motion**: Applies a 6-layer VFX contrast stack (dark `alpha` backing + small incandescent core) and non-linear burst-and-drag timing.
- **Multi-Instance Frame Drops**: Audits particle budgets (`ambient`, `combat`, `boss`, `showcase`), overdraw scores, ribbon tessellation, and material batching.

---

## Installation & Usage

### Option 1 — Install Directly From GitHub (Skills CLI)

```bash
npx skills add kltyton/LumenFX --skill lumenfx-vfx
```

### Option 2 — Copy or Symlink Into Your Agent's Skills Directory

Clone the repository and copy or symlink `skills/lumenfx-vfx` into your agent's skills folder:

- **Codex**: `~/.codex/skills/lumenfx-vfx`
- **Antigravity**: `~/.gemini/config/skills/lumenfx-vfx`
- **Claude Code / Cursor / Project-local**: `.agents/skills/lumenfx-vfx` or `.claude/skills/lumenfx-vfx`

### Running the Deterministic Validator Manually

The bundled validator requires only Python 3.9+ standard library (zero external packages):

```powershell
# Strict cross-version (1.20.1–26.3) check for a single effect
python skills/lumenfx-vfx/scripts/validate_lumenfx_effect.py path/to/effect.json --target all --tier combat --assets-root path/to/assets --strict

# Audit an entire effects directory for high-concurrency ambient/mount use
python skills/lumenfx-vfx/scripts/validate_lumenfx_effect.py assets/my_mod/lumenfx/effects --target 26.2 --tier ambient --assets-root assets
```

- `--target`: `all` (default, strict cross-version safe), `26.2` (Minecraft 26.2/26.3 Blaze3D), or `opengl_direct` (Minecraft 1.20.1–26.1.2).
- `--tier`: `ambient` (50–150+ concurrent entities), `combat` (10–20 instances), `boss` (1–3 instances), or `showcase`.
- `--assets-root`: Optional path to your resource pack `assets/` folder to verify referenced textures and `.fsh`/`.vsh` shaders on disk.
