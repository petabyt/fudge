package dev.danielc.common.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import dev.danielc.R
import dev.danielc.common.BackgroundViewModel
import dev.danielc.common.FileHandle
import dev.danielc.common.FileMetadata
import dev.danielc.common.MimeType
import dev.danielc.common.longToFileSize
import dev.danielc.common.ui.theme.FudgeTheme
import dev.danielc.fudge.AndroidRuntime
import dev.danielc.fudge.FileLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import kotlin.math.roundToInt
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

private const val MAX_BUFFER_SIZE = 10 * 1000000

private fun painterToImageBitmap(
    painter: Painter,
    density: Density = Density(1f),
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
): ImageBitmap {
    val size = painter.intrinsicSize
    val bitmap = ImageBitmap(size.width.toInt(), size.height.toInt())
    val canvas = Canvas(bitmap)
    CanvasDrawScope().draw(density, layoutDirection, canvas, size) {
        with(painter) {
            draw(size)
        }
    }
    return bitmap
}

data class ViewerState(
    val handle: FileHandle,
    val numberOfItems: Int,
    val metadata: FileMetadata? = null,
    val isLoading: Boolean = true,
    val isDecoding: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val currentDownloadProgress: Int = 0,
    val currentDownloadSpeed: String? = null,
    val currentDownloadStatusMessage: String? = null,
    val bitmap: ImageBitmap? = null,
    val bitmapLeft: ImageBitmap? = null,
    val bitmapRight: ImageBitmap? = null,
    val showSaveButton: Boolean = true,
    val showLoadDialog: Boolean = true,
    val hasSaved: Boolean = false,
)

open class FileDownloader(val file: FileHandle, val filename: String, val mimeType: String? = null) {
    private var temporaryBuffer: ByteArray? = null
    private var fileHandle: FileLayer.Handle? = null
    private var fileTotalSize: Long? = null
    private var rejectTransfers = false

    private var elapsed = TimeSource.Monotonic.markNow()

    open fun onSaved(handle: FileLayer.Handle) {}
    open fun onAutomaticallySaved() {}
    open fun onFinished(buffer: ByteArray) {}
    open fun onFinished(file: FileLayer.Handle) {}
    open fun updateSpeed(speed: String) {}
    open fun updateProgress(percent: Int) {}

    fun clear() {
        temporaryBuffer = null
        fileHandle = null
        rejectTransfers = false
        fileTotalSize = null
    }

    fun save(): FileLayer.Handle? {
        temporaryBuffer?.let {
            val fd = FileLayer.openFileForWriting(filename, mimeType)
            if (fd == null) {
                println("TODO: Failed to open file")
                return null
            }
            fd.write(it)
            fd.close()
            onSaved(fd)
            return fd
        }
        return null
    }
    fun cleanupAfterCancel() {
        rejectTransfers = true
        fileHandle?.let {
            fileHandle = null
            it.close()
            FileLayer.deleteFile(it)
        }
    }
    open fun setFileContents(data: ByteArray?, offset: Long, totalSize: Long) {
        //println("${data?.size}, ${offset}, ${totalSize}")
        var ms = elapsed.elapsedNow().toInt(DurationUnit.MILLISECONDS)
        if (ms == 0) ms++
        updateSpeed("${((data?.size ?: 0) / ms) / 1000.0}MB/s")
        elapsed = TimeSource.Monotonic.markNow()

        if (totalSize != 0L && data != null) {
            updateProgress((((data.size + offset).toFloat() / totalSize) * 100).toInt())
        }

        if (rejectTransfers) return
        val temporaryBufferRef = temporaryBuffer
        if (temporaryBufferRef == null) {
            temporaryBuffer = data
            if (totalSize != 0L) {
                fileTotalSize = totalSize
                if (data != null && (data.size.toLong() >= totalSize)) {
                    onFinished(data)
                    return
                }
            }
            return
        }

        if (data == null || data.isEmpty()) {
            if (fileHandle == null) {
                onFinished(temporaryBufferRef)
            } else {
                fileHandle!!.close()
                onFinished(fileHandle!!)
            }
            return
        }

        // Automatically route to file if too large
        if (temporaryBufferRef.size + data.size > MAX_BUFFER_SIZE || totalSize > MAX_BUFFER_SIZE && fileHandle == null) {
            fileHandle = FileLayer.openFileForWriting(filename, mimeType)
            if (fileHandle == null) {
                println("Rejecting transfers")
                rejectTransfers = true
                // TODO: set isLoading to false
                return
            } else {
                onAutomaticallySaved()
                fileHandle?.write(temporaryBufferRef)
            }
        }

        if (fileHandle != null) {
            fileHandle?.write(data)
        } else {
            temporaryBuffer = temporaryBufferRef + data
        }

        if (data.size + offset >= totalSize) {
            if (fileHandle == null) {
                onFinished(temporaryBuffer!!)
            } else {
                fileHandle!!.close()
                onFinished(fileHandle!!)
            }
        }
    }
}

class ViewerModel(val showSaveButton: Boolean = true, val showLoadDialog: Boolean = true) : BackgroundViewModel() {
    private val _viewerState = MutableStateFlow<ViewerState?>(null)
    val viewerState = _viewerState.asStateFlow()

    fun clear() {
        _viewerState.value = null
    }

    fun loadImage(buffer: ByteArray) {
        _viewerState.update { it?.copy(isDecoding = true) }
        val bitmap = AndroidRuntime.decodeImageContents(buffer, _viewerState.value?.metadata?.orientation)
        if (bitmap == null) {
            setError("Failed to decode image contents")
        } else {
            _viewerState.update { viewerState ->
                viewerState?.copy(
                    bitmap = bitmap,
                    isDecoding = false,
                    isLoading = false,
                )
            }
        }
    }
    fun loadImage(file: FileLayer.Handle) {
        setHasSaved(true)
        _viewerState.update { it?.copy(isDecoding = true) }
        val bitmap = AndroidRuntime.decodeImageFile(file, _viewerState.value?.metadata?.orientation)
        if (bitmap == null) {
            setError("Failed to decode image contents")
        }
        _viewerState.update { viewerState ->
            viewerState?.copy(
                bitmap = bitmap,
                isDecoding = false,
                isLoading = false,
            )
        }
    }

    fun update(file: FileHandle, numberOfItems: Int, left: ImageBitmap? = null, main: ImageBitmap? = null, right: ImageBitmap? = null) {
        _viewerState.update {
            ViewerState(
                handle = file,
                numberOfItems = numberOfItems,
                showSaveButton = showSaveButton,
                showLoadDialog = showLoadDialog,
                bitmap = main, bitmapLeft = left, bitmapRight = right,
            )
        }
    }
    fun updateMetadata(metadata: FileMetadata?) {
        val filename = metadata?.filename
        val saved = if (filename != null) FileLayer.doesFileExist(filename) else false
        _viewerState.update { viewerState ->
            viewerState?.copy(
                metadata = metadata,
                hasSaved = saved
            )
        }
    }
    fun updateThumbnails(left: ImageBitmap?, main: ImageBitmap?, right: ImageBitmap?) {
        _viewerState.update { viewerState ->
            viewerState?.copy(
                bitmap = main,
                bitmapLeft = left,
                bitmapRight = right,
            )
        }
    }
    fun updateProgress(downloadPercent: Int) {
        _viewerState.update { it?.copy(currentDownloadProgress = downloadPercent) }
    }
    fun updateSpeed(downloadSpeed: String) {
        _viewerState.update { it?.copy(currentDownloadSpeed = downloadSpeed) }
    }
    fun updateStatus(status: String?) {
        _viewerState.update { it?.copy(currentDownloadStatusMessage = status) }
    }
    fun setError(message: String) {
        _viewerState.update { it?.copy(isError = true, errorMessage = message) }
    }
    fun setHasSaved(v: Boolean = true) {
        _viewerState.update { it?.copy(hasSaved = v) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Viewer(modifier: Modifier = Modifier, state: ViewerState, switchTo: (Int) -> Unit, close: () -> Unit, cancel: () -> Unit) {
    val filename = state.metadata?.filename ?: "File"

    if (state.isError) {
        AlertDialog(
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            title = {
                Text(text = "Error", color = MaterialTheme.colorScheme.onErrorContainer)
            },
            text = {
                Text(state.errorMessage ?: "Unknown error", color = MaterialTheme.colorScheme.onErrorContainer)
            },
            onDismissRequest = {
                close()
            },
//            dismissButton = {
//                TextButton(
//                    onClick = {
//                        close()
//                    }
//                ) {
//                    Text("Open in default app")
//                }
//            },
            confirmButton = {
                TextButton(
                    onClick = {
                        close()
                    }
                ) {
                    Text("Exit")
                }
            }
        )
    } else if (state.isLoading && state.showLoadDialog) {
        val text = "Downloading " + when (MimeType.fromString(state.metadata?.mimeType)) {
            MimeType.JPEG, MimeType.PNG -> "image"
            MimeType.MOV -> "movie"
            else -> "file"
        }

        Dialog(onDismissRequest = { cancel() }, properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false) ) {
            Card(modifier = Modifier
                .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Box {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(text, style = MaterialTheme.typography.titleLarge)
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            progress = { state.currentDownloadProgress.toFloat() / 100 }
                        )
                        Row(Modifier.fillMaxWidth()) {
                            if (state.currentDownloadStatusMessage != null) {
                                Text(state.currentDownloadStatusMessage, style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f))
                            } else { Spacer(Modifier.weight(1f)) }
                            if (state.currentDownloadSpeed != null) {
                                Text(state.currentDownloadSpeed, style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.tertiary)
                            } else { Spacer(Modifier.weight(1f)) }
                        }
                        Button(onClick = {
                            cancel()
                        }) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }

    var showingInfoModal by remember { mutableStateOf(false) }
    val scaleFactor = remember { Animatable(1f) }
    val imageYOffset = remember { Animatable(0.dp, Dp.VectorConverter) }
    val imageXOffset = remember { Animatable(0.dp, Dp.VectorConverter) }

    // - Swipe image down to exit the viewer screen
    // - Swipe up to show info modal
    val scope = rememberCoroutineScope()
    val screenHeightDp = LocalWindowInfo.current.containerDpSize.height
    val minOffsetToClose = screenHeightDp / 14
    val swipeToCloseGesture = Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDrag = { change, dragAmount ->
                change.consume()
                scope.launch {
                    launch {
                        imageYOffset.snapTo(imageYOffset.value + dragAmount.y.toDp())
                    }
                    if ((imageYOffset.value.toPx() + dragAmount.y) >= 0) {
                        launch {
                            imageXOffset.snapTo(imageXOffset.value + dragAmount.x.toDp())
                        }
                        launch {
                            scaleFactor.animateTo((1f - (imageYOffset.value / 500.dp)).coerceIn(0.5f, 1f))
                        }
                    }
                }
            },
            onDragEnd = {
                if (imageYOffset.value > 0.dp && imageYOffset.value >= minOffsetToClose) {
                    close()
                    showingInfoModal = false
                } else if (!showingInfoModal && imageYOffset.value < 0.dp && imageYOffset.value <= -minOffsetToClose) {
                    showingInfoModal = true
                    scope.launch {
                        launch {
                            // TODO: this is a completely arbitrary offset
                            imageYOffset.animateTo(-(screenHeightDp / 4))
                        }
                        launch {
                            imageXOffset.animateTo(0.dp)
                        }
                    }
                } else {
                    showingInfoModal = false
                    scope.launch {
                        launch {
                            scaleFactor.animateTo(1f)
                        }
                        launch {
                            imageYOffset.animateTo(0.dp)
                        }
                        launch {
                            imageXOffset.animateTo(0.dp)
                        }
                    }
                }
            }
        )
    }

    // Render the bottom sheet independently, bring it up with the image
    val scaffoldState = rememberBottomSheetScaffoldState()
    if (imageYOffset.value < 0.dp) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState, sheetContent = {
                Column(Modifier.padding(15.dp)) {
                    Text("${state.metadata?.filename}")
                    Text("${state.metadata?.height}x${state.metadata?.height}")
                    Text(longToFileSize(state.metadata?.filesize?.toLong() ?: 0L))
                }
            }, modifier = modifier,
            sheetPeekHeight = if (imageYOffset.value < 0.dp) -imageYOffset.value else 0.dp
        ) {}
    }

    Box(modifier = modifier
        .fillMaxSize()
        .then(swipeToCloseGesture)
    ) {
        val pagerState = rememberPagerState(initialPage = state.handle.index, pageCount = {
            state.numberOfItems
        })
        LaunchedEffect(state) {
            snapshotFlow { pagerState.currentPage }.collect { page ->
                if (page != state.handle.index) {
                    switchTo(page)
                }
            }
        }

        val imgModifier = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scaleFactor.value
            scaleY = scaleFactor.value
        }
        .offset {
            IntOffset(imageXOffset.value.toPx().roundToInt(), imageYOffset.value.toPx().roundToInt())
        }

        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            if (page == state.handle.index) {
                if (state.bitmap != null) {
                    val zoomState = rememberZoomState(contentSize = Size(state.bitmap.width.toFloat(), state.bitmap.height.toFloat()))
                    Image(
                        modifier = imgModifier.zoomable(zoomState),
                        bitmap = state.bitmap,
                        contentDescription = filename,
                    )
                }
            } else if (page == state.handle.index - 1 && state.bitmapLeft != null) {
                Image(
                    modifier = imgModifier,
                    bitmap = state.bitmapLeft,
                    contentScale = ContentScale.FillWidth,
                    contentDescription = filename,
                )
            } else if (page == state.handle.index + 1 && state.bitmapRight != null) {
                Image(
                    modifier = imgModifier,
                    bitmap = state.bitmapRight,
                    contentScale = ContentScale.FillWidth,
                    contentDescription = filename,
                )
            } else {
                Image(
                    modifier = Modifier.align(Alignment.Center),
                    painter = painterResource(R.drawable.baseline_question_mark_24),
                    contentDescription = null,
                )
            }
        }
    }
}

@Preview(showBackground = true, device = "id:pixel_7", uiMode = 32)
@Composable
fun PreviewViewer(navController: NavController = rememberNavController()) {
    val painter = painterResource(R.drawable.image)
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ViewerState(
        handle = FileHandle(index = 10, "sdcard"),
        metadata = FileMetadata("DSCF00001.JPG", mimeType = MimeType.JPEG.mediaTypeString),
        bitmap = painterToImageBitmap(painter),
        bitmapLeft = painterToImageBitmap(painter),
        bitmapRight = painterToImageBitmap(painter),
        isLoading = false,
        currentDownloadSpeed = "5 mbps",
        currentDownloadProgress = 40,
        numberOfItems = 30,
        isError = false,
        currentDownloadStatusMessage = "Switching...",
        errorMessage = "BUG: Failed to decode, blah blah blah",
    )) }
    ViewerScreen(state, switchTo = { i ->
        state = state.copy(
            handle = state.handle.copy(index = i),
            currentDownloadProgress = 0,
            isLoading = true
        )
        scope.launch {
            while (state.currentDownloadProgress < 100) {
                state = state.copy(currentDownloadProgress = state.currentDownloadProgress + 1)
                delay(1)
            }
            state = state.copy(isLoading = false)
        }
    }, close = {
        navController.navigateUp()
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(state: ViewerState?, switchTo: (Int) -> Unit, close: () -> Unit, cancel: () -> Unit = {}, save: () -> Unit = {}, share: () -> Unit = {}) {
    return FudgeTheme {
        BackHandler {
            close()
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(),
                    title = {
                        Text(state?.metadata?.filename ?: "File")
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            close()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.outline_arrow_back_24),
                                contentDescription = null
                            )
                        }
                    },
                    actions = {
                        if (state != null) {
                            if (state.showSaveButton) {
                                IconButton(onClick = save, enabled = !state.hasSaved) {
                                    Icon(
                                        painter = painterResource(R.drawable.outline_save_24),
                                        contentDescription = "Save"
                                    )
                                }
                            }
                            IconButton(onClick = share) {
                                Icon(
                                    painter = painterResource(R.drawable.baseline_share_24),
                                    contentDescription = "Share"
                                )
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            if (state != null) {
                Viewer(Modifier.padding(innerPadding), state,
                    switchTo = switchTo,
                    close = close,
                    cancel = cancel
                )
            }
        }
    }
}