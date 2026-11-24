param(
    [Parameter(Mandatory)][string]$UnrealSourceRoot,
    [Parameter(Mandatory)][string]$WorkDirectory
)
$ErrorActionPreference = 'Stop'
. 'H:/便携工具/开发工具/命令行/PowerShell/configuration/Initialize-Utf8.ps1'
$sourceRoot = (Resolve-Path -LiteralPath $UnrealSourceRoot).Path
$build = Join-Path $sourceRoot 'Engine/Build/BatchFiles/Build.bat'
if (-not (Test-Path -LiteralPath $build -PathType Leaf)) { throw 'The selected Unreal source build entry is missing' }
$work = [IO.Path]::GetFullPath($WorkDirectory)
if (-not (Test-Path -LiteralPath $work)) { New-Item -ItemType Directory -Path $work | Out-Null }
$env:TEMP = $work
$env:TMP = $work
$env:TMPDIR = $work
$env:DOTNET_GENERATE_ASPNET_CERTIFICATE = 'false'
$project = Join-Path $PSScriptRoot 'LumenFxUvLayout.uproject'
& $build 'LumenFxUvLayout' 'Win64' 'Development' "-Project=$project" '-NoHotReloadFromIDE' '-WaitMutex'
exit $LASTEXITCODE
