<div align="center">
    <img src="./app/src/main/ic_launcher-playstore.png" width="128" height="128" style="display: block; margin: 0 auto"/>
    <h1>ViMusic</h1>
    <p>An Android application for streaming music from YouTube Music</p>
</div>

---

> **This is a fork.** The original [vfsfitvnm/ViMusic](https://github.com/vfsfitvnm/ViMusic) was
> archived, and by then it had stopped playing music: YouTube began answering the client it used
> with `LOGIN_REQUIRED`. This fork brings playback back and continues from there.
>
> No Google account, no sign-in, no Play Services, no Firebase.

<p align="center">
  <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width="30%" />
  <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width="30%" />
  <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg" width="30%" />

  <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg" width="30%" />
  <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/5.jpg" width="30%" />
  <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/6.jpg" width="30%" />
</p>

## What is different from upstream

**Playback works again.** Three things have to line up now, and all three are done without an
account: a session-bound proof-of-origin token on the `/player` request, the signature timestamp
taken from YouTube's player script, and the deciphered `sig` and de-throttled `n` on the stream URL.

**The cipher repairs itself.** YouTube rotates its player script every week or two, and the two
entry points it needs are minified closure locals with no stable name to match on. Rather than look
them up in a list that someone has to keep updating, the app *runs* candidates against the player
and keeps whichever one actually descrambles — see
[`cipher_discovery.js`](./app/src/main/assets/cipher_discovery.js). A curated registry is still
consulted, but only as a backstop when discovery finds nothing.

**Android Auto rebuilt.** Four browse tabs (the car shows no more than four), grouped shortcuts,
artwork grids, offline badges, and shuffle / repeat / start-radio / like controls on the
now-playing screen.

**Search shows real results as you type.** YouTube's suggestion endpoint returns query completions
*and* the entities it ranks — the artist, their songs, their playlists. Only the completions were
being read. Both are shown now, and where YouTube omits an artist row for a smaller artist, one is
rebuilt from the song credits.

**Artwork is sharp.** Thumbnails were being served at 60 pixels and upscaled everywhere, because
the resize helper did not recognise the image host YouTube now returns.

**Library screens reflect what you play.** Artists and Albums used to list only what had been
explicitly bookmarked, which for most people is nothing at all.

Also modernised throughout: AGP 8.13, Kotlin 2.3, Compose BOM 2026.06, Media3 1.11, Room 2.8, KSP
in place of kapt.

## Features
- Play (almost) any song or video from YouTube Music
- Background playback
- Cache audio chunks for offline playback
- Search for songs, albums, artists, videos and playlists
- Bookmark artists and albums
- Import playlists
- Fetch, display and edit song lyrics, plain or synchronised
- Local playlist management
- Reorder songs in a playlist or the queue
- Light / Dark / Dynamic theme
- Skip silence
- Sleep timer
- Audio normalization
- Android Auto
- Persistent queue
- Open YouTube/YouTube Music links (`watch`, `playlist`, `channel`)

## Installation

Builds are published on this repository's Releases page. This fork is not on F-Droid or
IzzyOnDroid; the badges that used to be here pointed at the upstream project and would have
installed a different application.

## Acknowledgments

This fork stands on other people's work, all of it GPL-3.0 like this project:

- [**vfsfitvnm/ViMusic**](https://github.com/vfsfitvnm/ViMusic) — the original application. Nearly
  everything here is still theirs.
- [**Metrolist**](https://github.com/mostafaalagamy/Metrolist) — the proof-of-origin token
  implementation in `app/src/main/kotlin/.../service/potoken/` is ported from it.
- [**zemer-cipher**](https://github.com/ZemerTeam/zemer-cipher) — the player registry format
  (`app/src/main/assets/player_configs.json`) and the shape of the `n`-transform wrapper.
- [**YouTube-Internal-Clients**](https://github.com/zerodytrash/YouTube-Internal-Clients): A python
  script that discovers hidden YouTube API clients. Just a research project.
- [**ionicons**](https://github.com/ionic-team/ionicons): Premium hand-crafted icons built by Ionic,
  for Ionic apps and web apps everywhere.

<a href="https://www.flaticon.com/authors/ilham-fitrotul-hayat" title="music icons">App icon based on icon created by Ilham Fitrotul Hayat - Flaticon</a>

## License

GPL-3.0, inherited from the original project. See [LICENSE](./LICENSE).

## Disclaimer
This project and its contents are not affiliated with, funded, authorized, endorsed by, or in any way associated with YouTube, Google LLC or any of its affiliates and subsidiaries.

Any trademark, service mark, trade name, or other intellectual property rights used in this project are owned by the respective owners.
