package dev.danielc.fudge.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.danielc.fudge.ObjectHandle
import dev.danielc.fudge.FileLayer
import dev.danielc.fudge.SortBy
import dev.danielc.fudge.StorageInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

class LocalGalleryViewModel: GalleryViewModel(isRemoteFilesystem = false) {
    val viewer = ViewerModel(showSaveButton = false, showLoadDialog = false)
    var files = emptyList<FileLayer.MediaStoreFile>()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            onRefresh()
            start()
        }
    }

    override fun onRefresh() {
        try {
            files = FileLayer.getDownloadedMediaFiles()
            reset()
            switchStorageDevice(StorageInfo(
                storageName = "Downloads",
                nFiles = files.size,
                objectListSortedOrder = SortBy.NEWEST_FIRST
            ))
            for (i in files.indices) {
                updateMetadata(ObjectHandle(i, currentFilesystemStateFlow.value?.storageName), files[i].metadata)
            }
            enqueueObjects((0..100).toList())
        } catch (_: Exception) {
            // ..
        }
    }

    override suspend fun fulfillThumbnail(file: ObjectHandle) {
        updateThumbnail(file, FileLayer.getMediaThumbnail(files[file.index]))
    }

    override suspend fun fulfillMetadata(file: ObjectHandle) {
        updateMetadata(file, files[file.index].metadata)
    }

    override fun itemClicked(ref: ObjectHandle) {
        val file = files[ref.index]
        val handle = ObjectHandle(ref.index)
        viewer.update(handle, files.size, getThumbnail(handle, -1), getThumbnail(handle, 0), getThumbnail(handle, 1))
        viewer.updateMetadata(file.metadata)
        if (file.metadata.getMimeType().isVideo()) {
            viewer.setError("Video not supported in viewer yet")
        } else {
            val handle = FileLayer.openFileForReading(file)
            if (handle == null) {
                viewer.setError("Failed to decode image")
            } else {
                viewer.loadImage(handle)
                handle.close()
            }
        }
    }

    fun share(i: Int) {
        FileLayer.openImageInDefaultApp(files[i])
    }
}

@Composable
fun LocalGallery(onItemClick: (ObjectHandle) -> Unit, modifier: Modifier, model: LocalGalleryViewModel?) {
    if (model != null) {
        Gallery(modifier, model, onItemClick = { obj ->
            onItemClick(obj)
        })
    }
}