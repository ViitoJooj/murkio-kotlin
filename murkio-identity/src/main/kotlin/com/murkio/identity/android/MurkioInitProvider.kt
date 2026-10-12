package com.murkio.identity.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Starts the SDK with the app. A content provider is created before
 * `Application.onCreate`, so by the time the first screen draws the SDK has
 * read the publishable key from the manifest. It provides no content.
 */
public class MurkioInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { Murkio.initializeFromManifest(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
