package dev.danielc.fudge

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import com.alexvas.rtsp.codec.VideoDecoderSurfaceThread
import com.alexvas.rtsp.widget.RtspProcessor
import com.limelight.binding.video.MediaCodecHelper
import dev.danielc.common.BackgroundViewModel
import dev.danielc.common.ModuleInstance
import dev.danielc.libpak.Pak
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import dev.danielc.R
import dev.danielc.common.Widget
import dev.danielc.common.screens.LiveviewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class ModuleLiveviewModel(val mod: ModuleInstance): LiveviewModel(), SurfaceHolder.Callback {
    val currentFps = MutableStateFlow(0)
    private var isRtsp: Boolean = false
    private var rtspProcessor: RtspProcessor? = null
    private var currentSurfaceHolder: SurfaceHolder? = null // should be WeakReference?
    private var blockingJob: Job? = null

//    external fun nativeSurfaceCreated(surfaceHolder: SurfaceHolder)
//    external fun nativeSurfaceChanged(holder: SurfaceHolder, i2: Int, width: Int, height: Int)
//    external fun nativeSurfaceDestroyed(holder: SurfaceHolder)

    override fun surfaceCreated(surfaceHolder: SurfaceHolder) {
        updateNative(surfaceHolder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, i2: Int, width: Int, height: Int) {
        updateNative(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d("lv", "surfaceDestroyed")
        setPaused(true)
        runBlocking {
            stopThread()
        }
    }

    fun updateNative(holder: SurfaceHolder) {
        currentSurfaceHolder = holder
        mod.updateNativeLiveview(holder.surface, false)
        val oldJob = blockingJob
        blockingJob = CoroutineScope(Dispatchers.IO).launch {
            oldJob?.join()
            mod.nativeLiveviewThread()
        }
    }
    fun updateRtsp(url: String) {
        isRtsp = true
        rtspProcessor = RtspProcessor(
            onVideoDecoderCreateRequested = {
                    videoMimeType,
                    videoRotation,
                    videoFrameQueue,
                    videoDecoderListener,
                    videoDecoderType,
                    videoFrameRateStabilization,
                ->
                VideoDecoderSurfaceThread(
                    currentSurfaceHolder!!.surface,
                    videoMimeType,
                    1920,
                    1080,
                    videoRotation,
                    videoFrameQueue,
                    videoDecoderListener,
                    videoDecoderType,
                    videoFrameRateStabilization,
                )
            }
        )
        MediaCodecHelper.initialize(Pak.getActivity(), /*glRenderer*/ "")

        rtspProcessor?.init(
            url.toUri(),
            null,
            null,
            null,
            RtspProcessor.DEFAULT_SOCKET_TIMEOUT
        )

        rtspProcessor?.start(requestVideo = true, requestAudio = false, requestApplication = false)
    }
    fun setPaused(v: Boolean) {
        mod.updateNativeLiveview(currentSurfaceHolder?.surface, v)
    }
    suspend fun stopThread() {
        blockingJob?.join()
    }
    fun update(w: Int, h: Int) {
        currentSurfaceHolder?.setFixedSize(720, 480)
    }
    fun clear() {
        rtspProcessor?.stopDecoders()
        rtspProcessor?.stop()
        isRtsp = false
    }

    override fun widgetUpdated(pane: Widget) {
        updateWidget(pane)
        mod.propChanged(pane)
    }
}

@Composable
fun FramebufferSurface(modifier: Modifier = Modifier, model: LiveviewModel) {
    if (model !is ModuleLiveviewModel) {
        Image(
            modifier = modifier,
            painter = painterResource(R.drawable.image),
            contentDescription = null
        )
        return
    }
    AndroidView(modifier = modifier, factory = { ctx ->
        val view = SurfaceView(ctx)
        view.holder.addCallback(model)
        view
    }, update = { view ->

    })
}