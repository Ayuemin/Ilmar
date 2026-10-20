package com.ayuemin.ilmar.attachments

import android.content.Intent
import android.webkit.MimeTypeMap

internal object FileExport {
    /** DocumentsUI appends the canonical extension for a MIME mismatch (e.g. .md + text/plain -> .md.txt). */
    fun intent(name: String, mime: String): Intent {
        val extension = name.substringAfterLast('.', "").lowercase()
        val canonical = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        val type = if (extension.isNotBlank() && canonical.equals(extension, true) && mime !in setOf("text/html", "image/svg+xml", "application/xml")) mime else "application/octet-stream"
        return Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type).putExtra(Intent.EXTRA_TITLE, name)
    }
}
