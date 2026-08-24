package it.vfsfitvnm.vimusic.service.cipher

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads YouTube's player script and pairs it with the registry entry describing how to call it.
 *
 * Two details matter and both were previously wrong:
 *
 *  - The `player_ias` build from `www.youtube.com` is the one every working implementation uses.
 *    `music.youtube.com` serves `player_es6`, a *different build under the same hash* whose
 *    functions take different arguments — calling into it with the registry's arguments cannot work.
 *  - Function names are not extracted from the script. They come from [PlayerConfig], because the
 *    current obfuscation leaves nothing to anchor a regex to.
 */
object PlayerJs {
    private const val TAG = "PlayerJs"
    private val http = OkHttpClient.Builder().build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private const val REGISTRY_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"

    private val hashPatterns = listOf(
        Regex("""/s/player/([a-f0-9]{8})/"""),
        Regex(""""jsUrl"\s*:\s*"[^"]*?/player/([a-f0-9]{8})/"""),
        Regex("""player\\?/([a-f0-9]{8})\\?/""")
    )

    data class Info(
        val playerHash: String,
        val signatureTimestamp: Int,
        val signatureExpression: String,
        val nExpression: String,
        val script: File
    )

    suspend fun load(context: Context, forceRefresh: Boolean = false): Info? = withContext(Dispatchers.IO) {
        runCatching {
            PlayerConfig.load(context)

            val hash = findPlayerHash() ?: run {
                Log.e(TAG, "could not determine the current player hash")
                return@runCatching null
            }

            var entry = PlayerConfig[hash]
            if (entry == null) {
                // An unrecognised player means the bundled registry has aged out; the upstream copy
                // is usually updated within hours of a rotation.
                Log.d(TAG, "player $hash not in bundled registry, refreshing from upstream")
                get(REGISTRY_URL)?.let(PlayerConfig::merge)
                entry = PlayerConfig[hash]
            }
            if (entry == null) {
                Log.e(TAG, "no registry entry for player $hash")
                return@runCatching null
            }

            val cacheDir = File(context.filesDir, "cipher")
            val script = File(cacheDir, "player_$hash.js")
            if (forceRefresh || !script.exists() || script.length() < 100_000) {
                val url = "https://www.youtube.com/s/player/$hash/player_ias.vflset/en_GB/base.js"
                val source = get(url) ?: run {
                    Log.e(TAG, "could not download $url")
                    return@runCatching null
                }
                cacheDir.mkdirs()
                cacheDir.listFiles()?.forEach { if (it.name.startsWith("player_") && it != script) it.delete() }
                script.writeText(source)
                Log.d(TAG, "downloaded player_ias $hash (${source.length} chars)")
            }

            Log.d(
                TAG,
                "player=$hash sts=${entry.signatureTimestamp} sig=${entry.signatureExpression}"
            )
            Info(
                playerHash = hash,
                signatureTimestamp = entry.signatureTimestamp,
                signatureExpression = entry.signatureExpression,
                nExpression = entry.nExpression,
                script = script
            )
        }.onFailure { Log.e(TAG, "player js load failed: ${it.message}", it) }.getOrNull()
    }

    /** The hash identifies the player build; both YouTube front-ends report the same one. */
    private fun findPlayerHash(): String? {
        for (page in listOf("https://www.youtube.com/iframe_api", "https://music.youtube.com/")) {
            val body = get(page) ?: continue
            hashPatterns.firstNotNullOfOrNull { it.find(body)?.groupValues?.get(1) }
                ?.let { return it }
        }
        return null
    }

    private fun get(url: String): String? = runCatching {
        http.newCall(
            Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        ).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()
}
