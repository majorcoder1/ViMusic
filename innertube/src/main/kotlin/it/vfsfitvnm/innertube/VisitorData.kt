package it.vfsfitvnm.innertube

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.serialization.Serializable

/**
 * A proof-of-origin token is minted against a session, and YouTube identifies that session by its
 * `visitorData`. The same value therefore has to be used for the token, for the `/player` request
 * and for the stream that follows, so it is fetched once and reused.
 */
object VisitorData {
    @Volatile
    private var cached: String? = null

    suspend fun get(): String? {
        cached?.let { return it }
        return runCatching { fetch() }.getOrNull()?.also { cached = it }
    }

    private suspend fun fetch(): String? {
        val response = Innertube.client.post("/youtubei/v1/visitor_id") {
            setBody(VisitorIdBody())
        }.body<VisitorIdResponse>()

        return response.responseContext?.visitorData
    }

    @Serializable
    private data class VisitorIdBody(
        val context: it.vfsfitvnm.innertube.models.Context = it.vfsfitvnm.innertube.models.Context.DefaultWeb
    )

    @Serializable
    private data class VisitorIdResponse(val responseContext: ResponseContext?) {
        @Serializable
        data class ResponseContext(val visitorData: String?)
    }
}
