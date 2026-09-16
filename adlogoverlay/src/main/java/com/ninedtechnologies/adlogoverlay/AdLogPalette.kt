package com.ninedtechnologies.adlogoverlay

import android.content.Context
import android.graphics.Color

/**
 * The overlay's colours for ONE Activity context, resolved from the host's semantic tokens.
 *
 * The panel is drawn on the host's own `surface` colour instead of a fixed dark scrim, so it
 * belongs to whichever theme is active. That has a consequence the event palette has to pay
 * for: text that is legible on a dark panel is not legible on a light one, so [AdLogKind]
 * carries two hues per kind and [isDarkSurface] decides which one is used.
 *
 * [PANEL_ALPHA] is the tightest translucency this can afford. The panel floats over
 * arbitrary app content - a white PDF page under a light panel, black letterboxing under a
 * dark one - so the surface that the text actually lands on is the token colour composited
 * over that content. At 95% the worst case is #F2F2F2 in light and #292929 in dark, and both
 * palettes are chosen against those worst cases rather than against the token colour itself.
 */
class AdLogPalette private constructor(
    val surface: Int,
    val panel: Int,
    val heading: Int,
    val subtext: Int,
    val border: Int,
    val container: Int,
    val isDarkSurface: Boolean
) {

    fun colorOf(kind: AdLogKind): Int = if (isDarkSurface) kind.onDark else kind.onLight

    /** Same hue, softer: a line's body reads as secondary to its own label. */
    fun dim(color: Int): Int = Color.argb(DIM_ALPHA, Color.red(color), Color.green(color), Color.blue(color))

    fun alpha(color: Int, a: Int): Int = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    companion object {

        private const val PANEL_ALPHA = 0xF2

        private const val DIM_ALPHA = 0xE6

        /** Below this luma the surface counts as dark. #1E1E1E scores 30, #FFFFFF scores 255. */
        private const val DARK_LUMA = 140

        fun resolve(context: Context, config: AdLogConfig): AdLogPalette {
            val surface = context.getColor(config.surfaceColor)
            val luma = (299 * Color.red(surface) + 587 * Color.green(surface) + 114 * Color.blue(surface)) / 1000
            return AdLogPalette(
                surface = surface,
                panel = Color.argb(PANEL_ALPHA, Color.red(surface), Color.green(surface), Color.blue(surface)),
                heading = context.getColor(config.headingColor),
                subtext = context.getColor(config.subtextColor),
                border = context.getColor(config.borderColor),
                container = context.getColor(config.containerColor),
                isDarkSurface = luma < DARK_LUMA
            )
        }
    }
}
