@echo off
REM ============================================================
REM  run_bibot_emulator.bat — Boot AVD BibotTest + cai APK + mo app.
REM  Cach dung: double-click file nay (khong can tham so).
REM  Yeu cau: SDK tai H:\androidkit\sdk, AVD ten "BibotTest",
REM  APK da build: android\app\build\outputs\apk\demo\debug\Bibot.apk
REM  (build truoc: ./gradlew :app:assembleDemoDebug --offline)
REM ============================================================

setlocal EnableExtensions
set "SDK=H:\androidkit\sdk"
set "ADB=%SDK%\platform-tools\adb.exe"
set "EMU=%SDK%\emulator\emulator.exe"
set "APK=%~dp0\android\app\build\outputs\apk\demo\debug\Bibot.apk"
set "DEV=emulator-5554"

if not exist "%ADB%" (
  echo [LOI] Khong thay adb: %ADB%
  pause & exit /b 1
)
if not exist "%EMU%" (
  echo [LOI] Khong thay emulator: %EMU%
  pause & exit /b 1
)
if not exist "%APK%" (
  echo [LOI] Khong thay APK: %APK%
  echo Hay build truoc: ./gradlew :app:assembleDemoDebug --offline
  pause & exit /b 1
)

echo [1/4] Kiem tra emulator...
"%ADB%" devices >nul 2>&1
if errorlevel 1 (
  echo [2/4] Khong co emulator '%DEV%' trong danh sach.
) else (
  echo [2/4] Emulator dang chay, bo qua boot.
)

echo [3/4] Cho boot xong (toi da ~5 phut)...
set /a TRIES=0
:waitboot
"%ADB%" -s %DEV% shell getprop sys.boot_completed >nul 2>&1
if errorlevel 1 (
  set /a TRIES+=1
  if %TRIES% geq 30 (
    echo [LOI] Het gio cho boot. Thu: adb kill-server ^& adb start-server roi chay lai.
    pause & exit /b 1
  )
  timeout /t 10 /nobreak >nul
  goto waitboot
) else (
  echo Boot xong.
  goto installed
)

echo [4/4] Cai APK + mo app...
"%ADB%" -s %DEV% install -r "%APK%"
if errorlevel 1 (
  echo [LOI] Cai APK that bai.
  pause & exit /b 1
)
"%ADB%" -s %DEV% shell am start -n com.bibot.app/.MainActivity
:installed
echo XONG - app Bibot dang mo tren emulator.
pause