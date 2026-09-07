@echo off
chcp 936 >nul
setlocal enabledelayedexpansion
set "ORIGINAL_PATH=%PATH%"

:run_script
set "RUN_EXIT_CODE=0"

rem =====================================================================
rem SPlayer for Android - RELEASE build + install
rem Flow: select devices -> pnpm build:android -> gradlew assembleRelease
rem -> pick arm64-v8a APK -> sign if needed -> install to selected devices
rem =====================================================================

rem ---- Tool paths (modify as needed) ----
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
set "ANDROID_HOME=D:\Android_Studio_SDK"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "ANDROID_NDK_HOME=%ANDROID_HOME%\ndk\29.0.13846066"
set "ANDROID_NDK_ROOT=%ANDROID_NDK_HOME%"

set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\cmdline-tools\latest\bin;%ORIGINAL_PATH%"

rem ---- pick latest build-tools dir (for apksigner / zipalign) ----
set "BUILD_TOOLS_DIR="
for /f "delims=" %%i in ('dir /b /ad /o-n "%ANDROID_HOME%\build-tools" 2^>nul') do (
  if not defined BUILD_TOOLS_DIR set "BUILD_TOOLS_DIR=%ANDROID_HOME%\build-tools\%%i"
)
if not defined BUILD_TOOLS_DIR (
  echo [FAIL] No build-tools found under %ANDROID_HOME%\build-tools
  set "RUN_EXIT_CODE=1"
  goto finish_script
)
set "APKSIGNER=%BUILD_TOOLS_DIR%\apksigner.bat"
set "ZIPALIGN=%BUILD_TOOLS_DIR%\zipalign.exe"

rem ---- Project config ----
set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"
set "ANDROID_DIR=%PROJECT_DIR%\android"
set "APK_OUT_DIR=%ANDROID_DIR%\app\build\outputs\apk\release"
set "TARGET_ABI=arm64-v8a"
set "APK_SIGNED_NAME=app-%TARGET_ABI%-release.apk"
set "APK_UNSIGNED_NAME=app-%TARGET_ABI%-release-unsigned.apk"
set "ALIGNED_APK=%TEMP%\splayer-release-aligned.apk"
set "SIGNED_APK=%TEMP%\splayer-release-signed.apk"
set "PACKAGE_ID=top.imsyy.splayer_next"

set "DEBUG_KS=%USERPROFILE%\.android\debug.keystore"

echo ===== Environment =====
echo JAVA_HOME=%JAVA_HOME%
echo ANDROID_HOME=%ANDROID_HOME%
echo BUILD_TOOLS_DIR=%BUILD_TOOLS_DIR%
echo PROJECT_DIR=%PROJECT_DIR%
echo TARGET_ABI=%TARGET_ABI%
echo PACKAGE_ID=%PACKAGE_ID%
echo DEBUG_KS=%DEBUG_KS%
echo.

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [FAIL] JDK not found at %JAVA_HOME%
  set "RUN_EXIT_CODE=1"
  goto finish_script
)
if not exist "%APKSIGNER%" (
  echo [FAIL] apksigner not found at %APKSIGNER%
  set "RUN_EXIT_CODE=1"
  goto finish_script
)
if not exist "%ZIPALIGN%" (
  echo [FAIL] zipalign not found at %ZIPALIGN%
  set "RUN_EXIT_CODE=1"
  goto finish_script
)
if not exist "%ANDROID_DIR%\gradlew.bat" (
  echo [FAIL] gradlew.bat not found at %ANDROID_DIR%
  set "RUN_EXIT_CODE=1"
  goto finish_script
)

if not exist "%DEBUG_KS%" (
  echo [INFO] %DEBUG_KS% missing, generating one ...
  if not exist "%USERPROFILE%\.android" mkdir "%USERPROFILE%\.android"
  "%JAVA_HOME%\bin\keytool.exe" -genkeypair -v ^
    -keystore "%DEBUG_KS%" ^
    -storepass android -keypass android ^
    -alias androiddebugkey ^
    -keyalg RSA -keysize 2048 -validity 10000 ^
    -dname "CN=Android Debug,O=Android,C=US"
  if errorlevel 1 (
    echo [FAIL] keytool failed to generate debug.keystore
    set "RUN_EXIT_CODE=1"
    goto finish_script
  )
)

rem =====================================================================
rem Device selection (before build)
rem =====================================================================
:select_devices
set "DEVICE_COUNT=0"
set "DEVICE_LIST="
for /f "skip=1 tokens=1" %%d in ('adb devices 2^>nul') do (
  if not "%%d"=="" (
    set /a "DEVICE_COUNT+=1"
    set "DEVICE_!DEVICE_COUNT!=%%d"
    if "!DEVICE_LIST!"=="" (
      set "DEVICE_LIST=%%d"
    ) else (
      set "DEVICE_LIST=!DEVICE_LIST! %%d"
    )
  )
)

echo.
echo ===== Detected %DEVICE_COUNT% device(s) =====
if %DEVICE_COUNT% equ 0 (
  echo [FAIL] No connected devices found
  set "RUN_EXIT_CODE=1"
  goto finish_script
)

rem ===== Show device list =====
echo Available devices:
for /l %%i in (1,1,%DEVICE_COUNT%) do (
  echo   %%i^) !DEVICE_%%i!
)
echo.

:ask_install_mode
if %DEVICE_COUNT% equ 1 (
  echo [1] Install to: !DEVICE_1!
) else (
  echo [1] Install to ALL devices
  echo [2] Select specific devices
)
echo [K] Install existing APK
echo [I] Refresh device list
echo.
set "INSTALL_MODE="
set /p "INSTALL_MODE=Please select: "

if /i "!INSTALL_MODE!"=="I" goto select_devices
if /i "!INSTALL_MODE!"=="K" goto install_existing_apk
if "!INSTALL_MODE!"=="1" (
  set "SELECTED_DEVICES=!DEVICE_LIST!"
  goto build_start
)
if %DEVICE_COUNT% gtr 1 (
  if "!INSTALL_MODE!"=="2" goto select_specific_devices
)

echo Invalid selection, please try again
goto ask_install_mode

:select_specific_devices
echo.
echo Enter device numbers (e.g. 13 or 1 3 to select device 1 and 3):
set "DEVICE_SELECTION="
set /p "DEVICE_SELECTION=Select devices: "

rem ===== Parse selection, extract digits one by one =====
set "SELECTED_DEVICES="
set "VALID_SELECTION=0"
set "REMAIN=%DEVICE_SELECTION%"

:parse_loop
if not defined REMAIN goto parse_done
set "CHAR=!REMAIN:~0,1!"
set "REMAIN=!REMAIN:~1!"
set "IS_DIGIT=0"
for %%n in (1 2 3 4 5 6 7 8 9) do (
  if "!CHAR!"=="%%n" set "IS_DIGIT=1"
)
if "!IS_DIGIT!"=="1" (
  if !CHAR! leq %DEVICE_COUNT% (
    set /a "VALID_SELECTION+=1"
    call :add_device_to_selection !CHAR!
  )
)
goto parse_loop

:parse_done
if %VALID_SELECTION% equ 0 (
  echo No valid devices selected, please try again
  goto select_specific_devices
)
echo Selected devices: !SELECTED_DEVICES!
goto build_start

rem ===== Subroutine: add device serial to SELECTED_DEVICES =====
:add_device_to_selection
set "IDX=%1"
for /f "tokens=1" %%s in ("!DEVICE_%IDX%!") do set "DEV_SERIAL=%%s"
if "!SELECTED_DEVICES!"=="" (
  set "SELECTED_DEVICES=!DEV_SERIAL!"
) else (
  set "SELECTED_DEVICES=!SELECTED_DEVICES! !DEV_SERIAL!"
)
goto :eof

rem =====================================================================
rem Build
rem =====================================================================
:build_start
if "%SKIP_BUILD%"=="1" (
  echo [INFO] Skipping build, using existing APK
  set "SKIP_BUILD="
  goto install_start
)

echo.
echo ===== Step 1/3: pnpm build:android (web + cap sync + node + embedded) =====
cd /d "%PROJECT_DIR%"
if errorlevel 1 (
  echo cd failed
  set "RUN_EXIT_CODE=1"
  goto finish_script
)
call pnpm build:android
if errorlevel 1 (
  echo [FAIL] pnpm build:android failed
  call :get_timestamp
  echo [BUILD FAILED] !TIMESTAMP!
  set "RUN_EXIT_CODE=1"
  goto finish_script
)

echo.
echo ===== Step 2/3: gradlew assembleRelease =====
cd /d "%ANDROID_DIR%"
call gradlew.bat assembleRelease
if errorlevel 1 (
  echo [FAIL] gradlew assembleRelease failed
  call :get_timestamp
  echo [BUILD FAILED] !TIMESTAMP!
  set "RUN_EXIT_CODE=1"
  goto finish_script
)
cd /d "%PROJECT_DIR%"

rem ---- Prefer pre-signed APK; otherwise use unsigned + debug sign ----
set "FINAL_APK="
if exist "%APK_OUT_DIR%\%APK_SIGNED_NAME%" (
  set "FINAL_APK=%APK_OUT_DIR%\%APK_SIGNED_NAME%"
  echo [INFO] Found pre-signed release APK: !FINAL_APK!
) else if exist "%APK_OUT_DIR%\%APK_UNSIGNED_NAME%" (
  set "UNSIGNED_APK=%APK_OUT_DIR%\%APK_UNSIGNED_NAME%"
  echo [INFO] Unsigned release APK: !UNSIGNED_APK!
  echo.
  echo ===== zipalign =====
  if exist "%ALIGNED_APK%" del /q "%ALIGNED_APK%"
  "%ZIPALIGN%" -p -f 4 "!UNSIGNED_APK!" "%ALIGNED_APK%"
  if errorlevel 1 (
    echo [FAIL] zipalign failed
    call :get_timestamp
    echo [BUILD FAILED] !TIMESTAMP!
    set "RUN_EXIT_CODE=1"
    goto finish_script
  )

  echo ===== apksigner sign with debug key =====
  if exist "%SIGNED_APK%" del /q "%SIGNED_APK%"
  call "%APKSIGNER%" sign ^
    --ks "%DEBUG_KS%" ^
    --ks-pass pass:android ^
    --key-pass pass:android ^
    --ks-key-alias androiddebugkey ^
    --out "%SIGNED_APK%" ^
    "%ALIGNED_APK%"
  if errorlevel 1 (
    echo [FAIL] apksigner sign failed
    call :get_timestamp
    echo [BUILD FAILED] !TIMESTAMP!
    set "RUN_EXIT_CODE=1"
    goto finish_script
  )
  set "FINAL_APK=%SIGNED_APK%"
  echo Signed APK: !FINAL_APK!
) else (
  echo [FAIL] No release APK found in %APK_OUT_DIR%
  call :get_timestamp
  echo [BUILD FAILED] !TIMESTAMP!
  echo Looked for:
  echo   %APK_SIGNED_NAME%
  echo   %APK_UNSIGNED_NAME%
  if exist "%APK_OUT_DIR%" dir /b "%APK_OUT_DIR%"
  set "RUN_EXIT_CODE=1"
  goto finish_script
)
echo.
call :get_timestamp
echo [BUILD OK] !TIMESTAMP!

rem =====================================================================
rem Install to selected devices
rem =====================================================================
:install_start
echo ===== Step 3/3: Installing %PACKAGE_ID% =====

for %%d in (!SELECTED_DEVICES!) do (
  echo.
  echo Installing to device: %%d
  adb -s %%d install -r "%FINAL_APK%"
  if errorlevel 1 (
    echo [WARN] install -r failed for %%d, trying uninstall + install ...
    adb -s %%d uninstall %PACKAGE_ID%
    adb -s %%d install "%FINAL_APK%"
    if errorlevel 1 (
      echo [FAIL] install failed for %%d. Skipping this device.
      call :get_timestamp
      echo [INSTALL FAILED] !TIMESTAMP!
    ) else (
      echo [OK] Installed to %%d successfully
      call :get_timestamp
      echo [INSTALL OK] !TIMESTAMP!
      echo Launching %PACKAGE_ID% on %%d
      adb -s %%d shell monkey -p %PACKAGE_ID% -c android.intent.category.LAUNCHER 1
    )
  ) else (
    echo [OK] Installed to %%d successfully
    call :get_timestamp
    echo [INSTALL OK] !TIMESTAMP!
    echo Launching %PACKAGE_ID% on %%d
    adb -s %%d shell monkey -p %PACKAGE_ID% -c android.intent.category.LAUNCHER 1
  )
)

echo.
echo ===== DONE (release) =====

:finish_script
echo.
echo [R] Rerun (full build + install)
echo [I] Reinstall to devices (skip build)
echo [K] Install existing APK (select devices)
echo [Q] Quit
echo.
choice /c RIKQ /n /m "Press R/I/K/Q: "
if errorlevel 4 goto end_script
if errorlevel 3 goto install_existing_apk
if errorlevel 2 goto reinstall_only
if errorlevel 1 goto run_script

rem ===== Reinstall only, skip build =====
:reinstall_only
if not defined FINAL_APK (
  echo [FAIL] No APK available. Please run a full build first.
  goto finish_script
)
if not exist "%FINAL_APK%" (
  echo [FAIL] APK not found: %FINAL_APK%
  goto finish_script
)
set "SKIP_BUILD=1"
goto select_devices

rem ===== Install existing APK, select devices =====
:install_existing_apk
echo.
echo ===== Looking for existing APK =====
if exist "%APK_OUT_DIR%\%APK_SIGNED_NAME%" (
  set "FINAL_APK=%APK_OUT_DIR%\%APK_SIGNED_NAME%"
  echo [OK] Found signed release APK: !FINAL_APK!
) else if exist "%APK_OUT_DIR%\%APK_UNSIGNED_NAME%" (
  set "UNSIGNED_APK=%APK_OUT_DIR%\%APK_UNSIGNED_NAME%"
  echo [INFO] Found unsigned release APK: !UNSIGNED_APK!
  echo.
  echo ===== zipalign =====
  if exist "%ALIGNED_APK%" del /q "%ALIGNED_APK%"
  "%ZIPALIGN%" -p -f 4 "!UNSIGNED_APK!" "%ALIGNED_APK%"
  if errorlevel 1 (
    echo [FAIL] zipalign failed
    call :get_timestamp
    echo [BUILD FAILED] !TIMESTAMP!
    set "RUN_EXIT_CODE=1"
    goto finish_script
  )

  echo ===== apksigner sign with debug key =====
  if exist "%SIGNED_APK%" del /q "%SIGNED_APK%"
  call "%APKSIGNER%" sign ^
    --ks "%DEBUG_KS%" ^
    --ks-pass pass:android ^
    --key-pass pass:android ^
    --ks-key-alias androiddebugkey ^
    --out "%SIGNED_APK%" ^
    "%ALIGNED_APK%"
  if errorlevel 1 (
    echo [FAIL] apksigner sign failed
    call :get_timestamp
    echo [BUILD FAILED] !TIMESTAMP!
    set "RUN_EXIT_CODE=1"
    goto finish_script
  )
  set "FINAL_APK=%SIGNED_APK%"
  echo Signed APK: !FINAL_APK!
) else (
  echo [FAIL] No existing APK found in %APK_OUT_DIR%
  call :get_timestamp
  echo [BUILD FAILED] !TIMESTAMP!
  echo Looked for:
  echo   %APK_SIGNED_NAME%
  echo   %APK_UNSIGNED_NAME%
  set "RUN_EXIT_CODE=1"
  goto finish_script
)
set "SKIP_BUILD=1"
if not defined SELECTED_DEVICES (
  echo.
  echo ===== Detected %DEVICE_COUNT% device(s) =====
  echo Available devices:
  for /l %%i in (1,1,%DEVICE_COUNT%) do (
    echo   %%i^) !DEVICE_%%i!
  )
  echo.
  goto ask_install_mode
)
goto build_start

rem ===== Subroutine: get formatted timestamp M/d-H:m:s =====
:get_timestamp
for /f "usebackq delims=" %%t in (`powershell -NoProfile -Command "Get-Date -Format 'M/d-H:m:s'"`) do set "TIMESTAMP=%%t"
goto :eof

:end_script
endlocal & exit /b %RUN_EXIT_CODE%
