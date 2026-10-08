package com.match3vision.analyzer.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Share and copy of the diagnostic export. Both read the files directory
 * the bubble already wrote. Neither path needs ADB.
 */
object DiagnosticShare {
    fun copyToClipboard(context: Context, text: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("hold-diagnostic", text))
        return true
    }

    /**
     * Opens the system share sheet with the export text and, when present,
     * the pinned overlay PNG. The caller must have already written [directory].
     */
    fun share(context: Context, directory: File, text: String): Boolean {
        directory.mkdirs()
        val textFile = File(directory, "export.txt")
        textFile.writeText(text)
        val attachments = DiagnosticFiles.shareAttachments(directory)
        val authority = context.packageName + ".fileprovider"
        val uris = ArrayList<android.net.Uri>(attachments.size)
        for (file in attachments) {
            uris.add(FileProvider.getUriForFile(context, authority, file))
        }
        val intent = if (uris.size > 1) {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData.newRawUri("hold-diagnostic", uris[0]).also { clip ->
                    for (uri in uris.drop(1)) clip.addItem(ClipData.Item(uri))
                }
                putExtra(Intent.EXTRA_TEXT, text)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uris.firstOrNull())
                putExtra(Intent.EXTRA_TEXT, text)
                uris.firstOrNull()?.let { clipData = ClipData.newRawUri("hold-diagnostic", it) }
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val chooser = Intent.createChooser(intent, "HOLD diagnosztika").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
        return true
    }
}
