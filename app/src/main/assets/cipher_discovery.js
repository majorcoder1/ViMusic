// Injected inside YouTube's player closure, immediately before `})(_yt_player);`.
//
// Being inside the closure is the whole point: the signature descrambler is a closure-local
// with a minified name, so it can only be reached from code that shares its scope. A direct
// eval() here sees that scope; the same eval from a separate <script> tag would not.
//
// Nothing is trusted. A cached or registry-supplied call is verified by running it, and falls
// through to a fresh search if it no longer descrambles -- which is what makes playback survive
// a player rotation without anyone shipping an update.
;window._ytCipherSetup = function (cfg) {
  var G = (typeof g !== "undefined") ? g
        : (typeof _yt_player !== "undefined") ? _yt_player
        : null;

  // Two probes of different lengths. Descrambling is reverse/swap/splice, so the output is a
  // permutation of the input minus a fixed number of characters -- a shape almost nothing else
  // in a two-megabyte script accidentally matches.
  var PROBE_A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  var PROBE_B = "zyxwvutsrqponmlkjihgfedcbaZYXWVUTSRQPONMLKJIHGFEDCBA";

  function isPermutationOf(input, result) {
    if (typeof result !== "string" || result === input) return false;
    if (result.length > input.length || result.length < input.length - 12) return false;
    var allowed = {}, i;
    for (i = 0; i < input.length; i++) allowed[input[i]] = true;
    var seen = {};
    for (i = 0; i < result.length; i++) {
      var ch = result[i];
      if (!allowed[ch] || seen[ch]) return false;
      seen[ch] = true;
    }
    return true;
  }

  function isDescrambler(fn, a, b) {
    try {
      var ra = fn(a, b, PROBE_A);
      if (!isPermutationOf(PROBE_A, ra)) return false;
      var rb = fn(a, b, PROBE_B);
      if (!isPermutationOf(PROBE_B, rb)) return false;
      // The same number of characters must be dropped regardless of input length.
      return (PROBE_A.length - ra.length) === (PROBE_B.length - rb.length);
    } catch (e) { return false; }
  }

  function resolveSignature(expression) {
    var m = /^([A-Za-z0-9_$]{1,8})\((\d{1,6}),(\d{1,6}),INPUT\)$/.exec(expression || "");
    if (!m) return null;
    var fn;
    try { fn = eval(m[1]); } catch (e) { return null; }
    if (typeof fn !== "function") return null;
    var a = parseInt(m[2], 10), b = parseInt(m[3], 10);
    return isDescrambler(fn, a, b) ? { fn: fn, a: a, b: b, expression: expression } : null;
  }

  function searchSignature(names, pairs) {
    for (var i = 0; i < names.length; i++) {
      var fn;
      try { fn = eval(names[i]); } catch (e) { continue; }
      if (typeof fn !== "function") continue;
      for (var j = 0; j < pairs.length; j++) {
        var a = pairs[j][0], b = pairs[j][1];
        if (isDescrambler(fn, a, b)) {
          return { fn: fn, a: a, b: b, expression: names[i] + "(" + a + "," + b + ",INPUT)" };
        }
      }
    }
    return null;
  }

  // The n transform is applied through the player's own URL class, which is how the player
  // itself reaches it -- construct one, read the parameter back out, see if it changed.
  var PROBE_N = "abcdefghij0123456789";

  function transformsN(ctor) {
    try {
      // Shape check before construction. Without it the search instantiates every class the
      // player exposes, and a few thousand of those log parse errors from their own internals
      // on the way to throwing -- noisy, and far slower than it needs to be.
      var proto = ctor.prototype;
      if (!proto || typeof proto.get !== "function") return false;

      var u = new ctor("https://x.googlevideo.com/videoplayback?n=" + PROBE_N, true);
      if (!u || typeof u.get !== "function") return false;
      var t = u.get("n");
      return typeof t === "string" && t.length > 0 && t !== PROBE_N;
    } catch (e) { return false; }
  }

  function resolveNClass(name) {
    if (!name || !G) return null;
    try {
      var C = G[name];
      return (typeof C === "function" && transformsN(C)) ? { ctor: C, name: name } : null;
    } catch (e) { return null; }
  }

  function searchNClass() {
    if (!G) return null;
    var keys;
    try { keys = Object.getOwnPropertyNames(G); } catch (e) { return null; }
    for (var i = 0; i < keys.length; i++) {
      try {
        var C = G[keys[i]];
        if (typeof C === "function" && transformsN(C)) return { ctor: C, name: keys[i] };
      } catch (e) {}
    }
    return null;
  }

  // Order matters: a verified cache is free, searching costs tens of milliseconds once per
  // player rotation, and the curated list is last because depending on it is the thing this
  // whole routine exists to avoid.
  var signature = resolveSignature(cfg.cachedSignature)
               || searchSignature(cfg.names || [], cfg.pairs || [])
               || resolveSignature(cfg.fallbackSignature);

  var nTransform = resolveNClass(cfg.cachedNClass)
                || searchNClass()
                || resolveNClass(cfg.fallbackNClass);

  window._sigFn = signature ? function (s) {
    try { return signature.fn(signature.a, signature.b, s); } catch (e) { return null; }
  } : null;

  window._nFn = nTransform ? function (n) {
    try {
      var u = new nTransform.ctor("https://x.googlevideo.com/videoplayback?n=" + n, true);
      var t = u.get("n");
      return (t && t !== n) ? t : n;
    } catch (e) { return n; }
  } : null;

  return {
    signature: signature ? signature.expression : null,
    nClass: nTransform ? nTransform.name : null
  };
};
