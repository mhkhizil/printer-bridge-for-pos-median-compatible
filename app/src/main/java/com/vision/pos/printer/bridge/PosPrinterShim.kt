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

  function invoke(method, options) {
    var incoming = options || {};
    return new Promise(function (resolve, reject) {
      var id = 'pp-' + (++sequence);
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
      if (typeof entry.callback === 'function') { entry.callback(result); }
      entry.reject(new Error(result.error || 'Printer command failed'));
      return;
    }
    if (typeof entry.callback === 'function') { entry.callback(result); }
    entry.resolve(result);
  };

  window.__posPrinterReject = function (id, message) {
    var entry = pending[id];
    if (!entry) { return; }
    delete pending[id];
    var text = message;
    try { var parsed = JSON.parse(message); text = (parsed && parsed.error) || message; } catch (ignore) {}
    var error = new Error(text || 'Printer command failed');
    if (typeof entry.callback === 'function') { entry.callback({ success: false, error: error.message }); }
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
