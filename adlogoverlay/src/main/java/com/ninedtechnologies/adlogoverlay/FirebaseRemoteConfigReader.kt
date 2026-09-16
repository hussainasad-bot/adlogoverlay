package com.ninedtechnologies.adlogoverlay

import com.google.android.gms.tasks.Tasks
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The ONLY file in this module that touches a Firebase type.
 *
 * `firebase-config` is a compileOnly dependency: it is on this module's compile classpath and
 * on nobody's runtime classpath but the host's. So this class may only be loaded once
 * [AdLogRemoteConfig.isOnClasspath] has confirmed the host really ships Firebase Remote Config.
 * Keep every Firebase reference in here - one stray import elsewhere, in a class that loads
 * unconditionally, turns a host without Firebase into a NoClassDefFoundError.
 *
 * Built against firebase-config 23.1.0. Everything used here - getInstance, getAll, getInfo,
 * FirebaseRemoteConfigValue.asString/getSource, the settings getters - has been public API
 * for many releases, and [AdLogRemoteConfig.read] catches Throwable, so a host on a version
 * where one of them moved reports an error line instead of crashing.
 */
internal object FirebaseRemoteConfigReader {

    fun read(): RemoteConfigSnapshot {
        // Throws IllegalStateException when the host has not initialised the default
        // FirebaseApp. AdLogRemoteConfig.read turns that into a readable error.
        val rc = FirebaseRemoteConfig.getInstance()
        val info = rc.info
        val settings = info.configSettings

        val entries = rc.all.map { (key, value) ->
            RemoteConfigEntry(key, value.asString(), sourceOf(value.source))
        }

        return RemoteConfigSnapshot(
            entries = AdLogRemoteConfig.sortForDisplay(entries),
            lastFetchStatus = statusOf(info.lastFetchStatus),
            fetchTimeMillis = info.fetchTimeMillis,
            minimumFetchIntervalSeconds = settings.minimumFetchIntervalInSeconds,
            fetchTimeoutSeconds = settings.fetchTimeoutInSeconds,
            readAtMillis = System.currentTimeMillis()
        )
    }

    /**
     * Fetches the latest template from the Firebase server into the APP'S OWN Remote Config and
     * activates it. Returns null on success, or why it failed. BLOCKING - call it off the main
     * thread; Tasks.await throws if called on it.
     *
     * fetch(0) rather than fetchAndActivate(): the latter honours the host's
     * minimumFetchInterval and would quietly hand back cached values inside that window, which
     * is exactly what a "fetch now" button must not do.
     *
     * Deliberately the app's default instance, not a private copy: percentile rollouts, A/B
     * variants and audiences are resolved against this installation, so only this instance
     * shows what the app really receives. The cost is that the app uses the new values from
     * here on - see AdLogRemoteConfig.fetchAndActivate.
     */
    fun fetchAndActivateBlocking(): String? {
        val rc = FirebaseRemoteConfig.getInstance()
        // Outlive the SDK's own network timeout, so a slow fetch reports the SDK's reason rather
        // than a bare timeout from here.
        val timeoutSeconds = rc.info.configSettings.fetchTimeoutInSeconds.coerceAtLeast(5L) + 10L
        return try {
            Tasks.await(rc.fetch(0L), timeoutSeconds, TimeUnit.SECONDS)
            Tasks.await(rc.activate(), timeoutSeconds, TimeUnit.SECONDS)
            null
        } catch (e: ExecutionException) {
            // Throttled, no network, a server error: the SDK's exception is the cause.
            val cause = e.cause ?: e
            "${cause.javaClass.simpleName}: ${cause.message ?: "no message"}"
        } catch (_: TimeoutException) {
            "no answer from Firebase within ${timeoutSeconds}s"
        }
    }

    private fun sourceOf(source: Int): RemoteConfigSource = when (source) {
        FirebaseRemoteConfig.VALUE_SOURCE_REMOTE -> RemoteConfigSource.REMOTE
        FirebaseRemoteConfig.VALUE_SOURCE_DEFAULT -> RemoteConfigSource.DEFAULT
        else -> RemoteConfigSource.STATIC
    }

    private fun statusOf(status: Int): RemoteConfigFetchStatus = when (status) {
        FirebaseRemoteConfig.LAST_FETCH_STATUS_SUCCESS -> RemoteConfigFetchStatus.SUCCESS
        FirebaseRemoteConfig.LAST_FETCH_STATUS_FAILURE -> RemoteConfigFetchStatus.FAILURE
        FirebaseRemoteConfig.LAST_FETCH_STATUS_THROTTLED -> RemoteConfigFetchStatus.THROTTLED
        else -> RemoteConfigFetchStatus.NO_FETCH_YET
    }
}
