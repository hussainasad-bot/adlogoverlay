package com.ninedtechnologies.adlogoverlay

import android.app.Activity
import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast

/**
 * Installs the floating ad-log overlay on every screen of the host app.
 *
 * ## Adding it to an app
 *
 * One line, and nothing else:
 *
 * ```
 * // app/build.gradle.kts  -  debugImplementation, never implementation
 * debugImplementation("com.9dtechnologies:adlogoverlay:<version>")
 * ```
 *
 * [AdLogInitProvider] installs the overlay before `Application.onCreate` runs, with the
 * module's own `adlog_*` colours. `debugImplementation` is the whole release story: the
 * library is not on the release compile or runtime classpath at all, so there is nothing for
 * R8 to strip and nothing to get wrong.
 *
 * ## Customising it
 *
 * To use the app's own colours, extra log tags or value labels, call [install] again with an
 * [AdLogConfig] from `Application.onCreate`; that config replaces the defaults. Because the
 * library exists only in debug builds, code shared by both variants cannot reference it - put a
 * same-signature object in `src/debug` that calls [install] and an empty twin in `src/release`,
 * and call that object from `Application.onCreate`:
 *
 * ```
 * // src/debug/.../AdLogSetup.kt
 * object AdLogSetup {
 *     fun install(app: Application) = AdLogOverlay.install(
 *         app,
 *         AdLogConfig(
 *             surfaceColor = R.color.surface,
 *             headingColor = R.color.text_primary,
 *             extraTags = setOf("myadmanager")
 *         )
 *     )
 * }
 *
 * // src/release/.../AdLogSetup.kt
 * object AdLogSetup {
 *     fun install(app: Application) = Unit
 * }
 * ```
 *
 * ## Two ways to feed it
 *
 * By default it reads the host process's OWN logcat and classifies what it finds, which
 * needs no change to the host's ad code at all but does depend on that code logging
 * something. [AdLogConfig.readLogcat]` = false` turns the reader off and leaves
 * [AdLogStore.post] as the only way in - call it from the host's ad callbacks for an
 * integration that does not depend on log text:
 *
 * ```
 * AdLogStore.post(AdLogKind.LOADED, "Home_NATIVE_AD", "onAdLoaded")
 * ```
 *
 * ## Why a per-Activity view rather than a system window
 *
 * `TYPE_APPLICATION_OVERLAY` needs SYSTEM_ALERT_WINDOW, a runtime grant and a settings
 * round-trip. Attaching to each Activity's own content frame needs no permission at all and
 * keeps the overlay inside the app where it belongs.
 *
 * ## Closing it completely
 *
 * Drag the collapsed bubble towards the bottom of the screen and a ✕ target appears; drop the
 * bubble on it and the overlay is removed from every screen - no panel, no bubble. Shake the
 * phone to bring it back, with the panel open. See [dismiss] and [reopen].
 *
 * Dismissal lasts for the process only: a relaunch always shows the bubble again, so nobody is
 * left with an overlay they cannot find.
 */
object AdLogOverlay {

    /**
     * Everything host-specific, in one place. Nothing below this line knows the app's
     * package, its theme or its ad stack: it reads colours through these resource ids and
     * tags through [AdLogConfig.extraTags].
     */
    var config: AdLogConfig = AdLogConfig()

    /**
     * Starts COLLAPSED, as the small bubble.
     *
     * This is not a cosmetic default. An app with a busy ad stack can write well over a hundred
     * logcat lines a second, most of them from the Mobile Ads SDK itself, and formatting and
     * laying all of that out continuously is expensive enough to stall a mid-range device.
     *
     * While collapsed, incoming lines are still read, classified and kept in the history buffer,
     * but never formatted or drawn - the costly half. So expanding the panel after the fact still
     * shows what just happened.
     */
    var collapsed: Boolean = true

    private var installed = false

    /**
     * True after the bubble was dropped on the ✕ target: nothing is attached to any screen.
     * Cleared by [reopen]. In memory only - see the class docs.
     */
    var dismissed: Boolean = false
        private set

    /**
     * Whether a dismissal can be undone by shaking. False on a device with no accelerometer, and
     * then the ✕ target is never offered: closing the overlay with no way back for the rest of
     * the process would be a trap.
     */
    val canShakeToReopen: Boolean get() = accelerometer != null

    private var app: Application? = null
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var listeningForShake = false
    private val shakeDetector = AdLogShakeDetector()

    /**
     * Activities between onStart and onStop - the screens a bubble can be on right now. Stopped
     * activities are removed at once, so this never holds on to anything off screen.
     */
    private val started = LinkedHashSet<Activity>()

    /**
     * Installs the overlay, or - when it is already installed - applies [config].
     *
     * Normally runs twice: [AdLogInitProvider] installs with the defaults before
     * `Application.onCreate`, then an app that customises calls this again with its own config.
     * That second call has to win, so the config is applied on every call and only the one-off
     * setup is skipped. Call it from `Application.onCreate`, before any screen exists: views
     * pick up the config when they are created.
     */
    @JvmOverloads
    fun install(app: Application, config: AdLogConfig = this.config) {
        this.config = config
        if (config.readLogcat) AdLogStore.start() else AdLogStore.stop()
        if (installed) return
        installed = true
        this.app = app
        sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // STARTED/STOPPED, not RESUMED/PAUSED. Going from A to B runs
        //   A.onPause -> B.onCreate -> B.onStart -> B.onResume -> A.onStop
        // so a resume/pause pairing leaves NOTHING attached for the whole of B's creation -
        // which is exactly the "overlay disappears for a few seconds on every screen
        // change" that a resume/pause pairing produced. The start/stop brackets overlap:
        // B attaches before A detaches, so the panel is continuously on screen.
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started += activity
                AdLogStore.currentScreen = AdLogOverlay.config.screenNameOf(activity)
                attach(activity)
                updateShakeListening()
            }
            override fun onActivityStopped(activity: Activity) {
                started -= activity
                detach(activity)
                updateShakeListening()
            }

            override fun onActivityCreated(a: Activity, s: Bundle?) = Unit
            override fun onActivityResumed(a: Activity) {
                // Resume is the authoritative "this is the screen you are looking at": on an
                // A->B transition both are STARTED for a moment, and only B resumes.
                AdLogStore.currentScreen = AdLogOverlay.config.screenNameOf(a)
                attach(a)                                   // covers a re-shown activity
            }
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, o: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) {
                started -= a
                detach(a)
            }
        })
    }

    /**
     * Removes the overlay from every screen until [reopen]. Called when the bubble is dropped
     * on the ✕ target; public so a host can close it from its own debug menu too.
     *
     * The logcat reader is left running, exactly as it is while collapsed - see the README's
     * "the reader never stops" note - so history is not lost while the overlay is closed.
     */
    fun dismiss() {
        if (dismissed || !canShakeToReopen) return
        dismissed = true
        started.toList().forEach(::detach)
        // The only place anyone learns how to get it back, so it is said once, right here.
        app?.let {
            Toast.makeText(it, "Ad log closed - shake the phone to bring it back", Toast.LENGTH_SHORT).show()
        }
        updateShakeListening()
    }

    /**
     * Brings the overlay back after [dismiss], with the panel open. A shake calls this; public so a host can wire its
     * own trigger, e.g. for a device with no accelerometer.
     */
    fun reopen() {
        if (!dismissed) return
        dismissed = false
        // Back with the panel open: a shake is a deliberate "show me the log", so it should not
        // need a second tap on the bubble.
        collapsed = false
        updateShakeListening()
        started.toList().forEach(::attach)
        started.lastOrNull()?.let { root(it)?.findViewWithTag<AdLogView>(TAG)?.confirmReopened() }
    }

    /**
     * Listens only while there is something to listen for: dismissed AND in the foreground. A
     * sensor listener left registered in the background burns battery for nothing, and Android
     * stops delivering sensor events to background apps anyway.
     *
     * SENSOR_DELAY_GAME is 50Hz - fast enough not to miss the short peaks of a shake, and far
     * below the 200Hz above which Android 12+ requires HIGH_SAMPLING_RATE_SENSORS.
     */
    private fun updateShakeListening() {
        val sm = sensorManager ?: return
        val sensor = accelerometer ?: return
        val wanted = dismissed && started.isNotEmpty()
        if (wanted && !listeningForShake) {
            shakeDetector.reset()
            listeningForShake = sm.registerListener(shakeListener, sensor, SensorManager.SENSOR_DELAY_GAME)
        } else if (!wanted && listeningForShake) {
            sm.unregisterListener(shakeListener)
            listeningForShake = false
        }
    }

    /** Registered without a Handler, so events arrive on the main thread and [reopen] can touch views. */
    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            // SensorEvent.timestamp is in nanoseconds; the detector works in milliseconds.
            if (shakeDetector.onSample(event.timestamp / 1_000_000L, v[0], v[1], v[2])) reopen()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun root(activity: Activity): FrameLayout? =
        activity.findViewById(android.R.id.content)

    private fun attach(activity: Activity) {
        if (dismissed) return
        val content = root(activity) ?: return
        if (content.findViewWithTag<AdLogView>(TAG) != null) return

        val view = AdLogView(activity)
        view.tag = TAG
        // NOT inside an apply{}: there the receiver is the view, so a bare `collapsed`
        // silently reads AdLogView.collapsed (false) instead of this object's - which
        // reopened the panel on every screen and quietly undid the collapsed-by-default
        // behaviour the reader's cost depends on.
        view.setCollapsed(AdLogOverlay.collapsed)
        content.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun detach(activity: Activity) {
        val content = root(activity) ?: return
        content.findViewWithTag<AdLogView>(TAG)?.let(content::removeView)
    }

    private const val TAG = "ad_log_overlay"
}
