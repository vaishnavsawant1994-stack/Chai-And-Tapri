@echo off
setlocal

if "%~1"=="" (
  echo Usage: %~nx0 ^<adb-serial^>
  echo Example: %~nx0 emulator-5556
  exit /b 2
)

set "ANDROID_SERIAL=%~1"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" (
  echo ADB not found at "%ADB%"
  exit /b 3
)
set "TARGET_FOUND="
for /f "tokens=1,2" %%A in ('"%ADB%" devices') do (
  if "%%A"=="%ANDROID_SERIAL%" if "%%B"=="device" set "TARGET_FOUND=1"
)
if not defined TARGET_FOUND (
  echo Requested authorized device "%ANDROID_SERIAL%" is not connected.
  exit /b 4
)
echo Running Android instrumentation only on "%ANDROID_SERIAL%". Other connected devices are excluded via ANDROID_SERIAL.
"%~dp0..\gradlew.bat" connectedDebugAndroidTest --console=plain -Pandroid.testInstrumentationRunnerArguments.notClass=com.chaiandtapri.counter.LiveBackendSyncE2ETest
exit /b %ERRORLEVEL%
