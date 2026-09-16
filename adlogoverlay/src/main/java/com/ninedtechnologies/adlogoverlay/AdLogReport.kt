package com.ninedtechnologies.adlogoverlay

/**
 * TOOLS > SHARE: everything a tester would otherwise screenshot, as one plain-text message that
 * pastes cleanly into Slack or a bug ticket. Pure, so the size limits are unit tested.
 *
 *     AD LOG REPORT
 *     App        PDF Reader 1.8.8 (211) · com.example.app
 *     ...
 *     STATS / LOG / REMOTE CONFIG
 */
internal object AdLogReport {

    /**
     * The whole report stays under this. It travels as an Intent extra, and a transaction over
     * Android's 1 MB binder limit fails outright; strings travel as UTF-16, so this is ~120 KB.
     */
    const val MAX_CHARS = 60_000

    /** Remote Config gets at most this much of the budget; the log is what a report is for. */
    private const val MAX_FRC_CHARS = 15_000

    private const val MAX_FRC_VALUE_CHARS = 1_500

    fun build(
        header: List<Pair<String, String>>,
        stats: AdStatsSnapshot,
        entries: List<AdLogEntry>,
        frc: RemoteConfigSnapshot?,
        now: Long,
        maxChars: Int = MAX_CHARS
    ): String {
        val head = StringBuilder()
        head.append("AD LOG REPORT\n")
        val width = (header.maxOfOrNull { it.first.length } ?: 0) + 2
        for ((label, value) in header) head.append(label.padEnd(width)).append(value).append('\n')

        head.append("\nSTATS\n")
        val rows = AdLogStatsText.sessionRows(stats, now)
        val rowWidth = rows.maxOf { it.first.length } + 2
        for ((label, spans) in rows) head.append(label.padEnd(rowWidth)).append(text(spans)).append('\n')
        for (slot in stats.ordered(now)) {
            head.append('\n').append(slot.placement)
            slot.format?.let { head.append("  (").append(it).append(')') }
            val badge = text(AdLogStatsText.badge(slot, now))
            if (badge.isNotEmpty()) head.append("  ").append(badge)
            head.append('\n')
            for (line in AdLogStatsText.lines(slot, now)) head.append("  ").append(text(line)).append('\n')
        }

        val config = frc?.let { remoteConfig(it) }.orEmpty()

        // The log fills what is left, newest lines first: when something has to go, it is the oldest.
        val budget = maxChars - head.length - config.length - 200
        val lines = entries.map(::line)
        var used = 0
        var kept = 0
        for (i in lines.indices.reversed()) {
            if (used + lines[i].length > budget) break
            used += lines[i].length
            kept++
        }
        val log = StringBuilder()
        log.append("\nLOG  (").append(entries.size).append(if (entries.size == 1) " line" else " lines")
        log.append(", oldest first)\n")
        if (kept < lines.size) {
            log.append("… ").append(lines.size - kept).append(" older lines left out to fit the share limit\n")
        }
        for (i in lines.size - kept until lines.size) log.append(lines[i])

        return (head.toString() + log + config).take(maxChars)
    }

    /** One log line with everything the panel shows, plus the raw text the panel shortens. */
    fun line(e: AdLogEntry): String {
        e.mark?.let { return "── MARK $it ── ${e.time}\n" }
        val sb = StringBuilder()
        val label = if (e.fastClickMillis != null) "FAST CLICK" else e.kind.label
        sb.append(e.time).append("  ").append(label).append("  ")
        e.placement?.let { p ->
            sb.append(p)
            AdPlacement.formatOf(p)?.let { sb.append(" (").append(it).append(')') }
            sb.append("  ")
        }
        e.loadMillis?.let { sb.append("in ").append(AdLogStatsText.loadTime(it)).append("  ") }
        e.screen?.let { sb.append('@').append(it).append("  ") }
        sb.append("| ").append(e.tag).append(": ").append(e.message).append('\n')
        e.fastClickMillis?.let { sb.append("    ↳ ").append(fastClickReason(it)).append('\n') }
        e.reason?.let { sb.append("    ↳ ").append(it).append('\n') }
        return sb.toString()
    }

    /** The words under a FAST CLICK line, shared with the panel. */
    fun fastClickReason(ms: Long): String =
        "clicked ${AdLogStatsText.loadTime(ms)} after it appeared - likely accidental"

    private fun remoteConfig(snap: RemoteConfigSnapshot): String {
        val sb = StringBuilder("\nREMOTE CONFIG\n")
        if (snap.error != null) return sb.append("Can't read it: ").append(snap.error).append('\n').toString()
        sb.append("Last download: ").append(snap.lastFetchStatus.label)
        if (snap.fetchTimeMillis > 0L) sb.append(" at ").append(AdLogClock.clockOf(snap.fetchTimeMillis, localOffset(snap.fetchTimeMillis)))
        sb.append('\n')
        for (entry in snap.entries) {
            val value = if (entry.value.length > MAX_FRC_VALUE_CHARS) {
                entry.value.take(MAX_FRC_VALUE_CHARS) + " … ${entry.value.length - MAX_FRC_VALUE_CHARS} more characters"
            } else {
                entry.value
            }
            val row = "${entry.key}  [${entry.source.label}]  $value\n"
            if (sb.length + row.length > MAX_FRC_CHARS) {
                sb.append("… ").append(snap.entries.size - snap.entries.indexOf(entry)).append(" more settings left out\n")
                break
            }
            sb.append(row)
        }
        return sb.toString()
    }

    private fun localOffset(at: Long): Long = java.util.TimeZone.getDefault().getOffset(at).toLong()

    private fun text(spans: List<AdLogSpan>): String = spans.joinToString("") { it.text }
}
