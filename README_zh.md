<p align="center">
  <img src="docs/publishing/artwork/lumenfx-icon-400.png" width="140" alt="LumenFX 图标" />
</p>

<h1 align="center">LumenFX</h1>

<p align="center">
  <strong>面向 Minecraft 的资源驱动跨引擎粒子特效运行库——原生支持 Unity ParticleSystem、Unreal Engine Niagara 与 Photon <code>.fx</code>，并在 26.2 提供动态 Vulkan / OpenGL 自动渲染。</strong>
</p>

<p align="center">
  <a href="README.md">English</a> | <strong>简体中文</strong> | <a href="https://github.com/kltyton/LumenFX/wiki">Wiki 文档</a>
</p>

---

## 分支关系与项目渊源

**LumenFX 是 [Photon](https://github.com/Low-Drag-MC/Photon)（[KilaBash](https://github.com/KilaBash) / Low-Drag-MC 著作的 Photon 26.2）的独立衍生分支（Fork）。**

在继承 Photon 粒子模拟核心、时间线、曲线与 `.fx` 特效播放能力的基础上，LumenFX 将运行层重构并扩展为**轻量化、纯资源驱动的客户端 VFX 运行库**：

- **运行包不含游戏内编辑器**——所有特效均以标准 Minecraft 资源包形式加载（`assets/<namespace>/lumenfx/...`），保持模组轻量与整洁。
- **跨引擎资产兼容**——通过统一入口同时运行 LumenFX 原生 JSON 特效、Photon `.fx` 资产、离线导出的 **Unity ParticleSystem（Shuriken）** 粒子系统，以及离线编译的 **Unreal Engine Niagara** 粒子系统（支持 CPU VectorVM、GPU SPIR-V 计算与 SimCache `.lfsc`）。
- **多版本多加载器统一交付**——完整覆盖 Minecraft **1.20.1、1.21.1、26.1.2 与 26.2**（Forge、NeoForge、Fabric），每个目标仅交付单一统一 JAR。
- **26.2 动态 Vulkan 优先 / OpenGL 自动回退**——无需玩家区分 Vulkan 版或 OpenGL 版，启动时自动选择最佳图形后端。

源自 Photon 26.2 的代码文件完整保留 KilaBash 的署名与版权声明，遵循随附的 [Photon 许可证（CC BY-NC-SA 4.0）](licenses/photon/LICENSE)；LumenFX 原创代码与离线工具采用 [MIT 许可证](LICENSE)。

---

## 实机特效展示

以下所有画面均由 LumenFX 在游戏内实时渲染截屏——**无需安装任何光影模组即可呈现泛光与自定义着色器效果**。

| 符文光环与多色螺旋丝带拖尾 | 星界能量漩涡（末地传送门祭坛） |
| :---: | :---: |
| ![符文光环与多色螺旋丝带拖尾](docs/publishing/screenshots/01_rune_ribbon_stream.png) | ![星界能量漩涡](docs/publishing/screenshots/02_astral_vortex_portal.png) |

| 内置泛光炽焰火球与火星烟雾 | 自定义 GLSL 着色器翡翠等离子彗尾 |
| :---: | :---: |
| ![内置泛光炽焰火球与火星烟雾](docs/publishing/screenshots/03_blazing_ember_fireball.png) | ![自定义 GLSL 着色器翡翠等离子彗尾](docs/publishing/screenshots/04_emerald_plasma_comet.png) |

<p align="center">
  <img src="docs/publishing/screenshots/05_luminous_physics_fountain.png" width="760" alt="重力物理模拟光泉粒子" /><br/>
  <em>重力抛物线物理光泉与地面溅射环</em>
</p>

---

## 核心特性

- **跨引擎统一运行时**：单一公共 Java API（`VfxEffects`）即可播放 LumenFX JSON、Photon `.fx`、Unity ParticleSystem 导出资源及 Unreal Engine Niagara CPU/GPU 与 SimCache 特效。
- **丰富渲染模式与内置泛光**：支持精灵（公告板 / 速度拉伸 / 水平 / 垂直）、丝带拖尾（Catmull–Rom 与 Hermite 自适应细分）、光束（Beam）、3D 网格（Mesh）及动态粒子光源，无需光影即可呈现炫目辉光。
- **完全可自定义 GLSL 着色器材质**：支持自定义片元着色器（`assets/<namespace>/shaders/vfx/*.fsh`）、多纹理采样器、粒子年龄/随机值/自定义参数通道、多种混合模式与精细深度控制。
- **动态图形后端自适应（Minecraft 26.2）**：
  - 每个版本目标仅发布**单一统一 JAR**；
  - 当系统显卡驱动及已装渲染模组支持 Vulkan 时（包含独立运行与 Sodium 共装），**默认自动启用 Vulkan**；
  - 当环境不支持 Vulkan 或检测到必须使用 OpenGL 的渲染模组（如 Iris）时，**自动无缝回退至 OpenGL**；
  - 始终尊重用户的显式图形后端参数（`--graphicsBackend`）、视频设置及原版崩溃恢复。
- **纯可选兼容，可完全独立运行**：不强制依赖 Iris、Sodium 或 KBR，开箱即用；与上述渲染模组共装时自动启用可选兼容路径。
- **有界多线程模拟与原子重载**：将粒子数值计算分配至有界工作线程池，支持游戏内资源包热重载（F3+T）并自动回收过期渲染管线。

---

## 支持版本与加载器

| Minecraft 版本 | 加载器 | Maven Artifact ID | Java 版本 | 图形后端策略 |
| :---: | :---: | :--- | :---: | :--- |
| **26.2** | NeoForge | `lumenfx-neoforge-26.2` | Java 25 | 动态 Vulkan 优先 / OpenGL 自动回退 |
| **26.2** | Fabric | `lumenfx-fabric-26.2` | Java 25 | 动态 Vulkan 优先 / OpenGL 自动回退 |
| **26.1.2** | NeoForge | `lumenfx-neoforge-26.1.2` | Java 25 | OpenGL |
| **26.1.2** | Fabric | `lumenfx-fabric-26.1.2` | Java 25 | OpenGL |
| **1.21.1** | NeoForge | `lumenfx-neoforge-1.21.1` | Java 21 | OpenGL |
| **1.21.1** | Fabric | `lumenfx-fabric-1.21.1` | Java 21 | OpenGL |
| **1.20.1** | Forge | `lumenfx-forge-1.20.1` | Java 17 | OpenGL |
| **1.20.1** | Fabric | `lumenfx-fabric-1.20.1` | Java 17 | OpenGL |

---

## 开发者快速上手

### 1. 添加 Maven 依赖

LumenFX 发布于 **Sighs Maven**：

```groovy
repositories {
    maven {
        name = 'Sighs Maven'
        url = uri('https://maven.sighs.cc/repository/maven-public/')
    }
}

dependencies {
    // 按需替换为上表中对应版本与加载器的 Artifact ID
    implementation 'com.kltyton.lumenfx:lumenfx-neoforge-26.2:0.1.3'
}
```

### 2. 在 Java 代码中生成与控制特效

```java
EffectHandle effect = VfxEffects.spawn(
        clientLevel,
        Identifier.parse("my_mod:magic/trail"),
        position,
        seed
);

effect.moveTo(nextPosition);
// 可选：setPose(position, quaternion, scale) 设置位置/旋转/缩放，setColor(r, g, b, a) 设置染色乘数
// 可选：由渲染器定位点驱动时调用一次 trackRenderSamples()
// 可选：Beam 光束特效可通过 setBeamEnd(targetPos) 设置世界终点

if (!effect.isAlive()) {
    // 特效已自然结束，或因切换维度 / 资源重载而失效
}

effect.close(); // 停止新粒子发射，让已有粒子自然播放完毕后回收
```

效果 ID `my_mod:magic/trail` 会自动解析至 `assets/my_mod/lumenfx/effects/magic/trail.json`（或 Photon `.fx` 资产）。

---

## 文档与离线制作工具

- **[GitHub Wiki 完整文档](https://github.com/kltyton/LumenFX/wiki)**（同步存档于 [`docs/wiki/`](docs/wiki/Home.md)）：
  - [快速入门与 API 指南](docs/wiki/Getting-Started.md)
  - [效果 JSON 与自定义 GLSL 材质参考](docs/wiki/Effect-Format-Reference.md)
  - [Niagara、Unity 与 Photon 工作流](docs/wiki/Niagara-and-Unity-Workflow.md)
  - [图形后端与模组兼容说明](docs/wiki/Graphics-Backends-and-Compatibility.md)
  - [Photon 分支关系与开源许可说明](docs/wiki/Fork-and-Licensing.md)
- **离线制作工具**（不会打包进游戏运行 JAR）：
  - [Unity ParticleSystem 导出器](tools/unity/README.md) — 在 Unity 编辑器中导出 Shuriken 层级、URP Shader Graph 材质、纹理与网格。
  - [Unreal Engine Niagara 离线转换器](tools/compiler/README.md) — 离线编译 Niagara CPU VectorVM、GPU 计算着色器、材质图、向量场与 SimCache。

---

## 从源码构建

```powershell
# 构建默认目标（neoforge-26.2）
.\gradlew.bat --no-daemon --console=plain build

# 或进入指定目标目录单独构建（如 fabric-26.2、neoforge-1.21.1、forge-1.20.1）
cd targets\fabric-26.2
.\gradlew.bat --no-daemon --console=plain build
```

---

## 开源许可与鸣谢

- **LumenFX 原创代码与离线工具**：采用 [MIT 许可证](LICENSE)。
- **源自 Photon 的运行层代码**：衍生自 **KilaBash** / Low-Drag-MC 开发的 [Photon](https://github.com/Low-Drag-MC/Photon)（Photon 26.2），依据 [知识共享署名-非商业性使用-相同方式共享 4.0 国际许可协议（CC BY-NC-SA 4.0）](licenses/photon/LICENSE) 分发，请在二次分发与衍生使用时严格遵守署名与非商业条款。
- **数学表达式解析器（`expr`）**：采用 Darius Bacon 的表达式实现，遵循 [licenses/expr/COPYING](licenses/expr/COPYING)。
- **第三方资源与引擎组件**：沿用各自的原始许可协议。
