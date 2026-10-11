# Day model offline da tai (download_piper_models.ps1) vao app Bibot qua adb.
# App đọc file từ: /sdcard/Android/data/com.bibot.app/files/models_in  (FilePaths.modelStagingDir)
# Sau đó vào Settings -> Mo hinh offline bam "Cai tu file sideload" (khong can mang).
#
# Chuong trinh debuggable (ban debug) moi dung duoc run-as fallback.

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

# --- thu muc chua file da tai (ten co dau, tim bang wildcard de khong phu thuoc encoding) ---
$srcDir = Get-ChildItem -Path $PSScriptRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like '*Piper*' } | Select-Object -First 1
if (-not $srcDir) { Write-Host "Khong tim thay thu muc model (ten co "Piper") trong $PSScriptRoot" -ForegroundColor Red; exit 1 }
$src = $srcDir.FullName

$pkg = 'com.bibot.app'
$dst = "/sdcard/Android/data/$pkg/files/models_in"
$tmp = "/data/local/tmp/bibot_stage"

$files = @(
    'vosk-model-small-vn-0.4.zip',
    'vosk-model-small-en-us-0.15.zip',
    'pretrained_vi.onnx',
    'pretrained_vi.onnx.json',
    'en_US-lessac-medium.onnx',
    'en_US-lessac-medium.onnx.json',
    'sherpa-onnx-v1.13.8-android.tar.bz2',
    'espeak-ng-data.zip'
)

# --- kiem tra adb + device ---
$null = Get-Command adb -ErrorAction SilentlyContinue
if (-not $?) { Write-Host "Khong tim thay adb trong PATH" -ForegroundColor Red; exit 1 }
$devices = (& adb devices) | Select-String -Pattern "device$"
if (-not $devices) { Write-Host "Khong co device/emulator nao ket noi (adb devices)" -ForegroundColor Red; exit 1 }

$pmPath = & adb shell pm path $pkg 2>$null
if (-not ($pmPath -match 'package:')) {
    Write-Host "App $pkg chua cai. Cai truoc: adb install app\build\outputs\apk\demo\debug\Bibot.apk" -ForegroundColor Red
    exit 1
}

function Push-One($file) {
    $local = Join-Path $src $file
    if (-not (Test-Path $local)) { Write-Host "  SKIP (khong co): $file" -ForegroundColor DarkGray; return $false }
    $sizeMb = [math]::Round((Get-Item $local).Length / 1MB, 1)

    # Cach 1: push truc tiep vao thu muc external cua app
    & adb shell mkdir -p $dst | Out-Null
    & adb push $local "$dst/" *> $null
    if ($LASTEXITCODE -eq 0) { Write-Host "  OK  $file ($sizeMb MB) -> models_in" -ForegroundColor Green; return $true }

    # Cach 2 (Android 11+ chan shell vao Android/data): push /data/local/tmp roi run-as copy
    & adb shell mkdir -p $tmp | Out-Null
    & adb push $local "$tmp/" *> $null
    if ($LASTEXITCODE -ne 0) { Write-Host "  FAIL ${file}: push loi ca 2 cach" -ForegroundColor Red; return $false }
    & adb shell "chmod 755 $tmp && chmod 644 $tmp/$file" | Out-Null
    & adb shell "run-as $pkg sh -c 'mkdir -p `"/sdcard/Android/data/$pkg/files/models_in`" && cp `"/data/local/tmp/$file`" `"/sdcard/Android/data/$pkg/files/models_in/`"'" *> $null
    $ok = $LASTEXITCODE -eq 0
    & adb shell "rm -rf $tmp" | Out-Null
    if ($ok) { Write-Host "  OK  $file ($sizeMb MB) -> models_in (run-as)" -ForegroundColor Green }
    else { Write-Host "  FAIL ${file}: run-as copy loi (can ban debuggable?)" -ForegroundColor Red }
    return $ok
}

Write-Host "Nguon : $src"
Write-Host "Dich  : $dst"
Write-Host ""
$okCount = 0
foreach ($f in $files) { if (Push-One $f) { $okCount++ } }

Write-Host ""
Write-Host "Da day $okCount file. Kiem tra tren may:" -ForegroundColor Cyan
& adb shell "ls -l `"$dst`"" 2>$null
if ($LASTEXITCODE -ne 0) { & adb shell "run-as $pkg ls -l /sdcard/Android/data/$pkg/files/models_in" 2>$null }
Write-Host ""
Write-Host "Tiep theo: mo app -> Cai dat -> Mo hinh offline -> bam 'Cai tu file sideload'." -ForegroundColor Cyan


