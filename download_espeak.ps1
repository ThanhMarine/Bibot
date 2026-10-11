# Tai espeak-ng-data (dinh kem giog Piper EN, ~392 file) roi nen thanh espeak-ng-data.zip
# de day vao may qua push_models.ps1 -> cai Piper offline khong can mang trong app.
#
# Nguon: huggingface.co/csukuangfj/vits-piper-en_US-lessac-medium (cung repo voi tokens.txt)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repo = 'csukuangfj/vits-piper-en_US-lessac-medium'
$api = "https://huggingface.co/api/models/$repo/tree/main?recursive=true"
$raw = "https://huggingface.co/$repo/resolve/main"

$srcDir = Get-ChildItem -Path $PSScriptRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like '*Piper*' } | Select-Object -First 1
if (-not $srcDir) { Write-Host "Khong tim thay thu muc model trong $PSScriptRoot" -ForegroundColor Red; exit 1 }
$dest = Join-Path $srcDir.FullName 'espeak-ng-data'
$zip = Join-Path $srcDir.FullName 'espeak-ng-data.zip'

Write-Host "Lay danh sach file tu HF API..."
$tree = Invoke-RestMethod -Uri $api -TimeoutSec 60
$files = @($tree | Where-Object { $_.type -eq 'file' -and $_.path -like 'espeak-ng-data/*' })
if ($files.Count -eq 0) { Write-Host "API tra ve 0 file" -ForegroundColor Red; exit 1 }
$need = @($files | Where-Object {
    $rel = $_.path.Substring('espeak-ng-data/'.Length)
    $out = Join-Path $dest $rel
    -not (Test-Path $out) -or (Get-Item $out).Length -ne $_.size
})
Write-Host "Tong $($files.Count) file, con thieu/can tai lai: $($need.Count)"

New-Item -ItemType Directory -Force -Path $dest | Out-Null
$done = 0
foreach ($f in $need) {
    $rel = $f.path.Substring('espeak-ng-data/'.Length)
    $out = Join-Path $dest $rel
    $outDir = Split-Path $out -Parent
    if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
    $url = "$raw/$($f.path)"
    try {
        Invoke-WebRequest -Uri $url -OutFile $out -TimeoutSec 60 -UseBasicParsing
    } catch {
        Write-Host "  FAIL $($f.path): $_" -ForegroundColor Red
    }
    $done++
    if ($done % 50 -eq 0) { Write-Host "  ... $done/$($need.Count)" }
}

$actual = @(Get-ChildItem -Path $dest -Recurse -File)
Write-Host "Da tai: $($actual.Count) file, $([math]::Round(($actual | Measure-Object Length -Sum).Sum/1MB,1)) MB"

if (Test-Path $zip) { Remove-Item $zip -Force }
Write-Host "Dang nen espeak-ng-data.zip..."
Compress-Archive -Path $dest -DestinationPath $zip -CompressionLevel Optimal
Write-Host "OK: $zip ($([math]::Round((Get-Item $zip).Length/1MB,1)) MB)"
Write-Host "Chay push_models.ps1 de day vao may, roi cai Piper (offline) trong app."

