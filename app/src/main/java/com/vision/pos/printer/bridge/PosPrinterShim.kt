package com.vision.pos.printer.bridge

/**
 * JS shim injected on every page load. It adapts the callback-only
 * `@JavascriptInterface` object into the exact contract the web app expects:
 *
 *     window.median.posPrinter.{connect,disconnect,discover,printRaw}(options)
 *
 * Each method returns a Promise (and also invokes `options.callback`), matching
 * the Median JavaScript Bridge convention used by the POS app.
 */
object PosPrinterShim {
    val JS: String = """
(function () {
  if (typeof window.__posPrinterNative === 'undefined') { return; }
  if (window.__posPrinterShimReady) { return; }
  window.__posPrinterShimReady = true;

  var pending = {};
  var sequence = 0;
  // Request ids are tagged with this document's epoch: a reply that belongs to a page
  // the operator already navigated away from can then never settle a new request.
  var epoch = window.__posPrinterEpoch ||
    ('pp-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8) + '-');
  window.__posPrinterEpoch = epoch;

  function invoke(method, options) {
    var incoming = options || {};
    return new Promise(function (resolve, reject) {
      var id = epoch + (++sequence);
      pending[id] = { resolve: resolve, reject: reject, callback: incoming.callback };
      var payload = {};
      for (var key in incoming) {
        if (key === 'callback') { continue; }
        if (typeof incoming[key] === 'undefined') { continue; }
        payload[key] = incoming[key];
      }
      try {
        window.__posPrinterNative[method](id, JSON.stringify(payload));
      } catch (error) {
        delete pending[id];
        reject(error);
      }
    });
  }

  window.__posPrinterResolve = function (id, resultJson) {
    var entry = pending[id];
    if (!entry) { return; }
    delete pending[id];
    var result;
    try { result = resultJson ? JSON.parse(resultJson) : undefined; } catch (ignore) { result = undefined; }
    if (result && result.success === false) {
      try { if (typeof entry.callback === 'function') { entry.callback(result); } } catch (ignore) {}
      entry.reject(new Error(result.error || 'Printer command failed'));
      return;
    }
    try { if (typeof entry.callback === 'function') { entry.callback(result); } } catch (ignore) {}
    entry.resolve(result);
  };

  window.__posPrinterReject = function (id, message) {
    var entry = pending[id];
    if (!entry) { return; }
    delete pending[id];
    var text = message;
    try { var parsed = JSON.parse(message); text = (parsed && typeof parsed.error === 'string' && parsed.error) || message; } catch (ignore) {}
    var error = new Error(text || 'Printer command failed');
    // The callback runs first (unchanged), but a throw in POS code must not leave the
    // promise unsettled: the entry is already gone, so nothing could ever settle it.
    try {
      if (typeof entry.callback === 'function') { entry.callback({ success: false, error: error.message }); }
    } catch (ignore) {}
    entry.reject(error);
  };

  window.median = window.median || {};
  window.median.posPrinter = {
    connect: function (options) { return invoke('connect', options); },
    disconnect: function (options) { return invoke('disconnect', options); },
    discover: function (options) { return invoke('discover', options); },
    printRaw: function (options) { return invoke('printRaw', options); }
  };
})();
""".trimIndent()
}
