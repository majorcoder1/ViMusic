package it.vfsfitvnm.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Thumbnail(
    val url: String,
    val height: Int?,
    val width: Int?
) {
    val isResizable: Boolean
        get() = !url.startsWith("https://i.ytimg.com")

    /**
     * Replaces the size directives YouTube bakes into the URL rather than appending to them.
     * The returned URLs are already sized for a phone list, so appending leaves a 60px image
     * that anything larger has to upscale.
     */
    fun size(size: Int): String = if (ResizableHosts.any(url::startsWith)) {
        "${url.substringBefore('=')}=w$size-h$size-l90-rj"
    } else url

    private companion object {
        val ResizableHosts = listOf(
            "https://lh3.googleusercontent.com",
            "https://yt3.googleusercontent.com",
            "https://yt3.ggpht.com",
            "https://music.youtube.com/image"
        )
    }
}
