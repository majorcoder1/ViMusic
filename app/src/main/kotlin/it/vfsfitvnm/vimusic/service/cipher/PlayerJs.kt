package it.vfsfitvnm.vimusic.service.cipher

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads YouTube's player script and works out how to call into it.
 *
 * Two details matter and both were previously wrong:
 *
 *  - The `player_ias` build from `www.youtube.com` is the one every working implementation uses.
 *    `music.youtube.com` serves `player_es6`, a *different build under the same hash* whose
 *    functions take different arguments -- calling into it with the wrong arguments cannot work.
 *  - The two entry points are found by *running* the player, not by reading it. Their names are
 *    minified and their behaviour is dispatched through XOR'd string-table indices, so there is
 *    nothing to anchor a regex to. [CipherWebView] probes candidates against the player's own code
 *    and keeps whichever one actually descrambles. See [candidateNames] for how the search space
 *    is narrowed to something small enough to brute force.
 *
 * A curated registry is still consulted, but only as a fallback when discovery comes up empty --
 * the app is no longer dead in the water if that registry stops being maintained.
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

    private val stsPattern = Regex("""signatureTimestamp[:=](\d{4,6})""")

    /**
     * Call sites of the form `name(int,int,…)`. The signature descrambler is one of these; the
     * pair of integers selects the operation. Names and pairs are searched as a cross product
     * because the descrambler's own call site is not always spelled out in the source.
     */
    private val dispatcherPattern =
        Regex("""\b([A-Za-z_$][A-Za-z0-9_$]{0,4})\(\s*(\d{1,6})\s*,\s*(\d{1,6})\s*,""")

    // Bounds the probe. Every candidate is one function call, so even the ceiling is milliseconds.
    private const val MAX_NAMES = 64
    private const val MAX_PAIRS = 128

    data class Info(
        val playerHash: String,
        val signatureTimestamp: Int,
        val candidateNames: List<String>,
        val candidatePairs: List<List<Int>>,
        /** Registry entry for this player, used only if the probe finds nothing. */
        val fallbackSignature: String?,
        val fallbackNClass: String?,
        /** Previously discovered call for this player, so the probe runs once per rotation. */
        val cachedSignature: String?,
        val cachedNClass: String?,
        val script: File
    )

    suspend fun load(context: Context, forceRefresh: Boolean = false): Info? = withContext(Dispatchers.IO) {
        runCatching {
            val hash = findPlayerHash() ?: run {
                Log.e(TAG, "could not determine the current player hash")
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
                cacheDir.listFiles()?.forEach {
                    if ((it.name.startsWith("player_") || it.name == "player_prepared.js") && it != script) it.delete()
                }
                script.writeText(source)
            }

            val source = script.readText()
            val discovered = if (forceRefresh) null else readDiscovered(context, hash)

            // Only the bundled copy is read here -- it costs nothing. The network copy is left
            // alone unless discovery actually fails, so a healthy install never waits on, or
            // depends on, a third party being reachable.
            PlayerConfig.load(context)
            val registryEntry = PlayerConfig[hash]

            val sts = stsPattern.find(source)?.groupValues?.get(1)?.toIntOrNull()
                ?: discovered?.signatureTimestamp
                ?: registryEntry?.signatureTimestamp
                ?: run {
                    Log.e(TAG, "no signature timestamp for player $hash")
                    return@runCatching null
                }

            val names = mutableSetOf<String>()
            val pairs = linkedSetOf<List<Int>>()
            for (match in dispatcherPattern.findAll(source)) {
                if (names.size < MAX_NAMES) names += match.groupValues[1]
                if (pairs.size < MAX_PAIRS) {
                    val a = match.groupValues[2].toIntOrNull() ?: continue
                    val b = match.groupValues[3].toIntOrNull() ?: continue
                    pairs += listOf(a, b)
                }
            }

            Log.i(
                TAG,
                "player=$hash sts=$sts candidates=${names.size}x${pairs.size} " +
                    "cached=${discovered != null} registry=${registryEntry != null}"
            )

            Info(
                playerHash = hash,
                signatureTimestamp = sts,
                candidateNames = names.toList(),
                candidatePairs = pairs.toList(),
                fallbackSignature = registryEntry?.signatureExpression,
                fallbackNClass = registryEntry?.nClass,
                cachedSignature = discovered?.signatureExpression,
                cachedNClass = discovered?.nClass,
                script = script
            )
        }.onFailure { Log.e(TAG, "player js load failed: ${it.message}", it) }.getOrNull()
    }

    /**
     * Pulls the curated registry over the network. Called only after discovery has failed, which
     * is the one situation where being out of date is better than having nothing.
     */
    suspend fun refreshRegistry(): Boolean = withContext(Dispatchers.IO) {
        val body = get(REGISTRY_URL) ?: return@withContext false
        PlayerConfig.merge(body)
        Log.d(TAG, "curated registry refreshed after discovery failed")
        true
    }

    /** Remembers what the probe worked out, so it only runs once per player rotation. */
    fun saveDiscovered(context: Context, hash: String, signature: String, nClass: String, sts: Int) {
        runCatching {
            val file = File(File(context.filesDir, "cipher").apply { mkdirs() }, "discovered.json")
            // Logged before the write, not after: as a lambda's last expression the call's return
            // value counts as used, and R8 will not strip it out of the release build.
            Log.d(TAG, "caching discovery for $hash: sig=$signature nClass=$nClass")
            file.writeText(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("hash", hash)
                        put("sig", signature)
                        put("nClass", nClass)
                        put("sts", sts)
                    }
                )
            )
        }.onFailure { Log.w(TAG, "could not cache discovery: ${it.message}") }
    }

    private fun readDiscovered(context: Context, hash: String): PlayerConfig.Entry? = runCatching {
        val file = File(File(context.filesDir, "cipher"), "discovered.json")
        if (!file.exists()) return null
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        if ((root["hash"] as? JsonPrimitive)?.content != hash) return null

        val sig = root["sig"]!!.jsonPrimitive.content
        val nClass = root["nClass"]!!.jsonPrimitive.content
        val sts = root["sts"]!!.jsonPrimitive.content.toInt()
        if (!PlayerConfig.isWellFormed(sig, nClass) || sts <= 0) return null

        PlayerConfig.Entry(signatureExpression = sig, nClass = nClass, signatureTimestamp = sts)
    }.getOrNull()

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
