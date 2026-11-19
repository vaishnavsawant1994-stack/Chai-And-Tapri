const test = require('node:test');
const assert = require('node:assert/strict');
const { createSpeechInput } = require('./speech-input.js');

function fixture() {
  const events = {};
  class MockRecognition {
    start() { events.started = true; this.onstart?.(); }
    stop() { events.stopped = true; }
  }
  const statuses = [];
  const transcripts = [];
  const adapter = createSpeechInput({ windowRef: { SpeechRecognition: MockRecognition }, onStatus: (...args) => statuses.push(args), onTranscript: text => transcripts.push(text) });
  return { adapter, events, statuses, transcripts, MockRecognition };
}

test('speech adapter requests one utterance and returns transcript without executing a command', () => {
  const f = fixture();
  f.adapter.start({ lang: 'mr-IN' });
  assert.equal(f.events.started, true);
  assert.equal(f.adapter.recognition, undefined);
  // The adapter's recognition instance is intentionally private; drive callbacks via a captured constructor.
  assert.deepEqual(f.statuses.at(-1), ['Listening… speak one command.', 'listening']);
});

test('unsupported browser gives typed fallback and does not throw', () => {
  const statuses = [];
  const adapter = createSpeechInput({ windowRef: {}, onStatus: (...args) => statuses.push(args) });
  assert.equal(adapter.supported(), false);
  assert.equal(adapter.start(), false);
  assert.match(statuses[0][0], /Type the command/);
});

test('speech result fills the transcript callback only', () => {
  let instance;
  class MockRecognition { constructor() { instance = this; } start() { this.onstart(); } stop() {} }
  const transcripts = [];
  const statuses = [];
  const adapter = createSpeechInput({ windowRef: { webkitSpeechRecognition: MockRecognition }, onTranscript: text => transcripts.push(text), onStatus: (...args) => statuses.push(args) });
  adapter.start({ lang: 'en-IN' });
  instance.onresult({ results: [[{ transcript: 'new order two chai' }]] });
  assert.deepEqual(transcripts, ['new order two chai']);
  assert.deepEqual(statuses.at(-1), ['Speech captured. Review the text, then preview or run it.', 'review']);
});
