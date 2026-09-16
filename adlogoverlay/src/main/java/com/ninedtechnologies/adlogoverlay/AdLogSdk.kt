package com.ninedtechnologies.adlogoverlay

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * What the host's consent state is, in the terms TOOLS shows it.
 *
 * [status] is UMP's `ConsentInformation.ConsentStatus`: 0 unknown, 1 not required, 2 required,
 * 3 obtained. [gdprApplies] is the IAB TCF `IABTCF_gdprApplies` value the consent form writes to the
 * app's default SharedPreferences - null until a form has decided it.
 */
internal class AdLogConsent(
    val status: Int,
    val canRequestAds: Boolean,
    val gdprApplies: Boolean?,
    val error: String? = null
)

/**
 * TOOLS' two Google tools: Ad Inspector and the consent status. Both SDKs are compileOnly, like
 * Firebase - the overlay uses the HOST's copies, and a row is only offered when the host has one.
 *
 * Every Google type lives in [MobileAdsInspector] and [UmpConsentReader], which load only after the
 * matching classpath check below. Everything is called through `catch (Throwable)`: the SDKs are
 * compiled against one version and run against the host's, and a moved method is a
 * NoSuchMethodError - an Error - that a debug overlay must report, not crash with.
 */
internal object AdLogSdk {

    val hasMobileAds: Boolean by lazy { classExists("com.google.android.gms.ads.MobileAds") }

    val hasConsent: Boolean by lazy { classExists("com.google.android.ump.UserMessagingPlatform") }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Opens Google's Ad Inspector. [onResult] runs on the main thread when it closes: null after a
     * normal close, or a plain-language reason when it could not open.
     */
    fun openAdInspector(context: Context, onResult: (String?) -> Unit) {
        if (!hasMobileAds) {
            onResult("The Google Mobile Ads SDK is not part of this app")
            return
        }
        try {
            MobileAdsInspector.open(context) { code, message ->
                val text = code?.let { inspectorError(it, message) }
                mainHandler.post { onResult(text) }
            }
        } catch (t: Throwable) {
            onResult("Ad Inspector could not open - ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
        }
    }

    /** Reads the consent state. Off the main thread: the first read can load SharedPreferences from disk. */
    fun consent(context: Context): AdLogConsent? {
        if (!hasConsent) return null
        val gdpr = gdprApplies(context)
        return try {
            UmpConsentReader.read(context, gdpr)
        } catch (t: Throwable) {
            AdLogConsent(0, false, gdpr, "${t.javaClass.simpleName}: ${t.message.orEmpty()}")
        }
    }

    /** Clears the stored consent, so the form shows again the next time the app asks for it. Null on success. */
    fun resetConsent(context: Context): String? {
        if (!hasConsent) return "The consent SDK is not part of this app"
        return try {
            UmpConsentReader.reset(context)
            null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
        }
    }

    /**
     * `AdInspectorError` codes, from the SDK's own constants (play-services-ads-api 25.4.0):
     * 0 internal error, 1 failed to load, 2 not in test mode, 3 already open.
     */
    fun inspectorError(code: Int, message: String?): String {
        val reason = when (code) {
            0 -> "Ad Inspector hit an internal error"
            1 -> "Ad Inspector could not load - check the connection"
            2 -> "Ad Inspector only opens on a test device - add this phone as a test device in AdMob"
            3 -> "Ad Inspector is already open"
            else -> "Ad Inspector closed with an error"
        }
        val detail = message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        return "$reason (code $code$detail)"
    }

    /** "obtained · can request ads · GDPR applies", toned for the panel. */
    fun consentSpans(c: AdLogConsent): List<AdLogSpan> {
        if (c.error != null) return listOf(AdLogSpan("can't read consent - ${c.error}", AdLogTone.BAD, bold = true))
        val sep = AdLogSpan("  ·  ", AdLogTone.MUTED)
        val status = when (c.status) {
            1 -> AdLogSpan("not required", AdLogTone.GOOD, bold = true)
            2 -> AdLogSpan("required", AdLogTone.BAD, bold = true)
            3 -> AdLogSpan("obtained", AdLogTone.GOOD, bold = true)
            // Unknown until the app asks UMP for an update this session.
            else -> AdLogSpan("unknown", AdLogTone.WARN, bold = true)
        }
        val ads = if (c.canRequestAds) AdLogSpan("can request ads") else AdLogSpan("can't request ads", AdLogTone.BAD, bold = true)
        val gdpr = when (c.gdprApplies) {
            true -> AdLogSpan("GDPR applies")
            false -> AdLogSpan("GDPR doesn't apply")
            null -> AdLogSpan("GDPR not decided", AdLogTone.MUTED)
        }
        return listOf(status, sep, ads, sep, gdpr)
    }

    /**
     * IAB TCF v2 keeps `IABTCF_gdprApplies` in the default SharedPreferences as a number: 1 applies,
     * 0 does not, unset undetermined. Read loosely - a CMP that wrote it as text still counts.
     */
    private fun gdprApplies(context: Context): Boolean? = try {
        val prefs = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
        when (val v = prefs.all["IABTCF_gdprApplies"]) {
            is Number -> v.toInt() == 1
            is String -> v.trim() == "1"
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun classExists(name: String): Boolean = try {
        Class.forName(name, false, AdLogSdk::class.java.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
}
