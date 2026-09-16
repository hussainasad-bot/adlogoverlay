package com.ninedtechnologies.adlogoverlay

import android.content.Context
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnAdInspectorClosedListener

/**
 * The ONLY file that touches a Google Mobile Ads type. Load it only after [AdLogSdk.hasMobileAds];
 * see [FirebaseRemoteConfigReader] for why the boundary matters.
 *
 * `MobileAds.openAdInspector(Context, OnAdInspectorClosedListener)` is the call from Google's
 * "Launch ad inspector" guide, checked against play-services-ads-api 25.4.0.
 */
internal object MobileAdsInspector {

    /** [onClosed] gets the error's code and message, or two nulls after a normal close. */
    fun open(context: Context, onClosed: (Int?, String?) -> Unit) {
        MobileAds.openAdInspector(context, OnAdInspectorClosedListener { error ->
            if (error == null) onClosed(null, null) else onClosed(error.code, error.message)
        })
    }
}
