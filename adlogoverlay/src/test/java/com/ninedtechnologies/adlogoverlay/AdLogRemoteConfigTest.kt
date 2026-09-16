package com.ninedtechnologies.adlogoverlay

import com.ninedtechnologies.adlogoverlay.RemoteConfigRowKind.FALSE
import com.ninedtechnologies.adlogoverlay.RemoteConfigRowKind.NOTE
import com.ninedtechnologies.adlogoverlay.RemoteConfigRowKind.NULL
import com.ninedtechnologies.adlogoverlay.RemoteConfigRowKind.TEXT
import com.ninedtechnologies.adlogoverlay.RemoteConfigRowKind.TRUE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdLogRemoteConfigTest {

    private val rc = AdLogRemoteConfig
    private val adTypes = mapOf("adType" to mapOf("2" to "Native", "3" to "Interstitial"))

    // ------------------------------------------------------------------ parseJson

    @Test
    fun parsesObjectsKeepingKeyOrderAndExactNumbers() {
        assertEquals(
            RemoteConfigJson.Obj(listOf("b" to RemoteConfigJson.Lit("1.0"), "a" to RemoteConfigJson.Str("x"))),
            rc.parseJson("""{ "b" : 1.0, "a":"x" }""")
        )
    }

    @Test
    fun decodesStringEscapes() {
        assertEquals(
            RemoteConfigJson.Arr(
                listOf(
                    RemoteConfigJson.Str("say \"hi\""),
                    RemoteConfigJson.Str("café"),
                    RemoteConfigJson.Str("a/b")
                )
            ),
            rc.parseJson("""["say \"hi\"", "café", "a\/b"]""")
        )
    }

    @Test
    fun rejectsMalformedJson() {
        listOf(
            """{"a":1,}""", "[1,]", "{a:1}", """{"a":1} x""", """{"a":tru}""",
            """{"a":"x}""", """{"a":1 "b":2}""", "[01]", "true", "", "   "
        ).forEach { assertNull("should reject: $it", rc.parseJson(it)) }
    }

    @Test
    fun acceptsEmptyContainers() {
        assertEquals(RemoteConfigJson.Obj(emptyList()), rc.parseJson("{ }"))
        assertEquals(RemoteConfigJson.Arr(emptyList()), rc.parseJson("[]"))
    }

    // ------------------------------------------------------------------- humanize

    @Test
    fun turnsCodeNamesIntoPlainWords() {
        assertEquals("Show loading before ad", rc.humanize("isShowLoadingBeforeAd"))
        assertEquals("Ad ID", rc.humanize("adId"))
        assertEquals("Splash first interstitial ad", rc.humanize("SPLASH_FIRST_INTER_AD"))
        assertEquals("Click PDF interstitial ad", rc.humanize("Click_PDF_INTER_AD"))
        assertEquals("Ads config debug", rc.humanize("ads_config_debug"))
        assertEquals("Load app open on home", rc.humanize("LOAD_APP_OPEN_ON_HOME"))
        assertEquals("HTTP server URL", rc.humanize("HTTPServerUrl"))
    }

    @Test
    fun keepsALoneIsAndLeavesUnsplittableNamesAlone() {
        assertEquals("Is", rc.humanize("is"))
        assertEquals("___", rc.humanize("___"))
    }

    // ------------------------------------------------------------ friendlyDuration

    @Test
    fun spellsOutDurations() {
        assertEquals("0 seconds", rc.friendlyDuration(0.0))
        assertEquals("1 second", rc.friendlyDuration(1.0))
        assertEquals("1.5 seconds", rc.friendlyDuration(1.5))
        assertEquals("1 minute", rc.friendlyDuration(60.0))
        assertEquals("1.5 minutes", rc.friendlyDuration(90.0))
        assertEquals("1 hour", rc.friendlyDuration(3_600.0))
        assertEquals("2 days", rc.friendlyDuration(172_800.0))
    }

    // --------------------------------------------------------------- friendlyValue

    @Test
    fun switchesAndMissingValuesReadAsWords() {
        assertEquals("Yes" to TRUE, rc.friendlyValue(null, RemoteConfigJson.Lit("true")))
        assertEquals("No" to FALSE, rc.friendlyValue(null, RemoteConfigJson.Lit("false")))
        assertEquals("(not set)" to NULL, rc.friendlyValue(null, RemoteConfigJson.Lit("null")))
        assertEquals("(blank)" to NOTE, rc.friendlyValue(null, RemoteConfigJson.Str("")))
    }

    @Test
    fun textThatLooksLikeAnotherTypeSaysSo() {
        assertEquals("true (as text)" to TEXT, rc.friendlyValue(null, RemoteConfigJson.Str("true")))
        assertEquals("7000 (as text)" to TEXT, rc.friendlyValue(null, RemoteConfigJson.Str("7000")))
        assertEquals("“ padded”" to TEXT, rc.friendlyValue(null, RemoteConfigJson.Str(" padded")))
        assertEquals("line\\nbreak" to TEXT, rc.friendlyValue(null, RemoteConfigJson.Str("line\nbreak")))
    }

    @Test
    fun durationsGetUnitsOnlyWhenTheNameStatesThem() {
        assertEquals("1500 (1.5 seconds)", rc.friendlyValue("beforeAdLoadingTimeInMs", RemoteConfigJson.Lit("1500")).first)
        assertEquals("3600 (1 hour)", rc.friendlyValue("minimumFetchIntervalInSeconds", RemoteConfigJson.Lit("3600")).first)
        // "TimeOut" names no unit, so none is guessed.
        assertEquals("7000", rc.friendlyValue("intentAdTimeOut", RemoteConfigJson.Lit("7000")).first)
    }

    @Test
    fun hostLabelsNameCodesAndTestAdsAreMarked() {
        assertEquals("Interstitial (3)", rc.friendlyValue("adType", RemoteConfigJson.Lit("3"), adTypes).first)
        assertEquals("9", rc.friendlyValue("adType", RemoteConfigJson.Lit("9"), adTypes).first)
        assertEquals(
            "ca-app-pub-3940256099942544/1033173712 (Google test ad)",
            rc.friendlyValue("adId", RemoteConfigJson.Str("ca-app-pub-3940256099942544/1033173712")).first
        )
        assertEquals(
            "ca-app-pub-1234567890123456/1234567890",
            rc.friendlyValue("adId", RemoteConfigJson.Str("ca-app-pub-1234567890123456/1234567890")).first
        )
    }

    // ---------------------------------------------------------------- rowsForValue

    @Test
    fun aListOfAdPlacementsReadsAsNamedGroups() {
        val raw = """[{"adName":"SPLASH_FIRST_INTER_AD","adType":3,"isAdShow":true,"beforeAdLoadingTimeInMs":1500,"adId":"ca-app-pub-3940256099942544/1033173712"},{"adType":2,"isAdShow":false}]"""
        assertEquals(
            listOf(
                RemoteConfigRow(0, "Splash first interstitial ad", null, heading = true, detail = "SPLASH_FIRST_INTER_AD"),
                RemoteConfigRow(1, "Ad type", "Interstitial (3)"),
                RemoteConfigRow(1, "Ad show", "Yes", TRUE),
                RemoteConfigRow(1, "Before ad loading time in ms", "1500 (1.5 seconds)"),
                RemoteConfigRow(1, "Ad ID", "ca-app-pub-3940256099942544/1033173712 (Google test ad)"),
                RemoteConfigRow(0, "Item 2", null, heading = true),
                RemoteConfigRow(1, "Ad type", "Native (2)"),
                RemoteConfigRow(1, "Ad show", "No", FALSE)
            ),
            rc.rowsForValue("ads_config_release", raw, adTypes)
        )
    }

    @Test
    fun nestedSectionsShortListsAndEmptyValues() {
        val raw = """{"interstitial_config":{"SWIPE_TAB_INTER_PRIORITY":3},"tags":["a","b"],"none":[],"blank":{}}"""
        assertEquals(
            listOf(
                RemoteConfigRow(0, "Interstitial config", null, heading = true),
                RemoteConfigRow(1, "Swipe tab interstitial priority", "3"),
                RemoteConfigRow(0, "Tags", "a, b"),
                RemoteConfigRow(0, "None", "(none)", NOTE),
                RemoteConfigRow(0, "Blank", "(nothing set)", NOTE)
            ),
            rc.rowsForValue("x", raw)
        )
    }

    @Test
    fun aLongListOfPlainValuesGoesOnePerLine() {
        val items = (1..20).joinToString(",") { "\"placement_number_$it\"" }
        val rows = rc.rowsForValue("x", """{"slots":[$items]}""")
        assertEquals(RemoteConfigRow(0, "Slots", null, heading = true), rows.first())
        assertEquals(RemoteConfigRow(1, "Item 1", "placement_number_1"), rows[1])
        assertEquals(21, rows.size)
    }

    @Test
    fun plainValuesAreOneBareRow() {
        assertEquals(listOf(RemoteConfigRow(0, null, "No", FALSE)), rc.rowsForValue("LOAD_APP_OPEN_ON_HOME", "false"))
        assertEquals(listOf(RemoteConfigRow(0, null, "hello world")), rc.rowsForValue("greeting", "hello world"))
        // Broken JSON is shown exactly as received.
        assertEquals(listOf(RemoteConfigRow(0, null, "{\"a\":")), rc.rowsForValue("x", "{\"a\":"))
    }

    // ------------------------------------------------------- topLevelArrayElements

    @Test
    fun splitsOnlyTopLevelCommas() {
        assertEquals(
            listOf("""{"a":1}""", """{"b":[2,3]}""", "\"x,y\""),
            rc.topLevelArrayElements("""[{"a":1}, {"b":[2,3]}, "x,y"]""")
        )
    }

    @Test
    fun handlesEmptyAndNonArrays() {
        assertEquals(emptyList<String>(), rc.topLevelArrayElements("[]"))
        assertEquals(emptyList<String>(), rc.topLevelArrayElements("[ ]"))
        assertNull(rc.topLevelArrayElements("""{"a":1}"""))
        assertNull(rc.topLevelArrayElements("7"))
    }

    @Test
    fun rejectsMalformedArrays() {
        assertNull(rc.topLevelArrayElements("[1,]"))
        assertNull(rc.topLevelArrayElements("[1,[2]"))
        assertNull(rc.topLevelArrayElements("[1][2]"))
        assertNull(rc.topLevelArrayElements("""["unclosed]"""))
    }

    // ----------------------------------------------------------------------- match

    private val adsConfig = RemoteConfigEntry(
        "ads_config_release",
        """[{"adName":"APP_OPEN","isShowLoadingBeforeAd":true},{"adName":"Intent_Open_INTER_AD","intentAdTimeOut":7000},{"adName":"Home_NATIVE","adType":2}]""",
        RemoteConfigSource.REMOTE
    )

    @Test
    fun emptyQueryShowsEverything() {
        assertEquals(RemoteConfigMatch(adsConfig.value), rc.match(adsConfig, ""))
    }

    @Test
    fun keyMatchShowsTheWholeValue() {
        assertEquals(RemoteConfigMatch(adsConfig.value), rc.match(adsConfig, "ADS_CONFIG"))
        // ...and so does the plain-words name.
        assertEquals(RemoteConfigMatch(adsConfig.value), rc.match(adsConfig, "ads config"))
    }

    @Test
    fun arrayIsNarrowedToMatchingElements() {
        val m = rc.match(adsConfig, "intent_open")!!
        assertEquals("""[{"adName":"Intent_Open_INTER_AD","intentAdTimeOut":7000}]""", m.value)
        assertEquals(1, m.matched)
        assertEquals(3, m.total)
    }

    @Test
    fun plainWordsFindTheSameSettingAsTheCodeName() {
        val m = rc.match(adsConfig, "show loading")!!
        assertEquals("""[{"adName":"APP_OPEN","isShowLoadingBeforeAd":true}]""", m.value)
    }

    @Test
    fun hostLabelsAreSearchable() {
        assertNull(rc.match(adsConfig, "native ("))
        val m = rc.match(adsConfig, "native (2)", adTypes)!!
        assertEquals("""[{"adName":"Home_NATIVE","adType":2}]""", m.value)
    }

    @Test
    fun scalarValuesMatchOrDoNot() {
        val flag = RemoteConfigEntry("LOAD_APP_OPEN_ON_HOME", "false", RemoteConfigSource.DEFAULT)
        assertEquals(RemoteConfigMatch("false"), rc.match(flag, "fals"))
        assertEquals(RemoteConfigMatch("false"), rc.match(flag, "no"))
        assertNull(rc.match(flag, "true"))
        assertNull(rc.match(adsConfig, "no_such_placement"))
    }

    // ---------------------------------------------------------------- friendlyFetchError

    @Test
    fun downloadFailuresReadAsWords() {
        assertEquals(
            "Asked Firebase too often - try again in a few minutes",
            rc.friendlyFetchError("FirebaseRemoteConfigFetchThrottledException: Fetch was throttled.")
        )
        assertEquals("No internet connection", rc.friendlyFetchError("UnknownHostException: Unable to resolve host"))
        assertEquals(
            "No answer from Firebase - check the internet connection",
            rc.friendlyFetchError("no answer from Firebase within 70s")
        )
        assertEquals("Couldn't download the settings", rc.friendlyFetchError("FirebaseRemoteConfigServerException: 500"))
    }

    // ------------------------------------------------------------- sortForDisplay

    @Test
    fun remoteValuesComeFirstThenAlphabetical() {
        val sorted = rc.sortForDisplay(
            listOf(
                RemoteConfigEntry("zeta", "1", RemoteConfigSource.DEFAULT),
                RemoteConfigEntry("Beta", "1", RemoteConfigSource.REMOTE),
                RemoteConfigEntry("alpha", "1", RemoteConfigSource.DEFAULT),
                RemoteConfigEntry("alpha_remote", "1", RemoteConfigSource.REMOTE)
            )
        )
        assertEquals(listOf("alpha_remote", "Beta", "alpha", "zeta"), sorted.map { it.key })
    }

    // ------------------------------------------------------------- changedKeys

    private fun snap(vararg entries: RemoteConfigEntry, error: String? = null) =
        RemoteConfigSnapshot(entries.toList(), RemoteConfigFetchStatus.SUCCESS, 1L, 0L, 60L, 1L, error)

    @Test
    fun changedKeysNamesValueSourceAddedAndRemovedKeys() {
        val before = snap(
            RemoteConfigEntry("same", "1", RemoteConfigSource.REMOTE),
            RemoteConfigEntry("value", "old", RemoteConfigSource.REMOTE),
            RemoteConfigEntry("source", "x", RemoteConfigSource.DEFAULT),
            RemoteConfigEntry("removed", "gone", RemoteConfigSource.REMOTE)
        )
        val after = snap(
            RemoteConfigEntry("same", "1", RemoteConfigSource.REMOTE),
            RemoteConfigEntry("value", "new", RemoteConfigSource.REMOTE),
            RemoteConfigEntry("source", "x", RemoteConfigSource.REMOTE),
            RemoteConfigEntry("added", "hi", RemoteConfigSource.REMOTE)
        )
        assertEquals(setOf("value", "source", "removed", "added"), rc.changedKeys(before, after))
    }

    @Test
    fun changedKeysIsEmptyWhenNothingChanged() {
        val s = snap(RemoteConfigEntry("k", "v", RemoteConfigSource.REMOTE))
        assertEquals(emptySet<String>(), rc.changedKeys(s, s.copy(fetchTimeMillis = 99L)))
    }

    @Test
    fun changedKeysRefusesToGuessWhenAReadFailed() {
        val ok = snap(RemoteConfigEntry("k", "v", RemoteConfigSource.REMOTE))
        val failed = snap(error = "Firebase hasn't started yet - tap FRC again in a moment")
        assertNull(rc.changedKeys(failed, ok))
        assertNull(rc.changedKeys(ok, failed))
        assertNull(rc.changedKeys(null, ok))
    }

    @Test
    fun sameContentIgnoresReadTime() {
        val a = RemoteConfigSnapshot(listOf(adsConfig), RemoteConfigFetchStatus.SUCCESS, 5L, 3600L, 6L, readAtMillis = 1L)
        assertTrue(a.sameContentAs(a.copy(readAtMillis = 999L)))
        assertFalse(a.sameContentAs(a.copy(fetchTimeMillis = 6L)))
        assertFalse(a.sameContentAs(null))
    }
}
