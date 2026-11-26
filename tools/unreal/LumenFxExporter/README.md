# LumenFX Unreal Engine 5 Editor Plugin

Editor plugin (`LumenFxExporter.uplugin`) that lets VFX artists validate and export **Unreal Engine 5 Niagara Systems** straight from the Content Browser into ready-to-use Minecraft resource packs for **LumenFX**.

## Features

- **Content Browser Right-Click Action**: Right-click any `NiagaraSystem` asset -> **Export to LumenFX (Minecraft)...**
- **Preflight Validation**: Inspects enabled emitters, CPU/GPU breakdown, Sprite/Ribbon/Mesh renderers, Minecraft ID syntax, and toolchain paths before exporting.
- **One-Click Pipeline**: Wraps `tools/unreal/Export-UnrealAssets.ps1` and `tools/compiler/LumenFx.Compiler.dll` asynchronously with live log streaming.
- **Minecraft Resource Pack Output**: Optionally writes `pack.mcmeta` so the output folder drops straight into `.minecraft/resourcepacks/`.
- **Scale Reminder**: `100 Unreal cm = 1 Minecraft block` (unit and axis conversion are handled automatically by the compiler and runtime).

## Installation

1. Copy the `tools/unreal/LumenFxExporter` folder into your UE5 project's `Plugins/LumenFxExporter` directory.
2. Build `LumenFx.Compiler` once from the LumenFX repository root:
   ```powershell
   dotnet build tools/compiler/LumenFx.Compiler.csproj -c Release
   ```
3. Launch your Unreal Engine 5 project (UE 5.3+ / 5.9 Preview; click **Yes** when prompted to build the editor plugin module).
4. Open **Edit -> Project Settings -> Plugins -> LumenFX Exporter** and configure:
   - **LumenFx Repository Root**: path to this LumenFX repo
   - **UAssetExport Dll Path**: path to your patched `UAssetExport.dll`
   - **Unreal Source Root** *(for GPU compute emitters)*: path to matching UE source
   - **Default Namespace / Output Directory**

## Usage

1. Right-click a `UNiagaraSystem` in the Content Browser -> **Export to LumenFX (Minecraft)...** (or open **Tools -> LumenFX Exporter (Minecraft)...**).
2. Confirm Namespace, Effect Path, and Output Directory.
3. Click **Validate** to verify emitters and toolchain setup, then **Export to LumenFX**.
4. Copy or point your Minecraft resource packs folder to the output directory and reload resources (`F3 + T`).
