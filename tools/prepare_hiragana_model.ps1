param(
    [string]$Destination = (Join-Path $PSScriptRoot '../app/src/main/assets/models/hiragana-gpt2-xsmall')
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$revision = 'b0ef59dcdfd8eaddc3010cb8a2060c6196bba573'
$baseUrl = "https://huggingface.co/hukuda222/hiragana-gpt2-xsmall/resolve/$revision"
$files = @(
    @{ Remote = 'onnx/model.onnx'; Local = 'model.onnx'; Sha256 = '9ded1f55511aeffa49ff2af4e41b35770b2b419011c14aba5423b50e103afa82' },
    @{ Remote = 'tokenizer_config.json'; Local = 'tokenizer_config.json'; Sha256 = '8d0e7f342e1ee96e5f4c501fb297540c4e92a2eca6e291fe2c08f663af35ba47' },
    @{ Remote = 'config.json'; Local = 'config.json'; Sha256 = '1cadf7e8f5ccef9385d65740a60b320cdd5b10e9d12ae300473c732d00000d88' },
    @{ Remote = 'README.md'; Local = 'MODEL_CARD.md'; Sha256 = 'eae5694735fd65c84bab7a6bd1c98ead8e6f6f0b01cffd3e4297a3fb78acae57' }
)
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
foreach ($entry in $files) {
    $target = Join-Path $Destination $entry.Local
    if ((Test-Path -LiteralPath $target) -and ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -eq $entry.Sha256)) {
        Write-Host "Verified $($entry.Local)"
        continue
    }
    $temporary = "$target.download"
    try {
        Invoke-WebRequest -UseBasicParsing "$baseUrl/$($entry.Remote)" -OutFile $temporary
        $actual = (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash
        if ($actual -ne $entry.Sha256) { throw "SHA256 mismatch for $($entry.Local): $actual" }
        Move-Item -LiteralPath $temporary -Destination $target -Force
        Write-Host "Downloaded and verified $($entry.Local)"
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}
Write-Host "Ready: hukuda222/hiragana-gpt2-xsmall at $revision (Apache-2.0)."
