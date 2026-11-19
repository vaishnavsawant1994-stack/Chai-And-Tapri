const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { required, validate, validateDataset, summarize } = require('./speech-trial-log.js');

function record(overrides = {}) {
  return Object.assign(Object.fromEntries(required.map(key => [key, ''])), {
    trialId:'T-001', date:'2026-10-28', locale:'hi-IN', deviceModel:'test phone', osVersion:'test OS', browser:'test browser', browserVersion:'1', microphone:'built-in', noiseCondition:'quiet', intendedCommand:'do cutting chai', recognizedTranscript:'do cutting chai', parsedAction:'order.create', confirmation:'accepted', actionCorrect:'yes', committed:'no', unexpectedMutation:'no', duplicateCommit:'no', recoveryOutcome:'not-needed', latencyMs:700, notes:''
  }, overrides);
}

test('valid device trial record passes schema validation', () => assert.equal(validate(record(),1), null));
test('missing required field is identified', () => assert.match(validate(record({browser:''}),1), /missing browser/));
test('recognized transcript may be empty for a no-match attempt', () => assert.equal(validate(record({recognizedTranscript:''}),1), null));
test('invalid classification is rejected', () => assert.match(validate(record({noiseCondition:'very noisy'}),1), /invalid noiseCondition/));
test('repeated trial IDs are rejected', () => assert.match(validateDataset([record(),record()]), /repeated trialId/));
test('empty dataset reports not evaluated and no accuracy percentage', () => {
  const result = summarize([]);
  assert.equal(result.status,'not_evaluated');
  assert.equal(result.exactTranscriptRate,null);
});
test('CLI rejects malformed JSONL', () => {
  const file = path.join(os.tmpdir(),`cat048-malformed-${process.pid}.jsonl`);
  fs.writeFileSync(file,'{not json}\n');
  const run = spawnSync(process.execPath,[path.join(__dirname,'speech-trial-log.js'),file],{encoding:'utf8'});
  fs.rmSync(file,{force:true});
  assert.notEqual(run.status,0);
  assert.match(run.stderr,/invalid JSON/);
});
