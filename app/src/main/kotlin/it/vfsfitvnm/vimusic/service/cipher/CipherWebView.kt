package it.vfsfitvnm.vimusic.service.cipher

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs YouTube's own player script so the signature and `n` transforms can be applied.
 *
 * The script is far too large and too obfuscated to reimplement, and it changes regularly, so it
 * is loaded verbatim into an offscreen WebView. The two entry points are then found by *running*
 * candidates against it -- see `assets/cipher_discovery.js` -- rather than by trusting a name from
 * a list, so a player rotation repairs itself instead of waiting on someone to publish an update.
 */
class CipherWebView private constructor(private val webView: WebView) {
    private val pending = Collections.synchronizedMap(HashMap<String, Continuation<String?>>())
    private val counter = AtomicLong()

    @JavascriptInterface
    fun onResult(requestId: String, value: String) {
        pending.remove(requestId)?.resume(value)
    }

    @JavascriptInterface
    fun onError(requestId: String, message: String) {
        Log.e(TAG, "cipher error [$requestId]: $message")
        pending.remove(requestId)?.resume(null)
    }

    @JavascriptInterface
    fun log(message: String) {
        Log.d(TAG, "js: $message")
    }

    suspend fun decipherSignature(signature: String) = call("decipherSig", signature)

    suspend fun transformN(n: String) = call("transformN", n)

    private suspend fun call(function: String, argument: String): String? =
        withTimeoutOrNull(CALL_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val requestId = "${counter.incrementAndGet()}"
                    pending[requestId] = continuation
                    webView.evaluateJavascript(
                        "$function(${quote(requestId)}, ${quote(argument)})",
                        null
                    )
                }
            }
        }

    fun close() = runCatching { webView.destroy() }.let { }

    companion object {
        private const val TAG = "CipherWebView"
        private const val READY_TIMEOUT_MS = 30_000L
        private const val CALL_TIMEOUT_MS = 10_000L

        private fun quote(value: String) =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        @SuppressLint("SetJavaScriptEnabled")
        suspend fun create(context: Context, info: PlayerJs.Info): CipherWebView? =
            withContext(Dispatchers.Main) {
                val ready = CompletableDeferred<Boolean>()
                val resumed = AtomicBoolean(false)

                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    settings.blockNetworkLoads = true
                }
                val instance = CipherWebView(webView)
                webView.addJavascriptInterface(instance, "CipherBridge")

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            Log.e(TAG, "js console: ${message.message()}")
                        }
                        return true
                    }
                }

                webView.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onReady(hasSignature: Boolean, hasN: Boolean, signature: String?, nClass: String?) {
                        Log.i(
                            TAG,
                            "ready: signature=$hasSignature nTransform=$hasN " +
                                "sig=$signature nClass=$nClass"
                        )
                        // Only a call that passed the behavioural check is worth remembering.
                        if (hasSignature && hasN && signature != null && nClass != null &&
                            PlayerConfig.isWellFormed(signature, nClass)
                        ) PlayerJs.saveDiscovered(
                            context = context,
                            hash = info.playerHash,
                            signature = signature,
                            nClass = nClass,
                            sts = info.signatureTimestamp
                        )
                        // Both halves, not either: a signature-less cipher still plays nothing,
                        // and calling it ready would skip the registry refresh that can supply it.
                        if (resumed.compareAndSet(false, true)) ready.complete(hasSignature && hasN)
                    }
                }, "CipherReady")

                val prepared = withContext(Dispatchers.IO) { prepareScript(context, info) }
                if (prepared == null) {
                    Log.e(TAG, "could not prepare player script")
                    runCatching { webView.destroy() }
                    return@withContext null
                }

                webView.loadDataWithBaseURL(
                    "file://${prepared.parentFile?.absolutePath}/",
                    buildHtml(prepared.name, info),
                    "text/html",
                    "utf-8",
                    null
                )

                val ok = withTimeoutOrNull(READY_TIMEOUT_MS) { ready.await() } ?: false
                if (!ok) {
                    Log.e(TAG, "cipher webview never became ready")
                    runCatching { webView.destroy() }
                    return@withContext null
                }
                instance
            }

        /**
         * Writes a copy of the player script with the discovery routine appended inside its closure.
         *
         * The script body is wrapped in `(function(g){ ... })(_yt_player)`, so the signature
         * function only exists inside that closure. Code in a separate script tag cannot see it;
         * the routine has to be injected before the closing call so it shares the scope.
         */
        private fun prepareScript(context: Context, info: PlayerJs.Info): java.io.File? = runCatching {
            val source = info.script.readText()
            val discovery = context.assets.open("cipher_discovery.js")
                .bufferedReader().use { it.readText() }

            val anchor = "})(_yt_player);"
            val modified = if (source.contains(anchor)) {
                source.replace(anchor, "\n" + discovery + "\n" + anchor)
            } else {
                Log.w(TAG, "closure anchor not found; the signature search will not reach scope")
                source + "\n" + discovery
            }

            val out = java.io.File(info.script.parentFile, "player_prepared.js")
            out.writeText(modified)
            out
        }.onFailure { Log.e(TAG, "prepareScript failed: ${it.message}", it) }.getOrNull()

        /** Everything the discovery routine needs, handed over as one JSON literal. */
        private fun configJson(info: PlayerJs.Info): String = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                info.cachedSignature?.let { put("cachedSignature", it) }
                info.fallbackSignature?.let { put("fallbackSignature", it) }
                info.cachedNClass?.let { put("cachedNClass", it) }
                info.fallbackNClass?.let { put("fallbackNClass", it) }
                put("names", JsonArray(info.candidateNames.map(::JsonPrimitive)))
                put(
                    "pairs",
                    JsonArray(info.candidatePairs.map { pair -> JsonArray(pair.map(::JsonPrimitive)) })
                )
            }
        )

        private fun buildHtml(scriptName: String, info: PlayerJs.Info): String {
            return """<!DOCTYPE html>
<html><head>
<script src="SCRIPT_NAME"></script>
<script>
  function decipherSig(requestId, value) {
    try {
      if (typeof window._sigFn !== "function") { CipherBridge.onError(requestId, "no signature function"); return; }
      var out = window._sigFn(value);
      if (out === null || out === undefined) { CipherBridge.onError(requestId, "signature call returned null"); return; }
      CipherBridge.onResult(requestId, String(out));
    } catch (e) { CipherBridge.onError(requestId, String(e)); }
  }

  function transformN(requestId, value) {
    try {
      if (typeof window._nFn !== "function") { CipherBridge.onError(requestId, "no n function"); return; }
      CipherBridge.onResult(requestId, String(window._nFn(value)));
    } catch (e) { CipherBridge.onError(requestId, String(e)); }
  }

  var found = { signature: null, nClass: null };
  try {
    if (typeof window._ytCipherSetup === "function") found = window._ytCipherSetup(CONFIG_JSON);
    else CipherBridge.log("discovery routine missing; closure injection did not take");
  } catch (e) { CipherBridge.log("discovery threw: " + e); }

  // Prove both actually run, rather than merely exist.
  var sigOk = false, nOk = false;
  try { sigOk = typeof window._sigFn === "function" && window._sigFn("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") !== null; } catch (e) {}
  try {
    var probe = window._nFn ? window._nFn("abcdefghij0123456789") : null;
    nOk = typeof probe === "string" && probe !== "abcdefghij0123456789";
  } catch (e) {}

  CipherBridge.log("discovery: sig=" + found.signature + " nClass=" + found.nClass +
                   " selftest signature=" + sigOk + " nTransform=" + nOk);
  CipherReady.onReady(sigOk, nOk, found.signature, found.nClass);
</script>
</head><body></body></html>"""
                .replace("SCRIPT_NAME", scriptName)
                .replace("CONFIG_JSON", configJson(info))
        }
    }
}
