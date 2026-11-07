package com.auralis.app.data

import java.util.UUID

/** Stable ids so adding the same folder twice updates one source instead of creating a duplicate. */
object SourceIds {
    fun forTree(treeUri: String): String =
        UUID.nameUUIDFromBytes("tree:$treeUri".toByteArray(Charsets.UTF_8)).toString()

    const val MEDIA_STORE = "mediastore"
}
