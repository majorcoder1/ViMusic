package it.vfsfitvnm.innertube.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * An InnerTube client identity.
 *
 * YouTube gates every endpoint on the client it believes is calling. The clients below were each
 * verified against the live API; the ones ViMusic originally shipped (`ANDROID_MUSIC`, `IOS_MUSIC`,
 * `TVHTML5_SIMPLY_EMBEDDED_PLAYER`) are no longer usable anonymously and now answer
 * `LOGIN_REQUIRED` or `ERROR`, which is what stopped playback working.
 */
@Serializable
data class Context(
    val client: Client,
    val thirdParty: ThirdParty? = null,
) {
    @Serializable
    data class Client(
        val clientName: String,
        val clientVersion: String,
        val platform: String? = null,
        val hl: String = "en",
        val gl: String = "US",
        val visitorData: String? = null,
        val androidSdkVersion: Int? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val userAgent: String? = null,
        /** Value for the `X-YouTube-Client-Name` header. Not part of the request body. */
        @Transient val id: Int = 0,
    )

    @Serializable
    data class ThirdParty(
        val embedUrl: String,
    )

    /** Binds this context to a session, so a proof-of-origin token minted for it is accepted. */
    fun withVisitorData(visitorData: String?) =
        if (visitorData == null) this else copy(client = client.copy(visitorData = visitorData))

    companion object {
        private const val WEB_REMIX_VERSION = "1.20260818.08.00"
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

        private const val ANDROID_VR_VERSION = "1.60.19"
        private const val IOS_VERSION = "20.10.4"

        /**
         * Metadata client: search, browse, radio/next, search suggestions.
         * This is the YouTube Music web app, and it still serves metadata anonymously.
         */
        val DefaultWeb = Context(
            client = Client(
                clientName = "WEB_REMIX",
                clientVersion = WEB_REMIX_VERSION,
                platform = "DESKTOP",
                userAgent = WEB_USER_AGENT,
                id = 67,
            )
        )

        /**
         * Playback client. Returns direct, un-ciphered audio URLs with no account, no proof-of-origin
         * token and no signature deciphering — the only reason playback works at all here.
         */
        val DefaultAndroidVr = Context(
            client = Client(
                clientName = "ANDROID_VR",
                clientVersion = ANDROID_VR_VERSION,
                platform = "MOBILE",
                androidSdkVersion = 32,
                osName = "Android",
                osVersion = "12L",
                deviceMake = "Oculus",
                deviceModel = "Quest 3",
                userAgent = "com.google.android.apps.youtube.vr.oculus/$ANDROID_VR_VERSION " +
                    "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                id = 28,
            )
        )

        /** Playback fallback, used when [DefaultAndroidVr] cannot serve a track. */
        val DefaultIos = Context(
            client = Client(
                clientName = "IOS",
                clientVersion = IOS_VERSION,
                platform = "MOBILE",
                osName = "iPhone",
                osVersion = "18.3.2.22D82",
                deviceMake = "Apple",
                deviceModel = "iPhone16,2",
                userAgent = "com.google.ios.youtube/$IOS_VERSION " +
                    "(iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)",
                id = 5,
            )
        )
    }
}
