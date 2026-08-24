package it.vfsfitvnm.innertube.requests

import io.ktor.client.call.body
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import it.vfsfitvnm.innertube.Innertube
import it.vfsfitvnm.innertube.models.Context
import it.vfsfitvnm.innertube.models.PlayerResponse
import it.vfsfitvnm.innertube.models.bodies.PlayerBody
import it.vfsfitvnm.innertube.utils.runCatchingNonCancellable

private const val PlayerFieldMask =
    "playabilityStatus.status,playabilityStatus.reason,playerConfig.audioConfig," +
        "streamingData.adaptiveFormats,videoDetails.videoId"

/**
 * Resolves the streaming formats for a track.
 *
 * Tries [Context.DefaultAndroidVr] first and falls back to [Context.DefaultIos]. Both return direct
 * audio URLs that need no signature deciphering, no proof-of-origin token and no signed-in account.
 */
suspend fun Innertube.player(body: PlayerBody) = runCatchingNonCancellable {
    val response = requestPlayer(body, body.context)
    if (response.isPlayable) return@runCatchingNonCancellable response

    val fallback = requestPlayer(body, Context.DefaultIos)
    if (fallback.isPlayable) fallback else response
}

private suspend fun Innertube.requestPlayer(body: PlayerBody, context: Context): PlayerResponse =
    client.post(player) {
        setBody(body.copy(context = context))
        headers { identify(context) }
        mask(PlayerFieldMask)
    }.body()

private val PlayerResponse.isPlayable: Boolean
    get() = playabilityStatus?.status == "OK" &&
        streamingData?.adaptiveFormats?.any {
            !it.url.isNullOrEmpty() || !it.signatureCipher.isNullOrEmpty()
        } == true
