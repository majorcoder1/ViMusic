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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs YouTube's own player script so the signature and `n` transforms can be applied.
 *
 * The script is far too large and too obfuscated to reimplement, and it changes regularly, so it
 * is loaded verbatim into an offscreen WebView and the two entry points identified by [PlayerJs]
 * are called by name.
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
                    fun onReady(hasSignature: Boolean, hasN: Boolean) {
                        Log.d(TAG, "ready: signature=$hasSignature nTransform=$hasN")
                        if (resumed.compareAndSet(false, true)) ready.complete(hasSignature || hasN)
                    }
                }, "CipherReady")

                webView.loadDataWithBaseURL(
                    "file://${info.script.parentFile?.absolutePath}/",
                    buildHtml(info),
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

        private fun buildHtml(info: PlayerJs.Info): String {
            // The registry gives an expression with INPUT standing in for the argument; the
            // implementation itself comes from the player script loaded above.
            val signature = info.signatureExpression.replace("INPUT", "sig")
            val nTransform = info.nExpression.replace("INPUT", "n")

            return """<!DOCTYPE html>
<html><head>
<script src="${info.script.name}"></script>
<script>
  window._sigFn = function (sig) { try { return $signature; } catch (e) { return null; } };
  window._nFn  = function (n)   { try { return $nTransform; } catch (e) { return n; } };

  function decipherSig(requestId, value) {
    try {
      var out = window._sigFn(value);
      if (out === null || out === undefined) { CipherBridge.onError(requestId, "signature call returned null"); return; }
      CipherBridge.onResult(requestId, String(out));
    } catch (e) { CipherBridge.onError(requestId, String(e)); }
  }

  function transformN(requestId, value) {
    try { CipherBridge.onResult(requestId, String(window._nFn(value))); }
    catch (e) { CipherBridge.onError(requestId, String(e)); }
  }

  // Prove both actually run before declaring the WebView usable.
  var sigOk = false, nOk = false;
  try { sigOk = typeof window._sigFn === "function" && window._sigFn("AAAAAAAAAA") !== null; } catch (e) {}
  try { var probe = window._nFn("AAAAAAAAAA"); nOk = typeof probe === "string" && probe !== "AAAAAAAAAA"; } catch (e) {}
  CipherBridge.log("self-test: signature=" + sigOk + " nTransform=" + nOk);
  CipherReady.onReady(sigOk, nOk);
</script>
</head><body></body></html>"""
        }
    }
}
