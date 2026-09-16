package com.ninedtechnologies.adlogoverlay

/**
 * What the panel is currently showing. Four independent facets plus a free-text query, all
 * ANDed, each of them ignored while it is empty.
 *
 * ## It filters the view, not the buffer
 *
 * [AdLogStore] keeps everything it ingested; this only decides what is drawn. Clearing a
 * facet brings the matching history straight back instead of starting an empty panel, which
 * is the behaviour a tester expects from a filter and the reason none of this lives in the
 * store.
 *
 * State is in an object rather than in the view because [AdLogOverlay] throws the view away
 * and builds a new one on every screen change - a filter that reset itself on navigation
 * would be unusable for watching an ad flow across screens.
 */
object AdLogFilter {

    /** Plain-language mode: the five moments of one ad's life and nothing else. */
    var qaOnly: Boolean = false

    /** Lower-cased; matched against [AdLogEntry.search]. */
    var query: String = ""
        set(value) { field = value.trim().lowercase() }

    val kinds = linkedSetOf<AdLogKind>()
    val screens = linkedSetOf<String>()
    val placements = linkedSetOf<String>()
    val reasons = linkedSetOf<String>()

    /**
     * QA mode is the story of one ad, in five words a tester already knows:
     *
     *     REQUESTED -> LOADED -> SAVED -> SHOWN        (and FAILED when it goes wrong)
     *
     * BLOCKED is the sixth, and earns its place because "we never even asked, a rule stopped
     * it" is the single most common answer to "why did no ad appear".
     */
    private val QA_KINDS = setOf(
        AdLogKind.REQUEST, AdLogKind.LOADED, AdLogKind.CACHE,
        AdLogKind.SHOWN, AdLogKind.FAILED, AdLogKind.DENIED
    )

    val isActive: Boolean
        get() = qaOnly || query.isNotEmpty() || kinds.isNotEmpty() ||
            screens.isNotEmpty() || placements.isNotEmpty() || reasons.isNotEmpty()

    fun clear() {
        qaOnly = false
        query = ""
        kinds.clear(); screens.clear(); placements.clear(); reasons.clear()
    }

    fun matches(e: AdLogEntry): Boolean {
        // A tester's MARK is where they are in the run; hiding it behind a filter would lose the place.
        if (e.mark != null) return true
        if (qaOnly) {
            // A fast click is exactly what QA mode exists to surface, whatever its kind.
            if (e.kind !in QA_KINDS && e.fastClickMillis == null) return false
            // A QA line must name the ad slot it is about, or it is a stray SDK line that
            // merely classified as an event. Failures are the exception: "something broke"
            // is worth showing even when the slot cannot be identified.
            if (e.placement == null && e.kind != AdLogKind.FAILED && e.kind != AdLogKind.DENIED) return false
        }
        if (kinds.isNotEmpty() && e.kind !in kinds) return false
        if (screens.isNotEmpty() && e.screen !in screens) return false
        if (placements.isNotEmpty() && e.placement !in placements) return false
        if (reasons.isNotEmpty() && e.reason !in reasons) return false
        if (query.isNotEmpty() && !e.search.contains(query)) return false
        return true
    }

    /** One line naming every active facet, for the header. */
    fun summary(): String {
        val parts = ArrayList<String>(5)
        if (qaOnly) parts += "QA"
        if (kinds.isNotEmpty()) parts += kinds.joinToString("/") { it.label.trim() }
        if (screens.isNotEmpty()) parts += screens.joinToString("/")
        if (placements.isNotEmpty()) parts += placements.joinToString("/")
        if (reasons.isNotEmpty()) parts += "${reasons.size} error${if (reasons.size == 1) "" else "s"}"
        if (query.isNotEmpty()) parts += "\"$query\""
        return parts.joinToString("  ·  ")
    }
}
