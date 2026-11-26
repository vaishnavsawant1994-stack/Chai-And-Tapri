# CT-10 observed cash + UPI split live E2E — 26 November 2026

## Scope

A focused API 37 emulator scenario passed through the actual Android persistence/outbox layer, authenticated HTTPS service, PostgreSQL 17, and owner dashboard projection. The run used a disposable database, restricted application login, loopback-only test server, synthetic account/product/order data, and a dedicated test-only localhost TLS certificate.

## Scenario and result

The Android test configured one synthetic tea product at 1,500 paise, confirmed opening stock of three, created an order, marked it ready, handed it over, and recorded a split payment of 500 paise cash and 1,000 paise observed UPI. It then uploaded the durable outbox through `SyncRunner` and queried the authenticated owner overview and cashflow endpoints.

- Focused API 37 instrumentation: **1/1 passed**, 0 failures, 0 errors, 0 skips.
- Six ordered business events were acknowledged; the local outbox was empty after sync.
- Owner overview reported one recorded order, 500 paise cash, 1,000 paise staff-declared UPI, and zero pending UPI.
- Owner cashflow reported 500 paise declared cash inflow and 1,000 paise declared UPI inflow.
- An independent aggregate query against the disposable database returned `500|1000|0` for staff-declared cash, staff-declared UPI, and pending UPI; one unique posted order was present.
- Android database cleanup, HTTPS process shutdown, ADB reverse removal, and disposable PostgreSQL container removal completed.

## Test TLS fixture

Added a reproducible self-signed localhost certificate and PKCS#12 pair under Android instrumentation assets. The certificate is for the loopback E2E test service only; its private key is test material and must never be used by a deployed service. The password `tapri-e2e-only` is intentionally a test fixture value, not a production secret. The existing transport integration fixture remains unchanged.

## Qualification boundary

The UPI amount records what staff observed. It is not payment-provider or bank verification. This result qualifies one synthetic Android-to-local-PostgreSQL flow only. It does not qualify a deployed backend, multi-device synchronization, physical payment integrations, broad Android regression, field/shop use, Bluetooth audio, or production security.

Evidence: ignored emulator artifacts under `../test-evidence/2026-11-26/ct10-live-split-e2e/`, including `focused-instrumentation.xml` and `qualification.txt`.
