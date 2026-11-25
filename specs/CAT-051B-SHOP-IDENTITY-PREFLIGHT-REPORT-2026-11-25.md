# CAT-051B Backup Shop-Identity Preflight

**Date:** 25 November 2026  
**Scope:** Authenticated backup verification and same-shop restore preflight on API 37  
**Result:** Focused tests passed; CAT-051B remains partial and not production-qualified.

## Defect corrected

Before this change, `PortableSnapshotBackup.verify()` authenticated the selected archive and checked that its header identity matched the encrypted payload, but did not compare that identity with the active local shop. The recovery UI could therefore show **Backup verified** and ask the owner to approve replacement before the restore engine rejected a different-shop archive. The restore cutover already preserved the active store, but the preflight message was late and misleading.

Verification now checks active-shop compatibility before returning verified metadata. A foreign backup for an active shop with business activity fails in preflight, before the replacement confirmation. If the local database key is unavailable, verification follows the same authenticated-header and trusted local-identity-binding check used by the supported key-loss recovery path. New/empty installations can still adopt a verified backup under existing policy.

## Evidence

Four focused API 37 instrumentation invocations passed **1/1 each**:

- `recoveryUiRejectsAuthenticatedBackupFromAnotherShopBeforeReplacementConfirmation`: actual MainActivity verification callback receives a synthetic SAF/MediaStore URI; it reports the identity mismatch before replacement, releases recovery busy state, and leaves the active shop order unchanged.
- `authenticatedBackupFromAnotherShopCannotReplaceExistingBusinessData`: service-level preflight rejects the foreign identity and the active order remains intact; restore also rejects it.
- `ownerBackupMenuCreatesEncryptedSyntheticBackupThroughSafUi`: same-shop backup export, document-picker selection, authentication, explicit replacement and restore still succeed with the preflight check enabled.
- `authenticatedBackupCanRecoverSameShopAfterDeviceKeyLossWithoutDiscardingCiphertext`: trusted same-shop local binding still permits preview and recovery after local key loss; mismatched binding and injected post-cutover failure remain rejected/rolled back.

Additional verification:

- Kotlin JVM: **96/96 passed**, with `testDebugUnitTest --rerun-tasks`.
- Debug APK and instrumentation APK assembly: **passed**.
- `lintDebug`: **0 errors, 189 warnings**.

The focused tests were run separately; the full Android connected suite was not run for this change. The recovery UI test uses the actual activity verification callback and a SAF-compatible content URI, but injects the selected URI through the callback rather than automating DocumentsUI for the mismatch case. Same-shop DocumentsUI export/restore was separately exercised by the existing end-to-end test.

## Remaining limits

This does not qualify physical cross-device or second-person recovery, actual low-storage exhaustion, hardware Keystore invalidation, every hard-interruption point, independent security review, complete role authorization, or production recovery. CAT-051B and the overall product remain open.
