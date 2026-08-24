package it.vfsfitvnm.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class PlayerResponse(
    val playabilityStatus: PlayabilityStatus?,
    val playerConfig: PlayerConfig?,
    val streamingData: StreamingData?,
    val videoDetails: VideoDetails?,
) {
    @Serializable
    data class PlayabilityStatus(
        val status: String?
    )

    @Serializable
    data class PlayerConfig(
        val audioConfig: AudioConfig?
    ) {
        @Serializable
        data class AudioConfig(
            private val loudnessDb: Double?,
            private val perceptualLoudnessDb: Double? = null
        ) {
            /**
             * How much louder than YouTube's reference level this track is, in dB. Playback should
             * apply the negation of this as gain.
             *
             * The old `+7` correction here existed only to rebase `ANDROID_MUSIC`'s reference level.
             * The playback clients used now report against the standard reference, so the value is
             * already normalized and shifting it would over-boost every track by 7 dB.
             */
            val normalizedLoudnessDb: Float?
                get() = loudnessDb?.toFloat()
        }
    }

    @Serializable
    data class StreamingData(
        val adaptiveFormats: List<AdaptiveFormat>?
    ) {
        val highestQualityFormat: AdaptiveFormat?
            get() = adaptiveFormats
                ?.filter { it.url != null || it.signatureCipher != null }
                ?.findLast { it.itag == 251 || it.itag == 140 }

        @Serializable
        data class AdaptiveFormat(
            val itag: Int,
            val mimeType: String,
            val bitrate: Long?,
            val averageBitrate: Long?,
            val contentLength: Long?,
            val audioQuality: String?,
            val approxDurationMs: Long?,
            val lastModified: Long?,
            val loudnessDb: Double?,
            val audioSampleRate: Int?,
            val url: String?,
            /** Web clients return this in place of [url]; it has to be deciphered first. */
            val signatureCipher: String? = null,
        )
    }

    @Serializable
    data class VideoDetails(
        val videoId: String?
    )
}
