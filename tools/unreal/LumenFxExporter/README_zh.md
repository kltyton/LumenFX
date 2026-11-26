# LumenFX Unreal Engine 5 编辑器插件

标准 UE5 编辑器插件（`LumenFxExporter.uplugin`），让特效创作者可以直接在虚幻引擎 5 的 Content Browser 中校验并一键将 **Niagara 粒子系统**导出为可直接放进 Minecraft 资源包的 **LumenFX 特效资源**，无需手动拼接命令行参数。

## 核心能力

- **Content Browser 右键一键导出**：右键任意 `NiagaraSystem` 资产 → **Export to LumenFX (Minecraft)...**。
- **导出前可视化预检**：自动统计启用发射器、CPU / GPU 分布与 Sprite / Ribbon / Mesh 渲染器，检查命名空间合法性与工具链路径。
- **异步编译与实时日志回显**：后台自动衔接 `Export-UnrealAssets.ps1` 与 `LumenFx.Compiler.dll`，在 Slate 窗口实时显示输出。
- **一键生成可用资源包**：可选自动生成 `pack.mcmeta`，导出目录可直接放入 `.minecraft/resourcepacks/` 并按 `F3 + T` 热重载查看。
- **尺度换算提示**：`100 Unreal cm = 1 Minecraft 方块`（坐标系与单位换算由离线编译器与运行库自动完成）。

## 安装步骤

1. 将本仓库 `tools/unreal/LumenFxExporter` 目录复制到你的 UE5 工程 `Plugins/LumenFxExporter/`。
2. 在 LumenFX 仓库根目录编译一次离线编译器：
   ```powershell
   dotnet build tools/compiler/LumenFx.Compiler.csproj -c Release
   ```
3. 打开你的 UE5 工程（支持 UE 5.3+ 至 5.9，首次打开按提示构建插件模块即可）。
4. 进入 **Edit → Project Settings → Plugins → LumenFX Exporter** 配置：
   - **LumenFx Repository Root**：LumenFX 仓库本地根目录
   - **UAssetExport Dll Path**：已打补丁的 `UAssetExport.dll` 路径
   - **Unreal Source Root**（若包含 GPU 发射器）：匹配版本的 UE 源码根目录
   - **Default Namespace / Default Output Directory**：默认命名空间与输出目录

## 使用流程

1. 在 Content Browser 右键选中目标 `UNiagaraSystem` → **Export to LumenFX (Minecraft)...**（或顶部菜单 **Tools → LumenFX Exporter (Minecraft)...**）。
2. 核对命名空间、特效名称与输出目录，勾选生成 `pack.mcmeta`。
3. 点击 **Validate** 预检无误后，点击 **Export to LumenFX**，完成后点击 **Open Output Folder** 即可查看导出的资源包。
