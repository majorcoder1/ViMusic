package it.vfsfitvnm.vimusic.service.cipher

import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Locates YouTube's player script and picks out the three things playback needs from it.
 *
 * The web clients hand back `signatureCipher` rather than a usable URL, and the `n` query
 * parameter has to be run through a transform before the CDN will serve more than a preview.
 * Both transforms live in this script, so it is downloaded and the relevant entry points are
 * identified here; executing them is [CipherWebView]'s job.
 *
 * The extraction patterns come from Metrolist (GPL-3.0), which tracks YouTube's obfuscation as it
 * changes; they are deliberately tried in order from most to least specific.
 */
object PlayerJs {
    private const val TAG = "PlayerJs"
    private val http = OkHttpClient.Builder().build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    data class Info(
        val signatureTimestamp: Int,
        val signature: Signature?,
        val nTransform: NTransform?,
        val script: File
    )

    /** Some builds pass a constant as the first argument, e.g. `hJ(6, decodeURIComponent(h.s))`. */
    data class Signature(val functionName: String, val constantArg: Int?)

    /** Some builds reach the transform through an array slot, e.g. `Xva[3](n)`. */
    data class NTransform(val functionName: String, val index: Int?)

    private val jsUrlPatterns = listOf(
        Regex(""""jsUrl"\s*:\s*"([^"]+)""""),
        Regex("""PLAYER_JS_URL"\s*:\s*"([^"]+)"""")
    )

    private val stsPatterns = listOf(
        Regex("""signatureTimestamp['":\s]+(\d+)"""),
        Regex("""sts['":\s]+(\d+)""")
    )

    private val signaturePatterns = listOf(
        Regex("""&&\s*\(\s*[a-zA-Z0-9${'$'}]+\s*=\s*([a-zA-Z0-9${'$'}]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9${'$'}]+\s*\.\s*[a-z]\s*\)"""),
        Regex("""&&\s*\(\s*[a-zA-Z0-9${'$'}]+\s*=\s*([a-zA-Z0-9${'$'}]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9${'$'}]+\s*\)"""),
        Regex("""\b[cs]\s*&&\s*[adf]\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9${'$'}]+)\("""),
        Regex("""\b[a-zA-Z0-9]+\s*&&\s*[a-zA-Z0-9]+\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9${'$'}]+)\("""),
        Regex("""\bm=([a-zA-Z0-9${'$'}]{2,})\(decodeURIComponent\(h\.s\)\)""")
    )

    private val nPatterns = listOf(
        Regex("""\.get\("n"\)\)&&\(b=([a-zA-Z0-9${'$'}]+)(?:\[(\d+)\])?\(([a-zA-Z0-9])\)"""),
        Regex("""\.get\("n"\)\)\s*&&\s*\(([a-zA-Z0-9${'$'}]+)\s*=\s*([a-zA-Z0-9${'$'}]+)(?:\[(\d+)\])?\("""),
        Regex("""\(\s*([a-zA-Z0-9${'$'}]+)\s*=\s*String\.fromCharCode\(110\)""")
    )

    suspend fun load(cacheDir: File, forceRefresh: Boolean = false): Info? = withContext(Dispatchers.IO) {
        runCatching {
            val jsUrl = findPlayerJsUrl() ?: return@runCatching null
            Log.d(TAG, "player js: $jsUrl")

            val script = File(cacheDir, "player.js")
            val source = if (!forceRefresh && script.exists() && script.length() > 100_000) {
                script.readText()
            } else {
                download(jsUrl)?.also {
                    cacheDir.mkdirs()
                    script.writeText(it)
                } ?: return@runCatching null
            }

            val sts = stsPatterns.firstNotNullOfOrNull { it.find(source)?.groupValues?.get(1)?.toIntOrNull() }
            if (sts == null) {
                Log.e(TAG, "no signatureTimestamp in player js")
                return@runCatching null
            }

            val signature = signaturePatterns.firstNotNullOfOrNull { pattern ->
                pattern.find(source)?.let { match ->
                    val name = match.groupValues[1]
                    val constant = match.groupValues.getOrNull(2)?.toIntOrNull()
                    Signature(name, constant)
                }
            }

            val nTransform = nPatterns.firstNotNullOfOrNull { pattern ->
                pattern.find(source)?.let { match ->
                    val groups = match.groupValues.drop(1).filter { it.isNotEmpty() }
                    val name = groups.firstOrNull { !it.all(Char::isDigit) } ?: return@let null
                    val index = groups.firstOrNull { it.all(Char::isDigit) }?.toIntOrNull()
                    NTransform(name, index)
                }
            }

            Log.d(TAG, "sts=$sts signature=$signature nTransform=$nTransform")
            Info(sts, signature, nTransform, script)
        }.onFailure { Log.e(TAG, "player js load failed: ${it.message}", it) }.getOrNull()
    }

    private fun findPlayerJsUrl(): String? {
        val html = get("https://music.youtube.com/") ?: return null
        val raw = jsUrlPatterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) } ?: return null
        val cleaned = raw.replace("\\/", "/")
        return if (cleaned.startsWith("http")) cleaned else "https://music.youtube.com$cleaned"
    }

    private fun download(url: String) = get(url)

    private fun get(url: String): String? = runCatching {
        http.newCall(
            Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        ).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()
}
