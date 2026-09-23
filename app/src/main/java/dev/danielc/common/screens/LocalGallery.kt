package dev.danielc.common.screens

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.danielc.common.FileHandle
import dev.danielc.common.SortBy
import dev.danielc.common.StorageInfo
import dev.danielc.fudge.FileLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LocalGalleryViewModel: GalleryViewModel(checkFileSaved = false) {
    val viewer = ViewerModel(showSaveButton = false, showLoadDialog = false)
    var files = emptyList<FileLayer.MediaStoreFile>()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            refresh()
            start()
        }
    }

    fun refresh() {
        try {
            files = FileLayer.getDownloadedMediaFiles()
            reset()
            setProperties(StorageInfo(
                name = "Downloads",
                nFiles = files.size,
                itemsSortedBy = SortBy.NEWEST_FIRST
            ))
            for (i in files.indices) {
                updateMetadata(i, files[i].metadata)
            }
            enqueueObjects((0..100).toList())
        } catch (e: Exception) {
            // ..
        }
    }

    override fun fulfillThumbnail(file: GalleryObjectReference) {
        updateThumbnail(file.index, FileLayer.getMediaThumbnail(files[file.index]))
    }

    override fun fulfillMetadata(file: GalleryObjectReference) {
        updateMetadata(file.index, files[file.index].metadata)
    }

    override fun itemClicked(ref: GalleryObjectReference) {
        val file = files[ref.index]
        val handle = FileHandle(ref.index)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalGallery(onItemClick: (Int) -> Unit, modifier: Modifier, model: LocalGalleryViewModel?) {
    if (model != null) {
        val state by model.uiState.collectAsStateWithLifecycle()
        Gallery(modifier, state, requestLoad = { items ->
            model.enqueueObjects(items)
        }, onItemClick = { i ->
            onItemClick(i)
        }, onRefresh = {
            model.refresh()
        })
    }
}