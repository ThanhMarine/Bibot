@echo off
REM ============================================================
REM  run_bibot_emulator.bat — Tạo & Khởi chạy AVD BibotTest + cài APK + mở app.
REM  Cach dung: double-click file nay (khong can tham so).
REM  Yeu cau: SDK tai H:\androidkit\sdk, APK da build: android\app\build\outputs\apk\demo\debug\Bibot.apk
REM  (build truoc: ./gradlew :app:assembleDemoDebug --offline)
REM ============================================================

setlocal EnableExtensions
set "SDK=H:\androidkit\sdk"
set "AVD_MANAGER=%SDK%\cmdline-tools\latest\bin\avdmanager.bat"
set "ADB=%SDK%\platform-tools\adb.exe"
set "EMU=%SDK%\emulator\emulator.exe"
set "APK=%~dp0\android\app\build\outputs\apk\demo\debug\Bibot.apk"
set "DEV=emulator-5554"
set "AVD_NAME=BibotTest"
set "AVD_SYS_IMG=%SDK%\system-images\android-34\default\x86_64"

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

echo [1/6] Kiem tra emulator...
"%ADB%" devices >nul 2>&1
if errorlevel 1 (
  echo [2/6] Khong co emulator '%DEV%' trong danh sach.
) else (
  echo [2/6] Emulator dang chay, bo qua boot.
)

echo [3/6] Tao AVD %AVD_NAME%...
"%AVD_MANAGER%" list avds --verbose | findstr /C:"%AVD_NAME%" >nul 2>&1
if errorlevel 1 (
  "%AVD_MANAGER%" create avd --name "%AVD_NAME%" --target "%AVD_SYS_IMG%" --force \
    --skin "768x1392" --sdcard 512M --none --wipe-data
  if errorlevel 1 (
    echo [LOI] Khong the tao AVD %AVD_NAME%.
    pause & exit /b 1
  )
  echo [3/6] Tao AVD hoan tat.
) else (
  echo [3/6] AVD %AVD_NAME% da ton tai.
)

echo [4/6] Khoi chay %AVD_NAME%...
if exist "%EMU%.exe" (
  start "BibotEmulator" "%EMU%" -avd "%AVD_NAME%" -skin "768x1392" -no-snapshot-load
) else (
  echo [LOI] Khong the tim emulator: %EMU%
  pause & exit /b 1
)

echo [5/6] Cho boot xong (toi da ~5 phut)...
set /a TRIES=0
:waitboot
if exist "%ADB%" (
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
    echo [OK] Emulator san sàng.
    goto installed
  )
) else (
  echo [LOI] Khong the tim ADB.
  pause & exit /b 1
)

echo [6/6] Cai APK...
if exist "%APK%" (
  "%ADB%" -s %DEV% install -r "%APK%"
  if errorlevel 1 (
    echo [LOI] Cai APK that bai.
    pause & exit /b 1
  )
  echo [OK] APK cai thanh cong.
) else (
  echo [LOI] Khong thay APK: %APK%
  pause & exit /b 1
)

REM Mo app
 "%ADB%" -s %DEV% shell am start -n com.bibot.app/.MainActivity
:installed
echo XONG - app Bibot dang mo tren emulator.
pause