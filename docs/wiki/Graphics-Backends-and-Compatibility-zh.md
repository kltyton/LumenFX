<p align="right">
  <a href="Graphics-Backends-and-Compatibility">English</a> | <strong>简体中文</strong>
</p>

# 图形后端与模组兼容说明

## 单一统一 JAR 交付

LumenFX 对每个 Minecraft 版本与加载器仅发布**单一统一 JAR**。玩家与整合包作者无需区分“Vulkan 专用版”或“OpenGL 专用版”。

---

## 动态图形后端自动判定（Minecraft 26.2）

在 **Minecraft 26.2**（NeoForge 与 Fabric）启动时，LumenFX 会动态检测运行环境并自动选择最佳图形后端：

```mermaid
flowchart TD
    Start[启动 Minecraft 26.2] --> Explicit{用户是否显式指定<br/>--graphicsBackend 或<br/>游戏内图形后端设置？}
    Explicit -->|是| Respect[严格尊重用户设置与<br/>原版崩溃恢复策略]
    Explicit -->|否| ModCheck{是否安装了仅支持 OpenGL<br/>的渲染模组？（如 Iris）}
    ModCheck -->|是| UseGL[自动选用 OpenGL 后端]
    ModCheck -->|否| DriverCheck{当前系统与显卡驱动<br/>是否支持 Vulkan？}
    DriverCheck -->|是| TryVK[默认选用 Vulkan 后端]
    TryVK -->|设备初始化成功| RunningVK[运行原生 Vulkan 特效管线]
    TryVK -->|初始化失败| FallbackGL[自动平滑回退至 OpenGL]
    DriverCheck -->|否| UseGL
```

### 核心行为保证

1. **支持时默认启用 Vulkan**：纯独立运行 Minecraft 26.2 或与支持 Vulkan 的渲染优化模组（如 **Sodium**）共装时，默认自动运行原生 Vulkan 特效管线。
2. **不可用时自动无缝回退 OpenGL**：若系统无可用 Vulkan 驱动、Vulkan 设备创建失败，或检测到仅支持 OpenGL 的光影模组（如 **Iris**），LumenFX 会自动切换至 OpenGL 特效管线，无需修改配置或更换模组文件。
3. **始终尊重用户显式偏好**：启动参数 `--graphicsBackend`、游戏内视频后端设置及原版启动崩溃恢复始终具有最高优先级。

---

## 历史版本目标（1.20.1、1.21.1、26.1.2）

Minecraft **1.20.1（Forge / Fabric）**、**1.21.1（NeoForge / Fabric）** 与 **26.1.2（NeoForge / Fabric）** 原生运行于 OpenGL。这六个目标同样提供完整的 LumenFX 运行时（Unity ParticleSystem、Unreal Engine Niagara CPU + GPU 计算与 SimCache 交接、以及 Photon `.fx`），在 OpenGL 管线上稳定运行。

---

## 可选模组兼容

LumenFX 完全支持**独立运行**，不强制依赖 `LDLib2`、`KilaGraph`、渲染优化、光影或动画模组。

| 模组 | 依赖类型 | 共装表现 |
| :--- | :---: | :--- |
| **Iris** | 可选（Optional） | 26.2 下自动选择 OpenGL 后端，并将场景颜色、深度与曝光与活动光影包正确合成。 |
| **Sodium** | 可选（Optional） | 同时兼容 26.2 Vulkan 与 OpenGL 管线，完整保留区块深度与透明粒子合成。 |
| **GeckoLib（4.x / 5.x）** | 可选（Optional） | 全 8 目标自动启用 Blockbench `particle_effects` 关键帧播放与 3D 定位点/骨骼姿态跟随（详见 [[GeckoLib 与 KBR 粒子关键帧\|GeckoLib-and-KBR-Keyframes-zh]]）。 |
| **KBR（KltytonBedrockRuntime）** | 可选（Optional） | 共享渲染兼容，且与 KBR 暴雪/3D 粒子自动分流和平共存，并支持 KBR 原生基岩动画未命中时回落至 LumenFX。 |
| **KilaGraph** | 可选（NeoForge 26.2） | 当环境中同时安装了 `kilagraph` 与 `ldlib2` 时，自动通过软兼容桥接启用 Photon `.fx` 着色器图材质；未安装时完全零依赖。 |

所有可选依赖均使用开放兼容版本范围声明，不锁死单一发行版本。
