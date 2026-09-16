package com.ninedtechnologies.adlogoverlay

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Where a Remote Config value came from - the single most useful fact about it when the
 * question is "what is this app actually receiving". Labels are written for anyone, not just
 * developers.
 *
 *  - [REMOTE]: downloaded from Firebase and in use. This is what was set in the console.
 *  - [DEFAULT]: the app's own built-in value. Nothing arrived for this setting, or nothing has
 *    been downloaded yet - the status lines say which.
 *  - [STATIC]: no value at all. getAll() does not normally return these; kept for completeness.
 */
enum class RemoteConfigSource(val label: String) {
    REMOTE("FROM FIREBASE"),
    DEFAULT("BUILT-IN DEFAULT"),
    STATIC("NOT SET")
}

/** Mirrors FirebaseRemoteConfig.LAST_FETCH_STATUS_* without referencing Firebase. */
enum class RemoteConfigFetchStatus(val label: String, val meaning: String?) {
    SUCCESS("✓ Downloaded", null),
    FAILURE("✗ Download failed", "Showing the last settings that did download, or built-in defaults"),
    THROTTLED("Paused - asked too often", "Firebase paused downloads for a while; showing the last good settings"),
    NO_FETCH_YET("Not downloaded yet", "Showing the app's built-in defaults")
}

data class RemoteConfigEntry(
    val key: String,
    val value: String,
    val source: RemoteConfigSource
)

/**
 * One read of the host's active Remote Config. [error] is non-null when nothing could be read,
 * and then says why in plain words.
 */
data class RemoteConfigSnapshot(
    val entries: List<RemoteConfigEntry>,
    val lastFetchStatus: RemoteConfigFetchStatus,
    /** Epoch millis of the last successful fetch; zero or negative means never. */
    val fetchTimeMillis: Long,
    val minimumFetchIntervalSeconds: Long,
    val fetchTimeoutSeconds: Long,
    val readAtMillis: Long,
    val error: String? = null
) {
    /** Equal content, ignoring when it was read - so a refresh that changed nothing redraws nothing. */
    fun sameContentAs(other: RemoteConfigSnapshot?): Boolean =
        other != null && copy(readAtMillis = 0L) == other.copy(readAtMillis = 0L)
}

/**
 * What FIND leaves of one entry: the value to display and, when a JSON array was narrowed to
 * the elements that matched, how many matched out of how many. [matched] and [total] are -1
 * when the value is shown whole.
 */
data class RemoteConfigMatch(val value: String, val matched: Int = -1, val total: Int = -1)

/**
 * What the last REFRESH did. [error] is null on success. [changedKeys] names every key whose value
 * or source differs from before the download - empty when nothing changed, null when the "before"
 * read had failed and there is nothing honest to compare against.
 */
data class RemoteConfigFetchOutcome(
    val atMillis: Long,
    val error: String?,
    val changedKeys: Set<String>?
)

/**
 * A parsed Remote Config JSON value. Numbers, true, false and null keep their exact text, so
 * `1.0` stays `1.0`; strings have their escapes decoded.
 */
sealed class RemoteConfigJson {
    data class Obj(val fields: List<Pair<String, RemoteConfigJson>>) : RemoteConfigJson()
    data class Arr(val items: List<RemoteConfigJson>) : RemoteConfigJson()
    data class Str(val value: String) : RemoteConfigJson()
    data class Lit(val raw: String) : RemoteConfigJson()
}

/** How a value should look: Yes in green, No in red, a missing value dimmed. */
enum class RemoteConfigRowKind { TEXT, TRUE, FALSE, NULL, NOTE }

/**
 * One line of the readable view.
 *
 *  - A [heading] names a group - one ad placement, a nested section - and the rows under it sit
 *    one [depth] deeper. [detail] is the raw identifier, shown dimmed, for a developer to search.
 *  - Otherwise [label] and [value] are a setting and what it is set to. A row with no label is a
 *    bare value.
 */
data class RemoteConfigRow(
    val depth: Int,
    val label: String?,
    val value: String?,
    val kind: RemoteConfigRowKind = RemoteConfigRowKind.TEXT,
    val heading: Boolean = false,
    val detail: String? = null
)

private val JSON_NUMBER = Regex("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?")

/**
 * The host app's Firebase Remote Config, for the overlay's FRC view.
 *
 * ## Optional, never a dependency
 *
 * `firebase-config` is compileOnly in this module, so it is on no consumer's classpath because
 * of the overlay and absent from the published POM. [isOnClasspath] asks the host's classloader
 * whether Firebase Remote Config is there; the FRC tab only exists when it is. Nothing in this
 * file references a Firebase type - [FirebaseRemoteConfigReader] holds all of them and is only
 * reached after that check.
 *
 * ## Reads by default; REFRESH is the one write, and only on request
 *
 * [read] returns the values that are ACTIVE right now - exactly what the app's own
 * getString/getBoolean calls return - and changes nothing.
 *
 * [fetchAndActivate] runs only when the tester taps REFRESH. It pulls the latest template from
 * the Firebase server into the app's own Remote Config and activates it, so it DOES change what
 * the app sees from then on - the same thing the app's next launch would do. Any code that
 * already parsed and cached its config keeps the old copy until it re-reads.
 *
 * It never calls addOnConfigUpdateListener: that opens a real-time connection the host never
 * asked for.
 *
 * ## Written for anyone
 *
 * [rowsForValue] turns every value into labelled lines a non-developer can read - no braces,
 * quotes or commas, setting names in plain words, Yes and No for switches, real units for
 * durations. See its docs for the exact rules.
 */
object AdLogRemoteConfig {

    private const val FRC_CLASS = "com.google.firebase.remoteconfig.FirebaseRemoteConfig"

    /**
     * True when the host ships Firebase Remote Config. `initialize = false` so asking does not
     * run Firebase's static initialisers.
     */
    val isOnClasspath: Boolean by lazy {
        try {
            Class.forName(FRC_CLASS, false, AdLogRemoteConfig::class.java.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    /**
     * Reads the active config. Call it OFF the main thread: the first getAll() of a process can
     * load the activated and default configs from disk.
     *
     * Catches Throwable on purpose. The Firebase API is compiled against one version and run
     * against the host's, so a moved method surfaces as NoSuchMethodError - an Error, not an
     * Exception - and a debug overlay must report that, not crash the app with it.
     */
    fun read(): RemoteConfigSnapshot {
        if (!isOnClasspath) return failed("Firebase Remote Config is not part of this app")
        return try {
            FirebaseRemoteConfigReader.read()
        } catch (t: Throwable) {
            failed(describe(t))
        }
    }

    /** True from a REFRESH tap until its result is published. Main thread only. */
    var fetchInFlight: Boolean = false
        private set

    /** The last REFRESH's result, or null if nobody has tapped REFRESH in this process. */
    var lastFetch: RemoteConfigFetchOutcome? = null
        private set

    // Lazy on purpose: this object is also exercised by plain JVM unit tests, where touching
    // Looper would throw. Nothing Android-specific is created until a real fetch runs.
    private val fetchScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Fetches from the Firebase server into the app's own Remote Config, activates it, and calls
     * [onFinished] on the main thread with the config as it now stands. A tap while a fetch is
     * already running is ignored.
     *
     * Owned by this object, not by a view: the tester may navigate while the network is slow,
     * and the result must still land - on whichever screen is showing by then.
     */
    fun fetchAndActivate(onFinished: (RemoteConfigSnapshot) -> Unit) {
        if (!isOnClasspath || fetchInFlight) return
        fetchInFlight = true
        fetchScope.launch {
            val before = read()
            val error = try {
                FirebaseRemoteConfigReader.fetchAndActivateBlocking()
            } catch (t: Throwable) {
                describe(t)
            }
            val after = read()
            val outcome = RemoteConfigFetchOutcome(
                atMillis = System.currentTimeMillis(),
                error = error ?: after.error,
                changedKeys = changedKeys(before, after)
            )
            mainHandler.post {
                lastFetch = outcome
                fetchInFlight = false
                onFinished(after)
            }
        }
    }

    /**
     * Keys whose value or source differs between two reads, including keys that appeared or
     * disappeared. Null when either read failed - an empty "before" would otherwise report every
     * key as changed.
     */
    fun changedKeys(before: RemoteConfigSnapshot?, after: RemoteConfigSnapshot): Set<String>? {
        if (before == null || before.error != null || after.error != null) return null
        val old = before.entries.associateBy { it.key }
        val new = after.entries.associateBy { it.key }
        return (old.keys + new.keys).filterTo(LinkedHashSet()) { old[it] != new[it] }
    }

    private fun describe(t: Throwable): String {
        val message = t.message.orEmpty()
        return if (t is IllegalStateException && message.contains("FirebaseApp")) {
            "Firebase hasn't started yet - tap FRC again in a moment"
        } else {
            "${t.javaClass.simpleName}: ${message.ifBlank { "no message" }}"
        }
    }

    private fun failed(reason: String) = RemoteConfigSnapshot(
        entries = emptyList(),
        lastFetchStatus = RemoteConfigFetchStatus.NO_FETCH_YET,
        fetchTimeMillis = 0L,
        minimumFetchIntervalSeconds = 0L,
        fetchTimeoutSeconds = 0L,
        readAtMillis = System.currentTimeMillis(),
        error = reason
    )

    /**
     * A download failure in words anyone understands. The raw SDK message is still shown
     * beneath it for a developer.
     */
    fun friendlyFetchError(error: String): String = when {
        error.contains("Throttled", ignoreCase = true) ->
            "Asked Firebase too often - try again in a few minutes"
        error.contains("UnknownHost", ignoreCase = true) ||
            error.contains("Unable to resolve host", ignoreCase = true) ->
            "No internet connection"
        error.contains("no answer", ignoreCase = true) ||
            error.contains("timed out", ignoreCase = true) ||
            error.contains("timeout", ignoreCase = true) ->
            "No answer from Firebase - check the internet connection"
        error.contains("hasn't started", ignoreCase = true) ->
            "Firebase hasn't started yet - try again in a moment"
        else -> "Couldn't download the settings"
    }

    /** Values that arrived from the server first, then defaults; alphabetical within each. */
    fun sortForDisplay(entries: List<RemoteConfigEntry>): List<RemoteConfigEntry> =
        entries.sortedWith(compareBy({ it.source.ordinal }, { it.key.lowercase() }))

    /**
     * Decides what FIND shows for [entry], or null when it does not match at all.
     *
     * The query is tested against BOTH what is stored and what is shown, so a developer's
     * `isShowLoadingBeforeAd` and a tester's "show loading" find the same setting, and
     * "interstitial" finds `adType: 3` when [valueLabels] names it.
     *
     * A name match shows the whole value. A JSON array - `ads_config_release` is one, holding a
     * config object per ad placement - is narrowed to only the items that match, because "what
     * does Remote Config say about Intent_Open_INTER_AD" should not mean scrolling through every
     * other placement to find it.
     */
    fun match(
        entry: RemoteConfigEntry,
        query: String,
        valueLabels: Map<String, Map<String, String>> = emptyMap()
    ): RemoteConfigMatch? {
        if (query.isEmpty()) return RemoteConfigMatch(entry.value)
        if (entry.key.contains(query, ignoreCase = true) ||
            humanize(entry.key).contains(query, ignoreCase = true)
        ) return RemoteConfigMatch(entry.value)

        val elements = topLevelArrayElements(entry.value)
        if (elements != null && elements.size > 1) {
            val hits = elements.filter {
                it.contains(query, ignoreCase = true) ||
                    readableText(null, "[$it]", valueLabels).contains(query, ignoreCase = true)
            }
            if (hits.isNotEmpty()) {
                return RemoteConfigMatch(hits.joinToString(",", "[", "]"), hits.size, elements.size)
            }
        }
        val found = entry.value.contains(query, ignoreCase = true) ||
            readableText(entry.key, entry.value, valueLabels).contains(query, ignoreCase = true)
        return if (found) RemoteConfigMatch(entry.value) else null
    }

    private fun readableText(key: String?, raw: String, valueLabels: Map<String, Map<String, String>>) =
        rowsForValue(key, raw, valueLabels).joinToString("\n") {
            listOfNotNull(it.label, it.value, it.detail).joinToString("  ")
        }

    // ------------------------------------------------------------------ readable rows

    private const val INLINE_LIST_CHARS = 60

    /** Fields that name an item, in the order they are looked for. `adName` first: ad configs. */
    private val TITLE_FIELDS = listOf("adName", "name", "title", "placement", "key", "id", "type")

    /** Kept in capitals when a name is turned into words. */
    private val ACRONYMS = setOf(
        "id", "ids", "url", "api", "sdk", "ui", "pdf", "iap", "roas", "frc", "json", "cta",
        "ump", "gdpr", "ab", "html", "http", "https"
    )

    /** Shorthand spelled out, whole words only. */
    private val EXPANSIONS = mapOf("inter" to "interstitial")

    /** Google's public sample publisher. Every Google test ad unit ID belongs to it. */
    private const val GOOGLE_TEST_PUBLISHER = "ca-app-pub-3940256099942544"

    private val WORD = Regex("[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|\\d+")

    /**
     * A code name as plain words: `isShowLoadingBeforeAd` -> "Show loading before ad",
     * `SPLASH_FIRST_INTER_AD` -> "Splash first interstitial ad", `adId` -> "Ad ID".
     *
     * Handles camelCase, snake_case and SCREAMING_SNAKE. A leading "is" is dropped from switch
     * names, because the Yes/No beside it already asks the question.
     */
    fun humanize(name: String): String {
        var words = WORD.findAll(name).map { it.value.lowercase() }.toList()
        if (words.isEmpty()) return name
        if (words.size > 1 && words[0] == "is") words = words.drop(1)
        val text = words.joinToString(" ") { word ->
            val expanded = EXPANSIONS[word] ?: word
            if (expanded in ACRONYMS) expanded.uppercase() else expanded
        }
        return text.replaceFirstChar { it.uppercase() }
    }

    /** "1.5 seconds", "1 hour", "2 days" - for durations a person should not have to convert. */
    fun friendlyDuration(seconds: Double): String {
        fun unit(amount: Double, word: String): String {
            val shown = if (amount == Math.floor(amount)) amount.toLong().toString()
            else String.format(Locale.US, "%.1f", amount)
            return "$shown $word${if (shown == "1") "" else "s"}"
        }
        return when {
            seconds < 60 -> unit(seconds, "second")
            seconds < 3_600 -> unit(seconds / 60, "minute")
            seconds < 86_400 -> unit(seconds / 3_600, "hour")
            else -> unit(seconds / 86_400, "day")
        }
    }

    /**
     * Every value of one Remote Config key as readable rows.
     *
     *  - JSON objects become `label  value` rows; a nested object becomes a heading with its
     *    fields indented beneath it.
     *  - A list of objects becomes one heading per item, named by its adName / name / title /
     *    ... field - so `ads_config_release` reads as a list of ad placements - or "Item 1",
     *    "Item 2" when there is no such field.
     *  - A short list of plain values stays on one line: `a, b, c`.
     *  - Anything that is not a JSON object or list - a switch, a number, text, or JSON too
     *    broken to read - is one bare row.
     *
     * Values: see [friendlyValue].
     */
    fun rowsForValue(
        key: String?,
        raw: String,
        valueLabels: Map<String, Map<String, String>> = emptyMap()
    ): List<RemoteConfigRow> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            parseJson(trimmed)?.let { json ->
                return ArrayList<RemoteConfigRow>().also { it.addValue(json, 0, valueLabels) }
            }
        }
        // Remote Config stores every value as text. A switch or a number is shown as one.
        val node = if (trimmed == "true" || trimmed == "false" || JSON_NUMBER.matches(trimmed)) {
            RemoteConfigJson.Lit(trimmed)
        } else {
            RemoteConfigJson.Str(raw)
        }
        val (text, kind) = friendlyValue(key, node, valueLabels)
        return listOf(RemoteConfigRow(0, null, text, kind))
    }

    /**
     * One plain value in words:
     *
     *  - true / false -> "Yes" / "No", null -> "(not set)", "" -> "(blank)".
     *  - A number whose setting name states its unit - `...InMs`, `...Millis`, `...Seconds` -
     *    gets it spelled out: `1500 (1.5 seconds)`. No unit is ever guessed from anything else.
     *  - A code the host has named through [valueLabels] shows the name: `Interstitial (3)`.
     *  - A Google test ad unit ID says so.
     *  - Text that would read as a different kind of value - "true", "7000" - is marked
     *    `(as text)`. In JSON `"true"` and `true` are different types, and parsers disagree on
     *    whether to accept one for the other, so that difference stays visible.
     */
    fun friendlyValue(
        key: String?,
        node: RemoteConfigJson,
        valueLabels: Map<String, Map<String, String>> = emptyMap()
    ): Pair<String, RemoteConfigRowKind> {
        val rawText = when (node) {
            is RemoteConfigJson.Lit -> node.raw
            is RemoteConfigJson.Str -> node.value
            else -> return "" to RemoteConfigRowKind.TEXT
        }
        key?.let { valueLabels[it]?.get(rawText) }?.let { name ->
            return "$name ($rawText)" to RemoteConfigRowKind.TEXT
        }
        return when (node) {
            is RemoteConfigJson.Lit -> when (node.raw) {
                "true" -> "Yes" to RemoteConfigRowKind.TRUE
                "false" -> "No" to RemoteConfigRowKind.FALSE
                "null" -> "(not set)" to RemoteConfigRowKind.NULL
                else -> withUnit(key, node.raw) to RemoteConfigRowKind.TEXT
            }
            is RemoteConfigJson.Str -> {
                val v = node.value
                val shown = v.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                when {
                    v.isEmpty() -> "(blank)" to RemoteConfigRowKind.NOTE
                    v != v.trim() -> "“$shown”" to RemoteConfigRowKind.TEXT
                    v == "true" || v == "false" || v == "null" || JSON_NUMBER.matches(v) ->
                        "$shown (as text)" to RemoteConfigRowKind.TEXT
                    v.contains(GOOGLE_TEST_PUBLISHER) -> "$shown (Google test ad)" to RemoteConfigRowKind.TEXT
                    else -> shown to RemoteConfigRowKind.TEXT
                }
            }
            else -> "" to RemoteConfigRowKind.TEXT
        }
    }

    private fun withUnit(key: String?, raw: String): String {
        val amount = raw.toDoubleOrNull() ?: return raw
        val lastWord = key?.let { WORD.findAll(it).lastOrNull()?.value?.lowercase() } ?: return raw
        val seconds = when (lastWord) {
            "ms", "millis", "milliseconds" -> amount / 1000.0
            "sec", "secs", "second", "seconds" -> amount
            else -> return raw
        }
        return "$raw (${friendlyDuration(seconds)})"
    }

    private fun MutableList<RemoteConfigRow>.addValue(
        node: RemoteConfigJson,
        depth: Int,
        labels: Map<String, Map<String, String>>
    ) {
        when (node) {
            is RemoteConfigJson.Obj ->
                if (node.fields.isEmpty()) add(note(depth, "(nothing set)"))
                else node.fields.forEach { (key, value) -> addField(key, value, depth, labels) }
            is RemoteConfigJson.Arr -> addItems(node, depth, labels)
            else -> add(valueRow(depth, null, null, node, labels))
        }
    }

    private fun MutableList<RemoteConfigRow>.addField(
        key: String,
        value: RemoteConfigJson,
        depth: Int,
        labels: Map<String, Map<String, String>>
    ) {
        val label = humanize(key)
        when (value) {
            is RemoteConfigJson.Obj ->
                if (value.fields.isEmpty()) {
                    add(RemoteConfigRow(depth, label, "(nothing set)", RemoteConfigRowKind.NOTE))
                } else {
                    add(RemoteConfigRow(depth, label, null, heading = true))
                    addValue(value, depth + 1, labels)
                }
            is RemoteConfigJson.Arr -> {
                val inline = inlineList(value, key, labels)
                when {
                    value.items.isEmpty() ->
                        add(RemoteConfigRow(depth, label, "(none)", RemoteConfigRowKind.NOTE))
                    inline != null -> add(RemoteConfigRow(depth, label, inline))
                    else -> {
                        add(RemoteConfigRow(depth, label, null, heading = true))
                        addItems(value, depth + 1, labels)
                    }
                }
            }
            else -> add(valueRow(depth, label, key, value, labels))
        }
    }

    private fun MutableList<RemoteConfigRow>.addItems(
        array: RemoteConfigJson.Arr,
        depth: Int,
        labels: Map<String, Map<String, String>>
    ) {
        if (array.items.isEmpty()) {
            add(note(depth, "(none)"))
            return
        }
        inlineList(array, null, labels)?.let {
            add(RemoteConfigRow(depth, null, it))
            return
        }
        array.items.forEachIndexed { index, item ->
            val number = "Item ${index + 1}"
            when (item) {
                is RemoteConfigJson.Obj -> {
                    val title = titleOf(item)
                    if (title == null) {
                        add(RemoteConfigRow(depth, number, null, heading = true))
                        addValue(item, depth + 1, labels)
                    } else {
                        val (field, name) = title
                        val readable = humanize(name)
                        add(
                            RemoteConfigRow(
                                depth, readable, null, heading = true,
                                detail = name.takeIf { it != readable }
                            )
                        )
                        val rest = item.fields.filterNot { it.first == field }
                        if (rest.isNotEmpty()) addValue(RemoteConfigJson.Obj(rest), depth + 1, labels)
                    }
                }
                is RemoteConfigJson.Arr -> {
                    add(RemoteConfigRow(depth, number, null, heading = true))
                    addItems(item, depth + 1, labels)
                }
                else -> add(valueRow(depth, number, null, item, labels))
            }
        }
    }

    private fun valueRow(
        depth: Int,
        label: String?,
        key: String?,
        node: RemoteConfigJson,
        labels: Map<String, Map<String, String>>
    ): RemoteConfigRow {
        val (text, kind) = friendlyValue(key, node, labels)
        return RemoteConfigRow(depth, label, text, kind)
    }

    private fun note(depth: Int, text: String) =
        RemoteConfigRow(depth, null, text, RemoteConfigRowKind.NOTE)

    private fun titleOf(obj: RemoteConfigJson.Obj): Pair<String, String>? {
        for (wanted in TITLE_FIELDS) {
            val field = obj.fields.firstOrNull { it.first.equals(wanted, ignoreCase = true) } ?: continue
            val name = (field.second as? RemoteConfigJson.Str)?.value?.takeIf { it.isNotBlank() } ?: continue
            return field.first to name
        }
        return null
    }

    private fun inlineList(
        array: RemoteConfigJson.Arr,
        key: String?,
        labels: Map<String, Map<String, String>>
    ): String? {
        if (array.items.isEmpty()) return null
        if (array.items.any { it is RemoteConfigJson.Obj || it is RemoteConfigJson.Arr }) return null
        return array.items.joinToString(", ") { friendlyValue(key, it, labels).first }
            .takeIf { it.length <= INLINE_LIST_CHARS }
    }

    // ------------------------------------------------------------------------- JSON

    /**
     * Parses a JSON object or list, or returns null when [raw] is not a well-formed one - it is
     * then shown exactly as received. Hand-written rather than org.json so key order is kept and
     * numbers keep their exact text (org.json prints `1.0` as `1`).
     */
    fun parseJson(raw: String): RemoteConfigJson? = JsonParser(raw).parseRoot()

    /**
     * The raw text of each top-level element of a JSON array, or null when [raw] is not a
     * well-formed array. Tokens are copied exactly - FIND rebuilds a narrowed array from them.
     */
    fun topLevelArrayElements(raw: String): List<String>? {
        val s = raw.trim()
        if (s.length < 2 || s[0] != '[' || s[s.length - 1] != ']') return null

        val elements = ArrayList<String>()
        var depth = 0
        var inString = false
        var escaped = false
        var start = 1

        for (i in 1 until s.length - 1) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth < 0) return null
                }
                ',' -> if (depth == 0) {
                    elements += s.substring(start, i).trim()
                    start = i + 1
                }
            }
        }
        if (inString || depth != 0) return null

        val last = s.substring(start, s.length - 1).trim()
        when {
            last.isNotEmpty() -> elements += last
            elements.isNotEmpty() -> return null        // trailing comma: "[1,]"
        }
        return elements
    }
}

/** Recursive-descent JSON parser behind [AdLogRemoteConfig.parseJson]. Null on anything malformed. */
private class JsonParser(private val s: String) {

    private var i = 0
    private var depth = 0

    fun parseRoot(): RemoteConfigJson? {
        skipWhitespace()
        if (peek() != '{' && peek() != '[') return null
        val root = value() ?: return null
        skipWhitespace()
        return if (i == s.length) root else null
    }

    private fun value(): RemoteConfigJson? {
        skipWhitespace()
        return when (peek()) {
            '{' -> nested { obj() }
            '[' -> nested { arr() }
            '"' -> string()?.let { RemoteConfigJson.Str(it) }
            null -> null
            else -> literal()
        }
    }

    /** A pathological, deeply nested value must not be able to overflow the stack. */
    private inline fun nested(block: () -> RemoteConfigJson?): RemoteConfigJson? {
        if (++depth > MAX_DEPTH) return null
        return block().also { depth-- }
    }

    private fun obj(): RemoteConfigJson? {
        i++                                             // '{'
        val fields = ArrayList<Pair<String, RemoteConfigJson>>()
        skipWhitespace()
        if (peek() == '}') {
            i++
            return RemoteConfigJson.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') return null
            val key = string() ?: return null
            skipWhitespace()
            if (peek() != ':') return null
            i++
            val v = value() ?: return null
            fields += key to v
            skipWhitespace()
            when (peek()) {
                ',' -> i++
                '}' -> {
                    i++
                    return RemoteConfigJson.Obj(fields)
                }
                else -> return null
            }
        }
    }

    private fun arr(): RemoteConfigJson? {
        i++                                             // '['
        val items = ArrayList<RemoteConfigJson>()
        skipWhitespace()
        if (peek() == ']') {
            i++
            return RemoteConfigJson.Arr(items)
        }
        while (true) {
            items += value() ?: return null
            skipWhitespace()
            when (peek()) {
                ',' -> i++
                ']' -> {
                    i++
                    return RemoteConfigJson.Arr(items)
                }
                else -> return null
            }
        }
    }

    private fun string(): String? {
        i++                                             // opening quote
        val out = StringBuilder()
        while (i < s.length) {
            when (val c = s[i++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (i >= s.length) return null
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) return null
                            val code = s.substring(i, i + 4)
                            if (!code.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
                            out.append(code.toInt(16).toChar())
                            i += 4
                        }
                        else -> return null
                    }
                }
                else -> out.append(c)
            }
        }
        return null
    }

    private fun literal(): RemoteConfigJson? {
        val start = i
        while (i < s.length && s[i] !in ",]}:" && !s[i].isWhitespace()) i++
        val raw = s.substring(start, i)
        val valid = raw == "true" || raw == "false" || raw == "null" || JSON_NUMBER.matches(raw)
        return if (valid) RemoteConfigJson.Lit(raw) else null
    }

    private fun peek(): Char? = if (i < s.length) s[i] else null

    private fun skipWhitespace() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private companion object {
        const val MAX_DEPTH = 64
    }
}
