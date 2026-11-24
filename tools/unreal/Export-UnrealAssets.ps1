param(
    [Parameter(Mandatory)][string]$SourceContent,
    [Parameter(Mandatory)][string]$JsonRoot,
    [Parameter(Mandatory)][string[]]$Packages,
    [Parameter(Mandatory)][string]$UAssetExportDll,
    [Parameter(Mandatory)][string]$TemporaryDirectory,
    [string]$EngineContent = '',
    [string[]]$PluginRoots = @(),
    [hashtable]$MountRoots = @{},
    [string]$Dotnet = 'dotnet',
    [string]$UnversionedEngine = 'VER_UE5_8'
)
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Text.UTF8Encoding]::new($false)
$env:TEMP = $TemporaryDirectory
$env:TMP = $TemporaryDirectory
$env:TMPDIR = $TemporaryDirectory
$env:DOTNET_GENERATE_ASPNET_CERTIFICATE = 'false'
$mounts = @{}
foreach ($key in $MountRoots.Keys) {
    if ($MountRoots[$key]) { $mounts[$key.Trim('/')] = [IO.Path]::GetFullPath([string]$MountRoots[$key]) }
}
$mounts['Game'] = [IO.Path]::GetFullPath($SourceContent)
if ($EngineContent) { $mounts['Engine'] = [IO.Path]::GetFullPath($EngineContent) }
foreach ($pluginRoot in $PluginRoots) {
    if (!$pluginRoot -or !(Test-Path -LiteralPath $pluginRoot)) { continue }
    Get-ChildItem -LiteralPath $pluginRoot -Filter '*.uplugin' -Recurse -File | ForEach-Object {
        $contentDir = Join-Path $_.DirectoryName 'Content'
        $mountName = [IO.Path]::GetFileNameWithoutExtension($_.Name)
        if ((Test-Path -LiteralPath $contentDir -PathType Container) -and !$mounts.ContainsKey($mountName)) {
            $mounts[$mountName] = [IO.Path]::GetFullPath($contentDir)
        }
    }
}
$outputRoot = [IO.Path]::GetFullPath($JsonRoot)
$explicitPackages = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$queue = [Collections.Generic.Queue[string]]::new()
foreach ($package in $Packages) {
    $pkg = $package.Split('.')[0]
    [void]$explicitPackages.Add($pkg)
    $queue.Enqueue($pkg)
}
$visited = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$catalog = [ordered]@{}
function Resolve-PackageImport([object]$asset, [int]$index) {
    if ($index -ge 0) { throw "Expected package import index: $index" }
    $item = $asset.Imports[-$index - 1]
    if ($item.OuterIndex -eq 0) { return [string]$item.ObjectName }
    return (Resolve-PackageImport $asset $item.OuterIndex) + '.' + $item.ObjectName
}
while ($queue.Count -gt 0) {
    $package = $queue.Dequeue()
    if (!$visited.Add($package) -or $package.StartsWith('/Script/', [StringComparison]::Ordinal)) { continue }
    $slash = $package.IndexOf('/', 1)
    if (!$package.StartsWith('/', [StringComparison]::Ordinal) -or $slash -le 1 -or $slash -ge ($package.Length - 1) -or $package.Contains('//') -or $package.Contains('..') -or $package.Contains('\') -or $package.Contains(':')) {
        throw "Invalid package: $package"
    }
    $mountName = $package.Substring(1, $slash - 1)
    if (!$mounts.ContainsKey($mountName)) {
        if ($explicitPackages.Contains($package)) { throw "Unconfigured mount root for package: $package" }
        continue
    }
    $mountRoot = $mounts[$mountName]
    $subPath = $package.Substring($slash + 1)
    $source = [IO.Path]::GetFullPath((Join-Path $mountRoot ($subPath.Replace('/', [IO.Path]::DirectorySeparatorChar) + '.uasset')))
    if (!$source.StartsWith($mountRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "Source escapes content root: $source" }
    if (!(Test-Path -LiteralPath $source -PathType Leaf)) {
        if ($mountName -eq 'Game' -or $explicitPackages.Contains($package)) { throw "Missing source package: $package" }
        continue
    }
    $relative = $package.TrimStart('/') + '.json'
    $destination = Join-Path $outputRoot $relative.Replace('/', [IO.Path]::DirectorySeparatorChar)
    $header = [IO.File]::OpenRead($source)
    try {
        $bytes = [byte[]]::new(24)
        $header.ReadExactly($bytes)
        $legacy = [BitConverter]::ToInt32($bytes, 4)
        $versioned = [BitConverter]::ToInt32($bytes, 12) -gt 0
        $version = if ($legacy -gt -8 -or $versioned) { 'UNKNOWN' } else { $UnversionedEngine }
    } finally { $header.Dispose() }
    & $Dotnet $UAssetExportDll $source $destination $version
    if ($LASTEXITCODE -ne 0) { throw "Cannot export source package: $package" }
    $catalog[$package] = $relative
    $asset = Get-Content -LiteralPath $destination -Raw -Encoding utf8 | ConvertFrom-Json -Depth 100
    for ($i = 0; $i -lt $asset.Imports.Count; $i++) {
        $imp = $asset.Imports[$i]
        if ($imp.ClassName -eq 'Package' -and $imp.OuterIndex -eq 0) {
            $dependency = [string]$imp.ObjectName
        } else {
            $dependency = (Resolve-PackageImport $asset (-$i - 1)).Split('.')[0]
        }
        if ($dependency.StartsWith('/', [StringComparison]::Ordinal) -and !$dependency.StartsWith('/Script/', [StringComparison]::Ordinal)) {
            $queue.Enqueue($dependency)
        }
    }
}
$catalog | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $outputRoot 'asset-index.json') -Encoding utf8
