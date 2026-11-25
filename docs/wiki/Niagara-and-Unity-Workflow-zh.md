<p align="right">
  <a href="Niagara-and-Unity-Workflow">English</a> | <strong>简体中文</strong>
</p>

# Niagara、Unity 与 Photon 工作流

LumenFX 将**离线制作/转换工具链**与**游戏运行 JAR**彻底解耦。所有 Unity 脚本、Unreal 编译工具及引擎依赖均不会打入 Minecraft 运行模组包中。

---

## 1. Unity ParticleSystem（Shuriken）导出工作流

Unity 编辑器导出工具位于仓库 `tools/unity/Editor/` 目录，支持 **Unity 2020.3 LTS 及更高版本**。

### 导出步骤

1. 将 `tools/unity/Editor` 复制到你的 Unity 工程中（例如 `Assets/Editor/LumenFX/`）。
2. 在 Unity Hierarchy 窗口选中粒子层级根物体（Root GameObject）。
3. 在顶部菜单点击 **Assets → LumenFX → Export Selected Particle Systems...**。
4. 填写资源命名空间（Namespace）、特效名称与输出目录，点击 **Validate**（校验）或 **Export**（导出）。

### 已支持的 Unity 模块与特性

- **Main 与 Emission**：持续时间、循环、预热（Prewarm）、模拟倍速、起始延迟、单曲线与双曲线随机范围（保留加权切线与 Loop/PingPong 环绕）、按时间发射、按距离发射、爆发概率与周期、Ring Buffer 缓冲模式。
- **发射形状（Shape）**：Point、Sphere、Hemisphere、Circle、Box、Rectangle、Donut、Cone、Single Sided Edge、Mesh 与 Sprite 发射形状（含形状遮罩纹理）。
- **运动与物理**：Velocity over Lifetime（含轨道/径向速度）、Force over Lifetime、Inherit Velocity、Limit Velocity、Size/Color/Rotation over Lifetime 及 by Speed、旋度噪声（Curl Noise）、外部力场（Force Fields）、世界与平面碰撞、触发器（Triggers）与子发射器（Sub Emitters）。
- **渲染器与材质**：Billboard（含速度拉伸、水平/垂直公告板、Align to Direction、最小/最大视口占比、翻转）、3D Mesh 网格粒子、粒子拖尾与丝带拖尾（Trails）、序列帧动画（Grid 与 Sprite List）、内置与 URP 粒子着色器，以及 **URP Shader Graph**（Unlit、Metallic Lit、Specular Lit、Complex Lit）与自定义 `LumenFxFragment` 标记。

完整导出规范见 [`tools/unity/README.md`](https://github.com/kltyton/LumenFX/blob/main/tools/unity/README.md)。

---

## 2. Unreal Engine Niagara 离线编译工作流

离线 Niagara 编译器位于仓库 `tools/compiler/`（.NET 10），配套导出脚本位于 `tools/unreal/`。

### 转换步骤

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
