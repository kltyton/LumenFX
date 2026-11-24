param(
    [Parameter(Mandatory)][string]$MikkSourceRoot,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][string]$WorkDirectory,
    [string]$ForsythSourceRoot
)
$ErrorActionPreference = 'Stop'
. 'H:/便携工具/开发工具/命令行/PowerShell/configuration/Initialize-Utf8.ps1'
$mikkRoot = (Resolve-Path -LiteralPath $MikkSourceRoot).Path
if (-not $ForsythSourceRoot) { $ForsythSourceRoot = Join-Path (Split-Path $mikkRoot -Parent) 'ForsythTriOO' }
$forsythRoot = (Resolve-Path -LiteralPath $ForsythSourceRoot).Path
foreach ($relative in @('Src/forsythtriangleorderoptimizer.h', 'Src/forsythtriangleorderoptimizer.cpp')) {
    if (-not (Test-Path -LiteralPath (Join-Path $forsythRoot $relative) -PathType Leaf)) {
        throw "Forsyth source is missing: $relative"
    }
}
foreach ($relative in @('inc/mikktspace.h', 'src/mikktspace.c')) {
    if (-not (Test-Path -LiteralPath (Join-Path $mikkRoot $relative) -PathType Leaf)) {
        throw "MikkTSpace source is missing: $relative"
    }
}
$locator = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
if (-not (Test-Path -LiteralPath $locator)) { throw 'Visual Studio installation locator is required' }
$msbuild = @(& $locator -latest -products '*' -requires Microsoft.Component.MSBuild -find 'MSBuild/Current/Bin/MSBuild.exe')
if ($LASTEXITCODE -ne 0 -or -not $msbuild) { throw 'MSBuild installation was not found' }
$output = [IO.Path]::GetFullPath($OutputDirectory)
$work = [IO.Path]::GetFullPath($WorkDirectory)
$temporary = Join-Path $work 'temp'
foreach ($directory in @($output, $work, $temporary)) {
    if (-not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory | Out-Null }
}
$env:TEMP = $temporary
$env:TMP = $temporary
$env:TMPDIR = $temporary
$project = Join-Path $PSScriptRoot 'lumenfx_mesh_source.vcxproj'
& $msbuild[0] $project '/nologo' '/m:1' '/p:Configuration=Release' '/p:Platform=x64' "/p:LumenFxMikkRoot=$mikkRoot" "/p:LumenFxForsythRoot=$forsythRoot" "/p:LumenFxMeshOutput=$output" "/p:LumenFxMeshWork=$work"
exit $LASTEXITCODE
