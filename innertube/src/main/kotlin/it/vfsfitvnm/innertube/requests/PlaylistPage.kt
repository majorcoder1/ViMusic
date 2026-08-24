package it.vfsfitvnm.innertube.requests

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import it.vfsfitvnm.innertube.Innertube
import it.vfsfitvnm.innertube.models.BrowseResponse
import it.vfsfitvnm.innertube.models.ContinuationResponse
import it.vfsfitvnm.innertube.models.MusicCarouselShelfRenderer
import it.vfsfitvnm.innertube.models.MusicResponsiveHeaderRenderer
import it.vfsfitvnm.innertube.models.MusicShelfRenderer
import it.vfsfitvnm.innertube.models.NavigationEndpoint
import it.vfsfitvnm.innertube.models.SectionListRenderer
import it.vfsfitvnm.innertube.models.bodies.BrowseBody
import it.vfsfitvnm.innertube.models.bodies.ContinuationBody
import it.vfsfitvnm.innertube.utils.from
import it.vfsfitvnm.innertube.utils.runCatchingNonCancellable

suspend fun Innertube.playlistPage(body: BrowseBody) = runCatchingNonCancellable {
    val response = client.post(browse) {
        setBody(body)
        mask(
            "contents(twoColumnBrowseResultsRenderer(" +
                "tabs.tabRenderer.content.sectionListRenderer.contents.musicResponsiveHeaderRenderer" +
                "(title,subtitle,secondSubtitle,straplineTextOne,thumbnail)," +
                "secondaryContents.sectionListRenderer.contents(" +
                "musicPlaylistShelfRenderer(continuations,contents.$musicResponsiveListItemRendererMask)," +
                "musicShelfRenderer(continuations,contents.$musicResponsiveListItemRendererMask)," +
                "musicCarouselShelfRenderer.contents.$musicTwoRowItemRendererMask))," +
                "singleColumnBrowseResultsRenderer.tabs.tabRenderer.content.sectionListRenderer.contents(" +
                "musicPlaylistShelfRenderer(continuations,contents.$musicResponsiveListItemRendererMask)," +
                "musicCarouselShelfRenderer.contents.$musicTwoRowItemRendererMask))," +
                "header.musicDetailHeaderRenderer(title,subtitle,thumbnail),microformat"
        )
    }.body<BrowseResponse>()

    val twoColumn = response.contents?.twoColumnBrowseResultsRenderer

    val header: MusicResponsiveHeaderRenderer? = twoColumn
        ?.tabs
        ?.firstOrNull()
        ?.tabRenderer
        ?.content
        ?.sectionListRenderer
        ?.contents
        ?.firstNotNullOfOrNull(SectionListRenderer.Content::musicResponsiveHeaderRenderer)

    // Two-column is what YouTube serves today; the single-column path is kept as a fallback so an
    // older or regional response shape still renders instead of showing an empty page.
    val sectionListRendererContents = twoColumn
        ?.secondaryContents
        ?.sectionListRenderer
        ?.contents
        ?: response
            .contents
            ?.singleColumnBrowseResultsRenderer
            ?.tabs
            ?.firstOrNull()
            ?.tabRenderer
            ?.content
            ?.sectionListRenderer
            ?.contents

    val musicShelfRenderer = sectionListRendererContents
        ?.firstNotNullOfOrNull(SectionListRenderer.Content::musicShelfRenderer)

    val musicCarouselShelfRenderer = sectionListRendererContents
        ?.firstNotNullOfOrNull(SectionListRenderer.Content::musicCarouselShelfRenderer)

    val legacyHeader = response.header?.musicDetailHeaderRenderer

    Innertube.PlaylistOrAlbumPage(
        title = header?.title?.text ?: legacyHeader?.title?.text,
        thumbnail = (header?.thumbnail ?: legacyHeader?.thumbnail)
            ?.musicThumbnailRenderer
            ?.thumbnail
            ?.thumbnails
            ?.firstOrNull(),
        // The artist lives in `straplineTextOne` now, and only the runs that link somewhere are
        // real artists — the rest are separators like ", " and " & ".
        authors = header
            ?.straplineTextOne
            ?.runs
            ?.filter { it.navigationEndpoint != null }
            ?.map { Innertube.Info<NavigationEndpoint.Endpoint.Browse>(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: legacyHeader
                ?.subtitle
                ?.splitBySeparator()
                ?.getOrNull(1)
                ?.map { Innertube.Info<NavigationEndpoint.Endpoint.Browse>(it) },
        year = header
            ?.subtitle
            ?.splitBySeparator()
            ?.lastOrNull()
            ?.firstOrNull()
            ?.text
            ?: legacyHeader
                ?.subtitle
                ?.splitBySeparator()
                ?.getOrNull(2)
                ?.firstOrNull()
                ?.text,
        url = response
            .microformat
            ?.microformatDataRenderer
            ?.urlCanonical,
        songsPage = musicShelfRenderer
            ?.toSongsPage(),
        otherVersions = musicCarouselShelfRenderer
            ?.contents
            ?.mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
            ?.mapNotNull(Innertube.AlbumItem::from)
    )
}

suspend fun Innertube.playlistPage(body: ContinuationBody) = runCatchingNonCancellable {
    val response = client.post(browse) {
        setBody(body)
        mask("continuationContents.musicPlaylistShelfContinuation(continuations,contents.$musicResponsiveListItemRendererMask)")
    }.body<ContinuationResponse>()

    response
        .continuationContents
        ?.musicShelfContinuation
        ?.toSongsPage()
}

private fun MusicShelfRenderer?.toSongsPage() =
    Innertube.ItemsPage(
        items = this
            ?.contents
            ?.mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
            ?.mapNotNull(Innertube.SongItem::from),
        continuation = this
            ?.continuations
            ?.firstOrNull()
            ?.nextContinuationData
            ?.continuation
    )
