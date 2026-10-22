package com.example.tp1.sources

import android.content.Intent

internal fun sourcePickerIntent(kind: SourceKind): Intent = Intent(
    if (kind == SourceKind.Tree) Intent.ACTION_OPEN_DOCUMENT_TREE else Intent.ACTION_OPEN_DOCUMENT
).apply {
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    if (kind == SourceKind.Document) {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    } else {
        addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }
}
