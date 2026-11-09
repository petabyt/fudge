package dev.danielc.common

import android.Manifest
import android.content.ClipData
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

actual object FileLayer {
    private external fun getExifThumbnail(filepath: String?): ByteArray?

    actual data class MediaStoreFile(
        val contentUri: Uri,
        val path: String,
        actual val metadata: FileMetadata,
    )

    actual data class Handle(
        val fd: ParcelFileDescriptor,
        val uri: Uri,
        val write: Boolean = true,
    ) {
        val streamIn: InputStream? = if (!write) FileInputStream(fd.fileDescriptor) else null
        val streamOut: OutputStream? = if (write) FileOutputStream(fd.fileDescriptor) else null
        actual fun write(byteArray: ByteArray) {
            streamOut?.write(byteArray)
        }
        actual fun close() {
            streamOut?.close()
            streamIn?.close()
            fd.close()
        }
        actual fun getPath(): String {
            return uri.toString()
        }
    }

    actual data class Directory actual constructor(
        actual val folder: String,
        actual val subfolder: String?,
        actual val customFullPath: String?,
    ) {
        actual fun getPath(): String {
            return folder + if (subfolder == null) "" else "/${subfolder}"
        }
    }

    actual fun readFile(path: String): ByteArray? {
        try {
            if (path.startsWith("file:///android_asset/")) {
                val assman = Pak.activity.assets
                val f = assman.open(path.substringAfter("file:///android_asset/"))
                return f.readBytes()
            } else {
                val f = File(path)
                if (!f.exists()) return null
                return f.readBytes()
            }
        } catch (_: Exception) { return null }
    }

    private fun shareFile(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(Pak.activity.contentResolver, "Shared Image", uri)
        }
        val chooserIntent = Intent.createChooser(intent, "Share")
        Pak.activity.startActivity(chooserIntent)
    }

    actual fun openImageInDefaultApp(file: MediaStoreFile) {
        shareFile(file.contentUri)
    }

    actual fun deleteFile(file: Handle) {
        Log.d("files", "Deleting ${file.uri}")
        val resolver = Pak.activity.contentResolver
        resolver.delete(file.uri, null, null)
    }

    actual fun openFileForReading(ref: MediaStoreFile): Handle? {
        val resolver = Pak.activity.contentResolver
        try {
            return Handle(resolver.openFileDescriptor(ref.contentUri, "r") ?: return null, ref.contentUri)
        } catch (ignored: Exception) { return null }
    }

    actual fun doesFileExist(filename: String, dir: Directory): Boolean {
        // querying doesn't work for files the app doesn't have access to

        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
            else
                "(${MediaStore.Files.FileColumns.DATA} LIKE ? OR ${MediaStore.Files.FileColumns.DATA} LIKE ?)"

        Pak.activity.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                arrayOf(filename, "%${Environment.DIRECTORY_DOWNLOADS}/${dir.getPath()}/%")
            else
                arrayOf(filename, "%/${Environment.DIRECTORY_PICTURES}/${dir.getPath()}/%", "%/${Environment.DIRECTORY_MOVIES}/${dir.getPath()}/%"),
            null
        ).use { cursor ->
            return cursor != null && cursor.count > 0
        }
    }

    actual fun openFileForWriting(filename: String, mimeType: String?, dir: Directory, mode: String): Handle? {
        val resolver = Pak.activity.contentResolver

        val pair = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Pair(MediaStore.Downloads.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_DOWNLOADS)
        } else {
            if (MimeType.fromString(mimeType).isVideo()) {
                Pair(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES)
            } else {
                Pair(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES)
            }
        }
        val collection = pair.first
        val directory = pair.second

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${directory}/${dir.getPath()}")
        }

        try {
            val uri = resolver.insert(collection, values) ?: return null
            return Handle(resolver.openFileDescriptor(uri, mode) ?: return null, uri)
        } catch (_: Exception) { return null }
    }

    actual fun getMediaThumbnail(file: MediaStoreFile): ImageBitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val bitmap = Pak.activity.contentResolver.loadThumbnail(file.contentUri, Size(640, 480), null)
                return bitmap.asImageBitmap()
            } catch (ignored: Exception) {
                println("Failed for ${file.contentUri}: ${ignored.message}")
                return null
            }
        } else {
            val thumb = getExifThumbnail(file.path) ?: return null
            return NativeRuntime.decodeImageContents(thumb, null)
        }
    }

    actual fun requestExternalImagesPermission() {
        val ctx = Pak.activity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                Handler(ctx.mainLooper).post {
                    ctx.requestPermissions(arrayOf<String?>(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO), 1)
                }
            }
        } else {
            // TODO: ???
        }
    }

    fun getInternalDataDirectory(): String {
        return Pak.activity.filesDir.path
    }

    actual fun getDefaultDownloadDirectory(): String {
        val mainStorage = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
        val path = mainStorage + File.separator + "fudge"
        val directory = File(path)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return path
    }

    actual fun getDownloadedMediaFiles(subfolder: String): List<MediaStoreFile> {
        val list = mutableListOf<MediaStoreFile>()
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.ORIENTATION,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
        )

        val selection = (
            "("
            + MediaStore.Files.FileColumns.MEDIA_TYPE + "="
            + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
            + " OR "
            + MediaStore.Files.FileColumns.MEDIA_TYPE + "="
            + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            + ")"
            + " AND "
            + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
            else
                "(${MediaStore.Files.FileColumns.DATA} LIKE ? OR ${MediaStore.Files.FileColumns.DATA} LIKE ?)"
        )

        Pak.activity.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            columns,
            selection,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/${subfolder}/%")
            else
                arrayOf("%/${Environment.DIRECTORY_PICTURES}/${subfolder}/%", "%/${Environment.DIRECTORY_MOVIES}/${subfolder}/%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val orientationColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val type = cursor.getInt(typeColumn)
                val collection = if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                }

                list += MediaStoreFile(
                    collection,
                    cursor.getString(dataColumn),
                    FileMetadata(
                        filename = cursor.getString(nameColumn),
                        mimeType = cursor.getString(mimeTypeColumn),
                        width = cursor.getInt(widthColumn),
                        height = cursor.getInt(heightColumn),
                        filesize = cursor.getInt(sizeColumn),
                        orientation = cursor.getInt(orientationColumn),
                    )
                )
            }
        }
        return list
    }

    actual fun requestLegacyPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (Pak.activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Pak.activity.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1);
            }
            if (Pak.activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Pak.activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1);
            }
        }
    }
}