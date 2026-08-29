package it.vfsfitvnm.innertube.requests

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import it.vfsfitvnm.innertube.Innertube
import it.vfsfitvnm.innertube.models.MusicShelfRenderer
import it.vfsfitvnm.innertube.models.NavigationEndpoint
import it.vfsfitvnm.innertube.models.SearchSuggestionsResponse
import it.vfsfitvnm.innertube.models.Thumbnail
import it.vfsfitvnm.innertube.models.bodies.SearchSuggestionsBody
import it.vfsfitvnm.innertube.utils.from
import it.vfsfitvnm.innertube.utils.runCatchingNonCancellable

/**
 * Query completions plus the entities YouTube itself ranks for the text typed so far.
 *
 * The entities are worth more than the completions: searching an artist through the normal search
 * endpoint returns the right artist *and* a handful of similar ones, whereas this returns the
 * artist, their songs and their playlists, in YouTube's own order.
 */
suspend fun Innertube.searchSuggestions(body: SearchSuggestionsBody) = runCatchingNonCancellable {
    val response = client.post(searchSuggestions) {
        setBody(body)
        mask(
            "contents.searchSuggestionsSectionRenderer.contents(" +
                "searchSuggestionRenderer.navigationEndpoint.searchEndpoint.query," +
                "$musicResponsiveListItemRendererMask)"
        )
    }.body<SearchSuggestionsResponse>()

    val sections = response.contents?.mapNotNull { it.searchSuggestionsSectionRenderer?.contents }

    Innertube.SearchSuggestions(
        queries = sections
            ?.flatten()
            ?.mapNotNull { it.searchSuggestionRenderer?.navigationEndpoint?.searchEndpoint?.query }
            .orEmpty(),
        items = sections
            ?.flatten()
            ?.mapNotNull { it.musicResponsiveListItemRenderer }
            ?.mapNotNull { renderer -> itemFrom(MusicShelfRenderer.Content(renderer)) }
            .orEmpty()
            .withArtistRecoveredFromSongs(body.input)
    )
}

/**
 * YouTube only puts an artist row in the suggestions for artists above some popularity threshold --
 * a 9.9M-listener act gets one, a 7K-subscriber act does not -- which left smaller artists with no
 * way through to their page.
 *
 * The link is there regardless: every song row credits whoever made it. So when no artist row came
 * back, one is rebuilt from the songs already in hand, costing no extra request.
 */
private fun List<Innertube.Item>.withArtistRecoveredFromSongs(query: String): List<Innertube.Item> {
    if (isEmpty() || any { it is Innertube.ArtistItem }) return this

    val target = query.foldedForMatching()
    if (target.isEmpty()) return this

    val credits = mapNotNull { item ->
        val authors = when (item) {
            is Innertube.SongItem -> item.authors
            is Innertube.VideoItem -> item.authors
            else -> null
        }

        authors
            ?.firstOrNull { it.name != null && it.endpoint?.browseId?.startsWith("UC") == true }
            ?.let { it to item.thumbnail }
    }

    // It has to be the artist that was actually typed. A "feat." track credits the host act
    // first -- matching on frequency alone would offer up the wrong name entirely.
    val (info, thumbnail) = credits.firstOrNull { it.first.name!!.foldedForMatching() == target }
        ?: credits.firstOrNull { it.first.name!!.foldedForMatching().startsWith(target) }
        ?: credits.firstOrNull { it.first.name!!.foldedForMatching().contains(target) }
        ?: return this

    // Artwork from one of their own releases stands in for a profile picture, which the song rows
    // do not carry; the alternative is an empty circle.
    return listOf(artistItem(info, thumbnail)) + this
}

private fun artistItem(
    info: Innertube.Info<NavigationEndpoint.Endpoint.Browse>,
    thumbnail: Thumbnail?
) = Innertube.ArtistItem(info = info, subscribersCountText = null, thumbnail = thumbnail)

/** Case and punctuation are noise when matching a typed name against a credited one. */
private fun String.foldedForMatching() = lowercase().filter(Char::isLetterOrDigit)

/**
 * The rows are all the same renderer, so the destination decides what each one is. Subtitles cannot
 * be trusted for this -- an artist row reads "9.9M monthly audience" with no type word at all.
 */
private fun itemFrom(content: MusicShelfRenderer.Content): Innertube.Item? {
    val endpoint = content.musicResponsiveListItemRenderer?.navigationEndpoint
    val browseId = endpoint?.browseEndpoint?.browseId

    return when {
        endpoint?.watchEndpoint != null ->
            Innertube.SongItem.from(content) ?: Innertube.VideoItem.from(content)

        browseId == null -> null
        browseId.startsWith("UC") -> Innertube.ArtistItem.from(content)
        browseId.startsWith("MPRE") -> Innertube.AlbumItem.from(content)
        else -> Innertube.PlaylistItem.from(content)
    }
}
