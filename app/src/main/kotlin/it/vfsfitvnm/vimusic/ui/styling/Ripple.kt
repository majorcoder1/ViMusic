package it.vfsfitvnm.vimusic.ui.styling

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material.ripple.createRippleModifierNode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.unit.Dp

/*
 * ViMusic draws from its own palette rather than Material, so it cannot use the `ripple()` that
 * ships with material/material3. `RippleTheme`, `LocalRippleTheme` and `rememberRipple` — what this
 * app used before — are now deprecated at error level; `createRippleModifierNode` is the supported
 * replacement for a custom design system, and is what backs the ripple below.
 *
 * The alpha values match the ones the old default `RippleTheme` produced, so ripples look the same.
 */

private val DarkThemeRippleAlpha = RippleAlpha(
    pressedAlpha = 0.10f,
    focusedAlpha = 0.12f,
    draggedAlpha = 0.08f,
    hoveredAlpha = 0.04f
)

private val LightThemeRippleAlpha = RippleAlpha(
    pressedAlpha = 0.24f,
    focusedAlpha = 0.24f,
    draggedAlpha = 0.16f,
    hoveredAlpha = 0.08f
)

@Stable
private class Ripple(
    private val bounded: Boolean,
    private val radius: Dp,
    private val color: Color,
    private val rippleAlpha: RippleAlpha
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        createRippleModifierNode(
            interactionSource = interactionSource,
            bounded = bounded,
            radius = radius,
            color = ColorProducer { color },
            rippleAlpha = { rippleAlpha }
        )

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other !is Ripple -> false
        else -> bounded == other.bounded && radius == other.radius &&
            color == other.color && rippleAlpha == other.rippleAlpha
    }

    override fun hashCode(): Int {
        var result = bounded.hashCode()
        result = 31 * result + radius.hashCode()
        result = 31 * result + color.hashCode()
        result = 31 * result + rippleAlpha.hashCode()
        return result
    }
}

/** A ripple in an explicit colour — for use before [LocalAppearance] has been provided. */
@Stable
fun ripple(
    color: Color,
    isDark: Boolean,
    bounded: Boolean = true,
    radius: Dp = Dp.Unspecified
): IndicationNodeFactory = Ripple(
    bounded = bounded,
    radius = radius,
    color = color,
    rippleAlpha = if (isDark) DarkThemeRippleAlpha else LightThemeRippleAlpha
)

/** A ripple that picks up the current [Appearance]. */
@Composable
fun ripple(
    bounded: Boolean = true,
    radius: Dp = Dp.Unspecified
): IndicationNodeFactory {
    val colorPalette = LocalAppearance.current.colorPalette
    return remember(bounded, radius, colorPalette.text, colorPalette.isDark) {
        ripple(
            color = colorPalette.text,
            isDark = colorPalette.isDark,
            bounded = bounded,
            radius = radius
        )
    }
}
