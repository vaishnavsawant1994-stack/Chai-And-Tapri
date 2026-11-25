# CT-13/14 configured product base units — 25 November 2026

## Delivered

- Added an owner-configurable sell/count base-unit label to each product, with trim/length/control-character validation.
- Persisted the field using additive SQLite schema migration v30→v31. Existing catalogue rows receive the explicit legacy label `units`; order, stock, payment, audit and outbox records are left intact.
- Displayed the unit on catalogue cards, stock thresholds, Quick Sale product/cart/receipt rows, live-order line/fulfillment controls, order details, inventory/movement views, physical-count review/history and count-history CSV export.
- Prevented changing a product's unit after any order line or stock movement exists. This avoids silently relabeling historical quantities.
- Kept units as the exact configured base quantity. Pack/carton-to-piece conversion, variants, recipes and purchase-unit conversion are not implemented.

## Verification

- Node: **52 passed, 0 failed** (`node --test prototype/*.test.cjs`).
- Kotlin JVM: **89 passed, 0 failed** across 11 XML test suites.
- API 37 focused Quick Sale UI test: **1/1 passed**. It saves a synthetic product using `cup`, confirms five cups of opening stock, checks the visible cart and committed receipt use `cup`, and verifies cash and UPI-pending labels remain distinct.
- API 37 SQLite migration test: **1/1 passed**. A synthetic v30 database with an order and stock movement upgrades to v31; the label defaults to `units`, financial/stock records survive, and relabeling is denied after history exists.
- `testDebugUnitTest assembleDebug assembleDebugAndroidTest`: **BUILD SUCCESSFUL**.
- `lintDebug`: **BUILD SUCCESSFUL**, 0 errors / 189 warnings.
- Evidence: [`quick-sale-unit-label-ui.xml`](../test-evidence/2026-11-25/ct13-base-unit/quick-sale-unit-label-ui.xml), [`schema-v30-v31-migration.xml`](../test-evidence/2026-11-25/ct13-base-unit/schema-v30-v31-migration.xml), and Kotlin JVM XML under the same evidence directory.

## Limits and next work

CT-13/14 remain partial. A user-configured label does not perform quantity conversion or validate domain-specific units; it is a display/recording convention. Legacy catalogues remain labeled `units` unless they are genuinely new products without transaction history. Product variants, purchase units, recipes, price history, wider catalogue usability/accessibility review, full regression suites and production migration/recovery remain open. No physical-shop, Bluetooth, security-review, cross-device or production qualification is claimed.

- Product unit labels have not been added to the authenticated sync contract; synchronized multi-device catalogues therefore do not yet preserve them. The change is local-only until a versioned sync-contract update is implemented and tested.

## Broad regression attempt

The focused tests and Kotlin JVM suite passed. A later broad connectedDebugAndroidTest attempt is **incomplete/failed**, not a qualification: it recorded 28 passes and one failure before ADB lost the device. The failed test was cashierCanSignInThroughAccessScreenAndSeesCounterOnlyMenu with an empty JUnit failure body; the emulator then disappeared during Gradle cleanup. Reboot attempts of both existing API 37 AVDs did not make a device available. The partial XML and run details are in [evidence](../test-evidence/2026-11-25/ct13-base-unit/full-suite-attempt.txt). The new migration and unit-display focused tests pass independently.
