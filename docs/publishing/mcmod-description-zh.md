<!-- ====================================================================== -->
<!-- 一句话简介 / About（可直接填入 MC百科 简介栏）                           -->
<!-- ====================================================================== -->
<!--
基于 Photon 26.2 的轻量级跨引擎粒子特效运行库，支持 Unity、Unreal Niagara、Photon 特效与自定义着色器，26.2 支持动态 Vulkan/OpenGL 自动切换。
-->

# ✨ LumenFX

**面向 Minecraft 的轻量级、纯资源驱动次世代粒子特效运行库——原生支持 Unity ParticleSystem、Unreal Engine Niagara 与 Photon `.fx` 特效，内置炫目泛光与自定义着色器渲染，并在 26.2 自动适配 Vulkan / OpenGL。**

> **分支与衍生说明：** 本模组是 **[Photon](https://github.com/Low-Drag-MC/Photon)**（原作者 **KilaBash** / Low-Drag-MC，Photon 26.2）的独立衍生分支（Fork）。在保留 Photon `.fx` 特效播放与模拟核心的基础上，移除了游戏内编辑器以保持运行库轻量化，并将特效管线扩展至 **Unity / UE Niagara 跨引擎资产** 与 **多版本统一架构**（支持 1.20.1、1.21.1、26.1.2 与 26.2 的 Forge / NeoForge / Fabric）。

---

## 🌟 实机特效展示

*以下图片均为 LumenFX 原生引擎在游戏内实时渲染截图——**无需安装任何光影模组即可呈现泛光与着色器效果！***

### 🔮 符文光环与多色丝带拖尾
支持高精度丝带（Ribbon）拖尾、螺旋轨迹与立体魔法阵，在夜色下勾勒流畅绚丽的能量流光。

![符文光环与多色丝带拖尾](https://raw.githubusercontent.com/kltyton/LumenFX/main/docs/publishing/screenshots/01_rune_ribbon_stream.png)
*(本地图文件：`docs/publishing/screenshots/01_rune_ribbon_stream.png`)*

### 🌌 星界能量漩涡与传送门氛围
将类 Unity / UE 的多层粒子、径向爆发与柔和辉光融入祭坛、Boss 战与传送门场景，大幅提升画面表现力。

![星界能量漩涡](https://raw.githubusercontent.com/kltyton/LumenFX/main/docs/publishing/screenshots/02_astral_vortex_portal.png)
*(本地图文件：`docs/publishing/screenshots/02_astral_vortex_portal.png`)*

### 🔥 内置泛光效果（无需光影模组）
炽烈火球、跃动火星与升腾烟羽开箱即享自然泛光质感，不装光影也能耀眼夺目；安装光影后亦可和谐共存。

![内置泛光炽焰火球](https://raw.githubusercontent.com/kltyton/LumenFX/main/docs/publishing/screenshots/03_blazing_ember_fireball.png)
*(本地图文件：`docs/publishing/screenshots/03_blazing_ember_fireball.png`)*

### 🎨 自定义着色器材质（渲染过程完全可定制）
支持资源包与模组自带 GLSL 片元着色器，自由打造流光等离子彗尾、能量波纹与各类程序化特效材质。

![自定义着色器材质拖尾](https://raw.githubusercontent.com/kltyton/LumenFX/main/docs/publishing/screenshots/04_emerald_plasma_comet.png)
*(本地图文件：`docs/publishing/screenshots/04_emerald_plasma_comet.png`)*

### 💧 物理模拟与重力粒子喷泉
支持重力抛物线、速度与外力曲线、阻力、旋度噪声及碰撞溅射，由有界多线程引擎高效驱动。

![物理模拟光泉粒子](https://raw.githubusercontent.com/kltyton/LumenFX/main/docs/publishing/screenshots/05_luminous_physics_fountain.png)
*(本地图文件：`docs/publishing/screenshots/05_luminous_physics_fountain.png`)*

---

## ⚡ 模组特色

- **单一统一包，26.2 动态 Vulkan 优先 / OpenGL 自动回退**：玩家无需纠结下载“Vulkan 版”还是“OpenGL 版”！在 Minecraft 26.2 中，LumenFX 会在显卡驱动与模组环境支持时**默认启用 Vulkan**，在不支持 Vulkan 或安装了仅支持 OpenGL 的光影模组（如 Iris）时**自动无缝回退至 OpenGL**，同时尊重玩家自己的图形后端设置。
- **开箱即用，无强制前置**：可完全独立运行，不强制依赖任何渲染优化或光影模组。
- **可选兼容 Iris / Sodium / KBR**：与常见光影与性能模组和平共处，整合包即装即用。
- **轻量运行库，无游戏内编辑器**：所有特效通过资源包驱动，运行 JAR 精简干净，不占用多余界面与内存。
- **跨引擎强大生态**：同时支持 LumenFX JSON、Photon `.fx`、离线导出的 **Unity ParticleSystem** 以及离线编译的 **Unreal Engine Niagara**（CPU VectorVM、GPU 计算着色器与 SimCache）。

---

## 📦 支持版本与加载器

| Minecraft 版本 | 加载器 | 图形后端 |
| :---: | :---: | :--- |
| **26.2** | NeoForge、Fabric | 动态 Vulkan 优先 / OpenGL 自动回退 |
| **26.1.2** | NeoForge、Fabric | OpenGL |
| **1.21.1** | NeoForge、Fabric | OpenGL |
| **1.20.1** | Forge、Fabric | OpenGL |

---

## 🛠️ 模组开发者与创作者资源

- **GitHub 开源仓库（含 Unity / UE Niagara 离线导出工具）：** [https://github.com/kltyton/LumenFX](https://github.com/kltyton/LumenFX)
- **完整 Wiki 文档与 Java API 教程：** [https://github.com/kltyton/LumenFX/wiki](https://github.com/kltyton/LumenFX/wiki)
- **Maven 仓库（Sighs Maven）：** `https://maven.sighs.cc/repository/maven-public/`（坐标 `com.kltyton.lumenfx:lumenfx-<加载器>-<MC版本>:0.1.3`）

---

## 📜 开源协议与致谢

- 本模组为 **KilaBash** / Low-Drag-MC 著作的 **[Photon](https://github.com/Low-Drag-MC/Photon)（Photon 26.2）** 之衍生分支（Fork）。
- 源自 Photon 的运行层代码遵循 **CC BY-NC-SA 4.0** 协议（详见仓库内 `licenses/photon/LICENSE`）；LumenFX 原创代码与离线工具遵循 **MIT** 协议。
- 允许在非商业整合包中自由收录使用。
