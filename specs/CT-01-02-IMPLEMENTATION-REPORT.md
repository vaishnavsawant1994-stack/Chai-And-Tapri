# CT-01 / CT-02 — Welcome and Shop Setup Vertical Slice

**Date:** 29 October 2026  
**Status:** Partial implementation; API 37 emulator-tested for the listed flows. This is not the complete onboarding or shopkeeper MVP.

## Delivered behavior

- CT-01 welcome page provides routes to Shop Setup, the existing counter prototype, and its Backup & Recovery menu. The recovery menu was opened from the welcome route on the emulator. The page shows no sales or balances.
- CT-02 saves shop name, supported business type, preferred language and INR currency to the existing encrypted SQLCipher `metadata` table; no database schema migration was required.
- Profile writes use the global business write barrier and one SQLite transaction. Each save appends an audit event and pending sync-outbox entry without placing the shop name in the audit text.
- The setup form validates name length, supported business type, language and currency. It explicitly says that this slice creates no sales or financial records and that catalogue/prices/opening stock are separate setup work.

## Files changed

- `android/app/src/main/java/com/chaiandtapri/counter/WelcomeActivity.kt` — CT-01 route screen.
- `android/app/src/main/java/com/chaiandtapri/counter/ShopSetupActivity.kt` — CT-02 profile form.
- `android/app/src/main/java/com/chaiandtapri/counter/CounterPersistence.kt` — profile record and persistence contract.
- `android/app/src/main/java/com/chaiandtapri/counter/CounterModel.kt` — validation and profile save/read path.
- `android/app/src/main/java/com/chaiandtapri/counter/SqliteCounterPersistence.kt` — encrypted metadata read/write, barrier, audit and outbox.
- `android/app/src/main/java/com/chaiandtapri/counter/MainActivity.kt` and `android/app/src/main/AndroidManifest.xml` — navigation and launcher routing.
- `android/app/src/androidTest/java/com/chaiandtapri/counter/SqliteCounterPersistenceTest.kt` — persistence, empty-financial-state, audit/outbox and maintenance-barrier assertions.

## Verification

Latest clean full Android command:

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest --console=plain
```

Node tests were run separately with `node --test prototype/*.test.cjs`.

| Suite/check | Result |
|---|---:|
| Node | 48 passed, 0 failed |
| Kotlin JVM | 23 passed, 0 failed |
| Android instrumentation | 10 passed, 0 failed |
| Total test methods | 81 passed, 0 failed |
| Debug and instrumentation APK builds | PASS |
| Android lint | 0 errors, 45 warnings |
| Emulator UI | CT-01 Welcome opened; CT-02 save showed success; saved profile re-opened with the same name after process restart |

Evidence is under `test-evidence/2026-10-29/`: `ct01-welcome.png`, `ct01-recovery-route.png`, `ct02-shop-setup.png`, `ct02-shop-setup-saved.png`, `ct02-shop-setup-after-restart.png`, and corresponding UI hierarchy XML files. The instrumentation result is `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`.

## Remaining scope

- CT-01 restore-from-backup opens the existing counter/recovery menu when the encrypted local database opens; a recovery-only screen for an unreadable/missing local database is not yet built.
- CT-02 does not configure the product catalogue, product prices, costs, units, opening stock or actual business identity verification.
- No staff account/login or role permissions are implemented.
- CT-03 through CT-32 remain pending or partial as marked in `MASTER-IMPLEMENTATION-TRACKER.md`.
- This verification is for an emulator and synthetic test data; it is not physical-device, field, security-review or production qualification.
