package it.vfsfitvnm.vimusic.service.cipher

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Which call to make for a given player build.
 *
 * YouTube's player no longer contains anything stable to pattern-match against — function identity
 * is computed at runtime from XOR'd string-table indices, so the old approach of locating the
 * signature and `n` functions by regex cannot work any more. Instead a small curated registry names
 * the call for each player hash, and the real player script supplies the implementation.
 *
 * Registry format and the n-transform wrapper follow zemer-cipher (GPL-3.0).
 */
object PlayerConfig {
    private const val TAG = "PlayerConfig"
    private const val SUPPORTED_SCHEMA = 1

    /**
     * Everything here is evaluated as JavaScript in the cipher WebView, so both fields are locked
     * to shapes that cannot smuggle arbitrary code in. The n-transform wrapper is built locally and
     * never read from the file.
     */
    private val SIG_PATTERN = Regex("""^[A-Za-z0-9${'$'}_]{1,8}\(\d+,\d+,INPUT\)$""")
    private val NCLASS_PATTERN = Regex("""^[A-Za-z0-9${'$'}_]{1,8}$""")

    data class Entry(
        val signatureExpression: String,
        val nExpression: String,
        val signatureTimestamp: Int
    )

    private var byHash: Map<String, Entry> = emptyMap()

    fun load(context: Context) {
        if (byHash.isNotEmpty()) return
        byHash = runCatching {
            context.assets.open("player_configs.json").bufferedReader().use { it.readText() }
        }.mapCatching(::parse).getOrElse {
            Log.e(TAG, "could not load bundled registry: ${it.message}", it)
            emptyMap()
        }
        Log.d(TAG, "registry loaded: ${byHash.size} players")
    }

    /** Accepts the newer entry when a remote copy is fetched; invalid entries are dropped. */
    fun merge(jsonText: String) {
        runCatching { parse(jsonText) }
            .onSuccess { if (it.isNotEmpty()) byHash = byHash + it }
            .onFailure { Log.w(TAG, "remote registry ignored: ${it.message}") }
    }

    operator fun get(playerHash: String): Entry? = byHash[playerHash]

    private fun parse(jsonText: String): Map<String, Entry> {
        val root = Json.parseToJsonElement(jsonText) as? JsonObject ?: error("root is not an object")
        val schema = (root["schemaVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
        require(schema == SUPPORTED_SCHEMA) { "unsupported schemaVersion $schema" }

        val players = (root["players"] as? JsonObject) ?: error("no players object")
        val result = mutableMapOf<String, Entry>()

        for ((hash, element) in players) {
            val entry = runCatching { element.jsonObject }.getOrNull() ?: continue
            val sig = (entry["sig"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val nClass = (entry["nClass"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val sts = (entry["sts"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue

            if (!SIG_PATTERN.matches(sig) || !NCLASS_PATTERN.matches(nClass) || sts <= 0) {
                Log.w(TAG, "skipping malformed entry for $hash")
                continue
            }

            val parsed = Entry(
                signatureExpression = sig,
                nExpression = buildNExpression(nClass),
                signatureTimestamp = sts
            )
            result[hash] = parsed

            // A player is often served under several hashes; the aliases share its call.
            (entry["aliases"] as? kotlinx.serialization.json.JsonArray)?.forEach { alias ->
                (alias as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { result[it] = parsed }
            }
        }
        return result
    }

    /**
     * The `n` transform is reached by constructing the player's own URL class and reading the
     * parameter back out of it, which is how the player itself applies it.
     */
    private fun buildNExpression(nClass: String): String =
        "(function(n){try{var u=new g.$nClass('https://x.googlevideo.com/videoplayback?n='+n,true);" +
            "var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)"
}
