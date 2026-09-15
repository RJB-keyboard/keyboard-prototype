param([switch]$VerifyOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$manifest = Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot 'surface-model.json') | ConvertFrom-Json
$modelPath = Join-Path $projectRoot 'app/src/main/assets/conversion/surface/model.bin'
$attribution = Join-Path $projectRoot 'app/src/main/assets/conversion/licenses/TATOEBA-ATTRIBUTION.tsv.zip'
function Assert-Hash([string]$Path, [string]$Expected) {
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Expected) {
        throw "SHA-256 mismatch: $Path. The source export is updated weekly; use the pinned snapshot instead of silently retraining."
    }
}
if ($VerifyOnly) {
    Assert-Hash $modelPath $manifest.modelSha256
    Assert-Hash $attribution $manifest.attributionSha256
    Write-Output 'Surface model verified.'
    exit
}
$corpusDirectory = Join-Path $projectRoot 'build/conversion-corpus'
New-Item -ItemType Directory -Force -Path $corpusDirectory | Out-Null
$archive = Join-Path $corpusDirectory 'jpn_sentences_detailed.tsv.bz2'
$corpus = Join-Path $corpusDirectory 'jpn_sentences_detailed.tsv'
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -UseBasicParsing -Uri $manifest.source -OutFile $archive
}
Assert-Hash $archive $manifest.sourceSha256
$bzipCommand = Get-Command bzip2 -ErrorAction SilentlyContinue
$bzip = if ($bzipCommand) { $bzipCommand.Source } else { 'C:/Program Files/Git/usr/bin/bzip2.exe' }
if (-not (Test-Path -LiteralPath $bzip)) { throw 'Install bzip2 or Git for Windows to unpack the corpus.' }
& $bzip -dkf $archive
if ($LASTEXITCODE -ne 0) { throw 'Corpus decompression failed.' }
& java (Join-Path $PSScriptRoot 'TrainSurfaceModel.java') $corpus $modelPath $attribution
if ($LASTEXITCODE -ne 0) { throw 'Surface model training failed.' }
Assert-Hash $modelPath $manifest.modelSha256
Assert-Hash $attribution $manifest.attributionSha256
Write-Output 'Surface model reproduced and verified.'
