<p align="right">
  <a href="AI-Agent-Skill">English</a> | <strong>简体中文</strong>
</p>

# AI Agent Skill（`lumenfx-vfx`，实验性功能）

> **实验性功能说明：** 仓库内置的 LumenFX 专用 AI Agent Skill 当前处于**实验性阶段**。JSON 校验规则、GLSL 规范与预算阈值会随 LumenFX 版本持续演进；AI 生成或修改的粒子效果请在发布前务必在游戏内重载资源（`F3+T`）并实机复核。

LumenFX 在仓库 [`skills/lumenfx-vfx/`](https://github.com/kltyton/LumenFX/tree/main/skills/lumenfx-vfx) 目录提供了遵循开放 Agent Skills 规范的专用技能包，适用于 **Codex**、**Antigravity**、**Claude Code**、**Cursor** 及其他兼容 `SKILL.md` 的 AI 编程助手。

---

## 目录内容

```text
skills/lumenfx-vfx/
├── SKILL.md                                     # 核心路由、10 条 LumenFX 工程红线与六阶段制作闭环
├── references/
│   ├── effect-json-schema.md                    # 源码核验的 JSON v1 规范与 26.2/26.3 vs 1.20.1–26.1.2 兼容矩阵
│   ├── custom-glsl-shaders.md                   # 跨版本 #version 330 片元着色器契约、EffectFrame UBO 与程序化模板
│   ├── aesthetics-timing-and-performance.md     # 六层视觉对比堆栈、三段非线性节奏与多实例并发预算
│   └── java-api-and-authoring-pipelines.md      # VfxEffects / EffectHandle / LumenFxKeyframeBridge 与三端工作流
├── scripts/
│   └── validate_lumenfx_effect.py               # 零依赖 Python 3 严格结构、GLSL 着色器、资产与预算校验脚本
└── examples/
    ├── arcane_impact_burst.json                 # 跨全版本 0 报错的 3 层战斗受击参考模板
    └── procedural_fresnel_ring.fsh              # 跨全版本 #version 330 程序化菲涅尔光环片元着色器模板
```

---

## 重点解决的常见问题

- **杜绝 26.2 / 26.3 资源重载崩溃与黑屏**：在启动游戏前自动拦截 `{"stops": [...]}` 非法颜色渐变写法（强制 4 通道 `[R, G, B, A]` 曲线）、不支持的 `"blend": "multiply"`（强制 `"modulate"`）、混合 `bursts` 字段、`noise.mode`/`field` 冲突及不完整的 `MeshRendererJson` 块。
- **统一跨版本自定义 GLSL 契约**：统一采用 `#version 330` + `effectUV`/`effectColor`/`effectParticle`/`EffectFrame` + `#moj_import <lumenfx:material_fog.glsl>`，一份 `.fsh` 同时兼容 **26.2 / 26.3**（Vulkan / OpenGL）与 **1.20.1–26.1.2**（`opengl_direct`）。
- **避免全 Additive 发白糊团与匀速死板运动**：内置六层视觉堆栈（L4 暗色 `alpha` 压底 + 3%–8% L1 白炽核心）与“慢蓄 → 瞬爆急刹 → 明先灭暗慢散”三段节奏规则。
- **控制高并发掉帧与过度绘制**：按 `ambient` / `combat` / `boss` / `showcase` 档位审计最大粒子数、Overdraw 风险、丝带细分度与材质合批习惯。

---

## 安装与使用方式

### 方式一：通过 Skills CLI 从 GitHub 直接安装

```bash
npx skills add kltyton/LumenFX --skill lumenfx-vfx
```

### 方式二：复制或软链接到本机 Agent 技能目录

克隆本仓库后，将 `skills/lumenfx-vfx` 复制或创建符号链接 / Junction 到对应目录即可：

- **Codex**：`~/.codex/skills/lumenfx-vfx`
- **Antigravity**：`~/.gemini/config/skills/lumenfx-vfx`
- **Claude Code / Cursor / 项目级**：`.agents/skills/lumenfx-vfx` 或 `.claude/skills/lumenfx-vfx`

### 手动运行确定性校验脚本

校验脚本仅依赖 Python 3.9+ 标准库，无需安装第三方包：

```powershell
# 对单个特效执行严格跨版本（1.20.1–26.3）校验
python skills/lumenfx-vfx/scripts/validate_lumenfx_effect.py path/to/effect.json --target all --tier combat --assets-root path/to/assets --strict

# 对整目录特效按高并发环境/坐骑档位（ambient）审计
python skills/lumenfx-vfx/scripts/validate_lumenfx_effect.py assets/my_mod/lumenfx/effects --target 26.2 --tier ambient --assets-root assets
```

- `--target`：`all`（默认，全版本严格安全子集）、`26.2`（Minecraft 26.2/26.3 Blaze3D）或 `opengl_direct`（1.20.1–26.1.2）。
- `--tier`：`ambient`（50–150+ 同屏实体）、`combat`（10–20 实例）、`boss`（1–3 实例）或 `showcase`。
- `--assets-root`：可选，指向资源包 `assets/` 目录以校验引用贴图与 `.fsh`/`.vsh` 文件是否存在及语法兼容性。
