package com.ninedtechnologies.adlogoverlay

import android.app.Activity

/**
 * Everything about the overlay that belongs to the HOST app rather than to the overlay.
 *
 * Colours are resource ids, not resolved ints, on purpose: they are looked up against each
 * Activity's own context, so `values-night` still decides which value comes back and the
 * overlay follows the app's theme without being told about it.
 */
data class AdLogConfig(
    val surfaceColor: Int = R.color.adlog_surface,
    val headingColor: Int = R.color.adlog_heading,
    val subtextColor: Int = R.color.adlog_subtext,
    val borderColor: Int = R.color.adlog_border,
    val containerColor: Int = R.color.adlog_container,

    /**
     * Log tags the host's ad stack uses that do not contain "ad". Tags that DO contain "ad"
     * are picked up without configuration.
     */
    val extraTags: Set<String> = emptySet(),

    /**
     * False turns the logcat reader off entirely and leaves [AdLogStore.post] as the only
     * way in - the integration for a host that would rather call the overlay directly.
     */
    val readLogcat: Boolean = true,

    /** How a screen is named in the `@Screen` suffix and in the screen filter. */
    val screenNameOf: (Activity) -> String = { it.javaClass.simpleName },

    /**
     * Plain names for coded values in Remote Config, so the FRC tab can show `Interstitial (3)`
     * instead of a bare `3`. Keyed by the JSON field name, then by the value as written:
     *
     * ```
     * mapOf("adType" to mapOf("1" to "Banner", "3" to "Interstitial"))
     * ```
     *
     * Only the host knows what its codes mean, which is why this lives here and not in the
     * overlay. A value with no entry is shown as it is.
     */
    val remoteConfigValueLabels: Map<String, Map<String, String>> = emptyMap(),

    /**
     * A click this soon after the ad appeared is flagged FAST CLICK - likely accidental, the kind of
     * click AdMob treats as invalid traffic. Measured from the ad's first SHOWN or IMPRESSION line.
     */
    val fastClickMillis: Long = 1_000L
)
