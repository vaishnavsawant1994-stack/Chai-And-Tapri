#!/usr/bin/env node
/* Validate and summarize CAT-048 device/noise trial JSONL records. */
'use strict';

const fs = require('node:fs');

const required = ['trialId','date','locale','deviceModel','osVersion','browser','browserVersion','microphone','noiseCondition','intendedCommand','recognizedTranscript','parsedAction','confirmation','actionCorrect','committed','unexpectedMutation','duplicateCommit','recoveryOutcome','latencyMs','notes'];
const classifications = {
  locale: new Set(['hi-IN','mr-IN','en-IN','mixed-hi-en','mixed-mr-en']),
  noiseCondition: new Set(['quiet','fan','traffic','kitchen','music','multiple-speakers']),
  confirmation: new Set(['accepted','edited','rejected','not-shown']),
  actionCorrect: new Set(['yes','no','not-applicable']),
  committed: new Set(['yes','no']), unexpectedMutation: new Set(['yes','no']), duplicateCommit: new Set(['yes','no']),
  recoveryOutcome: new Set(['not-needed','typed-fallback','retry-success','failed'])
};
function validate(record, lineNumber) {
  const missing = required.filter(key => record[key] === undefined || record[key] === null || (record[key] === '' && !['recognizedTranscript','notes'].includes(key)));
  if (missing.length) return `line ${lineNumber}: missing ${missing.join(', ')}`;
  if (!Number.isFinite(record.latencyMs) || record.latencyMs < 0) return `line ${lineNumber}: latencyMs must be a nonnegative number`;
  for (const [key, allowed] of Object.entries(classifications)) if (!allowed.has(record[key])) return `line ${lineNumber}: invalid ${key} classification`;
  return null;
}
function validateDataset(records) {
  const ids = new Set();
  for (let i=0; i<records.length; i++) {
    const error = validate(records[i],i+1);
    if (error) return error;
    if (ids.has(records[i].trialId)) return `line ${i+1}: repeated trialId ${records[i].trialId}`;
    ids.add(records[i].trialId);
  }
  return null;
}
function summarize(records) {
  const total = records.length;
  const exactTranscript = records.filter(row => row.recognizedTranscript.trim().toLocaleLowerCase() === row.intendedCommand.trim().toLocaleLowerCase()).length;
  const actionScored = records.filter(row => row.actionCorrect !== 'not-applicable');
  const actionCorrect = actionScored.filter(row => row.actionCorrect === 'yes').length;
  const unintended = records.filter(row => row.unexpectedMutation === 'yes').length;
  const duplicateCommit = records.filter(row => row.duplicateCommit === 'yes').length;
  const committed = records.filter(row => row.committed === 'yes').length;
  const latencies = records.map(row => row.latencyMs).sort((a,b) => a-b);
  const percentile95LatencyMs = total ? latencies[Math.ceil(total * 0.95) - 1] : null;
  const medianLatencyMs = total ? latencies[Math.floor((total - 1) / 2)] : null;
  return { status: total ? 'evaluated' : 'not_evaluated', trials: total, exactTranscriptRate: total ? exactTranscript / total : null, correctActionRate: actionScored.length ? actionCorrect / actionScored.length : null, unexpectedMutationCount: unintended, duplicateCommitCount: duplicateCommit, committedCount: committed, medianLatencyMs, percentile95LatencyMs };
}

if (require.main === module) {
  const file = process.argv[2];
  if (!file) { console.error('Usage: node prototype/speech-trial-log.js <trials.jsonl>'); process.exit(2); }
  let lines;
  try { lines = fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(line => line.trim()); }
  catch (error) { console.error(`Cannot read trial file: ${error.message}`); process.exit(2); }
  const records = [];
  const errors = [];
  lines.forEach((line,index) => {
    try {
      const row = JSON.parse(line);
      const error = validate(row,index + 1);
      if (error) errors.push(error); else records.push(row);
    } catch (error) { errors.push(`line ${index + 1}: invalid JSON (${error.message})`); }
  });
  const datasetError = validateDataset(records);
  if (datasetError) errors.push(datasetError);
  if (errors.length) { errors.forEach(error => console.error(error)); process.exit(1); }
  console.log(JSON.stringify(summarize(records),null,2));
  if (records.some(row => row.unexpectedMutation === 'yes')) process.exitCode = 1;
}
module.exports = { required, validate, validateDataset, summarize };
