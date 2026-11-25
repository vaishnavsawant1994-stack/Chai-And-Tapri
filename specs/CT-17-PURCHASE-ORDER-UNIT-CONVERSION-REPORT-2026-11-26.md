# CT-17 Purchase-Order Pack Conversion — 26 November 2026

## Implemented

Purchase-order draft entry now accepts each product's configured purchase unit, such as crates, and converts it to base-unit quantities for the existing encrypted purchase-order records. Costs remain explicit estimates per base unit. The draft screen describes the conversion; the order list renders both pack and base quantities when exact. Receipt entry remains explicitly in base units, so a short or partial delivery can be recorded without pretending it is a complete pack.

The original purchase quantity, pack label, conversion factor, converted quantity, and base-unit label are included in the audited draft event. Reusing existing base-unit line fields avoids a schema migration and keeps prior receipt constraints intact. Product base/purchase unit and factor changes are rejected once that product appears on an order, preventing an existing draft from being reinterpreted under a new conversion.

## Verification

- Kotlin JVM: **104/104 passed**, 0 failures/errors/skips. Includes two-crate draft → 24 bottle line, estimated total, no stock/payable effects, audit detail, conversion freeze, one-crate partial receipt → 12 bottle stock and corresponding supplier payable.
- `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug`: **PASS**; lint 0 errors / 197 warnings.
- Android UI runtime test: **not run** for this PO screen slice. Two attempts on the prior purchase-conversion UI encountered an Android Launcher/System UI ANR before the target screen; source compiles, but UI behavior remains unqualified.
- Node/backend suites were not rerun; no JavaScript/backend code changed.

## Limits

This does not add supplier transmission, automated ordering, package-price invoice parsing, fractional pack factors, order editing, or stock lot tracking. Drafts stay local and are not evidence that an order was sent. Receiving continues in base units and the operator confirms actual received quantities. CT-17 and overall procurement remain partial.
