param(
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$ProjectFile
)
$ErrorActionPreference = 'Stop'
. 'H:/便携工具/开发工具/命令行/PowerShell/configuration/Initialize-Utf8.ps1'
$locator = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
if (-not (Test-Path -LiteralPath $locator)) { throw 'Visual Studio installation locator is required' }
$msbuild = @(& $locator -latest -products '*' -requires Microsoft.Component.MSBuild -find 'MSBuild/Current/Bin/MSBuild.exe')
if ($LASTEXITCODE -ne 0 -or -not $msbuild) { throw 'MSBuild installation was not found' }
$project = (Resolve-Path -LiteralPath $ProjectFile).Path
$java = (Resolve-Path -LiteralPath $JavaHome).Path
& $msbuild[0] $project '/nologo' '/m:1' '/p:Configuration=Release' '/p:Platform=x64' "/p:LumenFxJavaHome=$java"
exit $LASTEXITCODE
