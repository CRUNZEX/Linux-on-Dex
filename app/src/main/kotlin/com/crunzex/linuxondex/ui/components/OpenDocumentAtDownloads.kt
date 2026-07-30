package com.crunzex.linuxondex.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri

/**
 * The system file picker, opened at Downloads instead of "Recent".
 *
 * Recent is empty for anything the user copied onto the device rather than
 * downloaded in-session, so the stock picker greets them with "No items" and
 * looks broken. Pointing it at Downloads — where large files like disk
 * images and ISOs actually land — removes that dead end.
 */
class OpenDocumentAtDownloads : ActivityResultContracts.OpenDocument() {

    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply {
            downloadsFolderUri()?.let { uri ->
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
            }
            // Large files are the whole point here; let the picker show them.
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }

    /**
     * The documents-provider URI for the Downloads folder. Returns null on
     * anything unexpected: a missing hint only costs the user one tap.
     */
    private fun downloadsFolderUri(): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DOWNLOADS_TREE_URI.toUri()
        } else {
            Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .toUri()
        }
    }.getOrNull()

    private companion object {
        const val DOWNLOADS_TREE_URI =
            "content://com.android.externalstorage.documents/document/primary%3ADownload"
    }
}
