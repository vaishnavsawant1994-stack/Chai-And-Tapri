# CT-18 Supplier Invoice Reference

**Date:** 1 November 2026  
**Status:** Implemented; JVM-tested and focused API 37 emulator-tested. Full Android regression remains failed/flaky.

## Delivered

- Purchase receipts accept an optional supplier invoice reference entered by the shop operator.
- References are trimmed, limited to 80 characters, reject control characters, and are unique case-insensitively per supplier.
- The reference is included in the receipt audit description and displayed in the procurement receipt history.
- SQLite/SQLCipher schema v22 adds nullable `purchase_receipts.supplier_invoice_reference`. The v21→v22 migration is additive and preserves existing receipts and financial state.
- Invalid or duplicate references are rejected before receipt posting, so stock, supplier payable, receipt history and audit state remain unchanged.
- The receiving UI keeps the direct-receipt dialog open when validation returns an error.

This is a typed text reference supplied by staff. It does not scan, OCR, attach, authenticate, or verify a supplier invoice. It does not prove the physical delivery matched the bill.

## Verification

- Node: 52/52.
- Kotlin JVM: 55/55.
- Focused API 37 v21→v22 migration/preservation test: 1/1 passed.
- Focused API 37 procurement receiving UI rendering test: 1/1 passed.
- Debug APK, instrumentation APK, and `lintDebug`: PASS; lint reports 0 errors and 154 warnings.
- Full API 37 instrumentation attempt: 63/77 passed, 14 failed, 0 errors, 0 skipped. During the run, Pixel Launcher and later System UI displayed “isn't responding” and displaced the app; UI automation then could not find controls. After emulator reboot, an isolated partial-UPI screen test reproduced a System UI ANR. Treat the complete Android regression as NOT GREEN. The two CT-18 focused tests passed before the full-suite emulator degradation.

Evidence: `../test-evidence/2026-11-01/ct18-invoice-migration-focused.xml`, `ct18-invoice-receiving-ui-focused.xml`, `ct18-full-android-instrumentation.xml`, `ct18-full-gradle.log`, `ct18-payment-isolated-rerun.log`, and `ct18-build-unit-lint.log`.

## Remaining work

- Rerun the complete Android suite on a stable emulator. This run exhausted host memory (approximately 0.1 GB free while the emulator was active); closing the emulator restored approximately 2.7 GB. The emulator used a 1 GB configured AVD. No host security settings or AVD data were changed.
- Complete broader CT-18 receipt corrections, supplier invoice attachment/OCR only if separately designed, and receiving policy validation.
- Continue CAT-051B recovery and security gates, CAT-052 sync qualification, remaining Android screens, owner dashboard, physical Bluetooth/microphone and real-shop trials. CT-18 does not qualify the full product or financial module.
