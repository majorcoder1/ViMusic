package it.vfsfitvnm.innertube.utils

import it.vfsfitvnm.innertube.models.NextResponse

/** Browse id prefix of the "Related" tab, which backs the Quick picks screen. */
internal const val RelatedBrowseIdPrefix = "MPTR"

/** Browse id prefix of the "Lyrics" tab. */
internal const val LyricsBrowseIdPrefix = "MPLY"

/**
 * Finds the watch-next tab whose browse id starts with [prefix].
 *
 * These tabs used to be addressed by position, which broke as soon as YouTube inserted a
 * "Comments" tab between "Lyrics" and "Related": the index that used to mean "Related" started
 * landing on a tab with no browse id at all, so the request bailed out and the screen sat on its
 * loading placeholders forever. The browse id prefix identifies the tab wherever it sits, and
 * unlike the tab's title it does not change with the user's language.
 */
internal fun NextResponse.watchNextTabBrowseId(prefix: String): String? = contents
    ?.singleColumnMusicWatchNextResultsRenderer
    ?.tabbedRenderer
    ?.watchNextTabbedResultsRenderer
    ?.tabs
    ?.firstNotNullOfOrNull { tab ->
        tab.tabRenderer?.endpoint?.browseEndpoint?.browseId?.takeIf { it.startsWith(prefix) }
    }
