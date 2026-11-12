package dev.danielc.fudge.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import fantasyfudge.app.generated.resources.*
import dev.danielc.fudge.BackgroundViewModel
import dev.danielc.fudge.ObjectHandle
import dev.danielc.fudge.FileMetadata
import dev.danielc.fudge.MimeType
import dev.danielc.fudge.StorageInfo
import dev.danielc.fudge.longToFileSize
import dev.danielc.fudge.ui.theme.FudgeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.compose.resources.painterResource

data class LiveFeedItem(
    val handle: ObjectHandle,
    val metadata: FileMetadata? = null,
    val progress: Int? = null,
    val hasFinished: Boolean = false,
    val savedPath: String? = null,
)

open class LiveFeedModel: BackgroundViewModel() {
    fun openFile() {}
    var storageDevice: StorageInfo? = null
    val items = MutableStateFlow<List<LiveFeedItem>>(emptyList())
    fun setStorageDevice(device: StorageInfo, allowSwitch: Boolean = true) {
        if (!allowSwitch && device.storageName != storageDevice?.storageName) return
        storageDevice = device
    }
    fun setItem(item: LiveFeedItem) {
        items.update { it + item }
    }
    fun updateItem(handle: ObjectHandle, path: String?, hasFinished: Boolean, progress: Int? = null) {
        items.update {
            it.map { item -> if (item.handle == handle) item.copy(savedPath = path, hasFinished = hasFinished, progress = progress) else item }
        }
    }
    fun hasFinished(handle: ObjectHandle): Boolean { return items.value.find { it.handle.index == handle.index }?.hasFinished ?: false }
    fun getItem(handle: ObjectHandle): LiveFeedItem? {
        return items.value.find { it.handle.index == handle.index }
    }
}

@Composable
fun LiveFeed(modifier: Modifier = Modifier, model: LiveFeedModel) {
    val list by model.items.collectAsStateWithLifecycle()
    val storageDevice = model.storageDevice

    Column(modifier.padding(5.dp)) {
        if (storageDevice != null) Text("Downloading ${list.size}/${storageDevice.nFiles}...", modifier = Modifier.padding(5.dp))
        LazyVerticalGrid(modifier = Modifier.fillMaxSize(), columns = GridCells.Fixed(1), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list.reversed()) { obj ->
                Column(Modifier
                    .indication(
                        indication = ripple(),
                        interactionSource = remember { MutableInteractionSource() }
                    )
                    .clickable(onClick = {
                        model.openFile()
                    })
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Row(Modifier.padding(15.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val icon = MimeType.getIcon(obj.metadata?.getMimeType())
                        Icon(
                            tint = MaterialTheme.colorScheme.primary,
                            painter = painterResource(icon),
                            contentDescription = null,
                        )
                        Text(obj.metadata?.filename ?: "", modifier = Modifier.weight(1f))
                        if (obj.metadata?.filesize != null && obj.metadata.filesize != 0) {
                            Text(longToFileSize(obj.metadata.filesize.toLong()))
                        }
                        Spacer(Modifier.weight(1f))
                        if (obj.hasFinished) {
                            Icon(painterResource(Res.drawable.outline_download_done_24), contentDescription = null)
                        }
                    }
                    if (obj.progress != null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), progress = { obj.progress / 100.0f })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, device = "id:pixel_7", uiMode = 32)
@Composable
fun PreviewLiveFeed(navController: NavHostController = rememberNavController()) {
    val model = LiveFeedModel()
    model.setStorageDevice(StorageInfo("downloads", nFiles = 5))
    model.setItem(LiveFeedItem(ObjectHandle(0), FileMetadata("DSC101.JPG"), progress = 100, hasFinished = true))
    model.setItem(LiveFeedItem(ObjectHandle(1), null, 40))
    
    return FudgeTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(),
                    title = {
                        Text("Downloads")
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            navController.navigateUp()
                        }) {
                            Icon(painterResource(Res.drawable.outline_arrow_back_24), contentDescription = null)
                        }
                    },
                )
            },
        ) { innerPadding ->
            LiveFeed(Modifier.padding(innerPadding), model = model)
        }
    }
}