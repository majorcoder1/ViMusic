package it.vfsfitvnm.vimusic.service.cipher

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A curated fallback naming the call to make for a given player build.
 *
 * This used to be the only source of truth, which meant playback died whenever the upstream copy
 * stopped being updated. [CipherWebView] now discovers both entry points by probing the player
 * itself, and this is consulted only when that fails -- a backstop rather than a dependency.
 *
 * Registry format follows zemer-cipher (GPL-3.0).
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
        val nClass: String,
        val signatureTimestamp: Int
    )

    /** Guards values that reach the cipher WebView, wherever they came from. */
    fun isWellFormed(signatureExpression: String, nClass: String) =
        SIG_PATTERN.matches(signatureExpression) && NCLASS_PATTERN.matches(nClass)

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
                nClass = nClass,
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

}
