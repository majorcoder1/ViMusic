package it.vfsfitvnm.innertube.models.bodies

import it.vfsfitvnm.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class PlayerBody(
    val context: Context = Context.DefaultAndroidVr,
    val videoId: String,
    val playlistId: String? = null,
    val contentCheckOk: Boolean = true,
    val racyCheckOk: Boolean = true,
    /**
     * Proof of origin for the *session*. YouTube rejects the request outright without it for the
     * web clients, and hands back a stream capped at roughly one megabyte for the rest.
     */
    val serviceIntegrityDimensions: ServiceIntegrityDimensions? = null,
    val playbackContext: PlaybackContext? = null
) {
    @Serializable
    data class ServiceIntegrityDimensions(val poToken: String)

    @Serializable
    data class PlaybackContext(val contentPlaybackContext: ContentPlaybackContext) {
        @Serializable
        data class ContentPlaybackContext(val signatureTimestamp: Int)
    }
}
