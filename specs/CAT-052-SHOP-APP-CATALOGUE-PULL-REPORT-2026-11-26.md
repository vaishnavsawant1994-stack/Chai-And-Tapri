# CAT-052 shop app catalogue pull — 26 November 2026

## Delivered

- Added `GET /v1/sync/catalogue/{shopId}`. It authenticates the bearer session and resolves the requested shop through the existing membership/session checks, then returns only the latest accepted product catalogue for that shop.
- The snapshot includes product ID, display/category, price and confirmation/active states, aliases, scan code, reorder threshold, and base/purchase-unit conversion. It excludes estimated costs and financial ledgers. It explicitly makes no stock-availability claim.
- Android validates the response shape, bounds, shop identity, product identities, prices, units, aliases, scan codes and revision before applying it.
- Manual owner sync, manual staff sync and background outbox sync now push allowed events and then fetch the accepted catalogue snapshot over the same HTTPS transport.
- SQLCipher persistence applies the snapshot atomically. It preserves locally queued, conflicted, or blocked catalogue edits; preserves existing stock and cost records; does not create a stock count for newly downloaded products; rejects another shop's snapshot and stale revisions; and advances the accepted server revision cursor.

## Verification

- Prototype Node: **52/52 passed**.
- Backend Node against a disposable PostgreSQL 17 instance: **44/44 passed, 0 skipped**. Coverage includes authenticated catalogue GET, unauthenticated denial, cost omission, catalogue projection and conversion rules.
- Kotlin JVM: **106/106 passed**.
- Debug APK and instrumentation APK builds: **PASS**.
- Android lint: **PASS**, 0 errors, 201 warnings.
- API 37 emulator catalogue parse/cache/persistence instrumentation: **1/1 passed**. The test exercises safe field allow-listing, wrong-shop and cost-bearing response rejection, local pending-edit preservation, newer snapshot update, stock-unreported behavior, revision cursor, stale revision rejection and shop-boundary rejection.
- A focused API 37 Android emulator E2E now registers, uploads, survives a lost acknowledgment/retry, downloads the shop catalogue over authenticated HTTPS, caches it in SQLCipher, and checks server projections against a disposable PostgreSQL 17 database. See [CAT-052 Android HTTPS E2E report](CAT-052-ANDROID-HTTPS-CATALOGUE-PULL-E2E-REPORT-2026-11-26.md).

## Limits

The catalogue snapshot is not inventory synchronization: new products have no locally confirmed stock and remain unavailable for sale until stock is physically recorded. No multi-device Android-to-server-to-Android network test was run. The service is not deployed. Pagination for very large catalogues, external production security review, physical-device/field tests, and release readiness remain open.
