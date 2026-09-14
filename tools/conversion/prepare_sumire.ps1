[CmdletBinding()]
param([switch]$VerifyOnly)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'sumire-assets.json') -Raw | ConvertFrom-Json
$assetRoot = Join-Path $projectRoot 'app/src/main/assets/conversion/sumire'
$downloadRoot = Join-Path $projectRoot 'build/conversion-download'

function Assert-Hash([string]$Path, [string]$Expected) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing dictionary file: $Path" }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actual -ne $Expected) { throw "Dictionary SHA-256 mismatch: $Path, expected $Expected, actual $actual" }
}

if (-not $VerifyOnly) {
    New-Item -ItemType Directory -Force -Path $downloadRoot, $assetRoot | Out-Null
    $archivePath = Join-Path $downloadRoot ($manifest.tag + '.zip')
    if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
        Invoke-WebRequest -Uri $manifest.url -OutFile $archivePath
    }
    Assert-Hash $archivePath $manifest.sha256
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        foreach ($file in $manifest.files) {
            if ([IO.Path]::GetFileName($file.name) -ne $file.name -or $file.name.Contains('..')) {
                throw "Invalid dictionary manifest filename: $($file.name)"
            }
            $entry = $archive.GetEntry($file.entry)
            if ($null -eq $entry) { throw "Archive is missing $($file.entry)" }
            $staged = Join-Path $downloadRoot $file.name
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $staged, $true)
            Assert-Hash $staged $file.sha256
            Copy-Item -LiteralPath $staged -Destination (Join-Path $assetRoot $file.name) -Force
        }
    } finally { $archive.Dispose() }
}

foreach ($file in $manifest.files) {
    Assert-Hash (Join-Path $assetRoot $file.name) $file.sha256
}
Write-Output "Verified $($manifest.files.Count) Sumire dictionary assets ($($manifest.tag))."
