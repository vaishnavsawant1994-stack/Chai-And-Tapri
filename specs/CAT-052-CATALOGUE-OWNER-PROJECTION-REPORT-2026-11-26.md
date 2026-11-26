# CAT-052 catalogue and owner projection slice — 26 November 2026

## Scope

Product saves now create a versioned, sendable catalogue event in the existing encrypted Android sync outbox. The event carries product identity, display/category fields, configured price state, aliases, stock threshold, estimated unit cost, scan code, base unit, purchase unit, and integer conversion factor. It uses the existing authenticated `projection_delta.v1` transport; it does not introduce a new endpoint or database table.

The backend validates the exact catalogue object shape and bounds, rejects catalogue writes from cashier sessions, and accepts owner/manager updates. Accepted catalogue revisions are reconstructed from the tenant-scoped sync event journal for the owner product-performance and inventory projections. The dashboard shows names, category/status, confirmed price and purchase-unit conversion, while withholding aliases, scan codes and cost fields. Catalogue-only products are shown with zero activity and `unreported` stock rather than fabricated quantities.

Purchase-unit label and conversion changes are rejected once accepted order, stock or stock-movement history uses the product. Price and descriptive fields can still be updated. The current backend history lock does not yet cover every procurement event shape; conversion mutation rules should be extended alongside any future product-linked receipt/return event contract.

## Verification

- Prototype Node suite: **52/52 passed**.
- Backend Node suite against a fresh disposable PostgreSQL 17 database: **43/43 passed, 0 skipped**. This includes the new owner catalogue upsert, owner product/inventory projection, duplicate retry, and conversion-lock end-to-end test. The container was stopped after the run; only synthetic records were used.
- Kotlin JVM: **105/105 passed**.
- Android debug APK and instrumentation APK build tasks: **PASS**.
- Android lint: **PASS**, 0 errors, 197 warnings.
- Android API 37 instrumentation for encrypted outbox persistence: **1/1 passed**. It verified the locally persisted event is sendable and carries product ID, base unit, purchase unit, factor and nullable scan code.
- `git diff --check`: passed after the code/test changes.
- Physical device, Bluetooth, production server, and browser visual qualification: not part of this slice.

## Product boundary

This is an Android-to-server accepted catalogue projection and owner-dashboard read path. It is not a catalogue download/sync path to other Android devices, and it does not mean the product-management screens or the owner dashboard are complete. Offline/unaccepted product edits remain local and are not represented in owner views. The unit-conversion history guard currently covers accepted orders, stock snapshots and stock movements; it should be expanded for future product-linked procurement events before changing the procurement sync contract.
