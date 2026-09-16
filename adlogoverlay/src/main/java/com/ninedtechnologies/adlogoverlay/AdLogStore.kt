package com.ninedtechnologies.adlogoverlay

import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.ArrayDeque

/**
 * What kind of ad moment a line describes. The only job of this is colour: every entry is
 * tinted by its kind so the stream is scannable without reading it.
 *
 * The set covers the full Google Mobile Ads callback surface (verified against the SDK
 * reference, not from memory):
 *  - `AdListener`: onAdLoaded, onAdFailedToLoad, onAdImpression, onAdClicked, onAdOpened,
 *    onAdClosed, onAdSwipeGestureClicked
 *  - `FullScreenContentCallback`: onAdShowedFullScreenContent,
 *    onAdDismissedFullScreenContent, onAdFailedToShowFullScreenContent, onAdImpression,
 *    onAdClicked
 *  - `OnPaidEventListener`: onPaidEvent  ->  [PAID]
 *  - `OnUserEarnedRewardListener`: onUserEarnedReward  ->  [REWARD]
 *  - `OnInitializationCompleteListener` and per-adapter status  ->  [INIT]
 * plus the cache/expiry/destroy vocabulary an app's own ad wrapper usually adds.
 */
enum class AdLogKind(val label: String, val onDark: Int, val onLight: Int) {
    // Two hues per kind, same meaning, because the panel now sits on the host's own
    // `surface` colour and therefore changes with the app's theme. `onDark` are the light
    // mid-saturation hues that survive a dark panel; `onLight` are their dark counterparts.
    // Both columns are measured against the WORST case the panel can produce - the surface
    // token composited over hostile app content, #292929 in dark and #F2F2F2 in light - and
    // every hue at every brightness the renderer actually uses clears WCAG AA (4.5:1) there.
    // Labels are whole words, padded to one column, because the reader is a tester and not
    // the person who wrote the ad stack: "REQUESTED" needs no glossary, "REQ"/"DSTRY" do.
    REQUEST("REQUESTED ", 0xFF64B5F6.toInt(), 0xFF0D47A1.toInt()),   // blue   - we asked for an ad
    LOADED("LOADED    ", 0xFF81C784.toInt(), 0xFF1B5E20.toInt()),    // green  - an ad came back, ready to show
    FAILED("FAILED    ", 0xFFE57373.toInt(), 0xFFB71C1C.toInt()),    // red    - no ad came back
    SHOWN("SHOWN     ", 0xFFFFD54F.toInt(), 0xFF6B5000.toInt()),     // amber  - on screen now
    DISMISSED("CLOSED    ", 0xFFCE93D8.toInt(), 0xFF4A148C.toInt()), // purple - user dismissed it
    IMPRESSION("IMPRESSION", 0xFF4DD0E1.toInt(), 0xFF005662.toInt()),// cyan   - counted as seen
    CLICK("CLICKED   ", 0xFFF48FB1.toInt(), 0xFF880E4F.toInt()),     // pink
    PAID("REVENUE   ", 0xFF9CCC65.toInt(), 0xFF2B5A19.toInt()),      // lime   - earned money
    REWARD("REWARDED  ", 0xFF4DB6AC.toInt(), 0xFF004D40.toInt()),    // teal   - user earned the reward
    CACHE("SAVED     ", 0xFFFFB74D.toInt(), 0xFF8C3500.toInt()),     // orange - stored in cache / served from it
    EXPIRED("EXPIRED   ", 0xFFFF8A65.toInt(), 0xFFA32E0A.toInt()),   // deep orange - cached ad went stale
    DESTROY("REMOVED   ", 0xFFB0BEC5.toInt(), 0xFF263238.toInt()),   // blue-grey
    DENIED("BLOCKED   ", 0xFFBCAAA4.toInt(), 0xFF3E2723.toInt()),    // brown  - we never asked; a rule stopped it
    INIT("SDK       ", 0xFF9FA8DA.toInt(), 0xFF1A237E.toInt()),      // indigo - setup, not an ad event
    OTHER("·         ", 0xFFCFD8DC.toInt(), 0xFF263238.toInt());

    companion object {

        /** "click" but never "doubleclick" - see the CLICK branch below. */
        private val CLICK_RE = Regex("(?<!double)click", RegexOption.IGNORE_CASE)

        /**
         * Classify a line. ORDER IS LOAD-BEARING - the first match wins, so the compound
         * phrases must be tested before the words they contain:
         * "onAdFailedToShowFullScreenContent" holds both "failed" and "show" (FAILED wins);
         * "paid_ad_impression" holds "impression" (PAID wins).
         */
        fun of(line: String): AdLogKind {
            val s = line.lowercase()
            return when {
                // revenue first - its strings contain "impression"
                s.contains("onpaidevent") || s.contains("paid_ad_impression") ||
                    s.contains("paid event") || s.contains("troas") ||
                    s.contains("adrevenue") -> PAID

                s.contains("userearnedreward") || s.contains("earned reward") ||
                    s.contains("rewarditem") -> REWARD

                // failure before anything containing "load"/"show"
                s.contains("failed") || s.contains("failure") || s.contains("error") ||
                    s.contains("no fill") || s.contains("nofill") -> FAILED

                // gates that refuse a request - distinct from a real network failure
                s.contains("denied") || s.contains("premiumuser") ||
                    s.contains("adsdisabled") || s.contains("sessionlimit") ||
                    s.contains("already requested") || s.contains("ads off") ||
                    s.contains("initializationfailed") -> DENIED

                s.contains("expired") || s.contains("expiry") || s.contains("stale") ||
                    s.contains("ttl") -> EXPIRED

                s.contains("destroy") || s.contains("released") || s.contains("dispose") ||
                    s.contains("cleared") -> DESTROY

                s.contains("dismissed") || s.contains("onadclosed") ||
                    s.contains("adclosed") || s.contains("closed") -> DISMISSED

                s.contains("impression") -> IMPRESSION

                // NOT a bare contains("click"): every Google ad URL carries
                // "googleads.g.doubleclick.net", so a plain match tagged every network
                // trace as a user click. The lookbehind keeps onAdClicked /
                // onAdSwipeGestureClicked / "..._clicked" and rejects doubleclick.
                CLICK_RE.containsMatchIn(s) -> CLICK

                s.contains("cached") || s.contains("cache") || s.contains("stored") ||
                    s.contains("reuse") || s.contains("preload") || s.contains("pool") ||
                    s.contains("already loaded") -> CACHE

                s.contains("showedfullscreencontent") || s.contains("showed") ||
                    s.contains("shown") || s.contains("onadopened") ||
                    s.contains("displaying") -> SHOWN

                s.contains("loaded") -> LOADED

                s.contains("request") || s.contains("calling") || s.contains("loading") ||
                    s.contains("requesting") -> REQUEST

                s.contains("initial") || s.contains("adapter") || s.contains("consent") ||
                    s.contains("mediation") -> INIT

                else -> OTHER
            }
        }
    }
}

/**
 * One line in the overlay. [time] is the clock as logcat printed it; [placement] is the ad
 * slot the line is about (`Home_NATIVE_AD`, `APP_OPEN`, ...) when one could be identified.
 */
data class AdLogEntry(
    val time: String,
    val tag: String,
    val message: String,
    val kind: AdLogKind,
    val placement: String? = null,
    /** Screen that was on top when this line was read - see [AdLogStore.currentScreen]. */
    val screen: String? = null,
    /** Plain-language "why" for a failure or a refusal; null for everything else. */
    val reason: String? = null
) {
    /**
     * Everything this line can be searched by, lower-cased once at ingest. Search then costs
     * one `contains` per entry per query instead of re-lowering six fields per keystroke.
     */
    val search: String = buildString {
        append(kind.label).append(' ').append(tag).append(' ').append(message)
        placement?.let { append(' ').append(it) }
        screen?.let { append(' ').append(it) }
        reason?.let { append(' ').append(it) }
    }.lowercase()
}

/**
 * Pulls the ad slot's name out of a log line.
 *
 * Ad wrappers all put the placement in the text, each in its own shape:
 * `"Home_NATIVE_AD request send"`, `"home_native_ad_loaded"`, `"Exit_Native calling"`.
 * They share one property worth keying on - a placement is a snake/camel identifier that
 * names an ad format - so one pattern finds all of them without needing a hardcoded list
 * that would rot the moment someone adds a slot.
 */
object AdPlacement {

    /** Identifiers like Home_NATIVE_AD, APP_OPEN, home_native_ad_impression. */
    private val TOKEN = Regex("""\b([A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+)\b""")

    /** A token only counts as a placement if it names a format. */
    private val FORMAT = Regex("""(native|banner|inter|app_?open|reward|splash)""", RegexOption.IGNORE_CASE)

    /** Verbs the sinks append; stripped so "home_native_ad_impression" reads as the slot. */
    private val VERB_SUFFIX = Regex(
        """_(impression|loaded|failed|error|request|requested|clicked|click|show|shown|dismissed|closed|paid)$""",
        RegexOption.IGNORE_CASE
    )

    fun of(message: String): String? {
        for (m in TOKEN.findAll(message)) {
            var t = m.groupValues[1]
            if (!FORMAT.containsMatchIn(t)) continue
            // Never mistake a class or package name for a placement.
            if (t.contains('.') || t.startsWith("com_")) continue
            t = VERB_SUFFIX.replace(t, "")
            if (t.length in 3..48) return t
        }
        return null
    }

    /** Coarse format for the line, derived from the slot name. */
    fun formatOf(placement: String?): String? {
        val p = placement?.lowercase() ?: return null
        return when {
            p.contains("app_open") || p.contains("appopen") -> "app open"
            p.contains("inter") -> "interstitial"
            p.contains("native") -> "native"
            p.contains("banner") -> "banner"
            p.contains("reward") -> "rewarded"
            else -> null
        }
    }
}

/**
 * Turns a failure line into a plain-English reason.
 *
 * Two independent sources of "why", both already present in the log text:
 *
 *  1. **Google's numeric codes.** `LoadAdError`/`AdError` print as
 *     `Code: 3, Message: ..., Domain: com.google.android.gms.ads`, and "3" on its own tells
 *     you nothing. The mapping is from the SDK's `AdRequest` reference (checked, not
 *     recalled): 0 internal, 1 invalid request, 2 network, 3 no fill, 8 app ID missing,
 *     9 mediation no fill, 10 request-id mismatch, 11 invalid ad string.
 *  2. **A host's own gates.** Wrappers of this shape refuse a request with `PremiumUser`,
 *     `Offline`, `AdsDisabled`, `AdLoading`, `AdSessionLimitReached`, `AdAlreadyLoaded`,
 *     `AdsInitializationFailed` or `AdLoadOnCount(n)` - the most common answer to "why did
 *     no ad appear", and worth spelling out rather than leaving as a class name.
 */
object AdFailureReason {

    private val CODES = mapOf(
        0 to "internal error (bad response from the ad server)",
        1 to "invalid request (check the ad unit ID)",
        2 to "network error (no connectivity)",
        3 to "no fill (request OK, but no inventory)",
        8 to "app ID missing",
        9 to "mediation no fill (every adapter failed)",
        10 to "request ID mismatch",
        11 to "invalid ad string"
    )

    private val GATES = mapOf(
        "premiumuser" to "user is premium - ads suppressed",
        "offline" to "device is offline",
        "adsdisabled" to "ads disabled by remote config",
        "adloading" to "a load for this placement is already in flight",
        "adsessionlimitreached" to "session cap for this placement reached",
        "adalreadyloaded" to "an ad is already cached for this placement",
        "adsinitializationfailed" to "Mobile Ads SDK never finished initialising",
        "adloadoncount" to "suppressed by the load-on-count rule"
    )

    private val CODE_RE = Regex("""code[:=]?\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)

    /** A short reason, or null when the line carries nothing decodable. */
    fun of(message: String): String? {
        val lower = message.lowercase()
        GATES.entries.firstOrNull { lower.contains(it.key) }?.let { return it.value }
        CODE_RE.find(message)?.groupValues?.get(1)?.toIntOrNull()?.let { code ->
            CODES[code]?.let { return "$it [code $code]" }
        }
        if (lower.contains("no fill") || lower.contains("nofill")) return CODES[3]
        if (lower.contains("timeout")) return "timed out"
        return null
    }
}

/**
 * Collects ad-related log lines for the debug overlay.
 *
 * ## Why it reads logcat rather than being called from the ad code
 *
 * A typical ad stack has no single event funnel to hook. Its logging is spread over
 * several independent sinks with uneven coverage, and the app module depends on the ad
 * modules and not the reverse, so an app-side sink cannot be called from inside a loader
 * without inverting the dependency or threading a listener through every class.
 *
 * Reading this process's own logcat sidesteps all of that: it picks up every one of those
 * sinks at once WITHOUT editing either module, and it additionally captures the Google
 * Mobile Ads SDK's own internal chatter - mediation, adapter latency, fill, paid events -
 * which no amount of instrumenting our own call sites would ever produce.
 *
 * An app may always read its own logs; `--pid` keeps it to our process, and since Android
 * 4.1 an app cannot read anyone else's anyway. No permission is involved.
 *
 * A host that WOULD rather hand events over directly can: set [AdLogConfig.readLogcat] to
 * false and call [post] from its own ad callbacks. Nothing else changes.
 */
object AdLogStore {

    private const val MAX_ENTRIES = 400

    /** Hard ceiling on lines ingested per second - see [record]. */
    private const val MAX_PER_SECOND = 25

    /**
     * A tag simply CONTAINING "ad" catches `AdLogs-->`, `AdLogs`, `AdsLogs`, `MyAdManager`,
     * `AdmobNativeLogs` and the Mobile Ads SDK's own `Ads` - this is why the
     * filter is tag-first: a word-boundary match on the MESSAGE would miss every one of
     * those, because "AdLogs" has no word break after "Ad". Tags with no "ad" in them at
     * all are the host's business: see [AdLogConfig.extraTags].
     */
    /** SDK plumbing that is not an ad lifecycle event - see [parse]. */
    private val NOISE_TAGS = setOf("gma debug", "gmadebug", "adservices", "webview")

    /** One JSON blob must never be able to fill the whole panel. */
    private const val MAX_MESSAGE_CHARS = 220

    /** Host-supplied tags that do not contain "ad" - see [AdLogConfig.extraTags]. */
    private val adTagExtras: Set<String> get() = AdLogOverlay.config.extraTags

    /** Word-boundary matching, so a message-side match cannot fire on "loaded" or "thread". */
    private val AD_MESSAGE_PATTERN = Regex(
        "\\b(ads?|adx|admob|adunit|ad_unit|adview|adrequest|aderror|interstitial|native|" +
            "banner|rewarded|app.?open|impression|ecpm|mediation|adapter|placement|" +
            "no.?fill|paid.?event|troas)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * Screen currently on top, stamped onto every entry so a tester can see WHERE an ad
     * event fired ("SHOWN Home_NATIVE_AD @MainActivity").
     *
     * It is the screen at the moment the line was READ, not the moment it was logged -
     * logcat delivers asynchronously, so a line emitted during a screen transition can be
     * attributed to the screen that follows it. In practice the gap is milliseconds and the
     * attribution is right; treat it as a strong hint rather than a guarantee.
     *
     * @Volatile because [AdLogOverlay] writes it on the main thread and the reader
     * coroutine reads it on Dispatchers.IO.
     */
    @Volatile
    var currentScreen: String? = null

    private val buffer = ArrayDeque<AdLogEntry>(MAX_ENTRIES)
    private val listeners = mutableListOf<(AdLogEntry) -> Unit>()

    /**
     * The values the filter UI offers, learned from what has actually been seen rather than
     * hardcoded - a placement list in code would rot the moment someone adds a slot.
     * Insertion-ordered so the chips do not reshuffle under the finger, and capped so a
     * pathological log cannot grow them without bound.
     */
    private const val MAX_FACETS = 40
    private val screensSeen = LinkedHashSet<String>()
    private val placementsSeen = LinkedHashSet<String>()
    private val reasonsSeen = LinkedHashSet<String>()

    /** Bumped whenever a facet set gains a value, so the UI can rebuild only when it must. */
    @Volatile
    var facetVersion: Int = 0
        private set

    @Synchronized
    fun screens(): List<String> = screensSeen.toList()

    @Synchronized
    fun placements(): List<String> = placementsSeen.toList()

    @Synchronized
    fun reasons(): List<String> = reasonsSeen.toList()

    private fun learn(set: LinkedHashSet<String>, value: String?) {
        if (value == null || value.isEmpty() || value in set) return
        if (set.size >= MAX_FACETS) set.remove(set.first())
        set += value
        facetVersion++
    }

    /** Owned and cancellable - never GlobalScope. [stop] tears it down. */
    private var scope: CoroutineScope? = null
    private var process: java.lang.Process? = null

    @Synchronized
    fun snapshot(): List<AdLogEntry> = buffer.toList()

    @Synchronized
    fun addListener(l: (AdLogEntry) -> Unit) { listeners += l }

    @Synchronized
    fun removeListener(l: (AdLogEntry) -> Unit) { listeners -= l }

    /**
     * Ingestion rate limit. A debug overlay must never be able to starve the app it is
     * watching: during SDK init and mediation waterfalls this process can emit hundreds of
     * ad lines a second, and every one of them costs a listener hop and eventually a
     * render. Past [MAX_PER_SECOND] we drop and count, then emit one summary line - the
     * information a reader loses is far smaller than the frames they get back.
     */
    private var windowStartMs = 0L
    private var windowCount = 0
    private var dropped = 0

    @Synchronized
    private fun record(entry: AdLogEntry) {
        val now = System.currentTimeMillis()
        if (now - windowStartMs >= 1000L) {
            if (dropped > 0) {
                buffer.addLast(
                    AdLogEntry(nowClock(), "AdLogStore", "+$dropped more lines suppressed (rate limit)", AdLogKind.OTHER)
                )
                dropped = 0
            }
            windowStartMs = now
            windowCount = 0
        }
        if (windowCount >= MAX_PER_SECOND) { dropped++; return }
        windowCount++

        learn(screensSeen, entry.screen)
        learn(placementsSeen, entry.placement)
        learn(reasonsSeen, entry.reason)

        if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
        buffer.addLast(entry)
        listeners.toList().forEach { it(entry) }
    }

    /** Records a line the app raises itself, for events logcat would not carry. */
    fun post(tag: String, message: String) {
        val kind = AdLogKind.of("$tag $message")
        record(
            AdLogEntry(
                nowClock(), tag, message, kind,
                AdPlacement.of(message), currentScreen, reasonFor(kind, message)
            )
        )
    }

    /**
     * Records an ad event the host hands over directly. This is the integration that does
     * not depend on the log at all - see [AdLogConfig.readLogcat].
     */
    fun post(kind: AdLogKind, placement: String?, message: String, tag: String = "app") =
        record(
            AdLogEntry(
                nowClock(), tag, message, kind, placement, currentScreen,
                reasonFor(kind, message)
            )
        )

    private fun reasonFor(kind: AdLogKind, message: String): String? =
        if (kind == AdLogKind.FAILED || kind == AdLogKind.DENIED) AdFailureReason.of(message) else null

    @Synchronized
    fun start() {
        if (!AdLogOverlay.config.readLogcat) return
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { pump(this) }
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        runCatching { process?.destroy() }
        process = null
    }

    private fun pump(cs: CoroutineScope) {
        var p: java.lang.Process? = null
        try {
            // -v time  -> "09-14 11:06:42.123 D/Tag( 1234): message"
            // --pid    -> our process only (API 24+, which is this module's minSdk)
            // -T 1     -> start at the tail instead of replaying the whole ring on open
            p = ProcessBuilder(
                "logcat", "-v", "time", "--pid=${Process.myPid()}", "-T", "1"
            ).redirectErrorStream(true).start()
            process = p
            BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                while (true) {
                    cs.ensureActive()
                    val line = reader.readLine() ?: break
                    parse(line)?.let(::record)
                }
            }
        } catch (e: Exception) {
            // stop() cancels this job and closes the stream under it. Whatever that throws is the
            // reader being switched off on purpose, not a failure worth a red FAILED line.
            if (!cs.isActive) return
            // A device that refuses to exec logcat is not a reason to crash a debug build.
            record(
                AdLogEntry(
                    nowClock(), "AdLogStore",
                    "logcat reader stopped: ${e.javaClass.simpleName}: ${e.message}",
                    AdLogKind.FAILED
                )
            )
            Log.w("AdLogStore", "logcat reader stopped", e)
        } finally {
            // A stop() that lands before the process has started finds nothing to destroy, so the
            // reader destroys its own process on the way out instead of leaking a logcat child.
            if (!cs.isActive) runCatching { p?.destroy() }
        }
    }

    /**
     * `09-14 11:06:42.123 D/Tag( 1234): message` -> an entry, or null when the line is not
     * ad-related. Split first, judge second: the tag is the stronger signal and a
     * message-only filter would drop every `AdLogs-->` line.
     */
    private fun parse(line: String): AdLogEntry? {
        // FAST REJECT, first thing. A busy app logs very heavily, and running a regex over
        // every line of the process's logcat was itself a measurable cost. Almost no
        // non-ad line contains "ad" in any case, and this check is a couple of memchrs.
        if (!line.contains("ad") && !line.contains("Ad") && !line.contains("AD")) return null

        val sep = line.indexOf("): ")
        val slash = line.indexOf('/')

        if (sep < 0 || slash < 0 || slash > sep) {
            // Unexpected shape - keep it only if the raw text looks ad-related.
            if (!AD_MESSAGE_PATTERN.containsMatchIn(line)) return null
            val loose = AdLogKind.of(line)
            return AdLogEntry(
                nowClock(), "logcat", line.trim(), loose, null, currentScreen,
                reasonFor(loose, line)
            )
        }

        val paren = line.indexOf('(', slash).takeIf { it in (slash + 1)..sep } ?: sep
        val tag = line.substring(slash + 1, paren).trim()
        val message = line.substring(sep + 3).trim()

        val lowerTag = tag.lowercase()

        // The SDK's HTTP tracing ("GMA Debug CONTENT {...}") is not an ad lifecycle event -
        // it is request/response plumbing, emitted as multi-hundred-character JSON that
        // buries everything else in the panel and whose URLs (googleads.g.doubleclick.net,
        // "event":"onNetworkRequest") also defeat message-based classification. Dropped at
        // the door rather than filtered later.
        if (NOISE_TAGS.any { lowerTag.contains(it) }) return null
        if (message.startsWith("CONTENT {") || message.startsWith("{\"timestamp\"")) return null

        val relevant = lowerTag.contains("ad") ||
            adTagExtras.any { lowerTag.contains(it) } ||
            AD_MESSAGE_PATTERN.containsMatchIn(message)
        if (!relevant) return null

        // "09-14 11:06:42.123 D" -> keep the clock, drop the date and the level letter.
        val head = line.substring(0, slash).trim()
        val time = head.substringAfter(' ').substringBeforeLast(' ').ifBlank { nowClock() }

        val shown =
            if (message.length > MAX_MESSAGE_CHARS) message.take(MAX_MESSAGE_CHARS) + " …" else message
        val kind = AdLogKind.of("$tag $message")
        return AdLogEntry(
            time, tag, shown, kind,
            AdPlacement.of(message), currentScreen, reasonFor(kind, message)
        )
    }

    private fun nowClock(): String {
        val t = System.currentTimeMillis()
        val s = (t / 1000) % 60
        val m = (t / 60000) % 60
        val h = (t / 3600000) % 24
        return String.format("%02d:%02d:%02d", h, m, s)
    }
}
