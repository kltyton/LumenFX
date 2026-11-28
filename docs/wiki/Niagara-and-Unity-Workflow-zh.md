<p align="right">
  <a href="Niagara-and-Unity-Workflow">English</a> | <strong>简体中文</strong>
</p>

# Niagara、Unity 与 Photon 工作流

LumenFX 将**离线制作/转换工具链**与**游戏运行 JAR**彻底解耦。所有 Unity 脚本、Unreal 编译工具及引擎依赖均不会打入 Minecraft 运行模组包中。

---

## 1. Unity ParticleSystem（Shuriken）— UPM 编辑器插件

Unity 编辑器导出器已封装为标准 Unity Package Manager（UPM）插件包（`com.kltyton.lumenfx.exporter`），位于仓库 `tools/unity/` 目录，支持 **Unity 2020.3 LTS 及更高版本**。

### 安装与导出步骤

1. 在 Unity 中打开 **Window → Package Manager → + → Add package from disk...** 选择 `tools/unity/package.json`（或选择 **Add package from git URL...** 填入 `https://github.com/kltyton/LumenFX.git?path=/tools/unity`）。
2. 可选创作辅助工具：
   - 通过 **GameObject → LumenFX** 一键创建按 Minecraft 1 方块尺度配置的预设模板（`Sprite Burst` / `Ribbon Trail` / `Mesh Spark`）。
   - 通过 **Tools → LumenFX → Toggle Minecraft 1-Block Grid** 在 Scene 视图中显示 1×1×1 方块网格与 1.8 格玩家身高参照框。
3. 在 Hierarchy 窗口选中一个或多个粒子层级根物体，打开 **Tools → LumenFX → Particle Exporter...**。
4. 配置命名空间、特效名（支持多选批量导出 **Batch Export**）、输出目录与可选 `pack.mcmeta` 生成，点击 **Validate**（支持窗口内点击错误直接定位对象）或 **Export**。

### 已支持的 Unity 模块与特性

- **Main 与 Emission**：持续时间、循环、预热（Prewarm）、模拟倍速、起始延迟、单曲线与双曲线随机范围（保留加权切线与 Loop/PingPong 环绕）、按时间发射、按距离发射、爆发概率与周期、Ring Buffer 缓冲模式。
- **发射形状（Shape）**：Point、Sphere、Hemisphere、Circle、Box、Rectangle、Donut、Cone、Single Sided Edge、Mesh 与 Sprite 发射形状（含形状遮罩纹理）。
- **运动与物理**：Velocity over Lifetime（含轨道/径向速度）、Force over Lifetime、Inherit Velocity、Limit Velocity、Size/Color/Rotation over Lifetime 及 by Speed、旋度噪声（Curl Noise）、外部力场（Force Fields）、世界与平面碰撞、触发器（Triggers）与子发射器（Sub Emitters）。
- **渲染器与材质**：Billboard（含速度拉伸、水平/垂直公告板、Align to Direction、最小/最大视口占比、翻转）、3D Mesh 网格粒子、粒子拖尾与丝带拖尾（Trails）、序列帧动画（Grid 与 Sprite List）、内置与 URP 粒子着色器，以及 **URP Shader Graph**（Unlit、Metallic Lit、Specular Lit、Complex Lit）与自定义 `LumenFxFragment` 标记。

完整导出规范见 [`tools/unity/README.md`](https://github.com/kltyton/LumenFX/blob/main/tools/unity/README.md)。

---

## 2. Unreal Engine Niagara — UE5 编辑器插件与离线编译器

离线 Niagara 编译器位于仓库 `tools/compiler/`（.NET 10），配套提供 **UE5 编辑器插件**（`tools/unreal/LumenFxExporter/LumenFxExporter.uplugin`）与导出脚本（`tools/unreal/`）。

### 方式 A — UE5 编辑器插件（创作者推荐）

1. 将 `tools/unreal/LumenFxExporter` 复制到 `<你的 UE5 工程>/Plugins/LumenFxExporter`，并在 **Edit → Plugins** 中启用 **LumenFX Niagara Exporter**。
2. 在 **Project Settings → Plugins → LumenFX Exporter** 中配置 LumenFX 仓库目录与默认输出路径。
3. 在内容浏览器（Content Browser）中右键任意 `UNiagaraSystem` 资产，点击 **LumenFX → Export to Minecraft LumenFX...** 即可在编辑器内完成前置校验、异步导出编译与日志查看。详见 [`tools/unreal/LumenFxExporter/README_zh.md`](https://github.com/kltyton/LumenFX/blob/main/tools/unreal/LumenFxExporter/README_zh.md)。

### 方式 B — 命令行转换步骤

1. 使用 `tools/unreal/Export-UnrealAssets.ps1` 将目标 Niagara 系统、发射器、材质、函数库与 Texture2D 描述导出为 UAssetAPI JSON，并解码原始分辨率 PNG。
2. 运行离线编译器生成 LumenFX 资源：
   ```powershell
   dotnet build tools/compiler/LumenFx.Compiler.csproj -o compiler-out
   dotnet compiler-out/LumenFx.Compiler.dll niagara <json-root> /Game/Path/To/NS_MyEffect.NS_MyEffect my_mod:effects/my_effect <png-root> <resources-root> <aliases.json> <render-profile.json> <vector-fields-root> <unreal-source-root>
   ```
3. 如需为 `.lftex.json` 旁车生成压缩 Mip 纹理，可运行 `./gradlew.bat bakeEffectTextures`。
4. 将生成的 `assets/<namespace>/lumenfx/...` 放入你的模组或资源包即可播放。

### 已支持的 Niagara 运行时能力

- **CPU VectorVM**：直接执行编译后的系统/发射器 Spawn 与 Update 字节码，支持持久 ID 表、LUT 与曲线采样、旋度噪声、3D 向量场（`.lfvf`）及 Niagara 积分器。
- **GPU 计算模拟（SPIR-V Compute）**：在当前活动的图形后端（Vulkan 或 OpenGL Compute）上原生执行 Niagara GPU 计算阶段，支持 SSBO 持久化状态以及停止发射后的粒子耗尽与实例自动回收。
- **SimCache 缓存回放（`.lfsc`）**：加载离线导出或运行时录制的 CPU / GPU SimCache 缓存，支持播放结束后平滑交接回实时 GPU 模拟（完整恢复 `Float`、`Int`、`Half`、持久 ID 表与 `AcquireTag`），并按当前设备分配上限安全截断与续跑。
- **渲染器类型**：Sprite（含 Cutout 裁剪几何与自动像素覆盖）、Ribbon（自定义截面、Hermite 自适应细分与双 UV）、Mesh（多级 LOD 槽位）、Light、Decal 与 Volume。

完整参数与高级用法见 [`tools/compiler/README.md`](https://github.com/kltyton/LumenFX/blob/main/tools/compiler/README.md)。

---

## 3. Photon `.fx` 资产兼容

由于 LumenFX 是 **Photon 26.2** 的衍生分支，现有 Photon `.fx` 特效可直接通过 `VfxEffects.spawn(...)` 加载播放：

- 将 `.fx` 文件与对应纹理/模型放入 `assets/<namespace>/lumenfx/effects/<path>.fx`。
- 完整支持 Photon 时间线、嵌套子效果、各类发射器、曲线与发射形状、碰撞与外力、光束（Beam）、拖尾（Trail）、3D 模型粒子、自定义着色器材质与异步预加载。
- 在自然结束、手动 `close()`（执行 Release 阶段）、切世界与资源重载时提供完整生命周期清理。

---

## 4. Blockbench 可视化创作 — ParticleCue Studio

如果你希望无需打开 Unity 或 Unreal Engine，直接在 Minecraft 模型与动画时间轴旁创作、预览与微调 LumenFX 特效，推荐安装 **[ParticleCue Studio](https://github.com/kltyton/ParticleCue-Studio)**（Blockbench 桌面版 `5.2.0+`）：

- **LumenFX JSON v1 可视化编辑**：直接新建与实时预览 `sprite`、`ribbon`、`beam` 与 `.lfmesh` 3D 网格发射器，内置交互式 SVG 曲线与 RGBA 渐变编辑器、光源、体积雾、贴花与碰撞模块。
- **KilaMaterial 与 KilaGraph 节点着色器**：支持六大程序化材质模块合成及 28 节点可视化 GLSL 图编辑器，实时 WebGL2 编译诊断（`GLSL OK`）并一键导出 `.fsh`。
- **Photon `.fx` 与引擎导出资产覆盖**：支持无损 GZIP NBT 编辑 Photon `.fx`，并可对 Unity Shuriken / UE Niagara 导出资产做无损参数覆盖回写（完整保留已编译程序与 `.lfsc` / `.lfvf` 引用）。
- **关键帧联动与便携分享**：直接在 Blockbench 粒子关键帧中实时预览 `pre_effect_script` 指令（可选联动 AnimCue Studio 与 PAL Bend Player Tools），并通过便携 ZIP（含外部 `.fsh`/贴图/`.lfmesh`）或单文件自包含 `.bbmodel` 跨机分享工程。
