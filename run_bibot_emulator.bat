@echo off
setlocal EnableExtensions EnableDelayedExpansion
title BibotTest - Android Emulator Launcher

REM ============================================================
REM BibotTest launcher
REM Uses the Emulator package currently installed at emulator-2
REM Android user data: H:\androidkit\.android
REM System image: Android 34 Google APIs x86_64
REM ============================================================

set "SDK=H:\androidkit\sdk"
set "EMU=%SDK%\emulator-2\emulator.exe"
set "ADB=%SDK%\platform-tools\adb.exe"
set "AVD_MANAGER=%SDK%\cmdline-tools\latest\bin\avdmanager.bat"
set "APK=%~dp0android\app\build\outputs\apk\demo\debug\Bibot.apk"

set "ANDROID_USER_HOME=H:\androidkit\.android"
set "ANDROID_AVD_HOME=H:\androidkit\.android\avd"
set "AVD_NAME=BibotTest"
set "SYS_DIR=%SDK%\system-images\android-34\google_apis\x86_64"
set "DEV="

echo.
echo ============================================================
echo  BibotTest Android Emulator Launcher
echo ============================================================
echo.

REM [1] Check files
echo [1/6] Kiem tra SDK va APK...

if not exist "%EMU%" (
    echo [ERROR] Khong tim thay Emulator: "%EMU%"
    goto :fail
)
if not exist "%ADB%" (
    echo [ERROR] Khong tim thay ADB: "%ADB%"
    goto :fail
)
if not exist "%AVD_MANAGER%" (
    echo [ERROR] Khong tim thay avdmanager: "%AVD_MANAGER%"
    goto :fail
)
if not exist "%SYS_DIR%\system.img" (
    echo [ERROR] Khong tim thay system image:
    echo "%SYS_DIR%\system.img"
    goto :fail
)
if not exist "%APK%" (
    echo [ERROR] Khong tim thay APK: "%APK%"
    echo Hay build: gradlew.bat :app:assembleDemoDebug --offline
    goto :fail
)

REM [2] Confirm AVD exists in the configured location
echo [2/6] Kiem tra AVD %AVD_NAME%...
if not exist "%ANDROID_AVD_HOME%\%AVD_NAME%.ini" (
    echo [ERROR] Khong tim thay:
    echo "%ANDROID_AVD_HOME%\%AVD_NAME%.ini"
    echo Khong tu dong tao AVD de tranh tao trung cau hinh.
    goto :fail
)

"%EMU%" -list-avds 2>nul | findstr /X /C:"%AVD_NAME%" >nul
if errorlevel 1 (
    echo [ERROR] Emulator khong liet ke AVD %AVD_NAME%.
    echo Kiem tra file .ini va duong dan AVD.
    goto :fail
)

REM [3] Start ADB
echo [3/6] Khoi dong ADB...
"%ADB%" start-server
if errorlevel 1 (
    echo [ERROR] Khong khoi dong duoc ADB.
    goto :fail
)

REM [4] Reuse existing emulator or launch BibotTest
echo [4/6] Kiem tra va khoi dong emulator...
for /f "tokens=1,2" %%A in ('"%ADB%" devices 2^>nul') do (
    if /I "%%B"=="device" (
        echo %%A | findstr /B /C:"emulator-" >nul
        if not errorlevel 1 if not defined DEV set "DEV=%%A"
    )
)

if defined DEV (
    echo Da tim thay emulator dang ket noi: !DEV!
) else (
    start "BibotEmulator" "%EMU%" -avd "%AVD_NAME%" -no-snapshot-load
)

REM [5] Wait for any connected emulator and for Android boot completion
echo [5/6] Cho Android boot hoan tat (toi da khoang 5 phut)...
set /a TRIES=0

:wait_device
set "DEV="
for /f "tokens=1,2" %%A in ('"%ADB%" devices 2^>nul') do (
    if /I "%%B"=="device" (
        echo %%A | findstr /B /C:"emulator-" >nul
        if not errorlevel 1 if not defined DEV set "DEV=%%A"
    )
)

if not defined DEV (
    set /a TRIES+=1
    if !TRIES! GEQ 60 (
        echo [ERROR] Khong phat hien emulator trong ADB.
        echo Chay: "%ADB%" devices -l
        goto :fail
    )
    timeout /t 5 /nobreak >nul
    goto :wait_device
)

set "BOOT="
for /f "delims=" %%B in ('"%ADB%" -s !DEV! shell getprop sys.boot_completed 2^>nul') do set "BOOT=%%B"

if not "!BOOT!"=="1" (
    set /a TRIES+=1
    if !TRIES! GEQ 60 (
        echo [ERROR] Android chua boot xong sau khoang 5 phut.
        goto :fail
    )
    timeout /t 5 /nobreak >nul
    goto :wait_device
)

echo Android da boot xong tren !DEV!.

REM [6] Install APK and launch
echo [6/6] Cai APK Bibot...
"%ADB%" -s !DEV! install -r "%APK%"
if errorlevel 1 (
    echo [ERROR] Cai APK that bai.
    goto :fail
)

echo Cai APK thanh cong.
echo Dang mo ung dung...
"%ADB%" -s !DEV! shell monkey -p com.bibot.app 1
if errorlevel 1 (
    echo [ERROR] Khong mo duoc com.bibot.app.
    echo Kiem tra applicationId trong Gradle.
    goto :fail
)

echo.
echo ============================================================
echo  HOAN TAT - Da gui lenh khoi chay Bibot.
echo ============================================================
pause
exit /b 0

:fail
echo.
echo Qua trinh that bai. Hay kiem tra loi phia tren.
pause
exit /b 1
