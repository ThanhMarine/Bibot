$ProgressPreference = 'SilentlyContinue'
$wc = New-Object System.Net.WebClient
$dest = "H:\Pi4_Xiaozhi\android_app\Gói ngôn ngữ Piper"
if (-not (Test-Path $dest)) { New-Item -ItemType Directory -Path $dest | Out-Null }

Write-Host "Downloading Piper EN ONNX..."
$url = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx"
$out = "$dest\en_US-lessac-medium.onnx"
try {
    $wc.DownloadFile($url, $out)
    $sizeMB = [math]::Round($out.Length/1MB,1)
    Write-Host "EN ONNX saved: $sizeMB MB"
} catch {
    Write-Host "FAIL EN ONNX: $_"
}

Write-Host "Downloading Piper EN JSON..."
$jsonUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json"
$jsonOut = "$dest\en_US-lessac-medium.onnx.json"
try {
    $wc.DownloadFile($jsonUrl, $jsonOut)
    $sizeMB2 = [math]::Round($jsonOut.Length/1MB,1)
    Write-Host "EN JSON saved: $sizeMB2 MB"
} catch {
    Write-Host "FAIL EN JSON: $_"
}

Write-Host "Downloading Piper VI ONNX..."
$viUrl = "https://raw.githubusercontent.com/phatjkk/vits-tts-vietnamese/main/pretrained_vi.onnx"
$viOut = "$dest\pretrained_vi.onnx"
try {
    $wc.DownloadFile($viUrl, $viOut)
    $sizeMB3 = [math]::Round($viOut.Length/1MB,1)
    Write-Host "VI ONNX saved: $sizeMB3 MB"
} catch {
    Write-Host "FAIL VI ONNX: $_"
}

Write-Host "Downloading Piper VI JSON..."
$viJsonUrl = "https://raw.githubusercontent.com/phatjkk/vits-tts-vietnamese/main/pretrained_vi.onnx.json"
$viJsonOut = "$dest\pretrained_vi.onnx.json"
try {
    $wc.DownloadFile($viJsonUrl, $viJsonOut)
    $sizeMB4 = [math]::Round($viJsonOut.Length/1MB,1)
    Write-Host "VI JSON saved: $sizeMB4 MB"
} catch {
    Write-Host "FAIL VI JSON: $_"
}

Write-Host "`nDone. Files in dest:`n$(Get-ChildItem $dest | Format-Table Name, Length -AutoSize)"