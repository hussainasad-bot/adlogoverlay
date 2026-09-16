package com.ninedtechnologies.adlogoverlay

import android.content.Context
import com.google.android.ump.UserMessagingPlatform

/**
 * The ONLY file that touches a User Messaging Platform type. Load it only after
 * [AdLogSdk.hasConsent]; see [FirebaseRemoteConfigReader] for why the boundary matters.
 *
 * Checked against user-messaging-platform 4.0.0: `getConsentStatus()`, `canRequestAds()` and
 * `reset()` on `ConsentInformation`. Google documents `reset()` as for debugging only - which is
 * all this overlay ever is.
 */
internal object UmpConsentReader {

    fun read(context: Context, gdprApplies: Boolean?): AdLogConsent {
        val info = UserMessagingPlatform.getConsentInformation(context)
        return AdLogConsent(info.consentStatus, info.canRequestAds(), gdprApplies)
    }

    fun reset(context: Context) {
        UserMessagingPlatform.getConsentInformation(context).reset()
    }
}
