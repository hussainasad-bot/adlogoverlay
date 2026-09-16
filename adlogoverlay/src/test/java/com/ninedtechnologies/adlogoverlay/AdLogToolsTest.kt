package com.ninedtechnologies.adlogoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The clock, the SHARE report, the SCREEN line and the TOOLS words. */
class AdLogToolsTest {

    private val hour = 3_600_000L
    private val day = 24 * hour

    // ---------------------------------------------------------------- clock

    @Test
    fun logcatClockParses() {
        assertEquals(((11L * 60 + 6) * 60 + 42) * 1000 + 123, AdLogClock.millisOfDay("11:06:42.123"))
        assertEquals(((9L * 60) * 60 + 5) * 1000, AdLogClock.millisOfDay("09:00:05"))
        assertNull(AdLogClock.millisOfDay("not a time"))
    }

    @Test
    fun aLineIsPlacedTodayInTheLocalZone() {
        // 2026-09-16 12:00:00 UTC, in a UTC+5 zone - the local clock reads 17:00:00.
        val now = 1_789_560_000_000L - Math.floorMod(1_789_560_000_000L, day) + 12 * hour
        val offset = 5 * hour
        assertEquals(now - 1_500, AdLogClock.epochMillisOf("16:59:58.500", now, offset))
        assertEquals("17:00:00.000", AdLogClock.clockOf(now, offset))
    }

    @Test
    fun aLineFromJustBeforeMidnightIsYesterday() {
        val midnightLocal = 1_789_560_000_000L - Math.floorMod(1_789_560_000_000L, day)
        val now = midnightLocal + 5_000                        // 00:00:05, UTC zone
        assertEquals(now - 7_000, AdLogClock.epochMillisOf("23:59:58.000", now, 0))
    }

    @Test
    fun anUnreadableClockFallsBackToNow() {
        assertEquals(42L, AdLogClock.epochMillisOf("??", 42L, 0))
    }

    // ---------------------------------------------------------------- report

    private fun entry(time: String, kind: AdLogKind, placement: String?, message: String) =
        AdLogEntry(time, "AdLogs-->", message, kind, placement, screen = "MainActivity", atMillis = 0)

    @Test
    fun theReportCarriesHeaderStatsLogAndConfig() {
        val stats = AdLogStats(0).apply {
            onEntry(AdLogEntry("t", "t", "", AdLogKind.REQUEST, "Inter_3", atMillis = 0), 1_000)
        }.snapshot()
        val entries = listOf(
            entry("11:54:32.418", AdLogKind.LOADED, "Home_NATIVE_AD_2", "Home_NATIVE_AD_2 Ad loaded").copy(loadMillis = 1_214),
            AdLogEntry("11:54:36.000", "AdLog", "MARK 1", AdLogKind.OTHER, atMillis = 0, mark = 1),
            entry("11:54:40.310", AdLogKind.CLICK, "Inter_3", "Inter_3 onAdClicked").copy(fastClickMillis = 408)
        )
        val frc = RemoteConfigSnapshot(
            entries = listOf(RemoteConfigEntry("ads_enabled", "true", RemoteConfigSource.REMOTE)),
            lastFetchStatus = RemoteConfigFetchStatus.SUCCESS,
            fetchTimeMillis = 0L, minimumFetchIntervalSeconds = 0L, fetchTimeoutSeconds = 60L, readAtMillis = 0L
        )
        val report = AdLogReport.build(listOf("App" to "PDF Reader 1.8.8", "Device" to "Pixel 3 XL"), stats, entries, frc, 60_000)

        assertTrue(report, report.startsWith("AD LOG REPORT\nApp     PDF Reader 1.8.8\nDevice  Pixel 3 XL\n"))
        assertTrue(report, "SESSION      1 min  ·  1 requested  ·  0 loaded  ·  0 shown" in report)
        assertTrue(report, "11:54:32.418  LOADED      Home_NATIVE_AD_2 (native)  in 1.2s  @MainActivity  | AdLogs-->: Home_NATIVE_AD_2 Ad loaded" in report)
        assertTrue(report, "── MARK 1 ── 11:54:36.000" in report)
        assertTrue(report, "11:54:40.310  FAST CLICK  Inter_3 (interstitial)" in report)
        assertTrue(report, "↳ clicked 0.4s after it appeared - likely accidental" in report)
        assertTrue(report, "ads_enabled  [FROM FIREBASE]  true" in report)
    }

    @Test
    fun anOversizedLogDropsItsOldestLinesFirst() {
        val stats = AdLogStats(0).snapshot()
        val entries = (1..500).map { i ->
            entry("12:00:00.000", AdLogKind.OTHER, null, "line $i " + "x".repeat(80))
        }
        val report = AdLogReport.build(emptyList(), stats, entries, null, 0, maxChars = 5_000)
        assertTrue(report.length <= 5_000)
        assertTrue("newest kept", "line 500 " in report)
        assertFalse("oldest dropped", "line 1 " in report)
        assertTrue(report, "older lines left out to fit the share limit" in report)
    }

    // ---------------------------------------------------------------- screen line

    @Test
    fun theScreenLineGoesAsDeepAsItCan() {
        assertEquals("MainActivity", AdLogWhere.describe("MainActivity", emptyList(), null))
        assertEquals(
            "MainActivity › AllFilesFragment › BottomSheetDialog “Sort by”",
            AdLogWhere.describe("MainActivity", listOf("AllFilesFragment"), "BottomSheetDialog “Sort by”")
        )
        assertEquals("MainActivity › RecentFragment +1", AdLogWhere.describe("MainActivity", listOf("AllFilesFragment", "RecentFragment"), null))
    }

    // ---------------------------------------------------------------- tools words

    @Test
    fun adInspectorErrorsSayWhatToDo() {
        assertTrue(AdLogSdk.inspectorError(2, "").startsWith("Ad Inspector only opens on a test device"))
        assertEquals("Ad Inspector is already open (code 3)", AdLogSdk.inspectorError(3, null))
        assertEquals("Ad Inspector could not load - check the connection (code 1: timeout)", AdLogSdk.inspectorError(1, "timeout"))
    }

    @Test
    fun consentReadsInPlainWords() {
        fun words(c: AdLogConsent) = AdLogSdk.consentSpans(c).joinToString("") { it.text }
        assertEquals("obtained  ·  can request ads  ·  GDPR applies", words(AdLogConsent(3, true, true)))
        assertEquals("unknown  ·  can't request ads  ·  GDPR not decided", words(AdLogConsent(0, false, null)))
        assertEquals("not required  ·  can request ads  ·  GDPR doesn't apply", words(AdLogConsent(1, true, false)))
        assertEquals(AdLogTone.BAD, AdLogSdk.consentSpans(AdLogConsent(2, false, true)).first().tone)
    }
}
