package com.example.data.local

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class LocalAudioScanner(private val context: Context) {

    fun scanLocalTracksFlow(
        existingTrackIds: Set<Long> = emptySet(),
        chunkSize: Int = 15
    ): Flow<List<Track>> = flow {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        val chunkBuffer = mutableListOf<Track>()
        var index = 0

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val dateAddedColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val dateModifiedColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val trackId = id + 500000L

                    // Skip tracks that are already saved in local database for instant scan performance
                    if (existingTrackIds.contains(trackId)) {
                        index++
                        continue
                    }

                    val title = cursor.getString(titleColumn) ?: "Track $index"
                    val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                    val album = cursor.getString(albumColumn) ?: "Local Songs"
                    val albumId = cursor.getLong(albumIdColumn)
                    val durationMs = cursor.getInt(durationColumn)
                    val filePath = cursor.getString(dataColumn) ?: ""

                    val dateAddedSec = if (dateAddedColumn >= 0) cursor.getLong(dateAddedColumn) else 0L
                    val dateModifiedSec = if (dateModifiedColumn >= 0) cursor.getLong(dateModifiedColumn) else 0L
                    val dateAddedMs = if (dateAddedSec > 0) dateAddedSec * 1000L else System.currentTimeMillis()
                    val dateModifiedMs = if (dateModifiedSec > 0) dateModifiedSec * 1000L else System.currentTimeMillis()

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()

                    val albumArtUri = resolveAlbumArtUri(
                        context = context,
                        filePath = filePath,
                        contentUri = contentUri,
                        trackId = trackId,
                        albumId = albumId
                    )

                    val cleanArtist = if (artist == "<unknown>" || artist.isBlank()) "Local Artist" else artist
                    val cleanAlbum = if (album == "<unknown>" || album.isBlank()) "Local Album" else album

                    val detectedFolder = try {
                        if (filePath.isNotBlank()) {
                            java.io.File(filePath).parentFile?.name ?: "Phone Storage"
                        } else "Phone Storage"
                    } catch (e: Exception) {
                        "Phone Storage"
                    }

                    chunkBuffer.add(
                        Track(
                            id = trackId,
                            title = title,
                            artist = cleanArtist,
                            album = cleanAlbum,
                            durationSeconds = (durationMs / 1000).coerceAtLeast(1),
                            audioUrl = if (contentUri.isNotBlank()) contentUri else filePath,
                            category = "Local",
                            coverGradientIndex = (index % 5),
                            albumArtUri = albumArtUri,
                            isLocal = true,
                            folderName = detectedFolder,
                            dateAddedTimestamp = dateAddedMs,
                            dateModifiedTimestamp = dateModifiedMs
                        )
                    )
                    index++

                    if (chunkBuffer.size >= chunkSize) {
                        emit(chunkBuffer.toList())
                        chunkBuffer.clear()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (chunkBuffer.isNotEmpty()) {
            emit(chunkBuffer.toList())
            chunkBuffer.clear()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun scanLocalTracks(): List<Track> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Track>()
        scanLocalTracksFlow(chunkSize = 100).collect { chunk ->
            result.addAll(chunk)
        }
        result
    }

    companion object {
        fun resolveAlbumArtUri(
            context: Context,
            filePath: String,
            contentUri: String,
            trackId: Long,
            albumId: Long
        ): String? {
            // 1. Primary: Extract embedded picture directly from audio file (ID3 Tag / metadata)
            val embeddedUri = extractEmbeddedPicture(context, filePath, contentUri, trackId)
            if (!embeddedUri.isNullOrEmpty()) {
                return embeddedUri
            }

            // 2. Fallback: MediaStore album art URI if available and valid
            if (albumId > 0) {
                val mediaStoreUriString = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                ).toString()

                val isValid = try {
                    val pfd = context.contentResolver.openFileDescriptor(Uri.parse(mediaStoreUriString), "r")
                    pfd?.use { true } ?: false
                } catch (e: Exception) {
                    false
                }

                if (isValid) {
                    return mediaStoreUriString
                }
            }

            // 3. Fallback: null so GlassArtworkCard uses the default glass gradient artwork
            return null
        }

        private fun extractEmbeddedPicture(
            context: Context,
            filePath: String,
            contentUri: String,
            trackId: Long
        ): String? {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                if (filePath.isNotBlank() && java.io.File(filePath).exists()) {
                    retriever.setDataSource(filePath)
                } else if (contentUri.isNotBlank()) {
                    retriever.setDataSource(context, Uri.parse(contentUri))
                } else {
                    return null
                }

                val artBytes = retriever.embeddedPicture
                if (artBytes != null && artBytes.isNotEmpty()) {
                    val cacheDir = java.io.File(context.cacheDir, "album_covers")
                    if (!cacheDir.exists()) {
                        cacheDir.mkdirs()
                    }
                    val coverFile = java.io.File(cacheDir, "cover_$trackId.jpg")
                    if (!coverFile.exists() || coverFile.length() == 0L) {
                        coverFile.writeBytes(artBytes)
                    }
                    return Uri.fromFile(coverFile).toString()
                }
            } catch (e: Exception) {
                // Ignore retrieval failure, fallback mechanism handles it
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    // ignore
                }
            }
            return null
        }
    }
}
