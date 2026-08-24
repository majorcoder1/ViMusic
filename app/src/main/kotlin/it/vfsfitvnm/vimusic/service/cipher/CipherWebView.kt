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
            val signatureExport = info.signature?.let { "window._sigFn = ${it.functionName};" } ?: ""
            val signatureConstant = info.signature?.constantArg?.toString() ?: "null"
            val nExport = info.nTransform?.let {
                val base = it.functionName
                if (it.index != null) "window._nFn = $base[${it.index}];" else "window._nFn = $base;"
            } ?: ""

            return """<!DOCTYPE html>
<html><head>
<script src="${info.script.name}"></script>
<script>
  window._sigConst = $signatureConstant;
  try { $signatureExport } catch (e) { CipherBridge.log("sig export failed: " + e); }
  try { $nExport } catch (e) { CipherBridge.log("n export failed: " + e); }

  function decipherSig(requestId, value) {
    try {
      var fn = window._sigFn;
      if (typeof fn !== "function") { CipherBridge.onError(requestId, "no signature function"); return; }
      var out = (window._sigConst === null) ? fn(value) : fn(window._sigConst, value);
      CipherBridge.onResult(requestId, String(out));
    } catch (e) { CipherBridge.onError(requestId, String(e)); }
  }

  function transformN(requestId, value) {
    try {
      var fn = window._nFn;
      if (typeof fn !== "function") { CipherBridge.onError(requestId, "no n function"); return; }
      CipherBridge.onResult(requestId, String(fn(value)));
    } catch (e) { CipherBridge.onError(requestId, String(e)); }
  }

  CipherReady.onReady(typeof window._sigFn === "function", typeof window._nFn === "function");
</script>
</head><body></body></html>"""
        }
    }
}
