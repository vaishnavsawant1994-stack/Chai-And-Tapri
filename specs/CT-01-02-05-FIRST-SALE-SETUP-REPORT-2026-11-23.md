# CT-01/02/05 First-Sale Setup Report

**Date:** 23 November 2026  
**Scope:** First owner enrollment, encrypted shop profile setup, catalogue handoff, and owner Shop Home setup checklist.

## Implemented

- New owner enrollment now opens CT-02 Shop Setup. Existing owner/staff sign-in continues to the counter as before.
- Shop Setup adds **Save and configure products**. It validates and saves the shop profile through the existing encrypted `CounterModel` path, then opens the real CT-13/14 product catalogue only after a successful save.
- **Back to counter** now routes to the existing MainActivity, including when Shop Setup was reached during initial enrollment.
- CT-05 Shop Home shows real setup status for shop identity, sellable products, and physically confirmed opening stock. Owner actions route to the existing profile, catalogue, and stock-count screens. The checklist reads local state and does not create business records.

## Verification

Focused synthetic API 37 instrumentation, each run against `emulator-5554`:

| Journey | Result |
| --- | ---: |
| First owner enrollment → CT-02 Shop Setup | 1/1 passed |
| Save shop profile → product catalogue | 1/1 passed |
| Shop Home checklist → CT-16 stock count | 1/1 passed |

The tests assert profile persistence and no fabricated orders, payments, or stock movements during setup/navigation. XML evidence is in `../test-evidence/2026-11-23/ct01-02-first-sale-onboarding/`.

Android JVM tests: **69 passed, 0 failed, 0 skipped**. `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`: **BUILD SUCCESSFUL**; lint: **0 errors, 176 warnings**. Each of the three connected onboarding tests passed individually.

The subsequent full isolated API 37 regression run executed **120 tests: 82 passed, 38 failed, 0 errors, 0 skipped** over 39m55s. This run is not green. At least 16 failure messages explicitly show the Android Files/DocumentUI “isn't responding” dialog obscuring unrelated screens; other UI lookup failures occurred in the same run. After an emulator reboot cleared the persisted system-dialog state, `paymentScreenHistoryShowsCashAndCreditEvidenceWithoutClaimingVerification` passed 1/1 and `firstOwnerEnrollmentRoutesToShopSetup` passed 1/1. This supports an AVD/UI-state cause for the affected flows, but it does not erase or qualify the 120-test failures. Archive: `../test-evidence/2026-11-23/ct01-02-first-sale-onboarding/full-android-regression.xml` and `.html`; post-reboot focused XML files are in the same directory. Node/backend suites were not rerun because this change did not modify those sources.

## Scope limits

This connects owner enrollment to the existing setup and catalogue workflows; it does not itself finish a product entry, physical count, or sale. Existing CT-13/14-to-Quick-Sale tests cover that separate continuation. CT-01–05 remain partial overall, first-run setup has only synthetic emulator testing, and no usability, physical-device, shop, production, or security-review qualification is implied.
