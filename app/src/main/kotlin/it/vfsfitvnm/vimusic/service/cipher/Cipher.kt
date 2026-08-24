package it.vfsfitvnm.vimusic.service.cipher

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns what YouTube's web client returns into a URL that will actually stream.
 *
 * A web `/player` response carries `signatureCipher` rather than a playable `url`, and the `n`
 * query parameter is throttled until it has been run through the player script's transform. Both
 * are applied here; the proof-of-origin token is appended separately by the caller.
 */
object Cipher {
    private const val TAG = "Cipher"

    private val lock = Mutex()
    private var info: PlayerJs.Info? = null
    private var webView: CipherWebView? = null

    val signatureTimestamp: Int? get() = info?.signatureTimestamp

    /** Downloads and prepares the player script if that has not happened yet. */
    suspend fun ensureReady(context: Context, forceRefresh: Boolean = false): Boolean = lock.withLock {
        if (!forceRefresh && webView != null && info != null) return@withLock true

        if (forceRefresh) {
            webView?.close()
            webView = null
            info = null
        }

        val cacheDir = File(context.filesDir, "cipher")
        val loaded = PlayerJs.load(cacheDir, forceRefresh) ?: return@withLock false
        info = loaded

        if (loaded.signature == null && loaded.nTransform == null) {
            Log.e(TAG, "player script gave up neither transform")
            return@withLock false
        }

        webView = CipherWebView.create(context, loaded)
        webView != null
    }

    /**
     * Produces a playable URL for one adaptive format.
     *
     * @param url the plain `url`, when the response provided one
     * @param signatureCipher the `s`/`sp`/`url` bundle, when it did not
     */
    suspend fun resolve(url: String?, signatureCipher: String?): String? {
        val base = when {
            signatureCipher != null -> decipher(signatureCipher) ?: return null
            url != null -> url
            else -> return null
        }
        return applyNTransform(base)
    }

    private suspend fun decipher(signatureCipher: String): String? {
        val parameters = signatureCipher.split("&")
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else Uri.decode(part.substring(0, index)) to Uri.decode(part.substring(index + 1))
            }
            .toMap()

        val target = parameters["url"] ?: return null
        val signature = parameters["s"] ?: return target
        val key = parameters["sp"] ?: "signature"

        val deciphered = webView?.decipherSignature(signature)
        if (deciphered == null) {
            Log.e(TAG, "could not decipher signature")
            return null
        }

        val separator = if ('?' in target) "&" else "?"
        return target + separator + key + "=" + Uri.encode(deciphered)
    }

    /** Leaves the URL untouched when it carries no `n`, which is the case for the mobile clients. */
    private suspend fun applyNTransform(url: String): String {
        val current = runCatching { Uri.parse(url).getQueryParameter("n") }.getOrNull() ?: return url
        val transformed = webView?.transformN(current)
        if (transformed.isNullOrEmpty() || transformed == "null") {
            Log.w(TAG, "n transform unavailable, leaving parameter as-is")
            return url
        }
        return url.replace("n=" + Uri.encode(current), "n=" + Uri.encode(transformed))
            .replace("n=$current", "n=" + Uri.encode(transformed))
    }
}
