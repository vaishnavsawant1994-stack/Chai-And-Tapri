# CT-32 Shop Profile and Settings

**Status:** Partial local Android workflow implemented and API 37 emulator-tested. This is not complete device/business administration.

## Delivered

- Owner-only access through the existing `ShopSetupActivity` and encrypted `ShopProfile` persistence.
- The screen distinguishes first-time shop setup from editing an existing shop profile.
- Existing owners can update shop name, business type, and preferred language. Currency remains INR in this prototype.
- A clearly labeled `Shop Profile & Settings · CT-32` entry is available from the owner counter and Shop Home.
- The screen explains that changing profile settings does not alter product prices, stock, or financial records.
- Existing persistence validation and audit behavior are reused; this slice adds no schema migration.

## Verification

Focused API 37 instrumentation test `shopProfileSettingsEditPersistsOwnerChangesWithoutFinancialSideEffects` passed 1/1. It edits the saved shop name, type, and language through the activity UI, reopens encrypted persistence, verifies the updated profile and audit record, and checks that no order, payment, or stock movement was created.

Full current-source verification after this change:

- Prototype Node: 52/52 passed.
- Kotlin JVM: 56/56 passed.
- Android API 37 instrumentation: 90/90 passed, 0 failures, 0 errors, 0 skipped.
- Debug APK and Android instrumentation APK build tasks: passed.
- Android lint: 0 errors, 161 warnings.
- Instrumentation XML: `../android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`.

The full emulator run is synthetic test coverage; it is not a physical-device or shop-field qualification. The backend suite was not rerun because this Android-only change does not alter backend sources; the latest backend/PostgreSQL integration result remains 20/20 as recorded in the 19 November project audit.

## Remaining scope

CT-32 still lacks device enrollment/revocation, branch selection, shop-local timezone and opening-hours preferences, payment configuration, synced profile changes, and a complete owner administration area. No new owner dashboard views or production controls are implied by this screen.
