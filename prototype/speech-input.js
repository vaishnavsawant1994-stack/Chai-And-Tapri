/* CAT-048 browser speech adapter. Recognition only fills a reviewable text field. */
(function (root, factory) {
  const api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.TapriSpeechInput = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  function createSpeechInput({ windowRef = globalThis, onStatus = () => {}, onTranscript = () => {} } = {}) {
    const Recognition = windowRef.SpeechRecognition || windowRef.webkitSpeechRecognition;
    let recognition = null;
    function supported() { return Boolean(Recognition); }
    function stop() { if (recognition) { recognition.stop(); recognition = null; } }
    function start({ lang = 'hi-IN' } = {}) {
      stop();
      if (!supported()) { onStatus('Speech recognition is unavailable in this browser. Type the command instead.', 'error'); return false; }
      try {
        recognition = new Recognition();
        recognition.lang = lang;
        recognition.continuous = false;
        recognition.interimResults = false;
        recognition.maxAlternatives = 1;
        recognition.onstart = () => onStatus('Listening… speak one command.', 'listening');
        recognition.onresult = event => {
          const transcript = event.results?.[0]?.[0]?.transcript?.trim();
          if (transcript) { onTranscript(transcript); onStatus('Speech captured. Review the text, then preview or run it.', 'review'); }
          else onStatus('No clear speech was recognized. Type the command instead.', 'error');
        };
        recognition.onerror = event => onStatus(event.error === 'not-allowed' || event.error === 'service-not-allowed' ? 'Microphone access was denied. Allow it in browser settings or type the command.' : `Speech recognition error (${event.error}). Type the command instead.`, 'error');
        recognition.onend = () => { recognition = null; };
        recognition.start();
        return true;
      } catch (error) {
        recognition = null;
        onStatus(`Could not start microphone (${error.name || 'error'}). Type the command instead.`, 'error');
        return false;
      }
    }
    return { supported, start, stop, setHandlers(handlers = {}) { onStatus = handlers.onStatus || (() => {}); onTranscript = handlers.onTranscript || (() => {}); } };
  }
  return { createSpeechInput };
});
