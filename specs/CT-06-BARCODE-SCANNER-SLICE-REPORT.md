# CT-06 / CT-13 / CT-14 - Barcode and QR Scanner Input Slice

Date: 21 November 2026

## Delivered

- Product records accept an optional scan code in the encrypted local catalogue.
- The code is trimmed, limited to 128 printable characters, and unique case-insensitively across products.
- SQLite schema v26 -> v27 adds a nullable `scan_code` column and a partial unique index. The migration is additive and preserves existing records.
- The owner catalogue editor can set and review the code.
- Quick Sale accepts a code from a paired keyboard-wedge barcode/QR reader or typed input. A successful exact match adds one unit to the current cart; it does not commit the sale.
- Unknown or ambiguous codes, inactive products, unconfirmed prices, unconfirmed opening stock, insufficient stock, and cart quantities at the configured limit leave the cart and business ledger unchanged.
- Normal tender selection and checkout remain the only operation that posts the sale, stock movement, and payment state.

## Boundaries

This slice does not implement camera-based decoding, scanner pairing, a physical scanner, or field testing. Product scan codes are local-only and are not included in the current accepted network sync contract. Android emulator input validates application behavior, not hardware scanning performance.

## Verification

- API 37 Quick Sale scanner-to-checkout UI test: 1/1 passed on 21 November 2026. It verified a trimmed exact code adds the product, an unknown code is rejected without changing the cart, no order is committed before checkout, and selecting the normal staff-declared cash tender posts exactly one canonical sale with the configured price, product line, one stock decrement, and a `staff_declared` payment event.
- API 37 schema migration test: 1/1 passed. It migrated a synthetic v26 database to v27, preserved its recorded sale and stock balance, then persisted and reopened a scan code.
- Kotlin JVM tests cover exact/case-insensitive matching, no partial matching, inactive products, ambiguous codes, empty input, duplicate code rejection, normalization, and control-character rejection.
- APK and instrumentation APK assembly passed before the full regression run.
- Earlier full API 37 emulator regression before the checkout test extension: 98/98 passed, 0 failures, 0 errors, 0 skipped. This includes 80 persistence/UI tests, HTTP transport integration, SQLCipher outbox retry, and sync session/runner suites. The live PostgreSQL-only class was explicitly excluded because no isolated backend was active.
- Focused API 37 scanner-to-checkout run: 1/1 passed after refining the test to tap the visible payment choice. The result was subsequently included in the fresh full-suite JUnit report below.
- Current-source verification on 21 November: Node 52/52 and Kotlin JVM 55/55 passed; debug and instrumentation APK builds passed; lint reported 0 errors / 161 warnings. The first combined emulator attempt was interrupted after 16 of 17 started tests when Androids package service/ADB transport became unavailable during a payment-screen test; that test then passed 1/1 in isolation. A fresh full API 37 run completed successfully: 98/98 passed, 0 failures, 0 errors, 0 skipped, including the scanner-to-checkout test. The opt-in live PostgreSQL E2E class was excluded because no isolated backend was active.
- Kotlin JVM: 63/63 passed.
- `assembleDebug` and `assembleDebugAndroidTest`: passed.
- Android lint: 0 errors, 161 warnings.
- Backend Node suite: 19 passed, 0 failed, 4 PostgreSQL-dependent tests skipped (no database configured).
- `git diff --check`: clean.
- JUnit output: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`; JVM XML: `android/app/build/test-results/testDebugUnitTest/`; lint report: `android/app/build/reports/lint-results-debug.txt`.

## Remaining work

Implement a camera scanning flow only with an explicit camera permission and a supported decoding library, test keyboard-wedge scanner models on hardware, review product sync/privacy requirements before syncing scan identifiers, and continue complete CT-06/CT-13 usability qualification.

## Superseding addendum - camera scanner - 21 November 2026

The previously keyboard-wedge-only Quick Sale scanner now has an explicit CameraX + bundled ML Kit on-device scan route. Optional camera permission is requested only when opening the scanner; it returns only raw product codes to Quick Sale's exact catalogue resolver. Permission denial/cancel retains typed and paired scanner fallback. It does not interpret UPI QR codes or commit a sale. Focused API 37 tests passed for raw scan result, permission/fallback UI, and code selection without a sale or stock mutation. Camera hardware/decoding remain unqualified. See `CT-06-CAMERA-BARCODE-SCANNER-REPORT.md`.