package com.ninedtechnologies.adlogoverlay

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The numbers behind the STATS tab: one funnel per ad slot, counted from the same classified lines
 * the log shows. Pure Kotlin - no Android types - so every rule here is unit tested.
 *
 * ## What counts
 *
 * Only lines that name a slot ([AdLogEntry.placement]). An SDK line that names no slot cannot be
 * attributed, and a guess would put numbers under the wrong ad.
 *
 *  - **requested, loaded, failed, clicked** - one per REQUESTED, LOADED, FAILED or CLICKED line. The
 *    same kind again for the same slot within [DUPLICATE_MS] is one event logged twice (a wrapper
 *    and its callback both logging it) and is not counted again.
 *  - **shown** - once per appearance. A full-screen ad logs SHOWN and then IMPRESSION for the same
 *    showing, so the first of the two after a load counts and the other does not.
 *  - **fast click** - a click less than the configured threshold after the ad appeared.
 *  - **load time** - LOADED minus the REQUESTED before it, for the same slot.
 *  - **cached** - loaded, and not yet shown, expired, removed or failed to show. Banners are never
 *    cached: a loaded banner is already on screen.
 *
 * Not thread safe; [AdLogStore] calls it under its own lock.
 */
internal class AdLogStats(now: Long) {

    var sessionStartMillis: Long = now
        private set

    private val slots = LinkedHashMap<String, Slot>()

    /** Fast clicks in the session, for the dot on the STATS tab. */
    var fastClickTotal: Int = 0
        private set

    val placements: Collection<String> get() = slots.keys

    fun clear(now: Long) {
        slots.clear()
        fastClickTotal = 0
        sessionStartMillis = now
    }

    /**
     * Counts [entry] and returns it with what the log line should say about it: the load time on a
     * LOADED line, the gap on a fast click. Lines that name no slot come back unchanged.
     */
    fun onEntry(entry: AdLogEntry, fastClickMillis: Long): AdLogEntry {
        val placement = entry.placement ?: return entry
        val slot = slots[placement] ?: run {
            // Only an event opens a block. A slot merely NAMED - in a settings dump, a "removed" line
            // for an ad never seen loading - would otherwise add a row of zeros.
            if (entry.kind !in OPENS_SLOT || slots.size >= MAX_SLOTS) return entry
            Slot(placement, slots.size).also { slots[placement] = it }
        }
        val t = entry.atMillis
        return when (entry.kind) {
            AdLogKind.REQUEST -> {
                if (slot.count(entry.kind, t)) {
                    slot.requested++
                    slot.requestAt = t
                }
                entry
            }

            AdLogKind.LOADED -> {
                if (!slot.count(entry.kind, t)) return entry
                slot.loaded++
                val load = slot.requestAt?.let { t - it }?.takeIf { it in 0..MAX_LOAD_MS }
                if (load != null) {
                    slot.loadTotalMs += load
                    slot.loadSamples++
                }
                slot.requestAt = null
                slot.appearedAt = null
                if (lifetimeOf(AdPlacement.formatOf(placement)) != null) slot.cachedSince = t
                if (load != null) entry.copy(loadMillis = load) else entry
            }

            AdLogKind.FAILED -> {
                if (entry.message.contains("show", ignoreCase = true)) {
                    // Failed to SHOW: the ad that was waiting is used up. Not a failed load.
                    slot.cachedSince = null
                } else if (slot.count(entry.kind, t)) {
                    slot.failed++
                    slot.requestAt = null
                    entry.reason?.let { slot.reasons[it] = (slot.reasons[it] ?: 0) + 1 }
                }
                entry
            }

            AdLogKind.SHOWN, AdLogKind.IMPRESSION -> {
                if (slot.appearedAt == null) {
                    slot.shown++
                    slot.appearedAt = t
                    slot.cachedSince = null
                }
                entry
            }

            AdLogKind.CLICK -> {
                if (!slot.count(entry.kind, t)) return entry
                slot.clicked++
                val gap = slot.appearedAt?.let { t - it }?.takeIf { it in 0 until fastClickMillis }
                    ?: return entry
                slot.fastClicks++
                fastClickTotal++
                entry.copy(fastClickMillis = gap)
            }

            AdLogKind.DISMISSED -> {
                slot.appearedAt = null
                entry
            }

            AdLogKind.EXPIRED, AdLogKind.DESTROY -> {
                slot.cachedSince = null
                entry
            }

            else -> entry
        }
    }

    fun snapshot(): AdStatsSnapshot = AdStatsSnapshot(
        sessionStartMillis,
        slots.values.map { s ->
            AdSlotSnapshot(
                placement = s.placement,
                format = AdPlacement.formatOf(s.placement),
                requested = s.requested,
                loaded = s.loaded,
                failed = s.failed,
                shown = s.shown,
                clicked = s.clicked,
                fastClicks = s.fastClicks,
                averageLoadMillis = if (s.loadSamples == 0) null else s.loadTotalMs / s.loadSamples,
                cachedSinceMillis = s.cachedSince,
                loadingSinceMillis = s.requestAt,
                topFailure = s.reasons.maxByOrNull { it.value }?.key,
                order = s.order
            )
        }
    )

    private class Slot(val placement: String, val order: Int) {
        var requested = 0
        var loaded = 0
        var failed = 0
        var shown = 0
        var clicked = 0
        var fastClicks = 0
        var loadTotalMs = 0L
        var loadSamples = 0
        var requestAt: Long? = null
        var cachedSince: Long? = null
        var appearedAt: Long? = null
        val reasons = LinkedHashMap<String, Int>()
        private val countedAt = HashMap<AdLogKind, Long>()

        /** True when this line is a new event rather than a repeat of the one just counted. */
        fun count(kind: AdLogKind, t: Long): Boolean {
            val last = countedAt[kind]
            if (last != null && kotlin.math.abs(t - last) < DUPLICATE_MS) return false
            countedAt[kind] = t
            return true
        }
    }

    companion object {
        /** Two lines of one kind for one slot this close together are one event. */
        const val DUPLICATE_MS = 500L

        /** A LOADED further than this from its REQUESTED is not the answer to it. */
        const val MAX_LOAD_MS = 120_000L

        /** A pathological log must not grow the table without bound. */
        const val MAX_SLOTS = 100

        private val OPENS_SLOT = setOf(
            AdLogKind.REQUEST, AdLogKind.LOADED, AdLogKind.FAILED,
            AdLogKind.SHOWN, AdLogKind.IMPRESSION, AdLogKind.CLICK
        )

        const val HOUR_MS = 3_600_000L

        /** Within this of Google's expiry, a cached ad is flagged as expiring soon. */
        const val EXPIRING_SOON_MS = 10 * 60_000L

        /**
         * How long Google says a loaded ad stays valid, by format. From the AdMob Android guides:
         * app open "will time out after four hours"; interstitial, rewarded and native all say
         * "ads expire after an hour". Banners are shown as soon as they load, so they have none.
         */
        fun lifetimeOf(format: String?): Long? = when (format) {
            "app open" -> 4 * HOUR_MS
            "interstitial", "rewarded", "native" -> HOUR_MS
            else -> null
        }
    }
}

internal enum class AdCacheState { NONE, FRESH, EXPIRING_SOON, EXPIRED }

internal class AdSlotSnapshot(
    val placement: String,
    val format: String?,
    val requested: Int,
    val loaded: Int,
    val failed: Int,
    val shown: Int,
    val clicked: Int,
    val fastClicks: Int,
    val averageLoadMillis: Long?,
    val cachedSinceMillis: Long?,
    val loadingSinceMillis: Long?,
    val topFailure: String?,
    val order: Int
) {
    val lifetimeMillis: Long? get() = AdLogStats.lifetimeOf(format)

    fun cacheAgeMillis(now: Long): Long? = cachedSinceMillis?.let { (now - it).coerceAtLeast(0L) }

    fun cacheState(now: Long): AdCacheState {
        val age = cacheAgeMillis(now) ?: return AdCacheState.NONE
        val life = lifetimeMillis ?: return AdCacheState.FRESH
        return when {
            age >= life -> AdCacheState.EXPIRED
            life - age <= AdLogStats.EXPIRING_SOON_MS -> AdCacheState.EXPIRING_SOON
            else -> AdCacheState.FRESH
        }
    }

    /** Loaded as a share of requested. Null when there is nothing to divide, or the log missed requests. */
    val fillPercent: Int?
        get() = if (requested == 0 || loaded > requested) null else (loaded * 100.0 / requested).roundToInt()

    /** Shown as a share of loaded. Null likewise. */
    val showPercent: Int?
        get() = if (loaded == 0 || shown > loaded) null else (shown * 100.0 / loaded).roundToInt()

    /**
     * Fill under half, not counting a request still waiting for its answer - one request in flight
     * is not a no-fill.
     */
    fun lowFill(now: Long): Boolean {
        val waiting = loadingSinceMillis?.let { now - it < AdLogStats.MAX_LOAD_MS } == true
        val answered = requested - if (waiting) 1 else 0
        return answered > 0 && loaded * 2 < answered
    }

    /**
     * Under half of the loaded ads shown, not counting the one cached right now: an ad still
     * waiting for its moment has not been wasted yet.
     */
    fun lowShow(): Boolean {
        val decided = loaded - if (cachedSinceMillis != null) 1 else 0
        return decided > 0 && shown * 2 < decided
    }

    /** 0 fast clicks, 1 low rates or an expired ad, 2 expiring soon, 3 fine. Lower sorts first. */
    fun severity(now: Long): Int = when {
        fastClicks > 0 -> 0
        lowFill(now) || lowShow() || cacheState(now) == AdCacheState.EXPIRED -> 1
        cacheState(now) == AdCacheState.EXPIRING_SOON -> 2
        else -> 3
    }
}

internal class AdStatsSnapshot(val sessionStartMillis: Long, val slots: List<AdSlotSnapshot>) {

    /** Problems first; within a severity, the order slots were first seen, so blocks do not reshuffle. */
    fun ordered(now: Long): List<AdSlotSnapshot> =
        slots.sortedWith(compareBy<AdSlotSnapshot>({ it.severity(now) }, { it.order }))

    val requested: Int get() = slots.sumOf { it.requested }
    val loaded: Int get() = slots.sumOf { it.loaded }
    val shown: Int get() = slots.sumOf { it.shown }
    val fastClicks: Int get() = slots.sumOf { it.fastClicks }
}

/** How a piece of STATS text should look; the view maps these onto its palette. */
internal enum class AdLogTone { NORMAL, MUTED, TITLE, GOOD, WARN, BAD }

internal data class AdLogSpan(val text: String, val tone: AdLogTone = AdLogTone.NORMAL, val bold: Boolean = false)

/**
 * The STATS tab's words, separate from its drawing: the panel paints these spans, SHARE joins their
 * text. One source, so the shared report always says what the tab says.
 */
internal object AdLogStatsText {

    private const val SEP = "  ·  "

    /** Label and value for the three session rows. */
    fun sessionRows(stats: AdStatsSnapshot, now: Long): List<Pair<String, List<AdLogSpan>>> {
        val rows = ArrayList<Pair<String, List<AdLogSpan>>>(3)
        rows += "SESSION" to listOf(
            AdLogSpan(sessionLength(now - stats.sessionStartMillis)),
            AdLogSpan(SEP, AdLogTone.MUTED),
            AdLogSpan("${stats.requested} requested"),
            AdLogSpan(SEP, AdLogTone.MUTED),
            AdLogSpan("${stats.loaded} loaded"),
            AdLogSpan(SEP, AdLogTone.MUTED),
            AdLogSpan("${stats.shown} shown")
        )

        val fast = stats.slots.filter { it.fastClicks > 0 }
        rows += "FAST CLICKS" to if (fast.isEmpty()) {
            listOf(AdLogSpan("none", AdLogTone.MUTED))
        } else {
            val names = fast.take(2).joinToString(", ") { it.placement } +
                if (fast.size > 2) " +${fast.size - 2}" else ""
            listOf(
                AdLogSpan("${stats.fastClicks}", AdLogTone.BAD, bold = true),
                AdLogSpan(SEP, AdLogTone.MUTED),
                AdLogSpan(names)
            )
        }

        val cached = stats.slots.filter { it.cachedSinceMillis != null }
        rows += "CACHED NOW" to if (cached.isEmpty()) {
            listOf(AdLogSpan("none", AdLogTone.MUTED))
        } else {
            val spans = arrayListOf(AdLogSpan(plural(cached.size, "ad")))
            val soon = cached.count { it.cacheState(now) == AdCacheState.EXPIRING_SOON }
            val expired = cached.count { it.cacheState(now) == AdCacheState.EXPIRED }
            if (soon > 0) {
                spans += AdLogSpan(SEP, AdLogTone.MUTED)
                spans += AdLogSpan("$soon expiring soon", AdLogTone.WARN, bold = true)
            }
            if (expired > 0) {
                spans += AdLogSpan(SEP, AdLogTone.MUTED)
                spans += AdLogSpan("$expired past expiry", AdLogTone.BAD, bold = true)
            }
            spans
        }
        return rows
    }

    /** The right-hand status of a slot's title line: cached for how long, and how close to expiry. Empty when nothing is cached. */
    fun badge(slot: AdSlotSnapshot, now: Long): List<AdLogSpan> {
        val age = slot.cacheAgeMillis(now) ?: return emptyList()
        val life = slot.lifetimeMillis
        return when (slot.cacheState(now)) {
            AdCacheState.EXPIRED ->
                listOf(AdLogSpan("● cached ${compact(age)} · expired", AdLogTone.BAD, bold = true))
            AdCacheState.EXPIRING_SOON ->
                listOf(AdLogSpan("● cached ${compact(age)} · expires in ${compactUp(life!! - age)}", AdLogTone.WARN, bold = true))
            else -> listOf(AdLogSpan("● cached ${compact(age)}", AdLogTone.GOOD))
        }
    }

    /** The lines under a slot's title. */
    fun lines(slot: AdSlotSnapshot, now: Long): List<List<AdLogSpan>> {
        val lines = ArrayList<List<AdLogSpan>>(4)

        val counts = arrayListOf(
            AdLogSpan("requested ${slot.requested}"), AdLogSpan(SEP, AdLogTone.MUTED),
            AdLogSpan("loaded ${slot.loaded}"), AdLogSpan(SEP, AdLogTone.MUTED),
            AdLogSpan("shown ${slot.shown}")
        )
        if (slot.clicked > 0) {
            counts += AdLogSpan(SEP, AdLogTone.MUTED)
            counts += AdLogSpan("clicked ${slot.clicked}")
        }
        lines += counts

        val rates = ArrayList<List<AdLogSpan>>(3)
        slot.fillPercent?.let { pct ->
            rates += if (slot.lowFill(now)) {
                listOf(AdLogSpan("fill "), AdLogSpan("$pct%", AdLogTone.BAD, bold = true))
            } else {
                listOf(AdLogSpan("fill $pct%"))
            }
        }
        slot.showPercent?.let { pct ->
            rates += if (slot.lowShow()) {
                listOf(AdLogSpan("shown "), AdLogSpan("$pct%", AdLogTone.BAD, bold = true))
            } else {
                listOf(AdLogSpan("shown $pct%"))
            }
        }
        slot.averageLoadMillis?.let { rates += listOf(AdLogSpan("loads in ${loadTime(it)}")) }
        if (rates.isNotEmpty()) {
            val line = ArrayList<AdLogSpan>()
            rates.forEachIndexed { i, part ->
                if (i > 0) line += AdLogSpan(SEP, AdLogTone.MUTED)
                line += part
            }
            lines += line
        }

        if (slot.failed > 0) {
            val line = arrayListOf(AdLogSpan("failed ${slot.failed}"))
            slot.topFailure?.let {
                line += AdLogSpan(SEP, AdLogTone.MUTED)
                line += AdLogSpan("mostly $it", AdLogTone.MUTED)
            }
            lines += line
        }

        if (slot.fastClicks > 0) {
            val word = if (slot.fastClicks == 1) "fast click" else "fast clicks"
            lines += listOf(AdLogSpan("${slot.fastClicks} $word - likely accidental", AdLogTone.BAD, bold = true))
        }
        return lines
    }

    /** "0.8s" under ten seconds, "12s" above. */
    fun loadTime(ms: Long): String =
        if (ms < 10_000L) String.format(Locale.US, "%.1fs", ms / 1000.0) else "${(ms / 1000.0).roundToInt()}s"

    /** An age, rounded down: "40s", "3m", "1h", "2h 5m". */
    fun compact(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3_600 -> "${s / 60}m"
            s % 3_600 / 60 == 0L -> "${s / 3_600}h"
            else -> "${s / 3_600}h ${s % 3_600 / 60}m"
        }
    }

    /**
     * A time left, rounded UP, so an age and its time left add up to the lifetime: cached 52m 30s of
     * an hour reads "cached 52m · expires in 8m", not "7m".
     */
    fun compactUp(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) + 999) / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3_600 -> "${(s + 59) / 60}m"
            else -> compact(((s + 59) / 60) * 60_000)
        }
    }

    /** "<1 min", "14 min", "1 h 5 min". */
    fun sessionLength(ms: Long): String {
        val min = ms.coerceAtLeast(0L) / 60_000
        return when {
            min < 1 -> "<1 min"
            min < 60 -> "$min min"
            min % 60 == 0L -> "${min / 60} h"
            else -> "${min / 60} h ${min % 60} min"
        }
    }

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
}
