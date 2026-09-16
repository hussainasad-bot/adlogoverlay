package com.ninedtechnologies.adlogoverlay

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Installs the overlay with no code in the host app.
 *
 * Android creates every ContentProvider declared in the merged manifest before it calls
 * Application.onCreate, so declaring this one in the library's own manifest means adding the
 * dependency is the entire integration - the same mechanism Firebase and WorkManager use to start
 * themselves. It serves no data; it exists only for [onCreate].
 *
 * A host that wants its own colours, tags or labels calls [AdLogOverlay.install] with an
 * [AdLogConfig] from its own Application.onCreate, which runs after this and replaces the
 * defaults installed here.
 *
 * A host that wants no automatic install removes this provider from its merged manifest:
 *
 * ```
 * <provider
 *     android:name="com.ninedtechnologies.adlogoverlay.AdLogInitProvider"
 *     tools:node="remove" />
 * ```
 */
internal class AdLogInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.let { AdLogOverlay.install(it) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
