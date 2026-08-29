package it.vfsfitvnm.innertube.models

import kotlinx.serialization.Serializable

/**
 * The suggestions endpoint answers with two sections: plain query completions, and a set of real
 * entities -- the artist, their songs, a playlist -- already ranked by YouTube. Only the first was
 * ever parsed, so the second was requested away by the field mask and discarded.
 */
@Serializable
data class SearchSuggestionsResponse(
    val contents: List<Content>?
) {
    @Serializable
    data class Content(
        val searchSuggestionsSectionRenderer: SearchSuggestionsSectionRenderer?
    ) {
        @Serializable
        data class SearchSuggestionsSectionRenderer(
            val contents: List<Content>?
        ) {
            @Serializable
            data class Content(
                val searchSuggestionRenderer: SearchSuggestionRenderer?,
                val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null
            ) {
                @Serializable
                data class SearchSuggestionRenderer(
                    val navigationEndpoint: NavigationEndpoint?,
                )
            }
        }
    }
}
