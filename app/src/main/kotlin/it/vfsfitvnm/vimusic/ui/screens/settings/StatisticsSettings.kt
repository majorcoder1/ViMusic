package it.vfsfitvnm.vimusic.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.vfsfitvnm.vimusic.Database
import it.vfsfitvnm.vimusic.LocalPlayerAwareWindowInsets
import it.vfsfitvnm.vimusic.ui.styling.LocalAppearance
import java.util.concurrent.TimeUnit

/**
 * A read-only summary of what the local database already records: what has been played, what has
 * been liked, and what has been kept. Everything here is on-device — nothing is reported anywhere.
 */
@Composable
fun StatisticsSettings() {
    val (colorPalette) = LocalAppearance.current

    val songsPlayed by Database.songsPlayedCount().collectAsState(initial = 0)
    val likedSongs by Database.likedSongsCount().collectAsState(initial = 0)
    val totalPlayTimeMs by Database.totalPlayTimeMs().collectAsState(initial = 0L)
    val artists by Database.bookmarkedArtistsCount().collectAsState(initial = 0)
    val albums by Database.bookmarkedAlbumsCount().collectAsState(initial = 0)
    val playlists by Database.playlistsCount().collectAsState(initial = 0)

    Column(
        modifier = Modifier
            .background(colorPalette.background0)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                    .asPaddingValues()
            )
    ) {
        SettingsEntryGroupText(title = "LISTENING")

        SettingsDescription(text = "Time listened\n${formatPlayTime(totalPlayTimeMs)}")
        SettingsDescription(text = "Songs played\n$songsPlayed")
        SettingsDescription(text = "Songs liked\n$likedSongs")

        SettingsEntryGroupText(title = "LIBRARY")

        SettingsDescription(text = "Artists followed\n$artists")
        SettingsDescription(text = "Albums saved\n$albums")
        SettingsDescription(text = "Playlists\n$playlists")

        SettingsEntryGroupText(title = "PRIVACY")

        SettingsDescription(
            text = "These figures are counted on this device from your own playback history. " +
                "Nothing here is uploaded or shared."
        )
    }
}

/** Reads as "3d 4h 12m" rather than a raw millisecond count. */
private fun formatPlayTime(millis: Long): String {
    if (millis <= 0) return "Nothing yet"

    val days = TimeUnit.MILLISECONDS.toDays(millis)
    val hours = TimeUnit.MILLISECONDS.toHours(millis) % 24
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60

    return buildList {
        if (days > 0) add("${days}d")
        if (hours > 0) add("${hours}h")
        if (minutes > 0 || isEmpty()) add("${minutes}m")
    }.joinToString(" ")
}
