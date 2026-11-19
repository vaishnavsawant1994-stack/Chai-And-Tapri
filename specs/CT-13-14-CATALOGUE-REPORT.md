# CT-13/14 Product Catalogue and Editor — Implementation Report

**Date:** 29 October 2026  
**Status:** Partial; debug build and API 37 instrumentation qualified for the behaviors below.

## Delivered

- Added encrypted SQLCipher product catalogue, with additive migrations through schema v8. Existing starter prices are illustrative and seeded with `price_confirmed=false`.
- Added a native product list/editor. Owners can add or edit product ID, name, category, INR price, activity, favorite flag, voice/search aliases, and explicitly confirm the entered price.
- New products start at zero stock. The editor does not create purchase receipts or opening inventory.
- Catalogue edits use the shared write barrier and commit atomically with a command receipt, audit event, and pending outbox record.
- Native counter creation and aliases resolve only active, owner-confirmed products and use configured integer paise prices. This guard is applied at startup and database-reopen paths.
- Schema v8 initializes fresh stock at zero and tracks explicit opening-stock confirmation. Legacy quantity values are retained but treated as unconfirmed; strict configured sales/handover cannot consume them before an audited physical count.
- The catalogue provides a single-product physical-count dialog requiring a quantity and reason. The idempotent update persists the stock movement, audit event, command receipt, and local outbox atomically.
- Emulator coverage proves unconfirmed-price and unconfirmed-stock rejection, alias parsing (`do cutting`), configured-price use, stock-count failure rollback/retry, sale after count, restart persistence, and duplicate-command rejection.
- Opened the catalogue list on the API 37 emulator; its screenshot and hierarchy are in `test-evidence/2026-10-29/cat13-product-catalogue.png` and `cat13-product-catalogue.xml`. Product editor UI interactions have not been manually exercised.
- Manually entered a synthetic Coffee stock count of 5 with note `physical count`; success message and confirmed quantity are visible in `test-evidence/2026-10-29/ct13-count-saved.png` and `ct13-count-saved.xml`.

## Verification

- Node: 48/48 passed.
- Kotlin JVM: 23/23 passed.
- Android instrumentation: 14/14 passed on `Medium_Phone_API_37.0` in the latest full run.
- Debug and instrumentation APK builds: PASS.
- Android lint: 0 errors, 60 warnings.
- Full command: `gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest`.
- Instrumentation result: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`.

## Not yet delivered

This is not a complete catalogue, inventory or Quick Sale module. Product units/conversions, cost and margin, barcode/QR scanning, multi-product count sessions, variance reports, purchase intake and reorder tools are absent. The opening-count dialog is a limited one-product flow, not the full CT-16 workflow. Product-price changes have no dedicated history view or approval role. The native UI is a simple programmatic Android screen and has not had a separate accessibility/usability review. Native Quick Sale, full live-order management, and complete CT-06 through CT-14 screen coverage remain pending.

No business data was sent to a backend; the outbox remains local and unsynchronized. Browser CAT-046 review authorization and physical-device/field qualification remain unchanged.
