package com.auralis.app.data.sources

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.auralis.app.data.SourceIds
import com.auralis.app.data.db.SourceDao
import com.auralis.app.data.db.SourceEntity
import com.auralis.app.data.db.TrackDao
import com.auralis.app.data.db.TrackEntity
import com.auralis.app.data.settings.SettingsRepository
import com.auralis.app.domain.model.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.FileNotFoundException

/** Imports local tracks in bounded batches on Dispatchers.IO. A bad file is isolated from the scan. */
class MusicScanner(
    private val context: Context,
    private val sources: SourceDao,
    private val tracks: TrackDao,
    private val settings: SettingsRepository,
) {
    private val artwork = ArtworkStore(context)
    private val scanMutex = Mutex()

    /** True when the user has linked at least one folder; without one the library stays empty. */
    suspend fun hasChosenFolders(): Boolean =
        sources.getAll().any { it.kind == SourceKind.TREE.name }

    suspend fun scanAll(): ScanResult = scanMutex.withLock { withContext(Dispatchers.IO) {
        val include = settings.settings.first().includeSubfolders
        val treeSources = sources.getAll().filter { it.kind == SourceKind.TREE.name }
        val scopes = ArrayList<FolderScope>(); val treeOnly = ArrayList<SourceEntity>()
        treeSources.forEach { source ->
            val scope = scopeFor(source, include)
            if (scope != null) scopes += scope else treeOnly += source
        }
        var indexed = 0; var failedSources = 0
        if (scopes.isEmpty()) {
            // No folder can be mapped to MediaStore: drop every device-wide entry, never a file.
            deleteStale(tracks.idsBySource(SourceIds.MEDIA_STORE).toSet())
        } else try { indexed += scanMediaStore(scopes) } catch (e: CancellationException) { throw e } catch (e: SecurityException) { failedSources++; Log.w(TAG, "MediaStore access was not granted", e) }
        catch (e: Exception) { failedSources++; Log.e(TAG, "MediaStore scan failed", e) }
        // Mapped folders are represented by MediaStore rows now; remove any earlier SAF duplicates.
        scopes.forEach { scope -> deleteStale(tracks.idsBySource(scope.source.id).toSet()) }
        treeOnly.forEach { source ->
            currentCoroutineContext().ensureActive()
            try { indexed += scanTree(source, include); sources.markScanned(source.id, System.currentTimeMillis()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failedSources++; Log.e(TAG, "SAF scan failed for ${source.name}", e) }
        }
        ScanResult(indexed, failedSources)
    } }

    private suspend fun scanMediaStore(scopes: List<FolderScope>): Int {
        val sourceId = SourceIds.MEDIA_STORE
        sources.upsert(SourceEntity(sourceId, SourceKind.MEDIA_STORE.name, "Chosen folders", null, System.currentTimeMillis(), null))
        val stale = tracks.idsBySource(sourceId).toMutableSet()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = mediaStoreProjection()
        var indexed = 0; var visited = 0; var batch = ArrayList<TrackEntity>(BATCH_SIZE)
        val (selection, args) = mediaStoreSelection(scopes)
        val cursor = context.contentResolver.query(collection, projection, selection, args, "${MediaStore.Audio.Media._ID} ASC") ?: throw IllegalStateException("MediaStore returned no cursor")
        cursor.use { c ->
            val idCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);val nameCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);val titleCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);val artistCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);val albumCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);val albumIdCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);val durationCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);val sizeCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);val modifiedCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED);val mimeCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE);val pathCol=relativePathColumn(c);val yearCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR);val trackCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            while(c.moveToNext()) {
                if (visited++ % BATCH_SIZE == 0) currentCoroutineContext().ensureActive()
                val id=c.getLong(idCol);val uri=ContentUris.withAppendedId(collection,id);val name=c.getString(nameCol)?.takeIf(String::isNotBlank) ?: "Audio $id";val albumId=c.getLong(albumIdCol)
                val path=if(pathCol>=0)c.getString(pathCol).orEmpty() else ""
                if (isIncompleteName(name) || isExcludedPath(path) || !isInScopes(path, scopes)) continue
                val artUri=if(albumId>0) Uri.parse("content://media/external/audio/albumart/$albumId") else null
                val row=safeMetadata(uri,sourceId,name,path,c.getLong(sizeCol).coerceAtLeast(0),c.getLong(modifiedCol).coerceAtLeast(0)*1000,c.getString(mimeCol) ?: mimeFor(name),c.getString(titleCol),c.getString(artistCol),c.getString(albumCol),c.getLong(durationCol).coerceAtLeast(0),c.getInt(yearCol).takeIf{it>0},c.getInt(trackCol).takeIf{it>0},albumId,artUri)
                stale.remove(row.id);batch+=row
                if(batch.size>=BATCH_SIZE){tracks.upsertBatch(batch);indexed+=batch.size;batch=ArrayList(BATCH_SIZE)}
            }
        }
        if(batch.isNotEmpty()){tracks.upsertBatch(batch);indexed+=batch.size}
        deleteStale(stale);sources.markScanned(sourceId,System.currentTimeMillis());Log.i(TAG,"MediaStore scan indexed $indexed tracks");return indexed
    }

    /**
     * Cheap diff used by the automatic scanner. Reads only id/size/date-modified from MediaStore and
     * the folder listing for SAF trees, then reads tags/artwork just for new or changed files. Files
     * that disappeared are removed from the library. Unchanged rows are left untouched so their
     * `addedAt` (and therefore library order) is preserved.
     */
    suspend fun scanIncremental(): ScanResult = scanMutex.withLock { withContext(Dispatchers.IO) {
        val include = settings.settings.first().includeSubfolders
        val treeSources = sources.getAll().filter { it.kind == SourceKind.TREE.name }
        val scopes = ArrayList<FolderScope>(); val treeOnly = ArrayList<SourceEntity>()
        treeSources.forEach { source ->
            val scope = scopeFor(source, include)
            if (scope != null) scopes += scope else treeOnly += source
        }
        var indexed = 0; var failedSources = 0
        if (scopes.isEmpty()) {
            deleteStale(tracks.idsBySource(SourceIds.MEDIA_STORE).toSet())
        } else try { indexed += scanMediaStoreIncremental(scopes) } catch (e: CancellationException) { throw e } catch (e: SecurityException) { failedSources++; Log.w(TAG, "MediaStore access was not granted", e) }
        catch (e: Exception) { failedSources++; Log.e(TAG, "Incremental MediaStore scan failed", e) }
        scopes.forEach { scope -> deleteStale(tracks.idsBySource(scope.source.id).toSet()) }
        treeOnly.forEach { source ->
            currentCoroutineContext().ensureActive()
            try { indexed += scanTreeIncremental(source, include); sources.markScanned(source.id, System.currentTimeMillis()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failedSources++; Log.e(TAG, "Incremental SAF scan failed for ${source.name}", e) }
        }
        ScanResult(indexed, failedSources)
    } }

    private suspend fun scanMediaStoreIncremental(scopes: List<FolderScope>): Int {
        val sourceId = SourceIds.MEDIA_STORE
        sources.upsert(SourceEntity(sourceId, SourceKind.MEDIA_STORE.name, "Chosen folders", null, System.currentTimeMillis(), null))
        val known = tracks.fingerprints(sourceId).associateBy { it.id }
        val seen = HashSet<String>(known.size)
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = mediaStoreProjection()
        val (selection, args) = mediaStoreSelection(scopes)
        var indexed = 0; var visited = 0; var batch = ArrayList<TrackEntity>(BATCH_SIZE)
        val cursor = context.contentResolver.query(collection, projection, selection, args, "${MediaStore.Audio.Media._ID} ASC") ?: throw IllegalStateException("MediaStore returned no cursor")
        cursor.use { c ->
            val idCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);val nameCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);val titleCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);val artistCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);val albumCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);val albumIdCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);val durationCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);val sizeCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);val modifiedCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED);val mimeCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE);val pathCol=relativePathColumn(c);val yearCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR);val trackCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            while(c.moveToNext()) {
                if (visited++ % BATCH_SIZE == 0) currentCoroutineContext().ensureActive()
                val id=c.getLong(idCol);val uri=ContentUris.withAppendedId(collection,id);val name=c.getString(nameCol)?.takeIf(String::isNotBlank) ?: "Audio $id"
                val path=if(pathCol>=0)c.getString(pathCol).orEmpty() else ""
                if (isIncompleteName(name) || isExcludedPath(path) || !isInScopes(path, scopes)) continue
                val size=c.getLong(sizeCol).coerceAtLeast(0);val modified=c.getLong(modifiedCol).coerceAtLeast(0)*1000
                val trackId=sha(uri.toString());seen+=trackId
                val previous=known[trackId]
                if (previous != null && previous.lastModified == modified && previous.sizeBytes == size) continue
                val albumId=c.getLong(albumIdCol)
                val artUri=if(albumId>0) Uri.parse("content://media/external/audio/albumart/$albumId") else null
                val row=safeMetadata(uri,sourceId,name,path,size,modified,c.getString(mimeCol) ?: mimeFor(name),c.getString(titleCol),c.getString(artistCol),c.getString(albumCol),c.getLong(durationCol).coerceAtLeast(0),c.getInt(yearCol).takeIf{it>0},c.getInt(trackCol).takeIf{it>0},albumId,artUri)
                batch+=row
                if(batch.size>=BATCH_SIZE){tracks.upsertBatch(batch);indexed+=batch.size;batch=ArrayList(BATCH_SIZE)}
            }
        }
        if(batch.isNotEmpty()){tracks.upsertBatch(batch);indexed+=batch.size}
        deleteStale(known.keys - seen);sources.markScanned(sourceId,System.currentTimeMillis())
        Log.i(TAG,"Incremental MediaStore: $indexed new/changed, ${seen.size} present")
        return indexed
    }

    private suspend fun scanTreeIncremental(source:SourceEntity, includeSubfolders:Boolean):Int {
        val tree=source.treeUri?.let(Uri::parse) ?: return 0
        val root=DocumentFile.fromTreeUri(context,tree) ?: throw FileNotFoundException("Folder grant is no longer available")
        val known=tracks.fingerprints(source.id).associateBy{it.id};val seen=HashSet<String>(known.size)
        val stack=ArrayDeque<Pair<DocumentFile,String>>().apply{add(root to "")};var batch=ArrayList<TrackEntity>(BATCH_SIZE);var indexed=0
        while(stack.isNotEmpty()){currentCoroutineContext().ensureActive();val (dir,prefix)=stack.removeLast()
            val files=dir.listFiles()
            if(files.any{it.name.equals(".nomedia",true)}) continue
            files.forEach{file->currentCoroutineContext().ensureActive();val name=file.name ?: return@forEach
            if(file.isDirectory){if(includeSubfolders&&!isExcludedPath("$prefix$name/")) stack.add(file to "$prefix$name/")} else if(file.isFile&&!isIncompleteName(name)&&!isExcludedPath("$prefix$name")&&isAudio(name,file.type)){
                val size=file.length().coerceAtLeast(0);val modified=file.lastModified().coerceAtLeast(0)
                val trackId=sha(file.uri.toString());seen+=trackId
                val previous=known[trackId]
                if(previous!=null&&previous.lastModified==modified&&previous.sizeBytes==size) return@forEach
                val row=safeMetadata(file.uri,source.id,name,"$prefix$name",size,modified,file.type?:mimeFor(name),null,null,null,0,null,null,0,null)
                batch+=row;if(batch.size>=BATCH_SIZE){tracks.upsertBatch(batch);indexed+=batch.size;batch=ArrayList(BATCH_SIZE)}
            }
        }}
        if(batch.isNotEmpty()){tracks.upsertBatch(batch);indexed+=batch.size}
        deleteStale(known.keys-seen)
        Log.i(TAG,"Incremental SAF ${source.name}: $indexed new/changed, ${seen.size} present")
        return indexed
    }

    /** Downloads in progress are invisible until they are complete, so they never appear half-indexed. */
    private fun isIncompleteName(name:String):Boolean {
        val lower=name.lowercase()
        return lower.endsWith(".part")||lower.endsWith(".tmp")||lower.endsWith(".crdownload")||lower.endsWith(".download")||lower.endsWith(".partial")
    }

    private suspend fun scanTree(source:SourceEntity, includeSubfolders:Boolean):Int {
        val tree=source.treeUri?.let(Uri::parse) ?: return 0
        val root=DocumentFile.fromTreeUri(context,tree) ?: throw FileNotFoundException("Folder grant is no longer available")
        val stale=tracks.idsBySource(source.id).toMutableSet();val stack=ArrayDeque<Pair<DocumentFile,String>>().apply{add(root to "")};var batch=ArrayList<TrackEntity>(BATCH_SIZE);var indexed=0
        while(stack.isNotEmpty()){currentCoroutineContext().ensureActive();val (dir,prefix)=stack.removeLast()
            val files=dir.listFiles()
            if(files.any{it.name.equals(".nomedia",true)}) continue
            files.forEach{file->currentCoroutineContext().ensureActive();val name=file.name ?: return@forEach
            if(file.isDirectory){if(includeSubfolders&&!isExcludedPath("$prefix$name/")) stack.add(file to "$prefix$name/")} else if(file.isFile&&!isIncompleteName(name)&&!isExcludedPath("$prefix$name")&&isAudio(name,file.type)){
                val row=safeMetadata(file.uri,source.id,name,"$prefix$name",file.length().coerceAtLeast(0),file.lastModified().coerceAtLeast(0),file.type?:mimeFor(name),null,null,null,0,null,null,0,null)
                stale.remove(row.id);batch+=row;if(batch.size>=BATCH_SIZE){tracks.upsertBatch(batch);indexed+=batch.size;batch=ArrayList(BATCH_SIZE)}
            }
        }}
        if(batch.isNotEmpty()){tracks.upsertBatch(batch);indexed+=batch.size};deleteStale(stale);return indexed
    }

    private fun safeMetadata(uri:Uri,sourceId:String,name:String,path:String,size:Long,modified:Long,mime:String,titleHint:String?,artistHint:String?,albumHint:String?,durationHint:Long,yearHint:Int?,trackHint:Int?,albumId:Long,artUri:Uri?):TrackEntity = try {
        metadata(uri,sourceId,name,path,size,modified,mime,titleHint,artistHint,albumHint,durationHint,yearHint,trackHint,albumId,artUri)
    } catch(e:Exception){Log.w(TAG,"Using file-name metadata fallback for $uri",e);fallback(uri,sourceId,name,path,size,modified,mime,titleHint,artistHint,albumHint,durationHint,yearHint,trackHint,e.message)}

    private fun metadata(uri:Uri,sourceId:String,name:String,path:String,size:Long,modified:Long,mime:String,titleHint:String?,artistHint:String?,albumHint:String?,durationHint:Long,yearHint:Int?,trackHint:Int?,albumId:Long,artUri:Uri?):TrackEntity {
        val r=MediaMetadataRetriever();var error:String?=null;val opened=try{r.setDataSource(context,uri);true}catch(e:Exception){error=e.message?:"Metadata unavailable";false}
        fun tag(k:Int):String?=if(opened)try{r.extractMetadata(k)}catch(e:Exception){error=error?:e.message;null}else null
        try {
            val title=tag(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf(String::isNotBlank)?:titleHint?.takeIf(String::isNotBlank)?:name.substringBeforeLast('.')
            val artist=tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf(String::isNotBlank)?:artistHint?.takeIf(String::isNotBlank)?:"Unknown artist"
            val album=tag(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf(String::isNotBlank)?:albumHint?.takeIf(String::isNotBlank)?:"Unknown album"
            val duration=tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf{it>0}?:durationHint
            val embedded=if(opened)try{r.embeddedPicture}catch(_:Exception){null}catch(e:OutOfMemoryError){Log.w(TAG,"Skipping oversized embedded artwork for $uri",e);null}else null
            val artKey=artwork.saveEmbedded(embedded)?:artUri?.let{u->artwork.saveMediaStore(albumId){context.contentResolver.openInputStream(u)}}
            val artColor=artKey?.let(artwork::colorOf)
            val ext=name.substringAfterLast('.',"").lowercase()
            return TrackEntity(id=sha(uri.toString()),sourceId=sourceId,uri=uri.toString(),relativePath=path,displayName=name,sizeBytes=size,lastModified=modified,ext=ext,mime=mime,title=title,artist=artist,albumArtist=tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?:artist,album=album,genres=tag(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty(),trackNumber=tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.toIntOrNull()?:trackHint,discNumber=null,year=tag(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4)?.toIntOrNull()?:yearHint,composer=tag(MediaMetadataRetriever.METADATA_KEY_COMPOSER),durationMs=duration,codec=ext.uppercase().ifBlank{null},container=mime.substringAfter('/',"audio"),sampleRateHz=tag(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull(),bitDepth=null,bitrateBps=tag(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),channels=null,lossless=ext=="flac"||ext=="wav",replayGainTrackDb=null,replayGainAlbumDb=null,artKey=artKey,artColor=artColor,addedAt=System.currentTimeMillis(),tagError=error)
        } finally { try{r.release()}catch(e:Exception){Log.d(TAG,"Metadata retriever release failed",e)} }
    }
    private fun isExcludedPath(path:String):Boolean {
        if (path.isBlank()) return false
        val segments=path.split('/').filter{it.isNotBlank()}
        if (segments.any{it.equals(".nomedia",true)}) return true
        return segments.any{it.lowercase() in EXCLUDED_SEGMENTS}
    }

    /** Maps a SAF tree grant to a MediaStore volume + relative path when the provider allows it. */
    private fun scopeFor(source:SourceEntity, includeSubfolders:Boolean):FolderScope? {
        val tree=source.treeUri?.let(Uri::parse) ?: return null
        if (tree.authority != "com.android.externalstorage.documents") return null
        val docId=runCatching{DocumentsContract.getTreeDocumentId(tree)}.getOrNull() ?: return null
        val idx=docId.indexOf(':'); if(idx<0) return null
        val rawVolume=docId.substring(0,idx)
        val rel=docId.substring(idx+1).trim('/').let{if(it.isEmpty()) "" else "$it/"}
        val primary=rawVolume.equals("primary",true)
        val volume=if(primary) MediaStore.VOLUME_EXTERNAL_PRIMARY else rawVolume
        val absolutePrefix=if(primary)
            Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')+"/"+rel
        else "/storage/"+rawVolume+"/"+rel
        return FolderScope(source,volume,rel,absolutePrefix,includeSubfolders)
    }

    private fun mediaStoreSelection(scopes:List<FolderScope>):Pair<String,Array<String>> {
        val args=ArrayList<String>()
        val selection=buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            append(" AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_ALARM} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) append(" AND ${MediaStore.Audio.Media.IS_PENDING} = 0")
            append(" AND (")
            scopes.forEachIndexed { index, scope ->
                if (index > 0) append(" OR ")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (scope.includeSubfolders) {
                        append("(${MediaStore.Audio.Media.VOLUME_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?)")
                        args += scope.volume; args += "${scope.relativePath}%"
                    } else {
                        append("(${MediaStore.Audio.Media.VOLUME_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} = ?)")
                        args += scope.volume; args += scope.relativePath
                    }
                } else {
                    if (scope.includeSubfolders) {
                        append("${MediaStore.Audio.Media.DATA} LIKE ?"); args += "${scope.absolutePrefix}%"
                    } else {
                        append("(${MediaStore.Audio.Media.DATA} LIKE ? AND ${MediaStore.Audio.Media.DATA} NOT LIKE ?)")
                        args += "${scope.absolutePrefix}%"; args += "${scope.absolutePrefix}%/%"
                    }
                }
            }
            append(")")
        }
        return selection to args.toTypedArray()
    }

    private fun isInScopes(path:String, scopes:List<FolderScope>):Boolean {
        if (path.isBlank()) return false
        val absolute = path.startsWith("/")
        return scopes.any { scope ->
            if (absolute) {
                if (!path.startsWith(scope.absolutePrefix)) false
                else scope.includeSubfolders || !path.removePrefix(scope.absolutePrefix).contains('/')
            } else {
                if (!path.startsWith(scope.relativePath)) false
                else scope.includeSubfolders || !path.removePrefix(scope.relativePath).contains('/')
            }
        }
    }

    private data class FolderScope(
        val source: SourceEntity,
        val volume: String,
        val relativePath: String,
        val absolutePrefix: String,
        val includeSubfolders: Boolean,
    )

    private suspend fun deleteStale(ids:Set<String>){ids.toList().chunked(DELETE_BATCH_SIZE).forEach{if(it.isNotEmpty())tracks.deleteIdsNow(it)}}
    private fun fallback(uri:Uri,sourceId:String,name:String,path:String,size:Long,modified:Long,mime:String,titleHint:String?,artistHint:String?,albumHint:String?,durationHint:Long,yearHint:Int?,trackHint:Int?,error:String?)=TrackEntity(id=sha(uri.toString()),sourceId=sourceId,uri=uri.toString(),relativePath=path,displayName=name,sizeBytes=size,lastModified=modified,ext=name.substringAfterLast('.',"").lowercase(),mime=mime,title=titleHint?.takeIf(String::isNotBlank)?:name.substringBeforeLast('.'),artist=artistHint?.takeIf(String::isNotBlank)?:"Unknown artist",albumArtist=artistHint?.takeIf(String::isNotBlank)?:"Unknown artist",album=albumHint?.takeIf(String::isNotBlank)?:"Unknown album",genres="",trackNumber=trackHint,discNumber=null,year=yearHint,composer=null,durationMs=durationHint,codec=name.substringAfterLast('.',"").uppercase(),container=mime.substringAfter('/',"audio"),sampleRateHz=null,bitDepth=null,bitrateBps=null,channels=null,lossless=name.endsWith(".flac",true)||name.endsWith(".wav",true),replayGainTrackDb=null,replayGainAlbumDb=null,artKey=null,artColor=null,addedAt=System.currentTimeMillis(),tagError=error)
    private fun isAudio(name:String,mime:String?)=mime?.startsWith("audio/")==true||name.substringAfterLast('.',"").lowercase() in AUDIO_EXTENSIONS

    /** `RELATIVE_PATH` only exists from API 29; older devices fall back to the absolute `DATA` path. */
    private fun relativePathColumn(cursor: android.database.Cursor): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
        else cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

    private fun mediaStoreProjection(): Array<String> = buildList {
        add(MediaStore.Audio.Media._ID)
        add(MediaStore.Audio.Media.DISPLAY_NAME)
        add(MediaStore.Audio.Media.TITLE)
        add(MediaStore.Audio.Media.ARTIST)
        add(MediaStore.Audio.Media.ALBUM)
        add(MediaStore.Audio.Media.ALBUM_ID)
        add(MediaStore.Audio.Media.DURATION)
        add(MediaStore.Audio.Media.SIZE)
        add(MediaStore.Audio.Media.DATE_MODIFIED)
        add(MediaStore.Audio.Media.MIME_TYPE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Audio.Media.RELATIVE_PATH)
        else add(MediaStore.Audio.Media.DATA)
        add(MediaStore.Audio.Media.YEAR)
        add(MediaStore.Audio.Media.TRACK)
    }.toTypedArray()
    private fun mimeFor(name:String)=when(name.substringAfterLast('.',"").lowercase()){ "mp3"->"audio/mpeg";"flac"->"audio/flac";"wav"->"audio/wav";"aac"->"audio/aac";"m4a"->"audio/mp4";"ogg"->"audio/ogg";"opus"->"audio/opus";else->"audio/*" }
    private fun sha(v:String)=java.security.MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).take(16).joinToString(""){"%02x".format(it)}
    data class ScanResult(val tracks:Int,val failedSources:Int)
    private companion object{const val TAG="AuralisScan";const val BATCH_SIZE=100;const val DELETE_BATCH_SIZE=500;val AUDIO_EXTENSIONS=setOf("mp3","flac","wav","aac","m4a","ogg","opus");val EXCLUDED_SEGMENTS=setOf("ringtones","alarms","notifications","recordings")}
}
