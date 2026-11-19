# CAT-049 Emulator Diagnosis — 28 October 2026

## Observed workstation

- Windows 11 Home Single Language, 25H2, OS build 26200.9711, x64 (Windows registry and `Win32_OperatingSystem` queried).
- Lenovo model 82K2, AMD Ryzen 5 5600H.
- `VirtualizationFirmwareEnabled=True`, `VMMonitorModeExtensions=True`, `SecondLevelAddressTranslationExtensions=True`.
- `HypervisorPresent=False`.
- Android Emulator `-accel-check` returns status 6: `Android Emulator hypervisor driver is not installed on this machine`.
- ADB lists no devices.

The OS API reports Windows 11 Home Single Language, 25H2, build 26200.9711; one registry product-name field still says “Windows 10 Home,” so the OS API/build values are used here. The Windows Optional Feature queries for `HypervisorPlatform` and `VirtualMachinePlatform` returned **“The requested operation requires elevation.”** No Windows feature, boot setting, BIOS setting, security option, or restart was changed.

## Existing AVD

`Medium_Phone_API_37.0` is installed and targets Android API 37, Google Play x86_64 image, 6 virtual CPUs, 2 GB RAM and automatic GPU mode. A fresh launch of `emulator.exe -avd Medium_Phone_API_37.0 -no-snapshot -no-window` exited with code 1 and printed `x86_64 emulation currently requires hardware acceleration` and `Android Emulator hypervisor driver is not installed on this machine`. It also warned that available host RAM (14,188 MiB) is below the suggested 16,384 MiB for the AVD. `-accel off` did not result in an online ADB device. `adb install -r ...app-debug.apk` returned `no devices/emulators found`.

## Diagnosis

The processor and BIOS/UEFI virtualization capability are available and enabled. The host hypervisor/accelerator is missing or inactive. The current API 37 x86_64 virtual device therefore cannot be qualified on this host yet. The Windows optional-feature state could not be read without administrator elevation.

Android's current guidance recommends Windows Hypervisor Platform (WHPX) on Windows 10 version 1803 or later; AMD systems require SVM enabled. Enabling WHPX and rebooting are system changes and were not attempted. Use Windows **Turn Windows features on or off** to enable **Windows Hypervisor Platform**, then restart. Do not disable Core Isolation or other security protections to try a different accelerator. See [official Android Emulator acceleration guidance](https://developer.android.com/studio/run/emulator-acceleration). Android Emulator Hypervisor Driver is scheduled to sunset on 31 December 2026, so WHPX is the preferred path.

## Next check after an authorized host change

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -accel-check
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Medium_Phone_API_37.0 -no-snapshot
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices -l
```

Require `-accel-check` to report a usable WHPX accelerator and `adb devices` to show the emulator as `device` before installing the app. Then run `.\gradlew.bat installDebug` from `android/` and execute the compiled Android instrumentation test with `.\gradlew.bat connectedDebugAndroidTest`.

## Cloud test option

`gcloud` and `firebase` CLIs are not available in this shell. Firebase Test Lab has not been configured, and no APK or test data was uploaded. External upload and any possible charges require separate user authorization.





## Recheck after the latest progress update

On 28 October 2026, `-accel-check` still returned 6, `HypervisorPresent` remained false, and a fresh launch of the same AVD exited with code 1 for missing x86_64 hardware acceleration. `adb devices -l` remained empty. A direct APK install attempt was not repeated because there was still no target device; the previous `adb install` result was `no devices/emulators found`.

The AVD's saved `hw.ramSize` is already 2048 MB. The emulator raises its effective guest RAM to 4096 MB and warns that host-available memory (14,188 MiB) is below its suggested 16,384 MiB. No AVD setting was changed because the missing hypervisor fails launch before RAM tuning can help.

The latest local regression rerun remains green: Node 43/43 and Kotlin JVM 16/16. `assembleDebugAndroidTest` remains buildable; the SQLite instrumentation test is still not executed.

## Recheck before WHPX enablement — 29 October 2026 (historical)

- `emulator.exe -accel-check` returned status 6: `Android Emulator hypervisor driver is not installed on this machine`.
- `adb devices -l` showed no attached or booted devices.
- Read-only host checks still show Windows 11 Home Single Language build 26200, AMD Ryzen 5 5600H, firmware virtualization enabled, and `HypervisorPresent=False`. No administrator query, feature change, security change, or restart was attempted.
- Fresh clean build: `clean assembleDebug assembleDebugAndroidTest testDebugUnitTest --console=plain` completed successfully. Kotlin JVM: 16 passed, 0 failed. APKs were rebuilt; instrumentation tests compiled but did not execute.
- Fresh Node run: 43 passed, 0 failed.
- At that point, emulator boot, installation, app launch, SQLite instrumentation, UI runtime, microphone, headset, and field qualification were blocked or pending. The later WHPX enablement/restart and emulator results are recorded below.

## WHPX authorized change before restart — historical

On 29 October 2026, the elevated DISM feature query confirmed `HypervisorPlatform` was **Disabled**. Following the user's explicit authorization, the Windows Hypervisor Platform feature alone was enabled using `DISM /Online /Enable-Feature /FeatureName:HypervisorPlatform /NoRestart`. DISM completed successfully with exit code 3010. A subsequent elevated query reported `State : Enabled`. At that point no restart had yet been performed.

Before restart, `-accel-check` returned status 6 and ADB was empty. This pre-restart state is superseded by the post-restart result below. The [continuation checkpoint](CAT-049-CONTINUATION-CHECKPOINT.md) records the saved handoff and has since been resumed.

## Post-restart result — 29 October 2026

The user approved restart, and after returning to the workspace the read-only checks showed `HypervisorPresent=True`; `emulator.exe -accel-check` returned status 0: `WHPX(10.0.26200) is installed and usable.` The existing `Medium_Phone_API_37.0` x86_64 AVD booted Android 17/API 37. ADB now reports `emulator-5554 device`, `sys.boot_completed=1`, and 8.7 GB available on `/data`. No AVD data was deleted or reconfigured. Emulator qualification and exact test results are in [CAT-049A status](CAT-049A-ANDROID-BUILD-STATUS.md) and the [Phase 0 report](PHASE-0-QUALIFICATION-REPORT.md).
