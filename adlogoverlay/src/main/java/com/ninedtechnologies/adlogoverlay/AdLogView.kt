package com.ninedtechnologies.adlogoverlay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Editable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The floating ad-log window: a translucent readout that sits on top of whatever screen is
 * showing, plus the small draggable bubble it collapses to.
 *
 * ## Three rules drive the whole design
 *
 * **1. It must never steal a touch it does not need.** The panel is decoration; the app
 * underneath has to stay usable. Android only lets a view consume a touch if it is
 * clickable, so the panel and its inert children are left non-clickable and
 * [onInterceptTouchEvent] is never overridden - `dispatchTouchEvent` then walks past the
 * panel to the real UI behind it. The things that DO take a touch say so for themselves: the
 * header (drag), the chips, the log scroller, the search field, the filter rows and the tool
 * rows - and every one of the last three is GONE until asked for.
 *
 * **2. It borrows the host's theme, not the host's layout.** The panel paints itself on the
 * host's `surface` token, so it is a light panel in a light app and a dark one in a dark app.
 * [AdLogPalette] then picks the event palette that is legible on that surface.
 *
 * **3. Nothing is rebuilt that can be appended.** Rendering is batched and appends in place;
 * see [pending].
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class AdLogView(context: Context) : FrameLayout(context) {

    companion object {
        private const val MAX_LINES = 60

        /** Batch window. 4 UI updates a second is plenty for reading a log. */
        private const val FLUSH_MS = 250L

        /** Trim only once the overshoot is this big, so the scan is amortised. */
        private const val TRIM_SLACK = 60

        /** How much history a freshly attached panel re-renders. */
        private const val ATTACH_TAIL = 50

        /**
         * How far back a filter or a query reaches. Deeper than [ATTACH_TAIL] because a
         * search that only looked at the last 50 lines would answer the wrong question, and
         * still bounded because one re-filter must stay a single O(n) pass.
         */
        private const val FILTER_TAIL = 250

        /** Typing re-filters at most this often. */
        private const val SEARCH_DEBOUNCE_MS = 200L

        private const val SNAP_MS = 240L
        private const val BUBBLE_DP = 38f

        /** The ✕ drop target shown while the bubble is dragged. */
        private const val DISMISS_DP = 52f

        /** How close the bubble's centre must come to the target's centre to be caught by it. */
        private const val DISMISS_CATCH_DP = 64f

        /** Target centre's height above the bottom system bar. */
        private const val DISMISS_LIFT_DP = 64f

        /**
         * Where the collapsed bubble lives, remembered across activities AND across
         * rotation - which is why it is a side plus a FRACTION rather than a pixel pair.
         * Pixels measured on a portrait screen put the bubble off the bottom of a
         * landscape one.
         */
        private var bubbleOnLeft = true
        private var bubbleYFraction = 0.25f

        /** Vertical offset of the expanded panel, likewise remembered across activities. */
        private var panelY = 0f

        private var searchOpen = false
        private var sheetOpen = false

        /** The TOOLS sheet - remembered across screens, like the filter sheet whose slot it shares. */
        private var toolsOpen = false

        /** The tab showing - remembered across screens, like search and the filter sheet. */
        private var openTab = Tab.ADS

        /**
         * The last Remote Config read. Kept here so a new screen draws the config at once
         * while a fresh read runs, instead of flashing "reading…" on every navigation.
         * Written only on the main thread.
         */
        private var lastFrc: RemoteConfigSnapshot? = null

        /**
         * Views currently attached. A REFRESH can outlast the screen that started it, and its
         * result has to reach whichever screen is showing when the network answers.
         * Main thread only; views leave the set on detach, so nothing off screen is held.
         */
        private val attachedViews = LinkedHashSet<AdLogView>()

        /**
         * FRC groups the tester folded, by the paths from AdLogRemoteConfig.headingPaths - or, for a
         * whole setting, its key. Kept here, not in the view, so a folded group stays folded across
         * refreshes, searches and screen changes. Main thread only.
         */
        private val foldedFrc = HashSet<String>()

        /** STATS blocks the tester folded, by slot name. Main thread only, kept here for the same reason. */
        private val foldedStats = HashSet<String>()

        /**
         * TOOLS > CLEAR emptied the store: every attached panel drops what it is showing. Only screens
         * on show are attached, and any other screen re-renders from the store when it attaches.
         */
        private fun onStoreCleared() = attachedViews.toList().forEach { it.afterStoreCleared() }

        /** A second tap within this confirms CLEAR or RESET. Both throw away something a tester may need. */
        private const val CONFIRM_MS = 3_000L

        /** STATS redraws at most this often while events arrive. */
        private const val STATS_REFRESH_MS = 1_000L

        /** STATS redraws this often when nothing arrives, so cache ages and expiry keep moving. */
        private const val STATS_TICK_MS = 15_000L

        /** The SCREEN line looks again this often while the panel is open - a dialog announces itself to nobody. */
        private const val WHERE_POLL_MS = 1_000L

        /** Width of the STATS session label column: "FAST CLICKS" plus a gap. */
        private const val SESSION_LABEL_CHARS = 13

        /** Width of the label column in the TOOLS and SCREEN rows, the same as the filter sheet's. */
        private const val ROW_LABEL_DP = 46f

        /**
         * Per-value ceiling in the FRC view. A Remote Config value can be an arbitrarily large
         * JSON blob, and a TextView lays out everything it is given on the main thread.
         */
        private const val MAX_FRC_VALUE_CHARS = 30_000

        /** Ceiling on readable lines per setting, for the same main-thread reason. */
        private const val MAX_FRC_ROWS = 1_500

        /** Width of the status block's label column: "Download rule" plus a gap. */
        private const val STATUS_LABEL_CHARS = 15

        /** Setting names longer than this push their value right instead of widening the column. */
        private const val MAX_LABEL_CHARS = 26

        /** Height of the text area on the ADS tab - a live tail is glanced at, not read. */
        private const val LOG_HEIGHT_DP = 150f

        /**
         * The STATS and FRC tabs are READ: a table of ad slots or a config needs far more lines than a
         * log tail, so their text area takes this share of the usable screen height instead - never
         * less than [LOG_HEIGHT_DP]. A fraction rather than a dp value so it scales with the phone;
         * on a typical 6-inch phone it comes to roughly 300dp, about twice the log.
         */
        private const val TALL_HEIGHT_FRACTION = 0.40f
    }

    /** ADS is the live log; STATS and FRC are read, and share the taller text area. */
    private enum class Tab { ADS, STATS, FRC }

    private val frcOpen: Boolean get() = openTab == Tab.FRC


    private val palette = AdLogPalette.resolve(context, AdLogOverlay.config)

    private val panel: LinearLayout
    private val logText: TextView
    private val scroller: ScrollView
    private val bubble: TextView
    private val dismissTarget: TextView

    /** True while a dragged bubble sits over the ✕ target - releasing now closes the overlay. */
    private var dismissArmed = false

    private lateinit var qaChip: TextView
    private lateinit var findChip: TextView

    /** The ADS | STATS | FRC tabs. FRC is null when the host has no Firebase Remote Config. */
    private lateinit var adsTab: TextView
    private lateinit var statsTab: TextView
    private var frcTab: TextView? = null

    /** The fast-click count the STATS tab's dot was last drawn for; -1 before the first draw. */
    private var dotDrawnFor = -1

    /** FRC tab only: pulls the latest config from the Firebase server. Null without Firebase. */
    private var fetchChip: TextView? = null

    /**
     * Owns off-main-thread work - Remote Config and consent reads, building a SHARE report.
     * Cancelled when this view detaches.
     */
    private var ioScope: CoroutineScope? = null

    private lateinit var toolsChip: TextView
    private lateinit var toolsSheet: LinearLayout
    private lateinit var clearChip: TextView
    /** Null when the host has no consent SDK. */
    private var resetChip: TextView? = null
    private var consentText: TextView? = null
    private lateinit var whereText: TextView

    /** True between a first tap on CLEAR or RESET and the second tap that confirms it. */
    private var clearArmed = false
    private var resetArmed = false
    private val disarm = Runnable {
        clearArmed = false
        resetArmed = false
        styleToolChips()
    }

    /** Uptime of the last STATS draw, for [STATS_REFRESH_MS]. */
    private var statsDrawnAt = 0L
    private lateinit var filterChip: TextView
    private lateinit var summaryRow: LinearLayout
    private lateinit var summaryText: TextView
    private lateinit var searchRow: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var filterSheet: LinearLayout
    private lateinit var kindChips: LinearLayout
    private lateinit var screenChips: LinearLayout
    private lateinit var placementChips: LinearLayout
    private lateinit var reasonRow: View
    private lateinit var reasonChips: LinearLayout

    private var builtFacetVersion = -1

    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0

    /**
     * Survives activity swaps because [AdLogOverlay] keeps the value and re-applies it.
     *
     * Starts COLLAPSED, matching [AdLogOverlay.collapsed]'s own default. The two have to
     * agree: this is the state `init` lays out with, before `attach` gets to call
     * [setCollapsed], so a `false` here means every newly attached view is briefly built
     * expanded - and briefly starts the reader - even when the panel is meant to be shut.
     */
    var collapsed: Boolean = true
        private set

    /**
     * Incoming entries are BATCHED, never rendered one at a time.
     *
     * The first cut of this rendered on every line and rebuilt the whole spannable each
     * time (`SpannableStringBuilder(logText.text)` copies everything, then trim copied it
     * again) - O(n) work per line over a 200-line buffer, so O(n^2) overall, on the main
     * thread, while the Mobile Ads SDK emits dozens of lines a second during init. That is
     * enough to visibly stall the whole app. Now lines land here on the reader thread and one
     * posted flush drains them every [FLUSH_MS], appending IN PLACE to the TextView's
     * Editable and scrolling once per batch.
     *
     * Filtering and search never break that: both narrow the batch on its way in, and a
     * CHANGE of filter costs one bounded re-render that is debounced by [SEARCH_DEBOUNCE_MS].
     */
    private val pending = ArrayList<AdLogEntry>(64)
    private var flushScheduled = false
    private var lineCount = 0

    private val flush = Runnable {
        synchronized(pending) {
            flushScheduled = false
            if (pending.isEmpty()) return@Runnable
            // Collapsed means nothing is on screen: drop the batch instead of formatting it.
            // STATS and FRC own the text while they are open, so log lines must not be appended
            // into the middle of them. Nothing is lost either way - AdLogStore keeps the
            // history, and renderAll() draws its tail when the log view comes back.
            if (collapsed || openTab != Tab.ADS) {
                pending.clear()
            } else {
                val batch = ArrayList(pending)
                pending.clear()
                renderBatch(batch)
            }
        }
        updateStatsDot()
        if (openTab == Tab.STATS && !collapsed) requestStatsRender()
        if (sheetOpen && builtFacetVersion != AdLogStore.facetVersion) rebuildFacets()
    }

    private val statsRender = Runnable {
        if (openTab == Tab.STATS && !collapsed) renderStats(resetScroll = false)
    }

    /** Keeps ages and expiry moving on STATS when no event arrives to redraw it. */
    private val statsTicker = object : Runnable {
        override fun run() {
            if (openTab != Tab.STATS || collapsed || !isAttachedToWindow) return
            renderStats(resetScroll = false)
            postDelayed(this, STATS_TICK_MS)
        }
    }

    private val whereTicker = object : Runnable {
        override fun run() {
            if (collapsed || !isAttachedToWindow) return
            updateWhere()
            postDelayed(this, WHERE_POLL_MS)
        }
    }

    private val reRender = Runnable {
        renderAll()
        styleFilterChrome()
    }

    private val listener: (AdLogEntry) -> Unit = { entry ->
        synchronized(pending) {
            pending += entry
            // Cheap guard against a runaway producer between flushes.
            if (pending.size > 400) pending.subList(0, pending.size - 400).clear()
            if (!flushScheduled) {
                flushScheduled = true
                postDelayed(flush, FLUSH_MS)
            }
        }
    }

    init {
        isClickable = false
        isFocusable = false

        // Every screen here is edge-to-edge, so android.R.id.content starts at y=0 behind
        // the status bar. Without this the panel sits on top of the clock and the icons, and
        // the bubble could be dropped underneath them.
        setOnApplyWindowInsetsListener { v, insets ->
            readSystemBars(insets)
            v.setPadding(0, insetTop, 0, 0)
            positionBubble(animate = false)
            insets
        }

        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = false
            background = GradientDrawable().apply {
                cornerRadius = dp(10f)
                setColor(palette.panel)
                // The panel's own edge cannot be `border_input`: at #F0F0F0 on a #FFFFFF
                // surface that token is a hairline meant for fields INSIDE a card, and it
                // vanishes against light app content. The subtext token is the dimmest one
                // that still separates the panel from whatever it is floating over.
                setStroke(dp(1f).toInt(), palette.subtext)
            }
            setPadding(dp(8f).toInt(), dp(6f).toInt(), dp(8f).toInt(), dp(8f).toInt())
        }

        panel.addView(buildHeader())
        panel.addView(buildSummaryRow())
        panel.addView(buildSearchRow())
        panel.addView(buildFilterSheet())
        panel.addView(buildToolsSheet())

        logText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            setTextColor(palette.colorOf(AdLogKind.OTHER))
            setLineSpacing(0f, 1.05f)
            isClickable = false
            setTextIsSelectable(false)
        }
        // FRC tab only: a tap on a fold line folds or unfolds it. See TapToFold.
        logText.setOnTouchListener(TapToFold())
        // The log area deliberately takes vertical drags so history can be scrolled back;
        // everywhere else on the panel still falls through to the app.
        scroller = ScrollView(context).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                logText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        panel.addView(
            scroller,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(LOG_HEIGHT_DP).toInt())
        )
        panel.addView(buildWhereRow())

        addView(
            panel,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply {
                    gravity = Gravity.TOP
                    leftMargin = dp(8f).toInt()
                    rightMargin = dp(8f).toInt()
                    topMargin = dp(4f).toInt()
                }
        )

        // Added BEFORE the bubble so the bubble draws on top of it when dropped onto it.
        dismissTarget = buildDismissTarget()
        addView(
            dismissTarget,
            LayoutParams(dp(DISMISS_DP).toInt(), dp(DISMISS_DP).toInt())
                .apply { gravity = Gravity.TOP or Gravity.START }
        )

        bubble = buildBubble()
        addView(
            bubble,
            LayoutParams(dp(BUBBLE_DP).toInt(), dp(BUBBLE_DP).toInt())
                .apply { gravity = Gravity.TOP or Gravity.START }
        )

        applyMode()
        styleFilterChrome()
        renderAll()
    }

    // ---------------------------------------------------------------- chrome

    private fun buildHeader(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The header doubles as the panel's drag handle. It has to be the handle rather
            // than the panel as a whole, because the log area below it owns its own vertical
            // drags for scrolling - one gesture cannot mean both.
            isClickable = true
            setOnTouchListener(DragPanelVertically())
        }
        adsTab = tab("ADS") { setTab(Tab.ADS) }.also {
            it.contentDescription = "Show the ad log"
            row.addView(it)
        }
        statsTab = tab("STATS") { setTab(Tab.STATS) }.also {
            it.contentDescription = "Show ad stats for each slot"
            row.addView(it)
        }
        // Only a host that ships Firebase Remote Config gets an FRC tab. For any other host it
        // could only ever say "not available".
        if (AdLogRemoteConfig.isOnClasspath) {
            frcTab = tab("FRC") { setTab(Tab.FRC) }.also {
                it.contentDescription = "Show this app's Firebase Remote Config"
                row.addView(it)
            }
        }
        row.addView(
            TextView(context).apply {
                // The dots read as a grab handle.
                text = "  ⠿"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(palette.subtext)
                isClickable = false
            }
        )

        // The chips take the flexible middle, right-aligned. Not clickable itself, so the free space
        // to their left still drags the panel; and on a narrow screen or a large font it is the
        // chips that run out of room, never the ✕ that closes the panel.
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            isClickable = false
        }
        row.addView(chips, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (frcTab != null) {
            fetchChip = chip("REFRESH") { startFetch() }.also {
                it.contentDescription = "Download the latest Remote Config from Firebase"
                chips.addView(it, chipParams())
            }
        }

        findChip = chip("FIND") { setSearchOpen(!searchOpen) }
        findChip.contentDescription = "Search the ad log"
        chips.addView(findChip, chipParams())

        filterChip = chip("FILTER") { setSheetOpen(!sheetOpen) }
        filterChip.contentDescription = "Filter ad events"
        chips.addView(filterChip, chipParams())

        qaChip = chip("QA") {
            AdLogFilter.qaOnly = !AdLogFilter.qaOnly
            reRender.run()
        }
        qaChip.contentDescription = "Show only ad events, in plain language"
        chips.addView(qaChip, chipParams())

        toolsChip = chip("TOOLS") { setToolsOpen(!toolsOpen) }
        toolsChip.contentDescription = "Mark, clear or share the log, open Ad Inspector, check consent"
        chips.addView(toolsChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        row.addView(
            TextView(context).apply {
                text = "✕"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(palette.heading)
                setPadding(dp(8f).toInt(), dp(2f).toInt(), dp(4f).toInt(), dp(6f).toInt())
                isClickable = true
                contentDescription = "Collapse ad log"
                setOnClickListener { setCollapsed(true) }
            }
        )
        return row
    }

    /**
     * The always-visible answer to "what am I looking at". Without it a filter left on from
     * three screens ago reads as "the ad stack went quiet", which is the worst failure mode
     * a debug tool can have.
     */
    private fun buildSummaryRow(): View {
        summaryText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            typeface = Typeface.MONOSPACE
            setTextColor(palette.heading)
            isSingleLine = true
            isClickable = false
        }
        val clear = chip("CLEAR") {
            AdLogFilter.clear()
            searchInput.setText("")
            rebuildFacets()
            reRender.run()
        }
        clear.contentDescription = "Clear all ad log filters"
        summaryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            setPadding(0, dp(3f).toInt(), 0, dp(1f).toInt())
            addView(
                summaryText,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(clear)
            visibility = GONE
        }
        return summaryRow
    }

    /**
     * Hidden until asked for, and that is not cosmetic: a focusable EditText parked in every
     * Activity's content root is a view the app's own screens can lose their first focus to,
     * and a keyboard that can open over a screen nobody asked to type on. GONE cannot take
     * focus, so the field only exists while it is wanted.
     */
    private fun buildSearchRow(): View {
        searchInput = EditText(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            hint = "find in log"
            setHintTextColor(palette.subtext)
            setTextColor(palette.heading)
            isSingleLine = true
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = true
            setOnClickListener { enableTyping() }
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(4f)
                setColor(palette.container)
                setStroke(dp(1f).toInt(), palette.border)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    AdLogFilter.query = s?.toString().orEmpty()
                    removeCallbacks(reRender)
                    postDelayed(reRender, SEARCH_DEBOUNCE_MS)
                }
            })
        }
        searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            setPadding(0, dp(3f).toInt(), 0, dp(2f).toInt())
            addView(
                searchInput,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                chip("✕") { searchInput.setText(""); setSearchOpen(false) }.apply {
                    contentDescription = "Close search"
                },
                chipParams()
            )
            visibility = GONE
        }
        return searchRow
    }

    /**
     * Four facets, each a scrollable strip of multi-select chips: which EVENT, which SCREEN,
     * which AD slot, which ERROR. Their values are learned from the log rather than listed in
     * code - a hardcoded placement list would be stale the day a slot is added.
     */
    private fun buildFilterSheet(): View {
        val kindRow = facetRow("EVENT").also { kindChips = it.second }
        val screenRow = facetRow("SCREEN").also { screenChips = it.second }
        val placementRow = facetRow("AD").also { placementChips = it.second }
        val errorRow = facetRow("ERROR").also { reasonChips = it.second }
        reasonRow = errorRow.first

        filterSheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = false
            setPadding(0, dp(3f).toInt(), 0, dp(4f).toInt())
            addView(kindRow.first)
            addView(screenRow.first)
            addView(placementRow.first)
            addView(errorRow.first)
            visibility = GONE
        }
        return filterSheet
    }

    /**
     * TOOLS, in the filter sheet's slot and style - one row per job, a label column then chips:
     *
     *     LOG      MARK  CLEAR  SHARE
     *     SDK      AD INSPECTOR                      (only when the host has Google Mobile Ads)
     *     CONSENT  obtained · can request ads · GDPR applies   RESET   (only with UMP)
     */
    private fun buildToolsSheet(): View {
        val (logRow, logChips) = facetRow("LOG")
        logChips.addView(
            chip("MARK") { addMark() }.apply { contentDescription = "Add a numbered divider to the log" },
            chipParams()
        )
        clearChip = chip("CLEAR") { onClearTapped() }.apply {
            contentDescription = "Clear the log and stats - tap twice"
        }
        logChips.addView(clearChip, chipParams())
        logChips.addView(
            chip("SHARE") { shareReport() }.apply { contentDescription = "Share the log, stats and Remote Config" },
            chipParams()
        )

        toolsSheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = false
            setPadding(0, dp(3f).toInt(), 0, dp(4f).toInt())
            // A hairline under the sheet: its chips act on things, where the log below only reads.
            background = LayerDrawable(arrayOf(GradientDrawable().apply { setColor(palette.border) })).apply {
                setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
                setLayerHeight(0, dp(1f).toInt().coerceAtLeast(1))
            }
            addView(logRow)
            visibility = GONE
        }

        if (AdLogSdk.hasMobileAds) {
            val (sdkRow, sdkChips) = facetRow("SDK")
            sdkChips.addView(
                chip("AD INSPECTOR") { openAdInspector() }.apply {
                    contentDescription = "Open Google's Ad Inspector"
                },
                chipParams()
            )
            toolsSheet.addView(sdkRow)
        }

        if (AdLogSdk.hasConsent) {
            val text = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                typeface = Typeface.MONOSPACE
                setTextColor(palette.subtext)
                text = "reading…"
                isClickable = false
            }
            consentText = text
            val reset = chip("RESET") { onResetTapped() }.apply {
                contentDescription = "Reset consent so the form shows again - tap twice"
            }
            resetChip = reset
            toolsSheet.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = false
                    setPadding(0, dp(2f).toInt(), 0, dp(2f).toInt())
                    addView(rowLabel("CONSENT"), LinearLayout.LayoutParams(dp(ROW_LABEL_DP).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(reset)
                }
            )
        }
        styleToolChips()
        return toolsSheet
    }

    /** Where the tester is, under everything else - see [AdLogWhere]. */
    private fun buildWhereRow(): View {
        whereText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            typeface = Typeface.MONOSPACE
            setTextColor(palette.heading)
            maxLines = 2
            isClickable = false
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            setPadding(0, dp(4f).toInt(), 0, 0)
            addView(rowLabel("SCREEN"), LinearLayout.LayoutParams(dp(ROW_LABEL_DP).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(whereText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun rowLabel(title: String): TextView = TextView(context).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(palette.subtext)
        isClickable = false
    }

    private fun facetRow(title: String): Pair<View, LinearLayout> {
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            setPadding(0, dp(2f).toInt(), 0, dp(2f).toInt())
            addView(rowLabel(title), LinearLayout.LayoutParams(dp(ROW_LABEL_DP).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    addView(chips)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        return row to chips
    }

    /**
     * A tab, deliberately NOT a chip. Chips in this header are filters over the log; a tab
     * switches to a different view. Drawn as text with an underline bar under the selected one,
     * so the two cannot be mistaken for each other.
     */
    private fun tab(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(dp(6f).toInt(), dp(2f).toInt(), dp(6f).toInt(), dp(5f).toInt())
        isClickable = true
        setOnClickListener { onClick() }
        styleTab(this, selected = false)
    }

    private fun styleTab(v: TextView, selected: Boolean) {
        v.setTextColor(if (selected) palette.heading else palette.subtext)
        v.isSelected = selected
        v.background = if (!selected) null else LayerDrawable(
            arrayOf(GradientDrawable().apply { setColor(palette.heading) })
        ).apply {
            setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            setLayerHeight(0, dp(2f).toInt())
        }
    }

    private fun chipParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { rightMargin = dp(5f).toInt() }

    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        // One line: squeezed for room, a chip is cut short rather than doubling the header's height.
        maxLines = 1
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(dp(7f).toInt(), dp(2f).toInt(), dp(7f).toInt(), dp(2f).toInt())
        isClickable = true
        setOnClickListener { onClick() }
        styleChip(this, selected = false, tint = palette.heading)
    }

    /** Filled when on, outlined when off - the state is readable without reading the label. */
    private fun styleChip(v: TextView, selected: Boolean, tint: Int) {
        v.setTextColor(if (selected) palette.surface else palette.subtext)
        v.background = GradientDrawable().apply {
            cornerRadius = dp(4f)
            if (selected) {
                setColor(tint)
            } else {
                setColor(palette.container)
                setStroke(dp(1f).toInt(), palette.subtext)
            }
        }
    }

    private fun rebuildFacets() {
        builtFacetVersion = AdLogStore.facetVersion

        kindChips.removeAllViews()
        for (kind in AdLogKind.entries) {
            val c = chip(kind.label.trim()) { toggle(AdLogFilter.kinds, kind) }
            styleChip(c, kind in AdLogFilter.kinds, palette.colorOf(kind))
            kindChips.addView(c, chipParams())
        }

        fillFacet(screenChips, AdLogStore.screens(), AdLogFilter.screens)
        fillFacet(placementChips, AdLogStore.placements(), AdLogFilter.placements)
        fillFacet(reasonChips, AdLogStore.reasons(), AdLogFilter.reasons)
        // The error strip is only a row of nothing until something has actually failed.
        reasonRow.visibility = if (reasonChips.childCount == 0) GONE else VISIBLE
    }

    private fun fillFacet(host: LinearLayout, values: List<String>, selection: MutableSet<String>) {
        host.removeAllViews()
        // A value that is filtered on but has aged out of the store still needs its chip,
        // or the filter becomes impossible to switch off from the sheet.
        val all = LinkedHashSet(values).apply { addAll(selection) }
        for (v in all) {
            val c = chip(v.take(26)) { toggle(selection, v) }
            styleChip(c, v in selection, palette.heading)
            host.addView(c, chipParams())
        }
    }

    private fun <T> toggle(set: MutableSet<T>, value: T) {
        if (!set.remove(value)) set.add(value)
        // Posted: this runs from a chip's own click, and the strip it lives in is about to
        // be emptied and refilled.
        post { rebuildFacets() }
        reRender.run()
    }

    /** Header chips and the summary line, kept in step with [AdLogFilter]. */
    private fun styleFilterChrome() {
        styleChip(qaChip, AdLogFilter.qaOnly, palette.colorOf(AdLogKind.LOADED))
        styleChip(findChip, searchOpen || AdLogFilter.query.isNotEmpty(), palette.colorOf(AdLogKind.REQUEST))
        val facetsOn = AdLogFilter.kinds.isNotEmpty() || AdLogFilter.screens.isNotEmpty() ||
            AdLogFilter.placements.isNotEmpty() || AdLogFilter.reasons.isNotEmpty()
        styleChip(filterChip, sheetOpen || facetsOn, palette.colorOf(AdLogKind.SHOWN))
        // Indigo, the colour of SDK lines: TOOLS acts on the SDK and the session, not on the view.
        styleChip(toolsChip, toolsOpen, palette.colorOf(AdLogKind.INIT))
        styleTab(adsTab, openTab == Tab.ADS)
        styleTab(statsTab, openTab == Tab.STATS)
        frcTab?.let { styleTab(it, frcOpen) }
        fetchChip?.let {
            it.visibility = if (frcOpen) VISIBLE else GONE
            val busy = AdLogRemoteConfig.fetchInFlight
            it.text = if (busy) "REFRESHING…" else "REFRESH"
            styleChip(it, busy, palette.colorOf(AdLogKind.REQUEST))
        }

        // QA, the facet sheet and the facet summary only mean something against the ad log.
        // On STATS and FRC they step aside rather than sitting there doing nothing when tapped.
        // FIND stays on FRC, where it searches the config; STATS is a table with nothing to find.
        // TOOLS is on every tab.
        val onLog = openTab == Tab.ADS
        qaChip.visibility = if (onLog) VISIBLE else GONE
        filterChip.visibility = if (onLog) VISIBLE else GONE
        findChip.visibility = if (openTab == Tab.STATS) GONE else VISIBLE
        searchRow.visibility = if (searchOpen && openTab != Tab.STATS) VISIBLE else GONE
        filterSheet.visibility = if (sheetOpen && onLog) VISIBLE else GONE
        toolsSheet.visibility = if (toolsOpen) VISIBLE else GONE

        if (AdLogFilter.isActive && onLog) {
            summaryText.text = AdLogFilter.summary()
            summaryRow.visibility = VISIBLE
        } else {
            summaryRow.visibility = GONE
        }
    }

    private fun setTab(tab: Tab) {
        if (openTab == tab) {
            // Tapping the tab already showing. On FRC that means "read it again now" - the
            // obvious gesture after the app has fetched. Elsewhere there is nothing to redo.
            if (tab == Tab.FRC) loadRemoteConfig()
            return
        }
        openTab = tab
        applyContentHeight()
        removeCallbacks(reRender)
        if (tab == Tab.FRC) loadRemoteConfig()
        // Draws the stats or the config, or - back on ADS - the log tail they were hiding.
        renderAll()
        styleFilterChrome()
        updateStatsTicker()
    }

    private fun setSearchOpen(open: Boolean) {
        searchOpen = open
        searchRow.visibility = if (open) VISIBLE else GONE
        if (open) {
            enableTyping()
        } else {
            dismissKeyboard()
            searchInput.setText("")
            AdLogFilter.query = ""
            removeCallbacks(reRender)
            reRender.run()
        }
        styleFilterChrome()
    }

    private fun setSheetOpen(open: Boolean) {
        sheetOpen = open
        // FILTER and TOOLS share one slot under the header: opening one closes the other.
        if (open) toolsOpen = false
        if (open) rebuildFacets()
        styleFilterChrome()
    }

    private fun setToolsOpen(open: Boolean) {
        toolsOpen = open
        if (open) {
            sheetOpen = false
            refreshConsent()
        }
        styleFilterChrome()
    }

    // ---------------------------------------------------------------- tools

    private fun addMark() {
        val n = AdLogStore.mark()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        // On the log the divider itself is the confirmation; elsewhere it would land unseen.
        if (openTab != Tab.ADS) toast("Mark $n added to the log")
    }

    private fun onClearTapped() {
        if (!clearArmed) {
            clearArmed = true
            resetArmed = false
            styleToolChips()
            removeCallbacks(disarm)
            postDelayed(disarm, CONFIRM_MS)
            return
        }
        removeCallbacks(disarm)
        clearArmed = false
        AdLogStore.clear()
        onStoreCleared()
        styleToolChips()
        toast("Log and stats cleared")
    }

    private fun onResetTapped() {
        if (!resetArmed) {
            resetArmed = true
            clearArmed = false
            styleToolChips()
            removeCallbacks(disarm)
            postDelayed(disarm, CONFIRM_MS)
            return
        }
        removeCallbacks(disarm)
        resetArmed = false
        styleToolChips()
        val error = AdLogSdk.resetConsent(context)
        toast(error ?: "Consent reset - close and reopen the app to see the consent form again", long = true)
        refreshConsent()
    }

    /** A chip waiting for its confirming tap turns red and asks. */
    private fun styleToolChips() {
        val red = palette.colorOf(AdLogKind.FAILED)
        clearChip.text = if (clearArmed) "CLEAR?" else "CLEAR"
        styleChip(clearChip, clearArmed, red)
        resetChip?.let {
            it.text = if (resetArmed) "RESET?" else "RESET"
            styleChip(it, resetArmed, red)
        }
    }

    private fun afterStoreCleared() {
        synchronized(pending) { pending.clear() }
        dotDrawnFor = -1
        updateStatsDot()
        if (sheetOpen) rebuildFacets()
        renderAll()
    }

    private fun openAdInspector() {
        AdLogSdk.openAdInspector(context) { error -> error?.let { toast(it, long = true) } }
    }

    private fun refreshConsent() {
        val text = consentText ?: return
        val appContext = context.applicationContext
        scope().launch {
            val consent = AdLogSdk.consent(appContext)
            ensureActive()
            this@AdLogView.post {
                val sb = SpannableStringBuilder()
                if (consent == null) sb.appendStyled("not available", palette.subtext)
                else appendSpans(sb, AdLogSdk.consentSpans(consent))
                text.text = sb
            }
        }
    }

    /**
     * SHARE: the whole log, the stats, Remote Config and the device, as text for Android's share
     * sheet - see [AdLogReport]. Built off the main thread; the screen is read here first, because
     * only the main thread may look at views.
     */
    private fun shareReport() {
        val activity = context
        val where = (activity as? Activity)?.let { AdLogWhere.of(it) }
        val appContext = context.applicationContext
        scope().launch {
            val now = System.currentTimeMillis()
            val header = reportHeader(appContext, now, where)
            val text = AdLogReport.build(
                header,
                AdLogStore.stats(),
                AdLogStore.snapshot(),
                if (AdLogRemoteConfig.isOnClasspath) AdLogRemoteConfig.read() else null,
                now
            )
            ensureActive()
            this@AdLogView.post {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Ad log - ${header.first().second}")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                val chooser = Intent.createChooser(send, "Share ad log")
                if (activity !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    activity.startActivity(chooser)
                } catch (e: Exception) {
                    toast("Can't share the log - ${e.javaClass.simpleName}: ${e.message.orEmpty()}", long = true)
                }
            }
        }
    }

    private fun reportHeader(app: Context, now: Long, where: String?): List<Pair<String, String>> {
        val header = ArrayList<Pair<String, String>>(6)
        val pm = app.packageManager
        val label = runCatching { app.applicationInfo.loadLabel(pm).toString() }.getOrDefault(app.packageName)
        val version = runCatching {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(app.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        }.getOrDefault("")
        header += "App" to listOf("$label $version".trim(), app.packageName).joinToString("  ·  ")
        header += "Device" to "${Build.MANUFACTURER} ${Build.MODEL}  ·  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        header += "Time" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss ZZZZZ", Locale.US).format(Date(now))
        where?.let { header += "Screen" to it }
        AdLogSdk.consent(app)?.let { c -> header += "Consent" to AdLogSdk.consentSpans(c).joinToString("") { it.text } }
        return header
    }

    private fun updateWhere() {
        val activity = context as? Activity ?: return
        val where = AdLogWhere.of(activity)
        if (whereText.text.toString() != where) whereText.text = where
    }

    private fun updateWhereTicker() {
        removeCallbacks(whereTicker)
        if (!collapsed && isAttachedToWindow) whereTicker.run()
    }

    /** A red dot on STATS while fast clicks exist - the one thing on that tab worth pulling a tester over for. */
    private fun updateStatsDot() {
        val n = AdLogStore.fastClickCount
        if (n == dotDrawnFor) return
        dotDrawnFor = n
        if (n == 0) {
            statsTab.text = "STATS"
            statsTab.contentDescription = "Show ad stats for each slot"
            return
        }
        statsTab.text = SpannableString("STATS●").apply {
            val dot = length - 1
            setSpan(ForegroundColorSpan(palette.colorOf(AdLogKind.FAILED)), dot, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.6f), dot, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(SuperscriptSpan(), dot, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        statsTab.contentDescription = "Show ad stats for each slot - $n fast click${if (n == 1) "" else "s"}"
    }

    private fun updateStatsTicker() {
        removeCallbacks(statsTicker)
        if (openTab == Tab.STATS && !collapsed && isAttachedToWindow) postDelayed(statsTicker, STATS_TICK_MS)
    }

    private fun toast(text: String, long: Boolean = false) =
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private fun scope(): CoroutineScope =
        ioScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { ioScope = it }

    private fun ime(): InputMethodManager? =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    /**
     * The field is NOT focusable until the tester puts a finger on it. It lives in every
     * Activity's content root, so a focusable EditText there is one the host's own screens
     * can lose their first focus to - and a keyboard that opens over a screen nobody asked
     * to type on. Focus is granted on an explicit touch and handed straight back on dismiss.
     */
    private fun enableTyping() {
        searchInput.isFocusable = true
        searchInput.isFocusableInTouchMode = true
        searchInput.requestFocus()
        ime()?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun dismissKeyboard() {
        searchInput.clearFocus()
        searchInput.isFocusable = false
        searchInput.isFocusableInTouchMode = false
        windowToken?.let { ime()?.hideSoftInputFromWindow(it, 0) }
    }

    private fun buildBubble(): TextView = TextView(context).apply {
        text = "AD"
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(palette.heading)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.panel)
            setStroke(dp(1f).toInt(), palette.subtext)
        }
        isClickable = true
        contentDescription = "Open ad log"
        setOnTouchListener(DragToMove { setCollapsed(false) })
    }

    /**
     * Moves the expanded panel up and down. Vertical only: the panel is full-width by
     * design, so sideways travel would just shave it off a screen edge.
     *
     * The offset is kept in [panelY] rather than in a layout param so it survives the
     * activity swap that [AdLogOverlay] does on every navigation.
     */
    private inner class DragPanelVertically : OnTouchListener {
        private var downY = 0f
        private var startY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    startY = panel.translationY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelY = (startY + (e.rawY - downY)).coerceIn(panelMinY(), panelMaxY())
                    panel.translationY = panelY
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.performClick()
                    return true
                }
            }
            return false
        }
    }

    /** Clamped so at least the header stays reachable at either end. */
    private fun panelMinY(): Float = -(panel.top.toFloat())

    private fun panelMaxY(): Float =
        (height - insetBottom - panel.top - dp(40f)).coerceAtLeast(0f)

    /**
     * Drag handling for the bubble, with a Messenger-style magnet release.
     *
     * A gesture that never travels beyond [slop] is a tap and opens the panel; anything
     * further moves the bubble and is NOT a tap, so the panel cannot spring open at the end
     * of every drag. On release the bubble animates to whichever side it ended up nearer and
     * parks there, keeping the height the user chose - that is what keeps it off the content
     * instead of sitting in the middle of it.
     *
     * Once a drag starts, a ✕ target rises near the bottom of the screen. Bring the bubble close
     * and it snaps onto the target with a haptic tick, so whether letting go will close the
     * overlay is settled before the finger lifts. Release there and [AdLogOverlay.dismiss] runs;
     * drag back out and the bubble follows the finger again. A cancelled gesture never dismisses.
     */
    private inner class DragToMove(private val onTap: () -> Unit) : OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0f
        private var startY = 0f
        private var dragging = false
        private val slop = dp(6f)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    downX = e.rawX; downY = e.rawY
                    startX = v.x; startY = v.y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        showDismissTarget()
                    }
                    if (dragging) {
                        val fingerX = (startX + dx).coerceIn(bubbleMinX(), bubbleMaxX())
                        val fingerY = (startY + dy).coerceIn(bubbleMinY(), bubbleMaxY())
                        val armed = AdLogOverlay.canShakeToReopen && isOverDismissTarget(fingerX, fingerY)
                        if (armed != dismissArmed) {
                            dismissArmed = armed
                            styleDismissTarget(armed, animate = true)
                            if (armed) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                        if (armed) {
                            v.x = dismissCenterX() - bubbleSize() / 2f
                            v.y = dismissCenterY() - bubbleSize() / 2f
                        } else {
                            v.x = fingerX
                            v.y = fingerY
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val dismiss = dismissArmed && e.actionMasked == MotionEvent.ACTION_UP
                        dismissArmed = false
                        hideDismissTarget()
                        if (dismiss) {
                            dismissOverlay()
                        } else {
                            // Not rememberBubble() on a dismiss: a shake then brings the bubble
                            // back where it lived, not parked on top of the ✕.
                            rememberBubble()
                            positionBubble(animate = true)
                        }
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                        onTap()
                    }
                    return true
                }
            }
            return false
        }
    }

    // -------------------------------------------------------------- dismiss target

    /** Decoration only: never clickable, and visible only while the bubble's own drag owns the touch. */
    private fun buildDismissTarget(): TextView = TextView(context).apply {
        text = "✕"
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    private fun dismissCenterX(): Float = width / 2f

    private fun dismissCenterY(): Float = height - insetBottom - dp(DISMISS_LIFT_DP)

    private fun isOverDismissTarget(bubbleX: Float, bubbleY: Float): Boolean {
        val half = bubbleSize() / 2f
        return hypot(bubbleX + half - dismissCenterX(), bubbleY + half - dismissCenterY()) <=
            dp(DISMISS_CATCH_DP)
    }

    /** Neutral when idle; the failure red and a little larger once the bubble is caught. */
    private fun styleDismissTarget(armed: Boolean, animate: Boolean) {
        dismissTarget.setTextColor(if (armed) palette.surface else palette.heading)
        dismissTarget.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (armed) {
                setColor(palette.colorOf(AdLogKind.FAILED))
            } else {
                setColor(palette.panel)
                setStroke(dp(1f).toInt(), palette.subtext)
            }
        }
        val scale = if (armed) 1.2f else 1f
        if (animate) {
            dismissTarget.animate().scaleX(scale).scaleY(scale).setDuration(120L).start()
        } else {
            dismissTarget.scaleX = scale
            dismissTarget.scaleY = scale
        }
    }

    private fun showDismissTarget() {
        if (!AdLogOverlay.canShakeToReopen || width == 0) return
        val size = dp(DISMISS_DP)
        val restY = dismissCenterY() - size / 2f
        dismissTarget.animate().cancel()
        styleDismissTarget(armed = false, animate = false)
        dismissTarget.x = dismissCenterX() - size / 2f
        dismissTarget.y = restY + dp(16f)
        dismissTarget.alpha = 0f
        dismissTarget.visibility = VISIBLE
        dismissTarget.animate().alpha(1f).y(restY).setDuration(160L).start()
    }

    private fun hideDismissTarget() {
        if (dismissTarget.visibility != VISIBLE) return
        dismissTarget.animate().cancel()
        dismissTarget.animate()
            .alpha(0f)
            .setDuration(120L)
            .withEndAction { dismissTarget.visibility = GONE }
            .start()
    }

    private fun dismissOverlay() {
        bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // Gone the instant the finger lifts, not a frame later when the view is removed.
        bubble.visibility = GONE
        // Posted: this runs inside the bubble's own touch dispatch, and dismissing removes this
        // whole view from the Activity.
        post {
            AdLogOverlay.dismiss()
            // dismiss() can decline. A bubble hidden above and never removed would otherwise be
            // gone from this screen with nothing to shake back.
            if (!AdLogOverlay.dismissed) {
                bubble.visibility = VISIBLE
                positionBubble(animate = false)
            }
        }
    }

    /** Called by [AdLogOverlay.reopen] - the buzz is the confirmation that the shake registered. */
    internal fun confirmReopened() {
        // On the view itself: after a shake the panel is open and the bubble is hidden.
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    // ------------------------------------------------------------- bubble geometry

    @Suppress("DEPRECATION")
    private fun readSystemBars(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            insetTop = bars.top; insetBottom = bars.bottom
            insetLeft = bars.left; insetRight = bars.right
        } else {
            insetTop = insets.systemWindowInsetTop
            insetBottom = insets.systemWindowInsetBottom
            insetLeft = insets.systemWindowInsetLeft
            insetRight = insets.systemWindowInsetRight
        }
    }

    private fun bubbleSize(): Float = dp(BUBBLE_DP)

    private fun bubbleMinX(): Float = insetLeft + dp(8f)

    private fun bubbleMaxX(): Float =
        (width - insetRight - bubbleSize() - dp(8f)).coerceAtLeast(bubbleMinX())

    private fun bubbleMinY(): Float = insetTop + dp(8f)

    private fun bubbleMaxY(): Float =
        (height - insetBottom - bubbleSize() - dp(8f)).coerceAtLeast(bubbleMinY())

    private fun rememberBubble() {
        bubbleOnLeft = (bubble.x + bubbleSize() / 2f) < width / 2f
        val span = bubbleMaxY() - bubbleMinY()
        bubbleYFraction = if (span <= 0f) 0f else ((bubble.y - bubbleMinY()) / span).coerceIn(0f, 1f)
    }

    /**
     * Puts the bubble where [bubbleOnLeft] and [bubbleYFraction] say it belongs, in the
     * CURRENT bounds. Because the stored position is a side plus a fraction, the same call
     * is correct after a rotation, after an activity swap and after the panel has been
     * opened and shut again - all three rebuild this view with different bounds.
     */
    private fun positionBubble(animate: Boolean) {
        if (width == 0 || height == 0) return
        val targetX = if (bubbleOnLeft) bubbleMinX() else bubbleMaxX()
        val targetY = bubbleMinY() + bubbleYFraction.coerceIn(0f, 1f) * (bubbleMaxY() - bubbleMinY())
        if (animate) {
            bubble.animate()
                .x(targetX).y(targetY)
                .setDuration(SNAP_MS)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        } else {
            bubble.animate().cancel()
            bubble.x = targetX
            bubble.y = targetY
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        positionBubble(animate = false)
        // Ceiling only. onSizeChanged runs before the children are laid out, so panel.top -
        // and therefore the floor - is not yet known; the drag handler owns the floor, where
        // it is. This is the half that matters after a rotation: a shorter screen can leave
        // the panel below its own bottom edge, a taller one never can.
        panelY = panelY.coerceAtMost(panelMaxY())
        if (!collapsed) panel.translationY = panelY
        // The STATS and FRC height is a share of the screen, so it has to be recomputed whenever the
        // screen size is known or changes. Posted: resizing a child from inside this view's own
        // layout pass would force a second pass.
        post { applyContentHeight() }
    }

    /** Set when the text area has just been resized; consumed by the next [onLayout]. */
    private var clampAfterResize = false

    /** Sizes the text area for the tab that is showing - see [TALL_HEIGHT_FRACTION]. */
    private fun applyContentHeight() {
        val usable = height - insetTop - insetBottom
        val target = if (openTab != Tab.ADS && usable > 0) {
            maxOf(dp(LOG_HEIGHT_DP), usable * TALL_HEIGHT_FRACTION).toInt()
        } else {
            dp(LOG_HEIGHT_DP).toInt()
        }
        val lp = scroller.layoutParams ?: return
        if (lp.height == target) return
        lp.height = target
        scroller.layoutParams = lp
        clampAfterResize = true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (clampAfterResize) {
            clampAfterResize = false
            keepPanelOnScreen()
        }
    }

    /**
     * A panel dragged low and then grown taller would run off the bottom and hide the very
     * lines it grew to show. Lift it just enough to fit. Runs once per resize, not on every
     * layout, so it never fights a drag the tester makes afterwards.
     */
    private fun keepPanelOnScreen() {
        if (collapsed || height == 0) return
        val fitY = (height - insetBottom - panel.top - panel.height).toFloat()
            .coerceAtLeast(panelMinY())
        if (panelY > fitY) {
            panelY = fitY
            panel.translationY = panelY
        }
    }

    // ---------------------------------------------------------------- state

    fun setCollapsed(value: Boolean) {
        collapsed = value
        AdLogOverlay.collapsed = value
        if (value) dismissKeyboard()
        applyMode()
        if (!value && isAttachedToWindow) {
            // Batches that arrived while collapsed were dropped rather than formatted, so
            // what is on screen is stale the moment the panel reopens.
            renderAll()
            if (sheetOpen) rebuildFacets()
            if (toolsOpen) refreshConsent()
            // Reopening is the natural "show me it now" - the host may have fetched and
            // activated since the config was last read.
            if (frcOpen) loadRemoteConfig()
        }
        updateStatsTicker()
        updateWhereTicker()
    }

    private fun applyMode() {
        panel.visibility = if (collapsed) GONE else VISIBLE
        bubble.visibility = if (collapsed) VISIBLE else GONE
        if (collapsed) {
            positionBubble(animate = false)
        } else {
            // Re-apply where the panel was dragged to on the previous screen.
            panel.translationY = panelY
            // Reopening straight onto a taller tab can overhang the bottom edge; check once it has
            // laid out. Only on STATS and FRC - the ADS tab keeps exactly the drag position it had.
            if (openTab != Tab.ADS) clampAfterResize = true
        }
    }

    // ---------------------------------------------------------------- content

    private fun renderAll() {
        // One entry point for both views, so the search debounce and every other caller of
        // reRender redraw whichever view is showing without knowing which one it is.
        when (openTab) {
            Tab.FRC -> return renderRemoteConfig(resetScroll = true)
            Tab.STATS -> return renderStats(resetScroll = true)
            Tab.ADS -> Unit
        }
        logText.setText("", TextView.BufferType.EDITABLE)
        lineCount = 0
        // Only the recent tail: re-rendering the whole buffer on every activity resume was
        // a visible hitch on navigation. A filter reaches further back than a plain attach,
        // because a query that only searched the last 50 lines would be misleading.
        val tail = if (AdLogFilter.isActive) FILTER_TAIL else ATTACH_TAIL
        val all = AdLogStore.snapshot()
        val window = if (all.size > tail) all.subList(all.size - tail, all.size) else all
        // Match first, format second. Formatting is the expensive half, and everything past
        // the last MAX_LINES matches would be built only to be trimmed off again.
        val hits = window.filter(AdLogFilter::matches)
        renderBatch(if (hits.size > MAX_LINES) hits.subList(hits.size - MAX_LINES, hits.size) else hits)
    }

    /** Formats one entry into its own small spannable - never touches the existing text. */
    private fun format(e: AdLogEntry): SpannableStringBuilder {
        e.mark?.let { return formatMark(e, it) }
        val sb = SpannableStringBuilder()
        val qa = AdLogFilter.qaOnly
        // A fast click is drawn as a failure: it is the line a tester has to act on.
        val fast = e.fastClickMillis != null
        val kindColor = palette.colorOf(if (fast) AdLogKind.FAILED else e.kind)
        // QA mode drops the milliseconds: "11:54:31" is what a tester reads off a stopwatch,
        // "11:54:31.487" is what a developer reads off a trace.
        val clock = if (qa) e.time.substringBefore('.') else e.time
        sb.append(clock).append("  ").append(if (fast) "FAST CLICK" else e.kind.label).append("  ")
        sb.setSpan(ForegroundColorSpan(kindColor), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val body = sb.length
        // WHAT the line is about, in plain terms. When the placement is known that is the
        // whole story a tester needs - "Home_NATIVE_AD (native)" says far more than the raw
        // "AdLogs-->: Home_NATIVE_AD request send" it came from.
        val placement = e.placement
        if (placement != null) {
            sb.append(placement)
            if (!qa) AdPlacement.formatOf(placement)?.let {
                sb.append("  (").append(it).append(")")
            }
        } else if (qa) {
            sb.append(e.message.take(70))
        } else {
            sb.append(e.tag).append(": ").append(e.message)
        }

        // A failure is only useful with its reason, so failures and refusals are printed at
        // full brightness instead of the dimmed body colour everything else gets.
        val isFailure = fast || e.kind == AdLogKind.FAILED || e.kind == AdLogKind.DENIED
        sb.setSpan(
            ForegroundColorSpan(if (isFailure) kindColor else palette.dim(kindColor)),
            body, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // How long the load took, bright: the number a tester compares between runs.
        e.loadMillis?.let { sb.appendStyled("  in ${AdLogStatsText.loadTime(it)}", palette.heading, bold = true) }

        // WHERE it happened, appended AFTER the body span so it keeps its own dimmer colour
        // instead of being repainted by a span that would otherwise cover it.
        e.screen?.let {
            val at = sb.length
            sb.append("  @").append(it)
            sb.setSpan(ForegroundColorSpan(palette.subtext), at, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        e.fastClickMillis?.let {
            val why = sb.length
            sb.append("\n              ↳ ").append(AdLogReport.fastClickReason(it))
            sb.setSpan(ForegroundColorSpan(kindColor), why, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), why, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (isFailure) {
            e.reason?.let { reason ->
                val why = sb.length
                sb.append("\n              ↳ ").append(reason)
                sb.setSpan(ForegroundColorSpan(kindColor), why, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), why, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // Underline rather than a highlight colour: a background wash would land under
        // fifteen different hues on two different surfaces, and only one of those thirty
        // combinations has to fail for the match to become the least readable line on screen.
        if (AdLogFilter.query.isNotEmpty()) underlineMatches(sb, AdLogFilter.query)

        sb.append("\n")
        return sb
    }

    /** `── MARK 2 ──────────── 11:54:36`, across the width of the log. */
    private fun formatMark(e: AdLogEntry, n: Int): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        sb.appendStyled("── MARK $n ", palette.heading, bold = true)
        val right = " ${e.time.substringBefore('.')}"
        // Measured, not counted: the monospace font has no box-drawing glyphs, and the fallback's
        // dash is wider than a column - counting columns ran the time onto a second line.
        val dash = SpannableStringBuilder().apply { appendStyled("─", palette.heading, bold = true) }
        val room = textWidth() - widthOf(sb) - logText.paint.measureText(right) - logText.paint.measureText("00")
        val dashes = (room / widthOf(dash)).toInt().coerceIn(3, 200)
        sb.appendStyled("─".repeat(dashes), palette.heading, bold = true)
        sb.appendStyled(right, palette.subtext)
        sb.append("\n")
        return sb
    }

    /** Pixels the text area is wide - before the first layout, what the panel's margins leave of the screen. */
    private fun textWidth(): Float =
        if (logText.width > 0) (logText.width - logText.totalPaddingLeft - logText.totalPaddingRight).toFloat()
        else resources.displayMetrics.widthPixels - dp(32f)

    /** Drawn width of styled text, fallback glyphs and bold included. */
    private fun widthOf(text: CharSequence, start: Int = 0, end: Int = text.length): Float =
        Layout.getDesiredWidth(text, start, end, logText.paint)

    private fun underlineMatches(sb: SpannableStringBuilder, query: String) {
        val hay = sb.toString().lowercase()
        var from = hay.indexOf(query)
        while (from >= 0) {
            sb.setSpan(UnderlineSpan(), from, from + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), from, from + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            from = hay.indexOf(query, from + query.length)
        }
    }

    /**
     * Appends a whole batch in place and scrolls ONCE. Cost is proportional to the batch,
     * not to everything already on screen - that is the whole point of the rewrite.
     */
    private fun renderBatch(batch: List<AdLogEntry>) {
        val visible = batch.filter(AdLogFilter::matches)
        if (visible.isEmpty()) return
        val editable = logText.editableText ?: run {
            logText.setText("", TextView.BufferType.EDITABLE)
            logText.editableText
        } ?: return

        val chunk = SpannableStringBuilder()
        visible.forEach { chunk.append(format(it)) }
        editable.append(chunk)
        // A failure adds a second "why" line, so count newlines rather than entries.
        lineCount += chunk.count { it == '\n' }

        trim(editable)
        scrollToEnd()
    }

    /**
     * Keeps the buffer bounded. Only runs once the overshoot is worth paying for, so the
     * scan is amortised over [TRIM_SLACK] lines instead of happening on every batch.
     */
    private fun trim(editable: Editable) {
        if (lineCount <= MAX_LINES + TRIM_SLACK) return
        val drop = lineCount - MAX_LINES
        var cut = -1
        var seen = 0
        for (i in editable.indices) {
            if (editable[i] == '\n') {
                seen++
                if (seen == drop) { cut = i + 1; break }
            }
        }
        if (cut > 0) {
            editable.delete(0, cut)
            lineCount -= drop
        }
    }

    /**
     * Always jump to the newest line. Posted rather than called inline because the
     * ScrollView cannot scroll past content it has not measured yet.
     */
    private fun scrollToEnd() = scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }

    // ------------------------------------------------------------ remote config

    /**
     * Reads the host's Remote Config off the main thread and redraws when it lands - the first
     * getAll() of a process can load the activated and default configs from disk.
     *
     * Back to the UI thread through post(), not Dispatchers.Main: this module depends on
     * coroutines-core alone, and Main needs coroutines-android, which a host may not ship.
     */
    private fun loadRemoteConfig() {
        scope().launch {
            val snapshot = AdLogRemoteConfig.read()
            // read() is blocking and cannot be interrupted; if this view detached while it ran,
            // stop here rather than posting to a view nobody will attach again.
            ensureActive()
            this@AdLogView.post {
                val changed = !snapshot.sameContentAs(lastFrc)
                lastFrc = snapshot
                // An unchanged refresh redraws nothing, so a tester scrolled halfway down a
                // long config is not thrown back to the top by every panel reopen.
                if (changed && frcOpen && isAttachedToWindow) renderRemoteConfig(resetScroll = false)
            }
        }
    }

    /**
     * REFRESH: pulls the latest template from the Firebase server into the app's own Remote Config
     * and activates it. See [AdLogRemoteConfig.fetchAndActivate] for why the app's own instance,
     * and what that changes for the app.
     */
    private fun startFetch() {
        AdLogRemoteConfig.fetchAndActivate { after ->
            lastFrc = after
            attachedViews.toList().forEach { it.onFetchFinished() }
        }
        // fetchInFlight is already set, so this draws "REFRESHING…" and the in-progress line.
        styleFilterChrome()
        renderRemoteConfig(resetScroll = false)
    }

    private fun onFetchFinished() {
        styleFilterChrome()
        // Always redraw, even when no value changed: the fetch line itself has news.
        if (frcOpen && !collapsed) renderRemoteConfig(resetScroll = false)
    }

    /** A tappable fold line in the FRC text: [start] to [end] folds or unfolds [key]. */
    private class FoldTarget(val start: Int, val end: Int, val key: String)

    /** Fold lines in the text currently shown; rebuilt with it. */
    private var foldTargets: List<FoldTarget> = emptyList()

    /**
     * STATS and FRC: a tap on a ▾ / ▸ line folds or unfolds that group. Only a tap - a drag still scrolls,
     * because the ScrollView takes the gesture over, and cancels it here, once it moves.
     */
    private inner class TapToFold : OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            // The ADS tab keeps its text inert, exactly as before.
            if (openTab == Tab.ADS || foldTargets.isEmpty()) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                }
                MotionEvent.ACTION_UP -> if (abs(e.x - downX) <= slop && abs(e.y - downY) <= slop) {
                    // Anywhere on a fold line counts, including the empty space after its text.
                    val offset = logText.getOffsetForPosition(e.x, e.y)
                    foldTargets.firstOrNull { offset >= it.start && offset < it.end }?.let {
                        v.performClick()
                        toggleFold(it.key)
                    }
                }
            }
            return true
        }
    }

    private fun toggleFold(key: String) {
        val folded = if (openTab == Tab.STATS) foldedStats else foldedFrc
        if (!folded.remove(key)) folded.add(key)
        logText.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        // Keep the scroll position: the tapped line stays under the finger.
        if (openTab == Tab.STATS) renderStats(resetScroll = false) else renderRemoteConfig(resetScroll = false)
    }

    // ------------------------------------------------------------------- stats

    private fun requestStatsRender() {
        removeCallbacks(statsRender)
        val wait = STATS_REFRESH_MS - (SystemClock.uptimeMillis() - statsDrawnAt)
        if (wait <= 0) statsRender.run() else postDelayed(statsRender, wait)
    }

    private fun renderStats(resetScroll: Boolean) {
        statsDrawnAt = SystemClock.uptimeMillis()
        val keepY = scroller.scrollY
        lineCount = 0
        val targets = ArrayList<FoldTarget>()
        logText.text = formatStats(AdLogStore.stats(), System.currentTimeMillis(), targets)
        foldTargets = targets
        scroller.post { scroller.scrollTo(0, if (resetScroll) 0 else keepY) }
    }

    /**
     * The session in three rows, then one foldable block per ad slot, problems first:
     *
     *     SESSION      14 min  ·  22 requested  ·  13 loaded  ·  9 shown
     *     FAST CLICKS  2  ·  Intent_Open_INTER_AD
     *     CACHED NOW   4 ads  ·  1 expiring soon
     *
     *     ▾ Home_NATIVE_AD  native               ● cached 52m · expires in 8m
     *       requested 6  ·  loaded 5  ·  shown 4
     *       fill 83%  ·  shown 80%  ·  loads in 1.2s
     *
     * The words come from [AdLogStatsText], shared with SHARE.
     */
    private fun formatStats(stats: AdStatsSnapshot, now: Long, foldTargets: MutableList<FoldTarget>): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val charWidth = logText.paint.measureText("0")
        for ((label, spans) in AdLogStatsText.sessionRows(stats, now)) {
            val start = sb.length
            sb.appendStyled(label.padEnd(SESSION_LABEL_CHARS), palette.subtext)
            appendSpans(sb, spans)
            sb.append("\n")
            hang(sb, start, SESSION_LABEL_CHARS, charWidth)
        }

        val slots = stats.ordered(now)
        if (slots.isEmpty()) {
            sb.appendStyled(
                "\nNo ad events yet. Counts fill in as the app requests and shows ads - from every line " +
                    "that names an ad slot.\n",
                palette.subtext
            )
            return sb
        }

        val width = textWidth()
        val spaceWidth = logText.paint.measureText(" ")
        for (slot in slots) {
            val lines = AdLogStatsText.lines(slot, now)
            val folded = slot.placement in foldedStats
            sb.append("\n")
            val start = sb.length
            sb.appendStyled(if (folded) "▸ " else "▾ ", palette.heading, bold = true)
            sb.appendStyled(slot.placement, palette.heading, bold = true)
            slot.format?.let { sb.appendStyled("  $it", palette.subtext) }
            if (folded) sb.appendStyled("  ·  ${lines.size} hidden", palette.subtext)
            val badge = AdLogStatsText.badge(slot, now)
            if (badge.isNotEmpty()) {
                // Right-aligned by padding with spaces, measured in pixels: the arrow and the dot come
                // from a fallback font and are not one column wide. One space spare; no room at all
                // puts the badge on a line of its own.
                val badgeText = SpannableStringBuilder().also { appendSpans(it, badge) }
                val gap = ((width - widthOf(sb, start, sb.length) - widthOf(badgeText)) / spaceWidth).toInt() - 1
                sb.append(if (gap >= 2) " ".repeat(gap) else "\n    ")
                sb.append(badgeText)
            }
            sb.append("\n")
            foldTargets += FoldTarget(start, sb.length, slot.placement)
            if (folded) continue
            for (line in lines) {
                val lineStart = sb.length
                sb.append("  ")
                appendSpans(sb, line)
                sb.append("\n")
                hang(sb, lineStart, 2, charWidth)
            }
        }
        return sb
    }

    private fun appendSpans(sb: SpannableStringBuilder, spans: List<AdLogSpan>) {
        for (span in spans) {
            val color = when (span.tone) {
                AdLogTone.NORMAL, AdLogTone.TITLE -> palette.heading
                AdLogTone.MUTED -> palette.subtext
                AdLogTone.GOOD -> palette.colorOf(AdLogKind.LOADED)
                AdLogTone.WARN -> palette.colorOf(AdLogKind.EXPIRED)
                AdLogTone.BAD -> palette.colorOf(AdLogKind.FAILED)
            }
            sb.appendStyled(span.text, color, bold = span.bold || span.tone == AdLogTone.TITLE)
        }
    }

    private fun renderRemoteConfig(resetScroll: Boolean) {
        val keepY = scroller.scrollY
        // The log's trim bookkeeping does not apply to this text; renderAll() rebuilds it from
        // zero when the log view returns.
        lineCount = 0
        val targets = ArrayList<FoldTarget>()
        logText.text = formatRemoteConfig(lastFrc, AdLogFilter.query, targets)
        foldTargets = targets
        scroller.post { scroller.scrollTo(0, if (resetScroll) 0 else keepY) }
    }

    /**
     * The config written for anyone, not just developers. Download state first - it decides
     * whether any value below came from Firebase at all - then every setting in plain words.
     *
     *     FIREBASE REMOTE CONFIG
     *     Settings this app gets from Firebase  ·  checked 15:00:12
     *
     *     Last download  ✓ Downloaded  ·  14:59:02 (1 minute ago)
     *     Settings       12 in total  ·  12 from Firebase  ·  0 built-in
     *     Download rule  Can re-download any time  ·  waits up to 1 minute
     *     Refresh        Tap REFRESH to download the latest settings
     *
     *     Ads config release  FROM FIREBASE  CHANGED
     *     ads_config_release  ·  1 of 24 items match
     *       ▸ Splash first interstitial ad  SPLASH_FIRST_INTER_AD
     *           Ad type                 Interstitial (3)
     *           Show loading before ad  No
     *           Ad ID                   ca-app-pub-…/1033173712 (Google test ad)
     */
    private fun formatRemoteConfig(
        snap: RemoteConfigSnapshot?,
        query: String,
        foldTargets: MutableList<FoldTarget>
    ): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val charWidth = logText.paint.measureText("0")

        if (snap == null) {
            sb.appendStyled("Reading this app's Remote Config…\n", palette.subtext)
            return sb
        }
        if (snap.error != null) {
            val red = palette.colorOf(AdLogKind.FAILED)
            sb.appendStyled("Can't show Remote Config\n", red, bold = true)
            sb.appendStyled("  ↳ ${snap.error}\n", red, bold = true)
            return sb
        }

        val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
        val green = palette.colorOf(AdLogKind.LOADED)
        val remote = snap.entries.count { it.source == RemoteConfigSource.REMOTE }
        val defaults = snap.entries.count { it.source == RemoteConfigSource.DEFAULT }

        sb.appendStyled("FIREBASE REMOTE CONFIG\n", palette.heading, bold = true)
        sb.appendStyled(
            "Settings this app gets from Firebase  ·  checked ${clock.format(Date(snap.readAtMillis))}\n\n",
            palette.subtext
        )

        statusRow(sb, "Last download", charWidth) {
            appendStyled(snap.lastFetchStatus.label, colorOf(snap.lastFetchStatus), bold = true)
            if (snap.fetchTimeMillis > 0L) {
                val age = ageOf(snap.readAtMillis - snap.fetchTimeMillis)
                appendStyled("  ·  ${clock.format(Date(snap.fetchTimeMillis))} ($age ago)", palette.subtext)
            }
        }
        snap.lastFetchStatus.meaning?.let { meaning ->
            statusRow(sb, "", charWidth) { appendStyled(meaning, palette.subtext) }
        }
        statusRow(sb, "Settings", charWidth) {
            appendStyled("${snap.entries.size} in total  ·  ", palette.heading)
            appendStyled("$remote from Firebase", colorOf(RemoteConfigSource.REMOTE), bold = true)
            appendStyled("  ·  ", palette.heading)
            appendStyled("$defaults built-in", colorOf(RemoteConfigSource.DEFAULT), bold = true)
        }
        statusRow(sb, "Download rule", charWidth) {
            val interval = snap.minimumFetchIntervalSeconds
            appendStyled(
                if (interval <= 0L) "Can re-download any time"
                else "At most once every ${AdLogRemoteConfig.friendlyDuration(interval.toDouble())}",
                palette.heading
            )
            val timeout = AdLogRemoteConfig.friendlyDuration(snap.fetchTimeoutSeconds.toDouble())
            appendStyled("  ·  waits up to $timeout", palette.subtext)
        }

        // What the last REFRESH did - or, before the first one, how to do one.
        val lastFetch = AdLogRemoteConfig.lastFetch
        statusRow(sb, "Refresh", charWidth) {
            val at = lastFetch?.let { "  ·  ${clock.format(Date(it.atMillis))}" }.orEmpty()
            when {
                AdLogRemoteConfig.fetchInFlight ->
                    appendStyled("Downloading from Firebase…", palette.colorOf(AdLogKind.REQUEST), bold = true)
                lastFetch == null ->
                    appendStyled("Tap REFRESH to download the latest settings", palette.subtext)
                lastFetch.error != null -> {
                    val reason = AdLogRemoteConfig.friendlyFetchError(lastFetch.error)
                    appendStyled("✗ $reason", palette.colorOf(AdLogKind.FAILED), bold = true)
                    appendStyled(at, palette.subtext)
                }
                lastFetch.changedKeys == null -> {
                    appendStyled("✓ Downloaded", green, bold = true)
                    appendStyled(at, palette.subtext)
                }
                lastFetch.changedKeys.isEmpty() -> {
                    appendStyled("✓ Downloaded - nothing changed", green, bold = true)
                    appendStyled(at, palette.subtext)
                }
                else -> {
                    val n = lastFetch.changedKeys.size
                    appendStyled("✓ Downloaded - $n setting${if (n == 1) "" else "s"} changed", green, bold = true)
                    appendStyled(at, palette.subtext)
                }
            }
        }
        // The SDK's own words stay available to a developer, one quiet line below.
        if (!AdLogRemoteConfig.fetchInFlight && lastFetch?.error != null) {
            statusRow(sb, "", charWidth) { appendStyled("↳ ${lastFetch.error}", palette.subtext) }
        }
        val changedByFetch = lastFetch?.changedKeys.orEmpty()

        val labels = AdLogOverlay.config.remoteConfigValueLabels
        val shown = snap.entries.mapNotNull { e -> AdLogRemoteConfig.match(e, query, labels)?.let { e to it } }
        if (query.isNotEmpty()) {
            statusRow(sb, "Search", charWidth) {
                appendStyled("${shown.size} of ${snap.entries.size} settings match “$query”", palette.heading)
            }
        }

        if (shown.isEmpty()) {
            sb.appendStyled(
                if (query.isEmpty()) "\nNo settings yet - nothing downloaded and no built-in defaults\n"
                else "\nNothing matches “$query”\n",
                palette.subtext
            )
        }

        for ((entry, match) in shown) {
            val rows = AdLogRemoteConfig.rowsForValue(entry.key, match.value, labels)
            // A single value has nothing to fold away; JSON with rows under it does.
            val canFold = rows.size > 1
            val folded = canFold && entry.key in foldedFrc
            sb.append("\n")
            val titleStart = sb.length
            if (canFold) sb.appendStyled(if (folded) "▸ " else "▾ ", palette.heading, bold = true)
            // The setting in plain words, where its value came from, and whether REFRESH changed it.
            sb.appendStyled(AdLogRemoteConfig.humanize(entry.key), palette.heading, bold = true)
            sb.appendStyled("  ${entry.source.label}", colorOf(entry.source), bold = true)
            if (entry.key in changedByFetch) {
                sb.appendStyled("  CHANGED", palette.colorOf(AdLogKind.SHOWN), bold = true)
            }
            sb.append("\n")
            // The exact key, quietly: what a developer types into the Firebase console or the code.
            sb.appendStyled(entry.key, palette.subtext)
            if (match.total > 0) {
                sb.appendStyled("  ·  ${match.matched} of ${match.total} items match", palette.subtext)
            }
            sb.append("\n")
            // Both title lines fold the whole setting - a bigger target than the arrow alone.
            if (canFold) foldTargets += FoldTarget(titleStart, sb.length, entry.key)
            if (folded) {
                val hint = if (AdLogRemoteConfig.anyMentions(rows, query)) "  ·  has matches" else ""
                sb.appendStyled("  ${rows.size} lines hidden$hint  ·  tap to show\n", palette.subtext)
            } else {
                appendRows(sb, entry.key, rows, query, charWidth, foldTargets)
            }
        }

        // Underlined in place, same treatment as a match in the log.
        if (query.isNotEmpty()) underlineMatches(sb, query)
        return sb
    }

    /** A status line: a fixed label column, then [body]. Wrapped text hangs under the body. */
    private inline fun statusRow(
        sb: SpannableStringBuilder,
        label: String,
        charWidth: Float,
        body: SpannableStringBuilder.() -> Unit
    ) {
        val start = sb.length
        sb.appendStyled(label.padEnd(STATUS_LABEL_CHARS), palette.subtext)
        sb.body()
        sb.append("\n")
        hang(sb, start, STATUS_LABEL_CHARS, charWidth)
    }

    /**
     * Readable rows (see [AdLogRemoteConfig.rowsForValue]) as indented text, with folded groups left
     * out. Settings next to each other share one label column so their values line up like a
     * table, and each top-level group - one ad placement - gets a blank line above it.
     */
    private fun appendRows(
        sb: SpannableStringBuilder,
        entryKey: String,
        rows: List<RemoteConfigRow>,
        query: String,
        charWidth: Float,
        foldTargets: MutableList<FoldTarget>
    ) {
        val limited = if (rows.size > MAX_FRC_ROWS) rows.subList(0, MAX_FRC_ROWS) else rows
        val shown = AdLogRemoteConfig.visibleRows(entryKey, limited, foldedFrc, query)
        var i = 0
        while (i < shown.size) {
            val first = shown[i].row
            var end = i
            while (end < shown.size && shown[end].row.depth == first.depth &&
                !shown[end].row.heading && shown[end].row.label != null
            ) end++
            if (end == i) {
                if (first.heading && first.depth == 0 && i > 0) sb.append("\n")
                appendRow(sb, shown[i], 0, charWidth, foldTargets)
                i++
            } else {
                val width = shown.subList(i, end).maxOf { it.row.label?.length ?: 0 }.coerceAtMost(MAX_LABEL_CHARS)
                for (k in i until end) appendRow(sb, shown[k], width, charWidth, foldTargets)
                i = end
            }
        }
        if (rows.size > limited.size) {
            val more = rows.size - limited.size
            val note = RemoteConfigRow(0, null, "… $more more lines - FIND narrows this", RemoteConfigRowKind.NOTE)
            appendRow(sb, RemoteConfigVisibleRow(note), 0, charWidth, foldTargets)
        }
    }

    private fun appendRow(
        sb: SpannableStringBuilder,
        visible: RemoteConfigVisibleRow,
        labelWidth: Int,
        charWidth: Float,
        foldTargets: MutableList<FoldTarget>
    ) {
        val row = visible.row
        val start = sb.length
        val indent = 2 + 2 * row.depth
        sb.append(" ".repeat(indent))
        val label = row.label
        val hangChars = when {
            row.heading -> {
                // ▾ open, ▸ folded, • nothing under it to fold.
                val arrow = when {
                    !visible.canFold -> "• "
                    visible.folded -> "▸ "
                    else -> "▾ "
                }
                sb.appendStyled("$arrow${label.orEmpty()}", palette.heading, bold = true)
                row.detail?.let { sb.appendStyled("  $it", palette.subtext) }
                if (visible.folded) {
                    val hint = if (visible.hiddenMatch) "  ·  has matches" else ""
                    sb.appendStyled("  ·  ${visible.hiddenRows} hidden$hint", palette.subtext)
                }
                indent + 2
            }
            label != null -> {
                sb.appendStyled(label.padEnd(labelWidth), palette.subtext)
                sb.append("  ")
                appendRowValue(sb, row)
                indent + maxOf(labelWidth, label.length) + 2
            }
            else -> {
                appendRowValue(sb, row)
                indent
            }
        }
        sb.append("\n")
        hang(sb, start, hangChars, charWidth)
        val path = visible.path
        if (visible.canFold && path != null) foldTargets += FoldTarget(start, sb.length, path)
    }

    private fun appendRowValue(sb: SpannableStringBuilder, row: RemoteConfigRow) {
        var text = row.value.orEmpty()
        if (text.length > MAX_FRC_VALUE_CHARS) {
            text = text.take(MAX_FRC_VALUE_CHARS) + " … ${text.length - MAX_FRC_VALUE_CHARS} more characters"
        }
        val emphasis = row.kind == RemoteConfigRowKind.TRUE || row.kind == RemoteConfigRowKind.FALSE
        sb.appendStyled(text, colorOf(row.kind), bold = emphasis)
    }

    /**
     * Wrapped lines of the paragraph that starts at [start] hang [chars] characters in, under the
     * value they belong to - a long ad unit ID otherwise breaks back to the left edge and out of
     * its column. [start] is always just after a newline, as a paragraph span requires.
     */
    private fun hang(sb: SpannableStringBuilder, start: Int, chars: Int, charWidth: Float) {
        sb.setSpan(
            LeadingMarginSpan.Standard(0, (chars * charWidth).toInt()),
            start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun colorOf(kind: RemoteConfigRowKind): Int = when (kind) {
        RemoteConfigRowKind.TRUE -> palette.colorOf(AdLogKind.LOADED)
        RemoteConfigRowKind.FALSE -> palette.colorOf(AdLogKind.FAILED)
        RemoteConfigRowKind.NULL, RemoteConfigRowKind.NOTE -> palette.subtext
        RemoteConfigRowKind.TEXT -> palette.heading
    }

    private fun colorOf(source: RemoteConfigSource): Int = when (source) {
        RemoteConfigSource.REMOTE -> palette.colorOf(AdLogKind.LOADED)    // arrived from Firebase
        RemoteConfigSource.DEFAULT -> palette.colorOf(AdLogKind.CACHE)    // the app's own fallback
        RemoteConfigSource.STATIC -> palette.colorOf(AdLogKind.DESTROY)   // no value at all
    }

    private fun colorOf(status: RemoteConfigFetchStatus): Int = when (status) {
        RemoteConfigFetchStatus.SUCCESS -> palette.colorOf(AdLogKind.LOADED)
        RemoteConfigFetchStatus.FAILURE -> palette.colorOf(AdLogKind.FAILED)
        RemoteConfigFetchStatus.THROTTLED -> palette.colorOf(AdLogKind.CACHE)
        RemoteConfigFetchStatus.NO_FETCH_YET -> palette.colorOf(AdLogKind.EXPIRED)
    }

    private fun ageOf(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        fun plural(n: Long, word: String) = "$n $word${if (n == 1L) "" else "s"}"
        return when {
            s < 60 -> plural(s, "second")
            s < 3_600 -> plural(s / 60, "minute")
            s < 86_400 -> plural(s / 3_600, "hour")
            else -> plural(s / 86_400, "day")
        }
    }

    private fun SpannableStringBuilder.appendStyled(text: String, color: Int, bold: Boolean = false) {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun dp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A new view per screen, but one filter state: the field has to be told what the
        // panel is already filtering on, or an active query reads as a dead ad stack.
        if (searchInput.text.toString() != AdLogFilter.query) {
            searchInput.setText(AdLogFilter.query)
            searchInput.setSelection(searchInput.text.length)
        }
        if (sheetOpen) rebuildFacets()
        styleFilterChrome()
        updateStatsDot()
        renderAll()
        AdLogStore.addListener(listener)
        attachedViews += this
        // The FRC view survives navigation; the cached read is drawn above, this refreshes it.
        if (frcOpen && !collapsed) loadRemoteConfig()
        if (toolsOpen && !collapsed) refreshConsent()
        updateStatsTicker()
        updateWhereTicker()
    }

    override fun onDetachedFromWindow() {
        attachedViews -= this
        AdLogStore.removeListener(listener)
        removeCallbacks(flush)
        removeCallbacks(reRender)
        removeCallbacks(statsRender)
        removeCallbacks(statsTicker)
        removeCallbacks(whereTicker)
        removeCallbacks(disarm)
        ioScope?.cancel()
        ioScope = null
        super.onDetachedFromWindow()
    }
}
