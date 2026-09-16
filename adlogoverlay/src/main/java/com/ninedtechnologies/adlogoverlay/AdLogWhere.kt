package com.ninedtechnologies.adlogoverlay

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import java.lang.reflect.Field

/**
 * The SCREEN line at the bottom of the panel: where the tester is right now, as deep as it can be
 * told - activity, then the fragment showing, then any dialog on top.
 *
 *     MainActivity › AllFilesFragment › BottomSheetDialog “Sort by”
 *
 * Nothing here has a callback to listen to - a plain Dialog announces itself to nobody - so the
 * panel asks again every second while it is open, and never while it is collapsed.
 */
internal object AdLogWhere {

    /** Joins the parts; the pure half, unit tested. */
    fun describe(activity: String, fragments: List<String>, dialog: String?): String = buildString {
        append(activity)
        if (fragments.isNotEmpty()) {
            append(" › ").append(fragments.last())
            // Split screens and view pagers can show two at once; say so rather than pick one silently.
            if (fragments.size > 1) append(" +").append(fragments.size - 1)
        }
        dialog?.let { append(" › ").append(it) }
    }

    fun of(activity: Activity): String {
        val fragments = if (hasAndroidxFragments) {
            try {
                AndroidxFragments.visibleLeaves(activity)
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val dialog = try {
            topWindow(activity)
        } catch (_: Throwable) {
            null
        }
        return describe(AdLogOverlay.config.screenNameOf(activity), fragments, dialog)
    }

    private val hasAndroidxFragments: Boolean by lazy {
        try {
            Class.forName("androidx.fragment.app.FragmentActivity", false, AdLogWhere::class.java.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    /**
     * The topmost dialog or popup window this activity has open, or null. Every window in the process
     * is public since Android 10 through [WindowInspector]; below that there is no public way to list
     * them, and the line stops at the fragment.
     */
    private fun topWindow(activity: Activity): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val decor = activity.window.decorView
        val appToken = activity.window.attributes.token
        var top: String? = null
        for (root in WindowInspector.getGlobalWindowViews()) {
            if (root === decor || !root.isAttachedToWindow || root.windowVisibility != View.VISIBLE) continue
            val lp = root.layoutParams as? WindowManager.LayoutParams ?: continue
            // Only this activity's own windows: a dialog carries its activity's token, a popup the
            // window it is anchored to.
            if (lp.token == null || (lp.token !== appToken && lp.token !== decor.windowToken)) continue
            top = when (lp.type) {
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG -> dialogName(root)
                in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW -> "Popup"
                else -> continue
            }
        }
        return top
    }

    /**
     * The dialog's class, and its first line of text - a plain `Dialog` is otherwise just "Dialog".
     *
     * The class comes from the window's callback, which is the Dialog itself. Reaching the window
     * from its decor view has no public API, so this reads DecorView's hidden `mWindow` field. If an
     * Android version blocks that, the name falls back to "Dialog" and nothing else changes.
     */
    private fun dialogName(root: View): String {
        val callback = decorWindowField?.takeIf { it.declaringClass.isInstance(root) }
            ?.let { runCatching { (it.get(root) as? Window)?.callback }.getOrNull() }
        val name = callback?.javaClass?.let { c -> c.simpleName.ifEmpty { c.name.substringAfterLast('.') } } ?: "Dialog"
        val text = firstText(root)?.let { " “$it”" }.orEmpty()
        return name + text
    }

    private val decorWindowField: Field? by lazy {
        try {
            Class.forName("com.android.internal.policy.DecorView").getDeclaredField("mWindow").apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    /** Depth first, so a dialog's title - drawn first - wins over its buttons. Never what someone typed. */
    private fun firstText(view: View): String? {
        if (view.visibility != View.VISIBLE) return null
        if (view is TextView && view !is EditText) {
            val t = view.text?.toString()?.trim()?.replace('\n', ' ')
            if (!t.isNullOrEmpty()) return if (t.length > MAX_TEXT) t.take(MAX_TEXT - 1) + "…" else t
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) firstText(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private const val MAX_TEXT = 28
}
