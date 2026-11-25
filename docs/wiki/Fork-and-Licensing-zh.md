<p align="right">
  <a href="Fork-and-Licensing">English</a> | <strong>简体中文</strong>
</p>

# 分支渊源与开源许可说明

## 与 Photon 的分支关系

**LumenFX 是 [Photon](https://github.com/Low-Drag-MC/Photon)（Photon 26.2，原作者 [KilaBash](https://github.com/KilaBash) / Low-Drag-MC）的独立衍生分支（Fork）。**

### 继承自 Photon 的部分

- 核心 `.fx` 效果加载、NBT 数据迁移、时间线及嵌套子效果播放框架。
- 基础粒子发射器架构、曲线、发射形状、碰撞与外力求解器、光束（Beam）、拖尾（Trail）、模型粒子及着色器材质接入层。
- 函数发射形状所用的 Darius Bacon 表达式求值器（`expr`）。

### LumenFX 与上游 Photon 的主要差异

| 维度 | 上游 Photon | LumenFX（衍生分支） |
| :--- | :--- | :--- |
| **游戏内编辑器** | 内置在模组中 | **从运行 JAR 中移除**——纯资源驱动轻量化运行库 |
| **外部引擎资产** | Photon `.fx` 制作 | 新增离线 **Unity ParticleSystem** 导出器与 **Unreal Engine Niagara**（CPU VectorVM + GPU SPIR-V + SimCache `.lfsc`）编译器 |
| **Minecraft 26.2 后端** | 目标特定管线 | **单一统一 JAR**，支持动态 Vulkan 优先与 OpenGL 自动回退 |
| **多版本矩阵** | 特定版本支持 | 统一多目标交付 **1.20.1、1.21.1、26.1.2 与 26.2**（Forge / NeoForge / Fabric） |
| **渲染模组前置** | 视版本而定 | 可完全独立运行；Iris、Sodium 与 KBR 均为纯可选兼容 |

---

## 开源许可明细

LumenFX 由原创代码、源自 Photon 的运行层代码及第三方组件共同构成，各部分严格遵循各自原有的开源协议：

1. **LumenFX 原创代码与离线工具**
   - 版权所有 © 2026 kltyton
   - 许可协议：**[MIT License](https://github.com/kltyton/LumenFX/blob/main/LICENSE)**

2. **源自 Photon 的运行层源码**
   - 版权所有 © 2025 Photon by KilaBash
   - 许可协议：**[知识共享署名-非商业性使用-相同方式共享 4.0 国际许可协议（CC BY-NC-SA 4.0）](https://github.com/kltyton/LumenFX/blob/main/licenses/photon/LICENSE)**
   - 所有衍生文件均保留 KilaBash / Photon 原始署名。二次分发或基于 Photon 代码继续衍生时，请严格遵守 CC BY-NC-SA 4.0 的署名、非商业性使用与相同方式共享条款。

3. **表达式解析器（`expr`）**
   - 作者 Darius Bacon — 遵循 [`licenses/expr/COPYING`](https://github.com/kltyton/LumenFX/blob/main/licenses/expr/COPYING)。

4. **第三方库与 Unreal 着色器组件**
   - 沿用各自原始许可协议（详见 `tools/compiler/licenses/` 与 `gradle.properties`）。

### 使用 LumenFX 创作的内容

使用 LumenFX 的 JSON 格式、离线导出工具或公开 Java API **制作出的粒子特效、资源包、着色器或附属模组**归创作者本人所有，不受 Photon 源码 CC BY-NC-SA 4.0 条款限制（与 Photon 许可中的“Content Created Using This Mod”条款保持一致）。
