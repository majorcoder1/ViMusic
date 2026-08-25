package it.vfsfitvnm.vimusic.ui.screens.artist

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import it.vfsfitvnm.innertube.Innertube
import it.vfsfitvnm.vimusic.ui.styling.LocalAppearance
import it.vfsfitvnm.vimusic.utils.color
import it.vfsfitvnm.vimusic.utils.secondary
import it.vfsfitvnm.vimusic.utils.semiBold

/**
 * Artist header: the photo carries the top of the screen, with the name, following count and
 * biography reading over the bottom of it.
 *
 * The previous header put a small circular thumbnail under a plain text title, which gave a page
 * about a person no sense of who it was about. The artwork is the most identifying thing the API
 * returns, so it leads.
 */
@Composable
fun ArtistHeader(
    artistPage: Innertube.ArtistPage?,
    thumbnailUrl: String?,
    name: String?,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {}
) {
    val (colorPalette, typography) = LocalAppearance.current
    var isDescriptionExpanded by remember { mutableStateOf(false) }

    val image = artistPage?.thumbnail?.url ?: thumbnailUrl
    val description = artistPage?.description
    val subscribers = artistPage?.subscriberCountText

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.15f)
        ) {
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colorPalette.background1)
                )
            }

            // The name sits on the photo, so the photo has to fade out underneath it or the
            // lettering competes with whatever happens to be in the lower half of the picture.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .drawWithCache {
                        val scrim = Brush.verticalGradient(
                            0f to colorPalette.background0.copy(alpha = 0f),
                            1f to colorPalette.background0
                        )
                        onDrawBehind { drawRect(scrim) }
                    }
                    .padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 12.dp)
            ) {
                BasicText(
                    text = name ?: "Unknown",
                    style = typography.xxl.semiBold.color(colorPalette.text),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                subscribers?.let {
                    BasicText(
                        text = "$it subscribers",
                        style = typography.xs.secondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { actions() }

        description?.takeIf { it.isNotBlank() }?.let { text ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .animateContentSize()
                    .clickable { isDescriptionExpanded = !isDescriptionExpanded }
            ) {
                BasicText(
                    text = "About",
                    style = typography.m.semiBold.color(colorPalette.text),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                BasicText(
                    text = text,
                    style = typography.xs.secondary,
                    maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis
                )
                BasicText(
                    text = if (isDescriptionExpanded) "Show less" else "Show more",
                    style = typography.xs.semiBold.color(colorPalette.accent),
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                )
            }
        }
    }
}
