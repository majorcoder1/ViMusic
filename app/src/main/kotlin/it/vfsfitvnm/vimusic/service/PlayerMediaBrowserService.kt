package it.vfsfitvnm.vimusic.service

import android.media.MediaDescription as BrowserMediaDescription
import android.media.browse.MediaBrowser.MediaItem as BrowserMediaItem
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.ServiceConnection
import android.media.session.MediaSession
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.service.media.MediaBrowserService
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.Player
import androidx.media3.datasource.cache.Cache
import it.vfsfitvnm.innertube.models.NavigationEndpoint
import it.vfsfitvnm.vimusic.Database
import it.vfsfitvnm.vimusic.R
import it.vfsfitvnm.vimusic.models.Album
import it.vfsfitvnm.vimusic.models.Artist
import it.vfsfitvnm.vimusic.models.PlaylistPreview
import it.vfsfitvnm.vimusic.models.Song
import it.vfsfitvnm.vimusic.models.SongWithContentLength
import it.vfsfitvnm.vimusic.utils.asMediaItem
import it.vfsfitvnm.vimusic.utils.forcePlayAtIndex
import it.vfsfitvnm.vimusic.utils.thumbnail
import it.vfsfitvnm.vimusic.utils.forcePlayFromBeginning
import it.vfsfitvnm.vimusic.utils.forceSeekToNext
import it.vfsfitvnm.vimusic.utils.forceSeekToPrevious
import it.vfsfitvnm.vimusic.utils.intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

// BrowserRoot.EXTRA_RECENT is API 24; spelled out so the minSdk 23 build needs no guard.
private const val EXTRA_RECENT = "android.service.media.extra.RECENT"

// Car screens are big and far away, and a grid tile is rendered large. The source images are
// only 60px until they are re-requested at a real size.
private const val CarArtworkSize = 1024

// Marks an artist node keyed by name rather than by a real Artist row.
private const val ArtistNamePrefix = "name:"

class PlayerMediaBrowserService : MediaBrowserService(), ServiceConnection {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var lastSongs = emptyList<Song>()

    private var bound = false
    private var binder: PlayerService.Binder? = null

    override fun onDestroy() {
        coroutineScope.cancel()
        if (bound) {
            unbindService(this)
        }
        super.onDestroy()
    }

    override fun onServiceConnected(className: ComponentName, service: IBinder) {
        if (service is PlayerService.Binder) {
            bound = true
            binder = service
            sessionToken = service.mediaSession.sessionToken
            service.mediaSession.setCallback(AutoSessionCallback(service.player, service.cache))
        }
    }

    override fun onServiceDisconnected(name: ComponentName) = Unit

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        return if (clientUid == Process.myUid()
            || clientUid == Process.SYSTEM_UID
            || clientPackageName == "com.google.android.projection.gearhead"
        ) {
            bindService(intent<PlayerService>(), this, Context.BIND_AUTO_CREATE)

            // On connecting, the car asks for a "recent" root holding a single playable item and
            // offers to resume it. Answering means the music picks up where it left off as soon
            // as the phone is plugged in, without the driver touching anything.
            if (rootHints?.getBoolean(EXTRA_RECENT) == true) return BrowserRoot(
                MediaId.recent,
                bundleOf(EXTRA_RECENT to true)
            )

            BrowserRoot(
                MediaId.root,
                bundleOf(
                    // Categories render as artwork tiles rather than a column of text, and songs
                    // as a list, which is what makes the browser readable at a glance.
                    ContentStyle.BROWSABLE_HINT to ContentStyle.GRID,
                    ContentStyle.PLAYABLE_HINT to ContentStyle.LIST,
                    ContentStyle.SUPPORTED to true
                )
            )
        } else {
            null
        }
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<BrowserMediaItem>>) {
        result.detach()
        coroutineScope.launch { result.sendResult(childrenOf(parentId)) }
    }

    /**
     * The car shows root browsables as tabs and keeps only the first four, so the tree is four
     * tabs wide by design: everything worth one tap while moving lives under Home, and the three
     * ways of going looking for something deliberately get a tab each.
     */
    private suspend fun childrenOf(parentId: String): MutableList<BrowserMediaItem> = when (parentId) {
        MediaId.recent -> Database
            .songsByPlayTimeDesc()
            .first()
            .take(1)
            .also { lastSongs = it }
            .map { it.asBrowserMediaItem() }
            .toMutableList()

        // The car shows four tabs and no more, so the fourth goes to whichever of Albums or
        // Playlists actually has something in it. A tab that opens on "No items" is worse than
        // three tabs.
        MediaId.root -> mutableListOf<BrowserMediaItem>().apply {
            add(homeBrowserMediaItem)
            add(songsTabBrowserMediaItem)

            // Artists fall back to bare names when no Artist row exists, and a grid of identical
            // placeholder icons reads worse than a list of names.
            add(artistsBrowserMediaItem(hasArtwork = Database.artistsInLibrary().first().isNotEmpty()))

            when {
                Database.albumsInLibrary().first().isNotEmpty() -> add(albumsBrowserMediaItem)
                Database.playlistPreviewsByDateAddedDesc().first().isNotEmpty() ->
                    add(playlistsBrowserMediaItem(group = null))
            }
        }

        MediaId.home -> {
            val recent = Database.songsByPlayTimeDesc().first()
            lastSongs = recent
            val cachedIds = cachedSongIds()

            // Only offer a shortcut that leads somewhere. An empty Favourites or Downloaded row
            // is a tap that ends in "No items", which is worse than not showing it at all.
            mutableListOf<BrowserMediaItem>().apply {
                add(shuffleBrowserMediaItem)
                if (Database.likedSongsCount().first() > 0) add(favoritesBrowserMediaItem)
                if (cachedIds.isNotEmpty()) add(offlineBrowserMediaItem)
                if (Database.playlistPreviewsByDateAddedDesc().first().isNotEmpty()) {
                    add(playlistsBrowserMediaItem(group = "Start something"))
                }

                // A short, glanceable tail so the commonest case -- carry on with what was
                // already playing -- never needs a second tap.
                addAll(recent.take(8).map { it.asBrowserMediaItem(cachedIds, "Recently played") })
            }
        }

        MediaId.songs -> Database
            .songsByPlayTimeDesc()
            .first()
            .take(100)
            .also { lastSongs = it }
            .let { songs -> val cachedIds = cachedSongIds(); songs.map { it.asBrowserMediaItem(cachedIds) } }
            .toMutableList()

        MediaId.playlists -> Database
            .playlistPreviewsByDateAddedDesc()
            .first()
            .map { it.asBrowserMediaItem }
            .toMutableList()

        MediaId.albums -> Database
            .albumsInLibrary()
            .first()
            .map { it.asBrowserMediaItem }
            .toMutableList()

        // Songs played before the artist tables were being filled in leave no Artist row, so
        // fall back to the artist names carried on the songs themselves rather than showing
        // an empty tab.
        MediaId.artists -> Database
            .artistsInLibrary()
            .first()
            .map { it.asBrowserMediaItem }
            .ifEmpty {
                Database
                    .songsByPlayTimeDesc()
                    .first()
                    .mapNotNull { it.artistsText?.trim()?.takeIf(String::isNotEmpty) }
                    .distinct()
                    .map(::artistNameBrowserMediaItem)
            }
            .toMutableList()

        else -> when {
            // An artist tile opens that artist's songs rather than starting playback, so a long
            // drive can be steered without leaving the browser. The shuffle row on top keeps the
            // one-tap option available for anyone who does not want to choose.
            parentId.startsWith("${MediaId.artists}/") -> {
                val node = parentId.substringAfter('/')
                val songs = songsForArtistNode(node)
                lastSongs = songs

                val cachedIds = cachedSongIds()
                mutableListOf(shuffleBrowserMediaItemFor(MediaId.forArtist(node)))
                    .apply { addAll(songs.map { it.asBrowserMediaItem(cachedIds) }) }
            }

            else -> mutableListOf()
        }
    }

    /**
     * An artist node is either a real [Artist] id or, for songs that never got one, the artist
     * name off the song itself under [ArtistNamePrefix].
     */
    private suspend fun songsForArtistNode(node: String): List<Song> =
        if (node.startsWith(ArtistNamePrefix)) {
            val name = Uri.decode(node.removePrefix(ArtistNamePrefix))
            Database.songsByPlayTimeDesc().first().filter { it.artistsText?.trim() == name }
        } else {
            Database.artistSongs(node).first()
        }

    /** Ids of songs held in full by the download cache, so rows can be badged as offline-safe. */
    private suspend fun cachedSongIds(): Set<String> {
        val cache = binder?.cache ?: return emptySet()
        return Database
            .songsWithContentLength()
            .first()
            .filter { it.contentLength?.let { length -> cache.isCached(it.song.id, 0, length) } == true }
            .mapTo(mutableSetOf()) { it.song.id }
    }

    private suspend fun searchLibrary(query: String): List<Song> {
        val songs = Database.songsByPlayTimeDesc().first()
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return songs

        return songs.filter {
            it.title.lowercase().contains(needle) ||
                it.artistsText?.lowercase()?.contains(needle) == true
        }
    }

    private fun uriFor(@DrawableRes id: Int) = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(resources.getResourcePackageName(id))
        .appendPath(resources.getResourceTypeName(id))
        .appendPath(resources.getResourceEntryName(id))
        .build()

    /**
     * Style hints travel on the item whose *children* they describe, so a browsable can pick its
     * own layout rather than inheriting whatever the root asked for.
     */
    private fun styleExtras(browsable: Int, playable: Int) = bundleOf(
        ContentStyle.BROWSABLE_HINT to browsable,
        ContentStyle.PLAYABLE_HINT to playable
    )

    private fun browsable(
        mediaId: String,
        title: String,
        @DrawableRes icon: Int,
        extras: Bundle
    ) = BrowserMediaItem(
        BrowserMediaDescription.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setIconUri(uriFor(icon))
            .setExtras(extras)
            .build(),
        BrowserMediaItem.FLAG_BROWSABLE
    )

    private fun playableShortcut(
        mediaId: String,
        title: String,
        subtitle: String,
        @DrawableRes icon: Int
    ) = BrowserMediaItem(
        BrowserMediaDescription.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIconUri(uriFor(icon))
            .setExtras(bundleOf(ContentStyle.GROUP_TITLE_HINT to "Start something"))
            .build(),
        BrowserMediaItem.FLAG_PLAYABLE
    )

    // Home is a mixed screen: the shortcuts read better as rows, but nothing underneath it is
    // browsable, so the browsable hint is academic and kept consistent rather than clever.
    private val homeBrowserMediaItem
        get() = browsable(
            MediaId.home,
            "Home",
            R.drawable.sparkles,
            styleExtras(ContentStyle.LIST, ContentStyle.LIST)
        )

    private val songsTabBrowserMediaItem
        get() = browsable(
            MediaId.songs,
            "Songs",
            R.drawable.musical_notes,
            styleExtras(ContentStyle.LIST, ContentStyle.LIST)
        )

    /**
     * A song whose artist never got an [Artist] row -- browsable by name, matched back against
     * the songs that carry it.
     */
    private fun artistNameBrowserMediaItem(name: String) = BrowserMediaItem(
        BrowserMediaDescription.Builder()
            .setMediaId(MediaId.forArtist("$ArtistNamePrefix${Uri.encode(name)}"))
            .setTitle(name)
            .setIconUri(uriFor(R.drawable.person))
            .setExtras(styleExtras(ContentStyle.GRID, ContentStyle.LIST))
            .build(),
        BrowserMediaItem.FLAG_BROWSABLE
    )

    // Grids: covers and faces are what make these scannable at a glance, and a glance is all a
    // driver gets. Their children are songs, which stay a list.
    private fun playlistsBrowserMediaItem(group: String?) = browsable(
        MediaId.playlists,
        "Playlists",
        R.drawable.playlist,
        styleExtras(ContentStyle.GRID, ContentStyle.LIST)
            .apply { group?.let { putString(ContentStyle.GROUP_TITLE_HINT, it) } }
    )

    private val albumsBrowserMediaItem
        get() = browsable(
            MediaId.albums,
            "Albums",
            R.drawable.disc,
            styleExtras(ContentStyle.GRID, ContentStyle.LIST)
        )

    private fun artistsBrowserMediaItem(hasArtwork: Boolean) = browsable(
        MediaId.artists,
        "Artists",
        R.drawable.person,
        styleExtras(if (hasArtwork) ContentStyle.GRID else ContentStyle.LIST, ContentStyle.LIST)
    )

    private val shuffleBrowserMediaItem
        get() = playableShortcut(
            MediaId.shuffle,
            "Shuffle everything",
            "Your whole library, in no particular order",
            R.drawable.shuffle
        )

    private val favoritesBrowserMediaItem
        get() = playableShortcut(
            MediaId.favorites,
            "Favourites",
            "Everything you have hearted",
            R.drawable.heart
        )

    private val offlineBrowserMediaItem
        get() = playableShortcut(
            MediaId.offline,
            "Downloaded",
            "Plays without a signal",
            R.drawable.airplane
        )

    private fun shuffleBrowserMediaItemFor(scopedId: String) = BrowserMediaItem(
        BrowserMediaDescription.Builder()
            .setMediaId("${MediaId.shuffle}/$scopedId")
            .setTitle("Shuffle")
            .setIconUri(uriFor(R.drawable.shuffle))
            .build(),
        BrowserMediaItem.FLAG_PLAYABLE
    )

    private val Artist.asBrowserMediaItem
        get() = BrowserMediaItem(
            BrowserMediaDescription.Builder()
                .setMediaId(MediaId.forArtist(id))
                .setTitle(name ?: "Unknown artist")
                .setIconUri(thumbnailUrl.thumbnail(CarArtworkSize)?.toUri() ?: uriFor(R.drawable.person))
                .setExtras(styleExtras(ContentStyle.GRID, ContentStyle.LIST))
                .build(),
            BrowserMediaItem.FLAG_BROWSABLE
        )

    /**
     * [group] draws a section header above the run of items that share it, which is what keeps
     * Home from reading as one undifferentiated column.
     */
    private fun Song.asBrowserMediaItem(
        cachedIds: Set<String> = emptySet(),
        group: String? = null
    ) = BrowserMediaItem(
        BrowserMediaDescription.Builder()
            .setMediaId(MediaId.forSong(id))
            .setTitle(title)
            .setSubtitle(artistsText)
            .setIconUri(thumbnailUrl.thumbnail(CarArtworkSize)?.toUri())
            .setExtras(
                Bundle().apply {
                    group?.let { putString(ContentStyle.GROUP_TITLE_HINT, it) }
                    // Marks the rows that will still play once the signal drops.
                    if (id in cachedIds) putLong(DownloadStatus.KEY, DownloadStatus.DOWNLOADED)
                }
            )
            .build(),
        BrowserMediaItem.FLAG_PLAYABLE
    )


    private val PlaylistPreview.asBrowserMediaItem
        get() = BrowserMediaItem(
            BrowserMediaDescription.Builder()
                .setMediaId(MediaId.forPlaylist(playlist.id))
                .setTitle(playlist.name)
                .setSubtitle(if (songCount == 1) "1 song" else "$songCount songs")
                .setIconUri(uriFor(R.drawable.playlist))
                .build(),
            BrowserMediaItem.FLAG_PLAYABLE
        )

    private val Album.asBrowserMediaItem
        get() = BrowserMediaItem(
            BrowserMediaDescription.Builder()
                .setMediaId(MediaId.forAlbum(id))
                .setTitle(title ?: "Unknown album")
                .setSubtitle(authorsText)
                .setIconUri(thumbnailUrl.thumbnail(CarArtworkSize)?.toUri() ?: uriFor(R.drawable.disc))
                .build(),
            BrowserMediaItem.FLAG_PLAYABLE
        )

    /**
     * Android Auto replaces the session callback when it binds, so this extends the service's own
     * rather than shadowing it -- otherwise the heart, shuffle and repeat buttons would go dead
     * the moment the phone was plugged into a car.
     */
    private inner class AutoSessionCallback(
        private val player: Player,
        private val cache: Cache
    ) : PlayerService.SessionCallback(player) {
        override fun onCustomAction(action: String, extras: Bundle?) {
            if (action == ACTION_START_RADIO) {
                val videoId = player.currentMediaItem?.mediaId ?: return
                binder?.playRadio(NavigationEndpoint.Endpoint.Watch(videoId = videoId))
            } else {
                super.onCustomAction(action, extras)
            }
        }

        /**
         * Voice search from the car. Matched against the local library only -- a spoken request
         * should start something immediately rather than wait on the network at 70mph.
         */
        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            coroutineScope.launch {
                val matches = searchLibrary(query.orEmpty())
                    .ifEmpty { Database.songsByPlayTimeDesc().first() }
                    .let { if (query.isNullOrBlank()) it.shuffled() else it }

                lastSongs = matches
                if (matches.isNotEmpty()) withContext(Dispatchers.Main) {
                    player.forcePlayFromBeginning(matches.map(Song::asMediaItem))
                }
            }
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            val data = mediaId?.split('/') ?: return
            var index = 0

            coroutineScope.launch {
                val mediaItems = when (data.getOrNull(0)) {
                    MediaId.shuffle -> when (data.getOrNull(1)) {
                        MediaId.artists -> data
                            .getOrNull(2)
                            ?.let { songsForArtistNode(it) }
                            ?.shuffled()

                        else -> lastSongs
                    }

                    MediaId.songs ->  data
                        .getOrNull(1)
                        ?.let { songId ->
                            index = lastSongs.indexOfFirst { it.id == songId }
                            lastSongs
                        }

                    MediaId.favorites -> Database
                        .favorites()
                        .first()
                        .shuffled()

                    MediaId.offline -> Database
                        .songsWithContentLength()
                        .first()
                        .filter { song ->
                            song.contentLength?.let {
                                cache.isCached(song.song.id, 0, it)
                            } ?: false
                        }
                        .map(SongWithContentLength::song)
                        .shuffled()

                    MediaId.playlists -> data
                        .getOrNull(1)
                        ?.toLongOrNull()
                        ?.let(Database::playlistWithSongs)
                        ?.first()
                        ?.songs
                        ?.shuffled()

                    MediaId.albums -> data
                        .getOrNull(1)
                        ?.let(Database::albumSongs)
                        ?.first()

                    MediaId.artists -> data
                        .getOrNull(1)
                        ?.let { songsForArtistNode(it) }

                    else -> emptyList()
                }?.map(Song::asMediaItem) ?: return@launch

                withContext(Dispatchers.Main) {
                    player.forcePlayAtIndex(mediaItems, index.coerceIn(0, mediaItems.size))
                }
            }
        }
    }

    /**
     * Presentation hints read by the car's media browser. They are the only real control an app
     * has over how it looks there -- the rest of the interface belongs to the car for safety
     * reasons -- so the tree is shaped around them rather than fought against.
     */
    private object ContentStyle {
        const val SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"

        const val GROUP_TITLE_HINT = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"

        const val LIST = 1
        const val GRID = 2
    }

    /** Puts a download badge on rows that will keep playing when the signal drops. */
    private object DownloadStatus {
        const val KEY = "android.media.extra.DOWNLOAD_STATUS"
        const val DOWNLOADED = 2L
    }

    private object MediaId {
        const val root = "root"
        const val home = "home"
        const val recent = "recent"
        const val songs = "songs"
        const val playlists = "playlists"
        const val albums = "albums"
        const val artists = "artists"

        const val favorites = "favorites"
        const val offline = "offline"
        const val shuffle = "shuffle"

        fun forSong(id: String) = "songs/$id"
        fun forPlaylist(id: Long) = "playlists/$id"
        fun forAlbum(id: String) = "albums/$id"
        fun forArtist(id: String) = "artists/$id"
    }
}
