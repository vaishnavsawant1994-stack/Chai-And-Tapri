# CT-01/02/05/06 Guided First-Sale Workflow Report

**Date:** 23 November 2026  
**Scope:** Owner Shop Home next-step routing and a connected synthetic owner setup → catalogue → physical opening count → Quick Sale workflow.

## Implemented

- Added a deterministic `FirstSaleSetupAction` derived from the saved shop name, sellable-product count, and physically confirmed stock count.
- CT-05 displays one owner-facing next action: configure shop identity, add priced products, confirm physical opening stock, or start Quick Sale.
- Each action routes to the existing native screen. The checklist does not create or infer prices, inventory, transactions, or payments.
- Added unit coverage for each stage and for the zero-product readiness boundary.
- Added a connected Android UI journey that enters synthetic shop setup, creates a product with an explicitly confirmed ₹15 price, records an actual count of eight, returns to Shop Home, starts Quick Sale, and records one cash sale.

## Verification

- Focused API 37 instrumentation: **1/1 passed** on `emulator-5554`; JUnit XML: `../test-evidence/2026-11-23/first-sale-guided-workflow/first-sale-guided-workflow.xml`.
- Persisted encrypted snapshot assertions: one ₹15 order at canonical price; one staff-declared cash entry; stock moved from 8 to 7; exactly one linked `quick_sale` movement; product audit evidence present.
- Kotlin JVM suite: **71 passed, 0 failed, 0 skipped**.
- `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`: **BUILD SUCCESSFUL**; lint **0 errors, 176 warnings**.
- Node and backend suites were not rerun; this change is Android-only.

Two early focused UI attempts exposed test harness issues, not a passing result: an active Android notification shade obscured the app on the first run, and the second run selected the search field instead of the product button. The emulator shade was collapsed, the selector was made specific to the priced product button, and the final focused run passed. The previous broad 120-case Android run (82 passed, 38 failed) remains a failed broad regression; this focused pass does not supersede it.

## Limits

This is one synthetic API 37 first-sale workflow. It does not qualify actual shop usability, physical hardware, offline day-long operation, cloud synchronization, security review, or release readiness. CT-01 through CT-06 and the six-wave product remain partial.
