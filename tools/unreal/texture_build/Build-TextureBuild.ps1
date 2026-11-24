param(
    [Parameter(Mandatory)][string]$UnrealSourceRoot,
    [Parameter(Mandatory)][string]$WorkDirectory,
    [string]$ExpectedRevision
)
$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path -LiteralPath $UnrealSourceRoot).Path
$build = Join-Path $sourceRoot 'Engine/Build/BatchFiles/Build.bat'
if (-not (Test-Path -LiteralPath $build -PathType Leaf)) {
    throw "The prepared Unreal source build entry is missing at '$build'"
}
$versionPath = Join-Path $sourceRoot 'Engine/Build/Build.version'
$version = Get-Content -LiteralPath $versionPath -Raw | ConvertFrom-Json
if ($version.MajorVersion -ne 5) {
    throw "The texture build requires Unreal Engine 5 ('$versionPath')"
}
$revision = & git -C $sourceRoot rev-parse --verify HEAD
if ($LASTEXITCODE -ne 0) {
    throw "Could not identify the Unreal Engine source revision at '$sourceRoot'"
}
if ($ExpectedRevision -and $revision -ne $ExpectedRevision) {
    throw "Expected Unreal Engine revision $ExpectedRevision, found $revision"
}
Write-Host "Unreal Engine $($version.MajorVersion).$($version.MinorVersion).$($version.PatchVersion) $revision"
$work = [IO.Path]::GetFullPath($WorkDirectory)
if (-not (Test-Path -LiteralPath $work)) { New-Item -ItemType Directory -Path $work | Out-Null }
$env:TEMP = $work
$env:TMP = $work
$env:TMPDIR = $work
$env:DOTNET_GENERATE_ASPNET_CERTIFICATE = 'false'
$project = Join-Path $PSScriptRoot 'LumenFxTextureBuild.uproject'
& $build 'LumenFxTextureBuild' 'Win64' 'Development' "-Project=$project" '-NoHotReloadFromIDE' '-WaitMutex'
exit $LASTEXITCODE
