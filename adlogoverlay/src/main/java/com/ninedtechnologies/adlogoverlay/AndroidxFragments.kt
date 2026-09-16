package com.ninedtechnologies.adlogoverlay

import android.app.Activity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

/**
 * The ONLY file that touches an AndroidX type. `androidx.fragment` is compileOnly, like Firebase:
 * load this only after [AdLogWhere] has found FragmentActivity on the host's classpath.
 */
internal object AndroidxFragments {

    /**
     * Class names of the innermost fragments on screen now: a container such as a NavHostFragment
     * gives way to the page inside it.
     */
    fun visibleLeaves(activity: Activity): List<String> {
        val fa = activity as? FragmentActivity ?: return emptyList()
        return leaves(fa.supportFragmentManager).map { f ->
            f.javaClass.simpleName.ifEmpty { f.javaClass.name.substringAfterLast('.') }
        }
    }

    private fun leaves(fm: FragmentManager): List<Fragment> {
        val out = ArrayList<Fragment>()
        for (f in fm.fragments) {
            // isVisible: added, not hidden, and its view attached and VISIBLE - which also drops
            // headless fragments such as permission or Glide helpers.
            if (!f.isResumed || !f.isVisible) continue
            // The old ViewPager keeps its neighbouring pages resumed and marks only the current one.
            @Suppress("DEPRECATION")
            if (!f.userVisibleHint) continue
            val children = leaves(f.childFragmentManager)
            if (children.isEmpty()) out += f else out += children
        }
        return out
    }
}
