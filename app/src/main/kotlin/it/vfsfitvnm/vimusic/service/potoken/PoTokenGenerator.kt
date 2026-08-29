package it.vfsfitvnm.vimusic.service.potoken

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Mints the two proof-of-origin tokens a playback needs.
 *
 * Ported from Metrolist (GPL-3.0). The pair is asymmetric and the order matters: the token bound
 * to the *session* goes on the `/player` request, and the one bound to the *video* is appended to
 * the resulting stream URL. Swapping them makes YouTube answer `UNPLAYABLE`.
 */
class PoTokenGenerator(private val context: Context) {
    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
    private var webViewBadImpl = false

    private val lock = Mutex()
    private var sessionId: String? = null
    private var streamingPot: String? = null
    private var generator: PoTokenWebView? = null

    /**
     * Blocking entry point for the playback path, which is a synchronous callback.
     *
     * Prefer [awaitWebClientPoToken] anywhere a coroutine is already available — warming up ahead
     * of time is what keeps this call cheap, since the expensive part (BotGuard cold start) then
     * has already happened.
     */
    fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        if (!webViewSupported || webViewBadImpl) return null
        return try {
            runBlocking { awaitWebClientPoToken(videoId, sessionId) }
        } catch (e: Exception) {
            Log.e(TAG, "poToken generation failed: ${e.message}", e)
            null
        }
    }

    /** Mints both tokens, reusing the existing WebView when one is already warm. */
    suspend fun awaitWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        // Returning null here used to be silent, which made "playback quietly downgraded itself"
        // impossible to tell apart from "the token was never asked for".
        if (!webViewSupported || webViewBadImpl) {
            Log.w(TAG, "poToken skipped: webViewSupported=$webViewSupported badImpl=$webViewBadImpl")
            return null
        }

        return try {
            val result = withTimeout(POTOKEN_TIMEOUT_MS) {
                generate(videoId, sessionId, forceRecreate = false)
            }
            if (result == null) Log.w(TAG, "poToken generator returned nothing")
            result
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "outer timeout wrapper fired: ${e.message}", e)
            // A WebView whose sandboxed process gets culled leaves this hanging forever; cap it so
            // playback can fall through to the non-PoToken clients instead of stalling.
            Log.w(TAG, "poToken generation timed out; continuing without one")
            lock.withLock {
                runCatching { withContext(Dispatchers.Main) { generator?.close() } }
                generator = null
                streamingPot = null
                this@PoTokenGenerator.sessionId = null
            }
            null
        } catch (e: BadWebViewException) {
            Log.e(TAG, "WebView is broken, disabling poTokens for this session", e)
            webViewBadImpl = true
            null
        } catch (e: Exception) {
            Log.e(TAG, "poToken generation failed: ${e.message}", e)
            null
        }
    }

    private suspend fun generate(videoId: String, session: String, forceRecreate: Boolean): PoTokenResult {
        val (webView, sessionPot, recreated) = lock.withLock {
            val shouldRecreate = forceRecreate || generator == null || generator!!.isExpired ||
                generator!!.isDead || sessionId != session

            if (shouldRecreate) {
                runCatching { withContext(Dispatchers.Main) { generator?.close() } }
                // Clear before the fallible steps so a failure can't pair a fresh session id with
                // a stale token on the next call.
                generator = null
                streamingPot = null
                sessionId = null

                val fresh = PoTokenWebView.getNewPoTokenGenerator(context)
                // The session-bound token has to be minted exactly once, before any video ones.
                val pot = try {
                    fresh.generatePoToken(session)
                } catch (t: Throwable) {
                    runCatching { fresh.close() }
                    throw t
                }

                generator = fresh
                streamingPot = pot
                sessionId = session
            }

            Triple(generator!!, streamingPot!!, shouldRecreate)
        }

        val videoPot = try {
            webView.generatePoToken(videoId)
        } catch (t: Throwable) {
            if (recreated) throw t
            // The WebView may have been torn down in the background; rebuild once and retry.
            Log.e(TAG, "Failed to obtain poToken, retrying with a fresh WebView", t)
            return generate(videoId, session, forceRecreate = true)
        }

        return PoTokenResult(playerRequestPoToken = sessionPot, streamingDataPoToken = videoPot)
    }

    private companion object {
        const val TAG = "PoTokenGenerator"

        // BotGuard cold start is ~2-5s in practice; this leaves slack on a slow device without
        // making the user wait too long before the fallback clients take over.
        const val POTOKEN_TIMEOUT_MS = 20_000L
    }
}
