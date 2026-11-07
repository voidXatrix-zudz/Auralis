package com.auralis.app.data.sources

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.auralis.app.data.SourceIds
import com.auralis.app.data.db.SourceDao
import com.auralis.app.data.db.SourceEntity
import com.auralis.app.data.db.toDomain
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Folders the user granted through the Storage Access Framework. Permission is persisted so the
 * folder stays readable after restarts, and released when the folder is removed.
 */
class SourceRepository(
    private val context: Context,
    private val dao: SourceDao,
) {
    fun observeSources(): Flow<List<MusicSource>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun addTreeSource(treeUri: Uri): Result<MusicSource> = withContext(Dispatchers.IO) {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
                .recoverCatching {
                    // Some providers only grant read access; library scanning must still work.
                    context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                .getOrThrow()
            val name = DocumentFile.fromTreeUri(context, treeUri)?.name
                ?: treeUri.lastPathSegment
                ?: "Music folder"
            val entity = SourceEntity(
                id = SourceIds.forTree(treeUri.toString()),
                kind = SourceKind.TREE.name,
                name = name,
                treeUri = treeUri.toString(),
                addedAt = System.currentTimeMillis(),
                lastScanAt = null,
            )
            dao.upsert(entity)
            entity.toDomain()
        }.onFailure { if (it is CancellationException) throw it else Log.e(TAG, "Could not add folder $treeUri", it) }
    }

    suspend fun removeSource(source: MusicSource) = withContext(Dispatchers.IO) {
        source.treeUri?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onFailure { Log.w(TAG, "Could not release permission for $uri", it) }
        }
        dao.delete(source.id)
    }

    private companion object {
        const val TAG = "SourceRepository"
    }
}
