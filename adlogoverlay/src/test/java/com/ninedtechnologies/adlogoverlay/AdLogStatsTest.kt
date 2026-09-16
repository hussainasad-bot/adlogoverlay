package com.ninedtechnologies.adlogoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdLogStatsTest {

    private val t0 = 1_000_000_000_000L
    private val fast = 1_000L

    private fun entry(kind: AdLogKind, placement: String?, at: Long, message: String = "", reason: String? = null) =
        AdLogEntry("12:00:00.000", "AdLogs-->", message, kind, placement, atMillis = at, reason = reason)

    private fun AdLogStats.feed(kind: AdLogKind, placement: String?, at: Long, message: String = "", reason: String? = null) =
        onEntry(entry(kind, placement, at, message, reason), fast)

    private fun AdLogStats.slot(name: String) = snapshot().slots.single { it.placement == name }

    private fun text(spans: List<AdLogSpan>) = spans.joinToString("") { it.text }

    // ---------------------------------------------------------------- classification the stats rely on

    @Test
    fun aLoadRequestIsARequestNotALoad() {
        assertEquals(AdLogKind.REQUEST, AdLogKind.of("AdLogs--> Home_NATIVE_AD_2 Ad loaded request"))
        assertEquals(AdLogKind.LOADED, AdLogKind.of("AdLogs--> Home_NATIVE_AD_2 Ad loaded"))
        assertEquals(AdLogKind.IMPRESSION, AdLogKind.of("AdLogs SPLASH_BANNER_AD_1 Ad Impression"))
    }

    @Test
    fun aSlotsOwnNameNeverDecidesTheKind() {
        // Real slot names from a host: "Click" and "FAIL" are part of the name, not the event.
        assertEquals(AdLogKind.REQUEST, AdLogKind.of("AdLogs Click_PDF_INTER_AD_3 Ad loaded request"))
        assertEquals(AdLogKind.LOADED, AdLogKind.of("AdLogs Print_Click_INTER_AD_3 Ad loaded"))
        assertEquals(AdLogKind.CLICK, AdLogKind.of("AdLogs Click_Tab_INTER_AD_3 onAdClicked"))
        assertEquals(AdLogKind.REQUEST, AdLogKind.of("AdLogs LANGUAGE_INTER_SPLASH_FAIL_AD_3 Ad loaded request"))
        // A verb glued onto the slot still counts.
        assertEquals(AdLogKind.IMPRESSION, AdLogKind.of("events home_native_ad_impression"))
        assertEquals(AdLogKind.FAILED, AdLogKind.of("AdLogs APP_OPEN_5_error_No fill."))
    }

    @Test
    fun aDumpOfSettingsIsNotAnEvent() {
        assertEquals(
            AdLogKind.OTHER,
            AdLogKind.of(
                "fetchAdConfigFromRemote using cached configurations: AdConfig(adName=SPLASH_FIRST_INTER_AD, " +
                    "isAdShow=true, intentAdTimeOut=7000, isShowLoadingBeforeAd=false)"
            )
        )
        assertEquals(
            AdLogKind.OTHER,
            AdLogKind.of("""fetchAdConfigFromRemote: [{"adName":"Click_PDF_INTER_AD","adType":3,"isShowLoadingBeforeAd":true}]""")
        )
        // Google's LoadAdError JSON is still a failure.
        assertEquals(
            AdLogKind.FAILED,
            AdLogKind.of("""AdLogs {"Code": 3, "Message": "No fill.", "Domain": "com.google.android.gms.ads", "Cause": "null"}""")
        )
    }

    @Test
    fun aSlotOnlyNamedByAnUncountedLineOpensNoBlock() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.OTHER, "SPLASH_FIRST_INTER_AD", t0, "AdConfig(adName=SPLASH_FIRST_INTER_AD, ...)")
        s.feed(AdLogKind.DESTROY, "Home_NATIVE_AD", t0, "Destroyed Home_NATIVE_AD")
        assertTrue(s.snapshot().slots.isEmpty())
    }

    // ---------------------------------------------------------------- slot names

    @Test
    fun aFailureTailIsNotPartOfTheSlotName() {
        assertEquals("APP_OPEN_5", AdPlacement.of("APP_OPEN_5_error_No fill."))
        assertEquals("Home_NATIVE_AD_2", AdPlacement.of("Home_NATIVE_AD_2_failed"))
    }

    @Test
    fun aMessageGluedOntoANumberedSlotFoldsIntoIt() {
        val known = listOf("REWARDED_6", "Intent_Open_INTER_AD_3")
        assertEquals("REWARDED_6", AdPlacement.canonical("REWARDED_6_No", known))
        assertEquals("Intent_Open_INTER_AD_3", AdPlacement.canonical("Intent_Open_INTER_AD_3_Ad", known))
    }

    @Test
    fun aSlotLoggedWithoutItsTypeFindsTheOnlyNumberedMatch() {
        assertEquals("APP_OPEN_5", AdPlacement.canonical("APP_OPEN", listOf("APP_OPEN_5", "Home_NATIVE_AD_2")))
        // Two candidates: no guess.
        assertEquals("Home_NATIVE", AdPlacement.canonical("Home_NATIVE", listOf("Home_NATIVE_1", "Home_NATIVE_2")))
    }

    @Test
    fun twoRealSlotsWithoutNumbersAreNeverMerged() {
        assertEquals("Exit_Native_Bottom", AdPlacement.canonical("Exit_Native_Bottom", listOf("Exit_Native")))
        assertEquals("Exit_Native", AdPlacement.canonical("Exit_Native", listOf("Exit_Native_Bottom")))
    }

    @Test
    fun caseIsIgnoredAndTheFirstSpellingKept() {
        assertEquals("Home_NATIVE_AD", AdPlacement.canonical("home_native_ad", listOf("Home_NATIVE_AD")))
    }

    // ---------------------------------------------------------------- funnel

    @Test
    fun theInterstitialFunnelCountsEachEventOnce() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.REQUEST, "Inter_3", t0)
        s.feed(AdLogKind.LOADED, "Inter_3", t0 + 800)
        s.feed(AdLogKind.SHOWN, "Inter_3", t0 + 5_000)
        s.feed(AdLogKind.IMPRESSION, "Inter_3", t0 + 5_100)   // same showing
        s.feed(AdLogKind.CLICK, "Inter_3", t0 + 9_000)
        s.feed(AdLogKind.DISMISSED, "Inter_3", t0 + 12_000)
        val slot = s.slot("Inter_3")
        assertEquals(1, slot.requested)
        assertEquals(1, slot.loaded)
        assertEquals(1, slot.shown)
        assertEquals(1, slot.clicked)
        assertEquals(0, slot.fastClicks)
        assertEquals(800L, slot.averageLoadMillis)
        assertNull("shown, so no longer cached", slot.cachedSinceMillis)
    }

    @Test
    fun aLineLoggedTwiceWithinTheWindowCountsOnce() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.REQUEST, "Inter_3", t0)
        s.feed(AdLogKind.REQUEST, "Inter_3", t0 + AdLogStats.DUPLICATE_MS - 1)
        s.feed(AdLogKind.REQUEST, "Inter_3", t0 + AdLogStats.DUPLICATE_MS + 1)
        assertEquals(2, s.slot("Inter_3").requested)
    }

    @Test
    fun theLoadedLineCarriesItsLoadTime() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.REQUEST, "Home_NATIVE_AD_2", t0)
        val loaded = s.feed(AdLogKind.LOADED, "Home_NATIVE_AD_2", t0 + 1_234)
        assertEquals(1_234L, loaded.loadMillis)
    }

    @Test
    fun aLoadWithNoRequestBeforeItHasNoLoadTime() {
        val s = AdLogStats(t0)
        assertNull(s.feed(AdLogKind.LOADED, "Home_NATIVE_AD_2", t0).loadMillis)
        s.feed(AdLogKind.REQUEST, "Home_NATIVE_AD_2", t0 + 10_000)
        assertNull(
            "an answer after MAX_LOAD_MS is not the answer to that request",
            s.feed(AdLogKind.LOADED, "Home_NATIVE_AD_2", t0 + 10_000 + AdLogStats.MAX_LOAD_MS + 1).loadMillis
        )
    }

    @Test
    fun linesThatNameNoSlotAreNotCounted() {
        val s = AdLogStats(t0)
        val e = s.feed(AdLogKind.LOADED, null, t0)
        assertNull(e.loadMillis)
        assertTrue(s.snapshot().slots.isEmpty())
    }

    // ---------------------------------------------------------------- fast clicks

    @Test
    fun aClickUnderTheThresholdAfterTheAdAppearedIsFast() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.LOADED, "Inter_3", t0)
        s.feed(AdLogKind.SHOWN, "Inter_3", t0 + 1_000)
        val click = s.feed(AdLogKind.CLICK, "Inter_3", t0 + 1_400)
        assertEquals(400L, click.fastClickMillis)
        assertEquals(1, s.slot("Inter_3").fastClicks)
        assertEquals(1, s.fastClickTotal)
    }

    @Test
    fun aClickAtTheThresholdIsNotFast() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.IMPRESSION, "Home_NATIVE_AD_2", t0)
        assertNull(s.feed(AdLogKind.CLICK, "Home_NATIVE_AD_2", t0 + fast).fastClickMillis)
    }

    @Test
    fun theGapIsMeasuredFromTheFirstAppearanceNotTheImpressionThatFollows() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.SHOWN, "Inter_3", t0)
        s.feed(AdLogKind.IMPRESSION, "Inter_3", t0 + 700)
        // 1.1s after SHOWN, only 0.4s after the IMPRESSION: not fast.
        assertNull(s.feed(AdLogKind.CLICK, "Inter_3", t0 + 1_100).fastClickMillis)
    }

    @Test
    fun aRepeatedClickLineIsNotASecondFastClick() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.SHOWN, "Inter_3", t0)
        s.feed(AdLogKind.CLICK, "Inter_3", t0 + 300)
        assertNull(s.feed(AdLogKind.CLICK, "Inter_3", t0 + 310).fastClickMillis)
        assertEquals(1, s.slot("Inter_3").clicked)
        assertEquals(1, s.fastClickTotal)
    }

    // ---------------------------------------------------------------- cache and expiry

    @Test
    fun aLoadedAdStaysCachedUntilItIsShown() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.LOADED, "Inter_3", t0)
        assertEquals(t0, s.slot("Inter_3").cachedSinceMillis)
        s.feed(AdLogKind.IMPRESSION, "Inter_3", t0 + 60_000)
        assertNull(s.slot("Inter_3").cachedSinceMillis)
    }

    @Test
    fun expiredRemovedOrFailedToShowAllEmptyTheCache() {
        for ((kind, message) in listOf(
            AdLogKind.EXPIRED to "AppOpen ad expired",
            AdLogKind.DESTROY to "Destroyed",
            AdLogKind.FAILED to "Ad failed to show The ad has already been shown"
        )) {
            val s = AdLogStats(t0)
            s.feed(AdLogKind.LOADED, "APP_OPEN_5", t0)
            s.feed(kind, "APP_OPEN_5", t0 + 1_000, message)
            assertNull("$kind", s.slot("APP_OPEN_5").cachedSinceMillis)
        }
    }

    @Test
    fun aFailedToShowIsNotAFailedLoad() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.FAILED, "Inter_3", t0, "Ad failed to show")
        assertEquals(0, s.slot("Inter_3").failed)
    }

    @Test
    fun bannersAreNeverCached() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.LOADED, "Home_BANNER_AD_1", t0)
        assertNull(s.slot("Home_BANNER_AD_1").cachedSinceMillis)
    }

    @Test
    fun expiryFollowsGooglesLifetimePerFormat() {
        assertEquals(4 * AdLogStats.HOUR_MS, AdLogStats.lifetimeOf("app open"))
        assertEquals(AdLogStats.HOUR_MS, AdLogStats.lifetimeOf("interstitial"))
        assertEquals(AdLogStats.HOUR_MS, AdLogStats.lifetimeOf("rewarded"))
        assertEquals(AdLogStats.HOUR_MS, AdLogStats.lifetimeOf("native"))
        assertNull(AdLogStats.lifetimeOf("banner"))

        val s = AdLogStats(t0)
        s.feed(AdLogKind.LOADED, "Home_NATIVE_AD_2", t0)
        val slot = s.slot("Home_NATIVE_AD_2")
        assertEquals(AdCacheState.FRESH, slot.cacheState(t0 + 49 * 60_000))
        assertEquals(AdCacheState.EXPIRING_SOON, slot.cacheState(t0 + 50 * 60_000))
        assertEquals(AdCacheState.EXPIRED, slot.cacheState(t0 + 60 * 60_000))
    }

    @Test
    fun theBadgeAgeAndTimeLeftAddUpToTheLifetime() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.LOADED, "Home_NATIVE_AD_2", t0)
        val slot = s.slot("Home_NATIVE_AD_2")
        assertEquals("● cached 3m", text(AdLogStatsText.badge(slot, t0 + 3 * 60_000 + 20_000)))
        assertEquals("● cached 52m · expires in 8m", text(AdLogStatsText.badge(slot, t0 + 52 * 60_000 + 30_000)))
        assertEquals("● cached 1h 4m · expired", text(AdLogStatsText.badge(slot, t0 + 64 * 60_000)))
        assertEquals(AdLogTone.WARN, AdLogStatsText.badge(slot, t0 + 55 * 60_000).single().tone)
    }

    // ---------------------------------------------------------------- rates and order

    @Test
    fun ratesMatchTheCountsBesideThem() {
        val s = AdLogStats(t0)
        var t = t0
        repeat(6) { i ->
            s.feed(AdLogKind.REQUEST, "Home_NATIVE_AD_2", t)
            if (i < 5) s.feed(AdLogKind.LOADED, "Home_NATIVE_AD_2", t + 1_200)
            if (i < 4) s.feed(AdLogKind.IMPRESSION, "Home_NATIVE_AD_2", t + 2_000)
            t += 10_000
        }
        val slot = s.slot("Home_NATIVE_AD_2")
        assertEquals(83, slot.fillPercent)   // 5 of 6
        assertEquals(80, slot.showPercent)   // 4 of 5
        assertEquals(
            "fill 83%  ·  shown 80%  ·  loads in 1.2s",
            text(AdLogStatsText.lines(slot, t)[1])
        )
    }

    @Test
    fun aRequestStillWaitingIsNotANoFill() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.REQUEST, "APP_OPEN_5", t0)
        val slot = s.slot("APP_OPEN_5")
        assertEquals(0, slot.fillPercent)
        assertFalse(slot.lowFill(t0 + 2_000))
        assertTrue("no answer after MAX_LOAD_MS is a no-fill", slot.lowFill(t0 + AdLogStats.MAX_LOAD_MS))
    }

    @Test
    fun theAdWaitingInCacheIsNotCountedAsWasted() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.LOADED, "APP_OPEN_5", t0)
        assertFalse(s.slot("APP_OPEN_5").lowShow())
    }

    @Test
    fun problemsSortFirstAndTheRestKeepTheirOrder() {
        val s = AdLogStats(t0)
        // fine
        s.feed(AdLogKind.REQUEST, "Exit_Native_4", t0)
        s.feed(AdLogKind.LOADED, "Exit_Native_4", t0 + 500)
        s.feed(AdLogKind.IMPRESSION, "Exit_Native_4", t0 + 900)
        // expiring soon
        s.feed(AdLogKind.LOADED, "Home_NATIVE_AD_2", t0)
        // low fill
        s.feed(AdLogKind.REQUEST, "APP_OPEN_5", t0)
        s.feed(AdLogKind.FAILED, "APP_OPEN_5", t0 + 300, "No fill")
        // fast click
        s.feed(AdLogKind.SHOWN, "Inter_3", t0)
        s.feed(AdLogKind.CLICK, "Inter_3", t0 + 200)

        val now = t0 + 55 * 60_000
        assertEquals(
            listOf("Inter_3", "APP_OPEN_5", "Home_NATIVE_AD_2", "Exit_Native_4"),
            s.snapshot().ordered(now).map { it.placement }
        )
    }

    @Test
    fun theFailureLineNamesTheMostCommonReason() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.FAILED, "APP_OPEN_5", t0, "code 3", reason = "no fill")
        s.feed(AdLogKind.FAILED, "APP_OPEN_5", t0 + 1_000, "code 2", reason = "network error")
        s.feed(AdLogKind.FAILED, "APP_OPEN_5", t0 + 2_000, "code 3", reason = "no fill")
        val lines = AdLogStatsText.lines(s.slot("APP_OPEN_5"), t0 + 3_000).map(::text)
        assertTrue(lines.toString(), "failed 3  ·  mostly no fill" in lines)
    }

    @Test
    fun sessionRowsSummariseEverySlot() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.REQUEST, "Inter_3", t0)
        s.feed(AdLogKind.LOADED, "Inter_3", t0 + 100)
        s.feed(AdLogKind.REQUEST, "Home_NATIVE_AD_2", t0 + 1_000)
        val rows = AdLogStatsText.sessionRows(s.snapshot(), t0 + 14 * 60_000).associate { it.first to text(it.second) }
        assertEquals("14 min  ·  2 requested  ·  1 loaded  ·  0 shown", rows["SESSION"])
        assertEquals("none", rows["FAST CLICKS"])
        assertEquals("1 ad", rows["CACHED NOW"])
    }

    @Test
    fun clearStartsAFreshSession() {
        val s = AdLogStats(t0)
        s.feed(AdLogKind.SHOWN, "Inter_3", t0)
        s.feed(AdLogKind.CLICK, "Inter_3", t0 + 100)
        s.clear(t0 + 5_000)
        assertTrue(s.snapshot().slots.isEmpty())
        assertEquals(0, s.fastClickTotal)
        assertEquals(t0 + 5_000, s.sessionStartMillis)
    }

    // ---------------------------------------------------------------- words

    @Test
    fun durationsReadTheWayTheyAreDrawn() {
        assertEquals("0.8s", AdLogStatsText.loadTime(800))
        assertEquals("12s", AdLogStatsText.loadTime(12_400))
        assertEquals("40s", AdLogStatsText.compact(40_900))
        assertEquals("2h", AdLogStatsText.compact(2 * AdLogStats.HOUR_MS + 30_000))
        assertEquals("2h 5m", AdLogStatsText.compact(2 * AdLogStats.HOUR_MS + 5 * 60_000))
        assertEquals("45s", AdLogStatsText.compactUp(44_100))
        assertEquals("<1 min", AdLogStatsText.sessionLength(59_000))
        assertEquals("1 h 5 min", AdLogStatsText.sessionLength(65 * 60_000))
    }
}
