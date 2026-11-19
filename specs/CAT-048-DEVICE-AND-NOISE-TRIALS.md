# CAT-048B/C — Device and Noisy-Counter Trial Protocol

## Status

Protocol and JSONL validator prepared. **No physical device or shop trials have been run or recorded.** Do not treat the protocol or automated parser tests as evidence of microphone recognition accuracy.

## Trial record

Each trial is one attempted command. Record one JSON object per line with these fields:

```json
{"trialId":"T-001","date":"YYYY-MM-DD","locale":"hi-IN","deviceModel":"","osVersion":"","browser":"","browserVersion":"","microphone":"built-in|wired|bluetooth model","noiseCondition":"quiet|fan|traffic|kitchen|music|multiple-speakers","intendedCommand":"","recognizedTranscript":"","parsedAction":"","confirmation":"accepted|edited|rejected|not-shown","actionCorrect":"yes|no|not-applicable","committed":"yes|no","unexpectedMutation":"yes|no","duplicateCommit":"yes|no","recoveryOutcome":"not-needed|typed-fallback|retry-success|failed","latencyMs":0,"notes":""}
```

Do not capture customer speech, names, phone numbers, payment credentials, or raw audio. Use scripted commands, synthetic product catalogues, test orders, and a controlled/no-customer environment. Record only command-attempt outcomes and device metadata. Stop a trial if bystanders' conversations are being captured.

Validate and summarize a JSONL file with:

`node prototype/speech-trial-log.js path/to/trials.jsonl`

The command validates required fields and categories, rejects repeated trial IDs, and reports exact transcript rate, correct-action rate, committed trials, duplicate commits, unexpected mutations, and median/p95 latency. An unexpected mutation makes the command exit nonzero. Recovery outcome and state-transition details remain inspectable in each record; exact transcript rate alone is not a safety metric. Empty datasets report `not_evaluated` with null rates, never 100%.

## Trial matrix

For each browser/device/microphone combination, perform at least 10 repetitions per command and 10 negative/ambiguous utterances in each condition. Include Hindi (`hi-IN`), Marathi (`mr-IN`), English India (`en-IN`), and realistic Hinglish mixtures as separate labels. Conditions: quiet baseline, fan, traffic recording at safe volume, kitchen-equipment recording, music, and multiple scripted speakers. Repeat permission allow/deny, microphone unavailable, recognition error, user cancellation, stop/restart, repeated command result, and typed fallback cases.

Use synthetic catalogue items with deliberately confusable names, quantities (1/2/3/12/50), order references, ready/given, cash amount, UPI pending, unknown product, removal, and ambiguous references. Include background conversational phrases that resemble a command but are not directed at the operator.

## Report two scorecards separately

**Recognition:** exact transcript, product/quantity/order-reference word error, recognition failure, latency median/p95, and edit/retry rate, grouped by locale, hardware, and noise condition.

**Transaction safety:** correct proposed action, operator acceptance/edit/rejection, command misroute, committed wrong action, unexpected financial/stock mutation, duplicate commit, and successful typed fallback. Any unexpected financial or stock mutation is a hard failure. Do not combine this with word accuracy into a single score.

## Exit criteria

- Zero unintended business mutations in the qualification set.
- Every ambiguous or unknown-product trial is rejected or held for operator correction.
- Repeated recognition results cannot duplicate a committed transaction (use the engine idempotency path in the eventual integration).
- Reported performance includes per-condition results and evidence files, not only an overall average.
- Test set, target devices, latency budget, and minimum recognition/action-correctness thresholds are agreed before pilot trials begin.

Until those criteria are met, CAT-048B/C remain pending. This harness is for controlled evaluation and does not itself perform microphone capture or guarantee privacy properties of the browser's recognition service.

## Android trial handling

The current protocol's `browser` and `browserVersion` fields describe the original browser trials. For a native Android run, record `browser` as `Android app` and `browserVersion` as the app version, and use the actual headset/microphone model in `microphone`. Do not mark Android trials complete based on the debug APK build or simulated transcript controls. The Android prototype uses one-shot system recognition; CAT-049 headset and continuous-session qualification require separate evidence. No physical Android trials have been collected as of 28 October 2026.

## Native contract fixture update

A shared TSV fixture now checks 12 representative voice-parser inputs in both JavaScript and Kotlin, including Devanagari creation and a product-specific partial handover. It verifies parser intent fields only; it is not a full transaction-engine behavioral parity suite. Latest automated counts: 43 Node tests and 17 Kotlin JVM tests. No physical speech trials have been recorded.

## Emulator-run update — 29 October 2026

No physical handset or shop trials were performed. The API 37 emulator is now available and permission-denial/typed-fallback behavior was exercised there. This is not CAT-048B/C physical device or noisy-counter evidence. The trial dataset remains empty and accuracy is not evaluated. Continue physical qualification with scripted commands and record only observed trials.
