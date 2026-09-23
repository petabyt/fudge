package dev.danielc.common.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.danielc.R
import dev.danielc.common.BackgroundViewModel
import dev.danielc.common.FileHandle
import dev.danielc.common.FileMetadata
import dev.danielc.common.MimeType
import dev.danielc.common.SortBy
import dev.danielc.common.StorageInfo
import dev.danielc.common.longToFileSize
import dev.danielc.common.ui.theme.FudgeRippleConfig
import dev.danielc.common.ui.theme.FudgeTheme
import dev.danielc.fudge.AndroidRuntime
import dev.danielc.fudge.FileLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

private fun bitmapFromColor(
    color: Color,
    width: Int = 512,
    height: Int = 400
): ImageBitmap {
    val imageBitmap = ImageBitmap(width, height)
    val drawScope = CanvasDrawScope()
    drawScope.draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(imageBitmap),
        size = Size(width.toFloat(), height.toFloat())
    ) {
        drawRect(color = color)
    }

    return imageBitmap
}

enum class DisplayType {
    THUMBNAILS,
    VERTICAL_TABLE,
}

data class GalleryObject(
    val metadata: FileMetadata? = null,
    val thumbnail: ImageBitmap? = null,
    var lastChecked: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow(),
    var invalidMetadata: Boolean = false,
    var invalidThumbnail: Boolean = false,
    val hasSaved: Boolean = false,
)

data class GalleryObjectReference(
    val index: Int,
)

data class FilesystemState(
    val storageName: String? = null,
    val userSortBy: SortBy = SortBy.NEWEST_FIRST,
    val objectListSortedOrder: SortBy = SortBy.NEWEST_FIRST,
    val displayType: DisplayType = DisplayType.THUMBNAILS,
    // object is null if it hasn't been loaded/checked yet
    val objects: List<GalleryObject?> = emptyList(),
    val sortedList: List<Int> = emptyList(),
    val queue: ArrayDeque<GalleryObjectReference> = ArrayDeque()
)

abstract class GalleryViewModel(val checkFileSaved: Boolean = true, var isThumbnailPriority: Boolean = true) : BackgroundViewModel() {
    private val _uiState = MutableStateFlow(FilesystemState())
    val uiState = _uiState.asStateFlow()
    private var thread: Job? = null
    private var threadIsPaused: Boolean = true
    private val queueMutex = Mutex()

    override fun onShutdown() {
        stop()
    }

    fun getMetadata(file: FileHandle): FileMetadata? {
        return _uiState.value.objects.getOrNull(file.index)?.metadata
    }
    fun getThumbnail(file: FileHandle, offset: Int): ImageBitmap? {
        return _uiState.value.objects.getOrNull(file.index + offset)?.thumbnail
    }

    open fun onRefresh() {}
    open fun onShare(ref: GalleryObjectReference) {}
    open fun itemClicked(ref: GalleryObjectReference) {}
    open fun init() {}
    abstract fun fulfillThumbnail(file: GalleryObjectReference)
    abstract fun fulfillMetadata(file: GalleryObjectReference)

    companion object {
        fun objectIsFulfilled(obj: GalleryObject?): Boolean {
            if (obj == null) return false
            return ((obj.metadata != null || obj.invalidMetadata) && (obj.thumbnail != null || obj.invalidThumbnail))
        }
    }

    fun setSortBy(sort: SortBy) {
        _uiState.update { it.copy(userSortBy = sort) }
        sortObjectList()
    }

    private fun sortObjectList() {
        if (_uiState.value.userSortBy != _uiState.value.objectListSortedOrder) {
            _uiState.update {
                it.copy(
                    sortedList = (List(it.objects.size) { index -> it.objects.size - 1 - index })
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    sortedList = (List(it.objects.size) { index -> index })
                )
            }
        }
    }

    private fun checkObject(obj: GalleryObject?, ref: GalleryObjectReference): Boolean {
        if (obj == null) {
            if (isThumbnailPriority) {
                fulfillThumbnail(ref)
            } else {
                fulfillMetadata(ref)
            }
        } else {
            obj.lastChecked = TimeSource.Monotonic.markNow()
            if (obj.thumbnail == null && !obj.invalidThumbnail) {
                fulfillThumbnail(ref)
            } else if (obj.metadata == null && !obj.invalidMetadata) {
                fulfillMetadata(ref)
            } else {
                return false
            }
        }
        return true
    }

    // Returns true when work was done
    private suspend fun tick(): Boolean {
        val queue = _uiState.value.queue
        val objects = _uiState.value.objects
        if (queue.isEmpty()) {
            // Iterate all recently checked objects fulfill them more (recently seen by user, should be priority)
            val newest = objects.sortedBy { it?.lastChecked }
            for (i in newest.indices) {
                if (checkObject(objects[i], GalleryObjectReference(i))) return true
            }
            return false
        }
        val ref = queueMutex.withLock {
            queue.removeLast()
        }
        if (ref.index >= objects.size || ref.index < 0) return false
        val obj = objects[ref.index]
        return checkObject(obj, ref)
    }

    fun start() {
        threadIsPaused = false
        if (thread == null) {
            thread = CoroutineScope(Dispatchers.IO).launch {
                val thread = thread
                while (thread != null && !thread.isCancelled) {
                    if (!threadIsPaused) {
                        if (tick()) continue
                    }
                    try {
                        delay(100)
                    } catch (_: CancellationException) {
                        break
                    }
                }
            }
        }
    }

    fun setPaused(v: Boolean) {
        threadIsPaused = v
    }

    fun stop() {
        setPaused(true)
        thread?.cancel()
        thread = null
    }

    fun reset() {
        CoroutineScope(Dispatchers.IO).launch {
            _uiState.update { currentState ->
                currentState.copy(objects = mutableListOf())
            }
        }
    }

    fun setProperties(info: StorageInfo) {
        CoroutineScope(Dispatchers.IO).launch {
            _uiState.update { currentState ->
                val list = currentState.objects.toMutableList()
                while (list.size < info.nFiles) list.add(null)
                currentState.copy(
                    objects = list,
                    storageName = info.name,
                    objectListSortedOrder = info.itemsSortedBy,
                )
            }
            sortObjectList()
        }
    }

    private fun updateObject(i: Int, block: (GalleryObject) -> GalleryObject) {
        CoroutineScope(Dispatchers.IO).launch {
            _uiState.update { currentState ->
                val list = currentState.objects.toMutableList()
                while (list.size <= i) list.add(null)
                val obj = list[i] ?: GalleryObject()
                list[i] = block(obj)
                currentState.copy(objects = list)
            }
            sortObjectList()
        }
    }

    fun updateThumbnail(i: Int, thumbData: ByteArray? = null) {
        updateThumbnail(i, if (thumbData != null) AndroidRuntime.decodeImageContents(thumbData, null) else null)
    }
    fun setHasSaved(i: Int, v: Boolean) {
        updateObject(i) { it.copy(hasSaved = v) }
    }
    fun updateMetadata(i: Int, md: FileMetadata? = null) {
        updateObject(i) { obj ->
            if (md == null) {
                obj.copy(metadata = null, invalidMetadata = true)
            } else {
                val filename = md.filename
                val saved = if (filename != null && checkFileSaved) FileLayer.doesFileExist(filename) else false
                obj.copy(metadata = md, hasSaved = saved)
            }
        }
    }
    fun updateThumbnail(i: Int, thumb: ImageBitmap? = null) {
        updateObject(i) { obj ->
            if (thumb == null) {
                obj.copy(
                    thumbnail = null,
                    invalidThumbnail = true,
                )
            } else {
                obj.copy(thumbnail = thumb)
            }
        }
    }
    fun enqueueObjects(indexes: List<Int>) {
        CoroutineScope(Dispatchers.IO).launch {
            queueMutex.withLock {
                for (i in indexes) {
                    _uiState.value.queue.removeIf { it.index == i }
                    val newIndex = _uiState.value.sortedList.getOrNull(i)
                    if (newIndex != null) {
                        _uiState.value.queue.addLast(GalleryObjectReference(newIndex))
                    }
                }
            }
        }
    }
    override fun onTrimMemory() {
        val nObjectsToFree = 10
        if (_uiState.value.objects.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            _uiState.update { currentState ->
                // Sort objects by oldest and deref the thumbnail
                val list = currentState.objects.toMutableList()
                val oldest = _uiState.value.objects.sortedByDescending { it?.lastChecked }
                var nFreed = 0
                for (e in oldest) {
                    if (e != null && e.thumbnail == null) {
                        val index = list.indexOf(e)
                        list[index] = list[index]?.copy(thumbnail = null)
                        nFreed++
                    }
                    if (nFreed >= nObjectsToFree) break else nFreed++
                }
                currentState.copy(objects = list)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryThumbnail(obj: GalleryObject?, onClick: () -> Unit = {}, scale: Float = 1f) {
    val boxModifier = Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainer)
    CompositionLocalProvider(LocalRippleConfiguration provides FudgeRippleConfig(Color.White)) {
        Box(
            boxModifier
            .combinedClickable(
                onClick = {
                    onClick()
                },
                onLongClick = {
                    onClick()
                }
            )
            .indication(
                indication = ripple(),
                interactionSource = remember { MutableInteractionSource() }
            )
            .graphicsLayer(clip = true)
        ) {
            if (obj != null) {
                val icon = MimeType.getIcon(obj.metadata?.getMimeType())
                if (obj.thumbnail != null) {
                    Image(modifier = Modifier.fillMaxSize().graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                    ), bitmap = obj.thumbnail, contentDescription = null, contentScale = ContentScale.Crop)
                }
                if (obj.thumbnail == null || obj.metadata?.getMimeType()?.isVideo() ?: false) {
                    Icon(
                        modifier = Modifier.align(Alignment.Center).size(45.dp),
                        painter = painterResource(icon),
                        contentDescription = null,
                    )
                }
                val filename = obj.metadata?.filename
                if (filename != null) {
                    Text(filename, modifier = Modifier.align(Alignment.BottomCenter)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
                            .padding(horizontal = 4.dp),
                        lineHeight = TextUnit(10f, TextUnitType.Sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                if (obj.hasSaved) {
                    Icon(
                        painterResource(R.drawable.outline_download_done_24),
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.TopEnd).size(15.dp) .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)).padding(2.dp)
                    )
                }
            }
            if (!GalleryViewModel.objectIsFulfilled(obj)) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(35.dp), color = ProgressIndicatorDefaults.linearColor.copy(0.5f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryFile(obj: GalleryObject?, onClick: () -> Unit = {}) {
    if (obj == null) return
    CompositionLocalProvider(LocalRippleConfiguration provides FudgeRippleConfig(Color.White)) {
        Box(
            Modifier
                .combinedClickable(
                    onClick = {
                        onClick()
                    },
                    onLongClick = {

                    }
                )
                .indication(
                    indication = ripple(),
                    interactionSource = remember { MutableInteractionSource() }
                )
                .padding(2.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val icon = MimeType.getIcon(obj.metadata?.getMimeType())
                Icon(
                    tint = MaterialTheme.colorScheme.primary,
                    painter = painterResource(icon),
                    contentDescription = null,
                )
                if (obj.metadata?.filename != null) {
                    Text(obj.metadata.filename!!, modifier = Modifier.weight(1f))
                }
                if (obj.metadata?.filesize != null) {
                    Text(longToFileSize(obj.metadata.filesize.toLong()))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Gallery(modifier: Modifier = Modifier, state: FilesystemState, requestLoad: (List<Int>) -> Unit = {}, onItemClick: (Int) -> Unit = {}, onRefresh: () -> Unit = {}, setSortBy: (SortBy) -> Unit = {}) {
    val haptic = LocalHapticFeedback.current
    var isRefreshing by remember { mutableStateOf(false) }
    var displayType by rememberSaveable { mutableStateOf(DisplayType.THUMBNAILS) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var rows by rememberSaveable { mutableIntStateOf(4) }
    @Composable
    fun menu() {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxWidth().padding(2.dp),
        ) {
            if (state.storageName != null) {
                Box(Modifier.padding(4.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))) {
                    Text(state.storageName, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(6.dp))
                }
            }
            Spacer(Modifier.weight(0.5f))
            IconButton(onClick = {
                displayType = if (displayType == DisplayType.THUMBNAILS) DisplayType.VERTICAL_TABLE else DisplayType.THUMBNAILS
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }, modifier = Modifier) {
                Icon(
                    modifier = Modifier.size(27.dp),
                    tint = MaterialTheme.colorScheme.onBackground,
                    painter = if (displayType == DisplayType.THUMBNAILS) painterResource(R.drawable.baseline_view_list_24) else painterResource(R.drawable.baseline_grid_view_24),
                    contentDescription = null
                )
            }
            IconButton(onClick = {
                setSortBy(if (state.userSortBy == SortBy.NEWEST_FIRST) SortBy.OLDEST_FIRST else SortBy.NEWEST_FIRST)
            }, modifier = Modifier) {
                Icon(
                    modifier = Modifier.size(27.dp),
                    tint = MaterialTheme.colorScheme.onBackground,
                    painter = painterResource(R.drawable.outline_sort_24),
                    contentDescription = null
                )
            }
        }
    }

    // Scale thumbnails on zoom gesture
    var scale by remember { mutableFloatStateOf(1f) }
    var isZooming by remember { mutableStateOf(false) }
    val gesture = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                if (event.changes.count { it.pressed } == 2) {
                    isZooming = true
                    scale = (scale * event.calculateZoom()).coerceIn(0f, 2f)
                    event.changes.forEach { it.consume() }
                } else if (isZooming) {
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (isZooming) {
                if (scale > 1f) {
                    if (rows > 1) rows--
                } else {
                    rows++
                }
                scale = 1f
                isZooming = false
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().then(gesture)) {
        val listState = rememberLazyGridState()
        PullToRefreshBox(
            state = rememberPullToRefreshState(),
            isRefreshing = isRefreshing,
            onRefresh = {
                if (!isZooming) {
                    CoroutineScope(Dispatchers.IO).launch {
                        isRefreshing = true
                        onRefresh()
                        refreshTrigger++
                        isRefreshing = false
                    }
                }
            },
        ) {
            val rows = if (displayType == DisplayType.THUMBNAILS) rows else 1
            LazyVerticalGrid(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                columns = GridCells.Fixed(rows)
            ) {
                item(span = { GridItemSpan(rows) }) {
                    menu()
                }

                if (state.objects.isNotEmpty()) {
                    itemsIndexed(state.sortedList.toList()) { _, entry ->
                        val obj = state.objects.getOrNull(entry)
                        if (displayType == DisplayType.THUMBNAILS) {
                            GalleryThumbnail(obj, onClick = {
                                onItemClick(entry)
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            }, scale = scale)
                        } else {
                            GalleryFile(obj, onClick = {
                                onItemClick(entry)
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            })
                        }
                    }
                } else {
                    item(span = { GridItemSpan(rows) }) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text("No files are present.")
                        }
                    }
                }
            }

            // Monitor recently viewed items so they can be sent to the queue
            LaunchedEffect(listState) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
                    .distinctUntilChanged()
                    .collect { visibleItems ->
                        requestLoad(visibleItems)
                    }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, device = "id:pixel_7", uiMode = 32)
@Composable
fun PreviewGalleryScreen(navController: NavHostController = rememberNavController()) {
    val state = FilesystemState(objects = mutableListOf(
        GalleryObject(FileMetadata("DCIM/", mimeType = MimeType.FOLDER.mediaTypeString), hasSaved = true),
        GalleryObject(FileMetadata("DSC1111.JPG", mimeType = MimeType.JPEG.mediaTypeString), thumbnail = bitmapFromColor(Color.Red)),
        GalleryObject(FileMetadata("DSC1234.MOV", mimeType = MimeType.MOV.mediaTypeString), thumbnail = bitmapFromColor(Color.Green)),
        GalleryObject(FileMetadata(), thumbnail = bitmapFromColor(Color.Cyan)),
        GalleryObject(FileMetadata(), thumbnail = bitmapFromColor(Color.Magenta)),
        GalleryObject(FileMetadata(), thumbnail = bitmapFromColor(Color.Yellow, width = 300)),
        null,
        null,
        null,
        GalleryObject(FileMetadata(), thumbnail = bitmapFromColor(Color.Gray)),
        GalleryObject(FileMetadata(), thumbnail = bitmapFromColor(Color.LightGray)),
        GalleryObject(FileMetadata(), thumbnail = bitmapFromColor(Color.DarkGray, width = 250)),
        GalleryObject(FileMetadata("DSC1132.JPG"), thumbnail = bitmapFromColor(Color.Red)),
        GalleryObject(FileMetadata(), thumbnail = bitmapFromColor(Color.Green)),
        GalleryObject(FileMetadata(), thumbnail = bitmapFromColor(Color.Blue)),
        GalleryObject(FileMetadata(), thumbnail = bitmapFromColor(Color.Cyan)),
    ), sortedList = List(40) { it }, storageName = "Card 2")
    return FudgeTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(),
                    title = {
                        Text("Gallery")
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            navController.navigateUp()
                        }) {
                            Icon(painterResource(R.drawable.outline_arrow_back_24), contentDescription = null)
                        }
                    },
                )
            },
        ) { innerPadding ->
            Gallery(Modifier.padding(innerPadding), state)
        }
    }
}