package dev.danielc.fudge
import dev.danielc.fudge.Runtime.errorCodeToString
import dev.danielc.fudge.screens.ConnectingRequiredAction
import dev.danielc.fudge.screens.ConnectingScreenModel
import dev.danielc.fudge.screens.ConsoleModel
import dev.danielc.fudge.screens.DashboardModel
import dev.danielc.fudge.screens.FileDownloader
import dev.danielc.fudge.screens.GalleryViewModel
import dev.danielc.fudge.screens.LiveFeedItem
import dev.danielc.fudge.screens.LiveFeedModel
import dev.danielc.fudge.screens.ModuleInstanceModel
import dev.danielc.fudge.screens.ModuleIntervalometerModel
import dev.danielc.fudge.screens.UiEvent
import dev.danielc.fudge.screens.ViewerModel
import dev.danielc.fudge.ui.BackupModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * To note that a Kotlin function is for use through JNI/C
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.BINARY)
annotation class CalledFromNative

typealias JobUpdateCallback = (ModuleJob) -> Unit

/**
 * A job id is passed each time a module function is called. A job can be canceled
 * at any time by the user, and the percent finished value can be updated by the module.
 */
data class ModuleJob(
    val onUpdate: JobUpdateCallback,
    val id: Int,
    var progressBarValue: Int? = null,
    var downloadSpeedString: String? = null,
    var isCancelled: Boolean = false,
    var isFinished: Boolean = false,
)

class ModuleBackupModel(val module: ModuleInstance): BackupModel(module.currentFilesystem) {
    override fun cancelJob(job: ModuleJob) { module.cancelJob(job) }
    override suspend fun fulfillFileMetadata(index: Int) { module.galleryViewModel.fulfillMetadata(ObjectHandle(index)) }
    override suspend fun downloadFile(index: Int, update: (ModuleJob) -> Unit) {
        val handle = ObjectHandle(index, module.galleryViewModel.currentFilesystemStateFlow.value?.storageName)
        module.galleryViewModel.updateDownloader(handle)
        val downloader = module.galleryViewModel.downloader
        module.getFileContents(file = handle, onUpdate = {
            update(it)
            if (it.isFinished && !it.isCancelled) downloader?.save()
        })
    }
    override fun onStart() { module.galleryViewModel.setPaused(true) }
    override fun onStop() { module.galleryViewModel.setPaused(false) }
}

class ModuleLiveFeedModel(val module: ModuleInstance): LiveFeedModel() {
    var downloader: FileDownloader? = null
    var job: Job? = null
    var downloadJob: ModuleJob? = null
    override fun onShutdown() {
        runBlocking {
            job?.cancel()
            job?.join()
        }
    }
    fun start() {
        if (job != null) return
        job = CoroutineScope(Dispatchers.IO).launch {
            items.collectLatest {
                it.lastOrNull()?.let { file ->
                    if (!file.hasFinished) {
                        module.updateStorageDeviceStatus(file.handle.storageName, "Downloading ${file.metadata?.filename}")
                        module.getFileContents(file = file.handle, onUpdate = { job ->
                            module.updateStorageDeviceStatus(file.handle.storageName, null, if (job.isFinished) null else job.progressBarValue)
                            if (job.isFinished) downloadJob = null else downloadJob = job
                        })
                    }
                }
            }
        }
    }
    fun addFileMetadata(file: ObjectHandle, v: FileMetadata?)  {
        setItem(LiveFeedItem(file, v))
    }
    fun setFileContents(file: ObjectHandle, data: ByteArray?, offset: Long, totalSize: Long) {
        val item = getItem(file)
        if (downloader?.file != file) {
            downloader = object : FileDownloader(
                file,
                item?.metadata?.filename ?: "unknown${file.index}.jpg",
                item?.metadata?.mimeType ?: MimeType.JPEG.mediaTypeString,
                module.viewerViewModel.dir
            ) {
                override fun onFinished(file: FileLayer.Handle) {
                    module.updateStorageDeviceStatus(this.file.storageName, "Finished downloading")
                    downloader = null
                }
                override fun onFinished(buffer: ByteArray) {
                    module.updateStorageDeviceStatus(this.file.storageName, "Finished downloading")
                    downloader = null
                    save()
                }
                override fun onSaved(handle: FileLayer.Handle) {
                    updateItem(file, handle.getPath(), true)
                    module.addDownloadedItem(handle.getPath(), null)
                }
            }
        }
        downloader?.setFileContents(data, offset, totalSize)
    }
}

class ModuleGalleryViewModel(val module: ModuleInstance, val viewerViewModel: ViewerModel): GalleryViewModel(currentFilesystem = module.currentFilesystem) {
    var downloader: FileDownloader? = null
    var viewerDownloadJob: ModuleJob? = null
    override suspend fun fulfillThumbnail(file: ObjectHandle) {
        if (module.getFileThumbnail(file = file).failed) {
            updateThumbnail(file, thumbData = null)
        }
    }

    override suspend fun fulfillMetadata(file: ObjectHandle) {
        if (module.getFileMetadata(file = file).failed) {
            updateMetadata(file, null)
        }
    }

    override suspend fun toggleStorageDevice() {
        filesystemMutex.withLock {
            for (dev in module.storageDevices.value) {
                if (dev.storageName != module.currentFilesystem.value?.storageName) {
                    // TODO: Stow cached thumbnails/metadata in module
                    module.switchStorageDevice({}, dev.storageName)
                    switchStorageDevice(dev)
                }
            }
        }
    }

    fun updateDownloader(file: ObjectHandle) {
        val md = module.galleryViewModel.getMetadata(file)
        downloader = object : FileDownloader(file, md?.filename ?: "unknown${file.index}.jpg", MimeType.JPEG.mediaTypeString, module.viewerViewModel.dir) {
            private var isNotUpdatingDownloadSpeed: Boolean = false
            override fun onFinished(buffer: ByteArray) {
                module.viewerViewModel.loadImage(buffer)
            }
            override fun onFinished(file: FileLayer.Handle) {
                module.viewerViewModel.loadImage(file)
            }
            override fun onSaved(handle: FileLayer.Handle) {
                module.viewerViewModel.setHasSaved(true)
                module.galleryViewModel.setHasSaved(file, true)
                module.addDownloadedItem(handle.getPath(), null)
            }
            override fun onAutomaticallySaved() {
                module.viewerViewModel.updateStatus("File too big; saving...")
            }
            override fun setFileContents(data: ByteArray?, offset: Long, totalSize: Long) {
                if ((offset == 0L || isNotUpdatingDownloadSpeed) && data != null) {
                    if (!isNotUpdatingDownloadSpeed) if (viewerViewModel.viewerState.value?.currentDownloadSpeed == null) isNotUpdatingDownloadSpeed = true
                }
                super.setFileContents(data, offset, totalSize)
            }
            override fun updateSpeed(speed: String) {
                viewerViewModel.updateSpeed(speed)
            }
        }
    }

    fun goToViewer(file: ObjectHandle) {
        CoroutineScope(Dispatchers.IO).launch {
            val galleryState = currentFilesystemStateFlow.value
            viewerViewModel.clear()
            viewerViewModel.update(file, galleryState?.objects?.size ?: 0, getThumbnail(file, -1), getThumbnail(file, 0), getThumbnail(file, 1))
            viewerViewModel.updateMetadata(getMetadata(file))

            fun onCancel() {
                CoroutineScope(Dispatchers.IO).launch {
                    module.goBack(Screen.FILE_GALLERY, false)
                }
            }

            viewerViewModel.updateStatus("Switching to viewer...")
            var rc = module.switchScreen(Screen.FILE_VIEWER, false, { job ->
                viewerDownloadJob = job
                if (job.isFinished) {
                    viewerDownloadJob = null
                }
                viewerViewModel.updateProgress(job.progressBarValue ?: 0)
            })
            viewerViewModel.updateStatus(null)

            if (rc == PakReturnCode.CANCELLED) {
                onCancel()
            } else {
                if (getMetadata(file) == null) {
                    viewerViewModel.updateStatus("Grabbing metadata...")
                    module.getFileMetadata(file = file)
                }
                updateDownloader(file)
                viewerViewModel.updateStatus("Downloading...")
                rc = module.getFileContents({ job ->
                    viewerDownloadJob = job
                    if (job.isCancelled) {
                        viewerViewModel.updateStatus("Cancelling...")
                    }
                    viewerViewModel.updateProgress(job.progressBarValue ?: 0)
                    if (job.isFinished) {
                        viewerDownloadJob = null
                    }
                }, file)
                if (rc == PakReturnCode.CANCELLED) {
                    onCancel()
                } else if (rc.failed) {
                    viewerViewModel.setError("Image download error: ${errorCodeToString(rc)}")
                } else if (viewerViewModel.viewerState.value?.isLoading == true) {
                    viewerViewModel.setError("BUG: Image not loaded entirely by module")
                }
            }
        }
    }
}

class ModuleDashboardModel(val module: ModuleInstance) : DashboardModel(module.manifest, storageDevices = module.storageDevices.asStateFlow()) {
    override fun disconnect() {
        scope.launch {
            module.homeModelView.showDisconnectDialog(true)
        }
    }
    override fun propChanged(pane: Widget) {
        CoroutineScope(Dispatchers.IO).launch {
            module.propChanged(pane)
        }
    }
    override fun runCommand(line: String) {
        CoroutineScope(Dispatchers.IO).launch {
            module.runCommand(line)
        }
    }
    override fun save() {
        module.userSave()
    }
    override suspend fun onStorageDeviceClicked(name: String) {
        val dev = module.storageDevices.value.find { it.storageName == name } ?: return
        if (dev.type == PakStorageType.PAK_LIVE_QUEUE) {
            module.switchScreen(Screen.LIVE_FEED, isInNavBar = true)
        } else {
            module.switchScreen(Screen.FILE_GALLERY, isInNavBar = true)
        }
    }
}

class ModuleConnectingScreenModel(val module: ModuleInstance) : ConnectingScreenModel(module.debugLogModel) {
    var lastWiFiApFilter: WiFi.ApFilter? = null
    var lastWiFiSetupOption: String? = null
    var isSecondaryConnection = false
    var secondaryConnectionJob: Job? = null
    var lastWiFiAdapter: WiFi.Adapter? = null
    override fun onTryAgain() {
        if (isSecondaryConnection) {
            if (secondaryConnectionJob?.isCompleted ?: true) {
                module.addWiFiConnection(lastWiFiApFilter ?: return, lastWiFiSetupOption ?: return, gotoScreen = false)
            }
        } else {
            if (module.initJob?.isCompleted ?: true) {
                module.initThread()
            }
        }
    }

    override fun onCancel(): Boolean {
        Pak.interruptAll()
        WiFi.interruptAll()
        return true
    }
}

private fun savedDeviceEntityToInfo(saved: SavedDeviceEntity?): SavedDeviceInfo? {
    return if (saved == null) null else SavedDeviceInfo(
        saved.uniqueIdentifier,
        saved.name,
        saved.auxillaryData,
    )
}

abstract class ModuleBase: NativeModule() {
    private var jobs = mutableMapOf<Int, ModuleJob>()
    private val jobsMapMutex = Mutex()
    private var jobCounter = 0
    private var threadPolicy = ModuleThreadPolicy.NO_CONCURRENT_JOBS
    private val jobMutex = Mutex()

    fun cancelAllJobs() {
        jobs.forEach { it.value.isCancelled = true }
    }

    @CalledFromNative
    fun setThreadPolicy(v: Int) {
        threadPolicy = ModuleThreadPolicy.fromCode(v)
    }

    suspend fun createJob(onUpdate: JobUpdateCallback): ModuleJob {
        jobsMapMutex.withLock {
            val id = ++jobCounter
            val job = ModuleJob(
                id = id,
                onUpdate = onUpdate
            )
            jobs.put(id, job)
            return job
        }
    }

    suspend fun closeJob(job: ModuleJob) {
        jobsMapMutex.withLock {
            val key = jobs.entries.find { it.value == job }?.key
            if (key != null) {
                jobs.remove(key)
            }
        }
    }

    fun getJob(job: Int): ModuleJob? {
        return jobs.entries.find { it.key == job }?.value
    }

    fun cancelJob(job: ModuleJob) {
        job.isCancelled = true
    }

    suspend fun withJob(callback: JobUpdateCallback, block: (ModuleJob) -> Int): PakReturnCode {
        val job = createJob(callback)
        var rc = if (threadPolicy == ModuleThreadPolicy.NO_CONCURRENT_JOBS) {
            jobMutex.withLock { PakReturnCode.fromCode(block(job)) }
        } else {
            PakReturnCode.fromCode(block(job))
        }
        if (job.isCancelled) rc = PakReturnCode.CANCELLED
        //;job.progressBarValue = null
        job.isFinished = true
        callback(job)
        closeJob(job)
        return rc
    }
    suspend fun findConnection(onUpdate: JobUpdateCallback = {}): PakReturnCode {
        return withJob(onUpdate) { job ->
            onFindConnection(job.id)
        }
    }
    suspend fun getFileMetadata(onUpdate: JobUpdateCallback = {}, file: ObjectHandle): PakReturnCode {
        return withJob(onUpdate) { job ->
            onRequestFileMetadata(job.id, file)
        }
    }
    suspend fun getFileThumbnail(onUpdate: JobUpdateCallback = {}, file: ObjectHandle): PakReturnCode {
        return withJob(onUpdate) { job ->
            onRequestFileThumbnail(job.id, file)
        }
    }
    suspend fun getFileContents(onUpdate: JobUpdateCallback = {}, file: ObjectHandle): PakReturnCode {
        return withJob(onUpdate) { job ->
            onRequestFileContents(job.id, file)
        }
    }
    suspend fun switchStorageDevice(onUpdate: JobUpdateCallback = {}, name: String): PakReturnCode {
        return withJob(onUpdate) { job ->
            onSwitchStorageDevice(job.id, name)
        }
    }
    suspend fun tryConnectWiFi(net: WiFi.Adapter, saved: SavedDeviceEntity?, onUpdate: JobUpdateCallback = {}): PakReturnCode {
        return withJob(onUpdate) { job ->
            onTryConnectWiFi(net, savedDeviceEntityToInfo(saved), job.id)
        }
    }
    suspend fun tryConnectUSB(device: USB.Device, saved: SavedDeviceEntity?, onUpdate: JobUpdateCallback = {}): PakReturnCode {
        return withJob(onUpdate) { job ->
            onTryConnectUSB(device, savedDeviceEntityToInfo(saved), job.id)
        }
    }
    suspend fun tryConnectBluetooth(device: Bluetooth.Device, saved: SavedDeviceEntity?, onUpdate: JobUpdateCallback = {}): PakReturnCode {
        return withJob(onUpdate) { job ->
            onTryConnectBluetooth(device, savedDeviceEntityToInfo(saved), job.id)
        }
    }
    suspend fun runCommand(cmd: String, vararg params: String): PakReturnCode {
        return withJob({}) { job ->
            val list = listOf(cmd) + params
            onRunCommand(job.id, list.getOrNull(0), list.getOrNull(1), list.getOrNull(2), list.getOrNull(3))
        }
    }
    suspend fun runCommand(cmd: Command): PakReturnCode {
        return runCommand(cmd.cmd)
    }
    suspend fun propChanged(pane: Widget): PakReturnCode {
        return withJob({}) { job ->
            onPropChanged(job.id, pane)
        }
    }
}

/**
 * Serializable ID of connection instance that can be passed between activities
 */
@Serializable
data class ModuleInstanceRequest(
    val manifestName: String,
    val targetIndex: Int,
    val chosenSetupOption: String? = null,
    val savedDeviceUniqueId: String? = null,
    val chosenConnectableDeviceIndex: Int? = null,
) {
    fun getManifest(): ModuleManifest {
        return Runtime.getManifestFromName(manifestName)!!
    }
    fun getTarget(): ModuleManifest.Target {
        return getManifest().targets[targetIndex]
    }
    fun getSetupOption(): ModuleManifest.SetupOption? {
        for (t in getTarget().setupOptions) {
            if (chosenSetupOption == t.name) {
                return t
            }
        }
        return null
    }
    fun getConnectableDevice(): ConnectableDevice? { return Runtime.connectableDevices.value.getOrNull(chosenConnectableDeviceIndex ?: return null) }
    fun getSavedDeviceEntity(): SavedDeviceEntity? {
        return if (savedDeviceUniqueId == null) null else Runtime.findSavedDeviceEntity(savedDeviceUniqueId)
    }
}

/**
 * Instance of a module with a single connection
 */
class ModuleInstance(var request: ModuleInstanceRequest, val homeModelView: ModuleInstanceModel): ModuleBase() {
    val manifest = request.getManifest()
    val chosenConnectableDevice = request.getConnectableDevice()
    var storageDevices = MutableStateFlow(emptyList<StorageInfo>())
    var currentFilesystem: MutableStateFlow<StorageInfo?> = MutableStateFlow(null)

    val liveFeedModel = ModuleLiveFeedModel(this)
    val viewerViewModel = ViewerModel()
    val galleryViewModel = ModuleGalleryViewModel(this, viewerViewModel)
    val intervalometerModel = ModuleIntervalometerModel(this)
    val debugLogModel = ConsoleModel()
    val connectingModel = ModuleConnectingScreenModel(this)
    val dashboardModel = ModuleDashboardModel(this)
    val liveviewWorker = ModuleLiveviewModel(this)
    val backupModel = ModuleBackupModel(this)

    val target = manifest.targets[request.targetIndex]
    private val companionName = "${target.company} ${target.deviceId.getReadableName()}"

    private var currentTickIntervalUs: Int = (100 * 1000)
    private var mainLoopJob: Job? = null
    var initJob: Job? = null
    var currentScreen: Screen = Screen.CONNECT
    var isConnected = false
    var disconnectReason: String? = null
    var disconnectedErrorCode: PakReturnCode? = null
    private var isNavigating = false
    var downloadedItemPaths: List<String> = emptyList()

    init {
        if (request.savedDeviceUniqueId != null) {
            val savedEntry = Runtime.savedDevices.value.find { it.uniqueIdentifier == request.savedDeviceUniqueId }
            if (savedEntry != null) dashboardModel.setSaved()
            // TODO: Set saved/update timestamp
        }

        var rc = when (manifest.moduleType) {
            ModuleManifest.ModuleType.SHARED_LIBRARY -> {
                NativeRuntime.setupSharedLibraryModule(this, manifest.getModulePath())
            }
            ModuleManifest.ModuleType.QUICKJS -> {
                val path = manifest.getModulePath()
                var fileContents = FileLayer.readFile(path)
                if (fileContents == null) {
                    debugLog("Failed to read ${path}")
                    -1
                } else {
                    fileContents += 0.toByte()
                    NativeRuntime.setupJavascriptModule(this, fileContents)
                }
            }
            ModuleManifest.ModuleType.WEBASSEMBLY -> {
                debugLog("<error>Wasm not implemented yet")
                -1
            }
        }
        if (rc != 0) {
            homeModelView.initializationError.value = true
        } else {
            request.chosenSetupOption?.let { setSetupOptionName(it) }
            rc = init()
            if (rc != 0) {
                homeModelView.initializationError.value = true
            }
        }

        Runtime.addModuleInstance(this)

        // Keeping the screen on is desirable for downloader and liveview, which is most module functionality
        NativeRuntime.keepScreenOn(true)
    }

    fun dumpStatus(): String {
        return "Manifest: ${request.manifestName}, Target: ${request.targetIndex}, SetupOption: ${request.chosenSetupOption}"
    }
    fun addDownloadedItem(path: String, metadata: FileMetadata?) {
        downloadedItemPaths += path
    }
    fun trimMemory() {
        galleryViewModel.onTrimMemory()
    }
    fun userSave() {
        val name = dashboardModel.state.value.nameOfDevice ?: return
        saveDeviceSignature(SavedDeviceInfo(
            uniqueIdentifier = name,
            name = name,
            privateData = null,
        ))
    }
    fun getStorageDevice(name: String?): StorageInfo? {
        if (currentFilesystem.value != null) return currentFilesystem.value
        return storageDevices.value.find { it.storageName == name }
    }
    fun updateStorageDeviceStatus(name: String?, message: String?, percent: Int? = null) {
        // Assumes name == currentFilesystem.storageName
        currentFilesystem.update {
            it?.copy(currentStatus = message ?: it.currentStatus, currentProgress = percent)
        }
    }

    @CalledFromNative
    fun saveDeviceSignature(info: SavedDeviceInfo) {
        CoroutineScope(Dispatchers.IO).launch {
            dashboardModel.setSaved()
            NativeRuntime.getDatabaseNullable()!!.deviceDao().saveDevice(SavedDeviceEntity(
                uniqueIdentifier = info.uniqueIdentifier,
                name = info.name,
                auxillaryData = info.privateData,
                manifestName = manifest.name,
                targetIndex = request.targetIndex,
                setupOption = request.chosenSetupOption,
                bluetoothMacAddress = getConnectedMacAddress(),
                androidAssociationId = takeAndroidAssocationId(),
                wifiInfo = getWiFiInfo(),
            ))
        }
    }
    @CalledFromNative
    fun setTickRate(us: Int) {
        currentTickIntervalUs = us
    }
    @CalledFromNative
    fun debugLog(s: String) {
        debugLogModel.addLine(s)
    }
    suspend fun stopAllThreads() {
        println("Stopping module threads")
        cancelAllJobs()
        Bluetooth.interruptAll()
        Pak.interruptAll()
        WiFi.interruptAll()
        liveFeedModel.onShutdown()
        galleryViewModel.stop()
        initJob?.cancelAndJoin()
        mainLoopJob?.cancelAndJoin()
    }
    suspend fun deregister() {
        NativeRuntime.keepScreenOn(false)
        stopAllThreads()
        println("Deregistering module")
        Runtime.removeModuleInstance(this)
        if (!homeModelView.initializationError.value) {
            close()
            free()
        }
    }
    @CalledFromNative
    fun setUserInstruction(s: String?) {
        homeModelView.sendUiEvent(UiEvent(type = UiEvent.UiEventType.SNACKBAR_MESSAGE, message = s))
    }
    @CalledFromNative
    fun getMetadata(file: ObjectHandle): FileMetadata? {
        return galleryViewModel.getMetadata(file)
    }
    @CalledFromNative
    fun setProperty(type: String, value: String) {
        if (ModuleProperty.fromId(type) == ModuleProperty.NAME_OF_DEVICE) {
            val dir = FileLayer.Directory(subfolder = if (Runtime.appSettings.value.perDeviceSubFolder) value else null)
            viewerViewModel.dir = dir
            galleryViewModel.downloadDir = dir
        }
        dashboardModel.setProperty(ModuleProperty.fromId(type) ?: return, value)
    }
    @CalledFromNative
    fun setProperty(type: String, value: Int) {
        dashboardModel.setProperty(ModuleProperty.fromId(type) ?: return, value)
    }
    @CalledFromNative
    fun setScreenSupported(id: Int, v: Boolean) {
        homeModelView.setSupportedScreen(Screen.fromId(id)!!, v)
    }
    @CalledFromNative
    fun setDashboardPane(pane: Widget) {
        if (pane.args.group == Widget.Group.LIVEVIEW) {
            liveviewWorker.updateWidget(pane)
        } else {
            dashboardModel.setDashboardPane(pane)
        }
    }
    @CalledFromNative
    fun setProgressBar(job: Int, v: Int) {
        val jobObj = getJob(job)
        if (jobObj != null && jobObj.progressBarValue != v) {
            jobObj.progressBarValue = v
            jobObj.onUpdate(jobObj)
        }
    }
    fun setDownloadStats(job: Int, timeMs: Long, nBytes: Int) {
        if (galleryViewModel.viewerDownloadJob?.id == job) {
            viewerViewModel.updateSpeed("${(nBytes * 8) / timeMs}Mbps")
        } else if (liveFeedModel.downloadJob?.id == job) {
            // TODO: Should livefeed have stats?
        }
    }
    @CalledFromNative
    fun isJobCancelled(job: Int): Boolean {
        val jobObj = getJob(job)
        if (jobObj != null) {
            return jobObj.isCancelled
        }
        return false
    }
    @CalledFromNative
    fun addFileMetadata(file: ObjectHandle, v: FileMetadata?) {
        galleryViewModel.updateMetadata(file, v)
        val dev = getStorageDevice(file.storageName)
        if (dev != null && dev.type == PakStorageType.PAK_LIVE_QUEUE) {
            liveFeedModel.addFileMetadata(file, v)
        }

        // Update the image viewer state if it doesn't already have the metadata
        val viewerState = viewerViewModel.viewerState.value
        if (viewerState != null) {
            if (viewerState.handle.index == file.index && viewerState.handle.storageName == file.storageName) {
                viewerViewModel.updateMetadata(v)
            }
        }
    }
    @CalledFromNative
    fun setFileContents(file: ObjectHandle, data: ByteArray?, offset: Long, totalSize: Long) {
        val dev = getStorageDevice(file.storageName)
        if (dev != null && dev.type == PakStorageType.PAK_LIVE_QUEUE) {
            liveFeedModel.setFileContents(file, data, offset, totalSize)
        }
        galleryViewModel.downloader?.setFileContents(data, offset, totalSize)
    }
    @CalledFromNative
    fun addFileThumbnail(file: ObjectHandle, data: ByteArray?) {
        galleryViewModel.updateThumbnail(file, data)
    }
    @CalledFromNative
    fun setStorageInfo(info: StorageInfo) {
        if (storageDevices.value.find { it.storageName == info.storageName } == null) storageDevices.update { it + info }
        if (currentFilesystem.value == null) {
            galleryViewModel.switchStorageDevice(info)
            currentFilesystem.value = info
        }
    }
    @CalledFromNative
    fun addWiFiConnection(filter: WiFi.ApFilter, setupOption: String, gotoScreen: Boolean = true) {
        connectingModel.lastWiFiApFilter = filter
        connectingModel.lastWiFiSetupOption = setupOption

        connectingModel.secondaryConnectionJob = CoroutineScope(Dispatchers.IO).launch {
            if (gotoScreen) homeModelView.goToScreen(Screen.CONNECT_SECONDARY, false)
            connectingModel.isSecondaryConnection = true
            setSetupOptionName(setupOption)

            if (!WiFi.checkPermission()) {
                connectingModel.setRequiredAction(ConnectingRequiredAction.ACCEPT_PERMISSION)
                WiFi.requestConnectPermission()
            } else {
                connectingModel.setRequiredAction(ConnectingRequiredAction.NONE)
                val callback = object : WiFi.WiFiDiscoveryCallback() {
                    override fun failed(reason: String, code: Int) {
                        debugLog("<error>${reason}")
                    }

                    override fun onConnected(net: WiFi.Adapter) {
                        debugLog("Connected to network")
                        connectingModel.lastWiFiAdapter = net
                        connectingModel.setPopupText(null)
                        request = request.copy(
                            chosenSetupOption = setupOption
                        )
                        CoroutineScope(Dispatchers.IO).launch {
                            val rc = tryConnectWiFi(net, null)
                            if (rc.success) {
                                homeModelView.back(false)
                            } else {
                                debugLog("Failed to connect: ${rc}")
                                net.disassociate()
                                net.invalidateNetwork()
                            }
                        }
                    }
                }
                connectingModel.setPopupText("Connecting to ${filter.ssidPattern ?: "?"}")
                connectingModel.setTryAgainDisabled(true)
                val rc = WiFi.connectToAccessPointCompanion(mutableListOf(filter), companionName, callback, true)
                if (rc.failed) {
                    debugLog("<error>Association error: ${rc}")
                }
                connectingModel.setTryAgainDisabled(false)
                connectingModel.setPopupText(null)
            }
        }
    }
    private suspend fun setIsConnected() {
        isConnected = true
        startMainLoop()
        liveFeedModel.start()
        switchScreen(Screen.DASHBOARD, false)
    }
    private suspend fun wifiConnectRoutine(filters: List<ModuleManifest.WiFiFilter>, saved: SavedDeviceEntity?) {
        connectingModel.setTryAgainDisabled(true)
        val callback = object : WiFi.WiFiDiscoveryCallback() {
            override fun failed(reason: String, code: Int) {
                debugLog("<error>${reason}")
            }
            override fun onConnected(net: WiFi.Adapter) {
                connectingModel.setPopupText(null)
                CoroutineScope(Dispatchers.IO).launch {
                    if (tryConnectWiFi(net, saved).success) {
                        setWiFiDevice(net)
                        setIsConnected()
                    }
                }
            }
            override fun onConnecting(ssid: String?) {
                connectingModel.setPopupText(if (ssid != null) "Connecting to ${ssid}..." else "Connecting to a network...")
            }
            override fun onUserCancelled() {
                debugLog("Cancelled")
            }
        }

        if (WiFi.requiresManualAccessPointConnection) {
            val primaryAdapter = WiFi.primaryAdapter
            // Try connection over primary adapter in case user connected to access point manually
            if (primaryAdapter != null) {
                setUserInstruction("Please connect to the WiFi Access point manually.")
                val rc = tryConnectWiFi(primaryAdapter, saved, { job -> connectCallback(job) })
                if (rc == PakReturnCode.CANCELLED) return
                if (rc.success) {
                    setIsConnected()
                    return
                } else {
                    debugLog("<error>Failed to connect")
                }
            }
        } else {
            var rc = PakReturnCode.NONE
            if (saved == null) {
                rc = WiFi.connectToAccessPointCompanion(filters.map {
                    WiFi.ApFilter(
                        ssidPattern = it.ssidPattern,
                        password = it.defaultPassword
                    )
                }.toMutableList(), companionName, callback)
            } else {
                if (saved.wifiInfo != null) {
                    val filter = WiFi.ApFilter(
                        ssidPattern = saved.wifiInfo.ssid,
                        password = saved.wifiInfo.password,
                        bssid = saved.wifiInfo.bssid,
                    )
                    rc = WiFi.connectToAccessPointCompanion(mutableListOf(filter), companionName, callback)
                }
            }
            if (rc.failed) {
                debugLog("<error>Association error: ${rc}")
            }
        }
        connectingModel.setTryAgainDisabled(false)
        connectingModel.setPopupText(null)
    }

    private fun connectCallback(job: ModuleJob) {
        connectingModel.setProgress(job.progressBarValue)
    }

    private suspend fun bluetoothConnectRoutine(info: List<ModuleManifest.BluetoothFilter>, saved: SavedDeviceEntity?) {
        if (!Bluetooth.checkPermission()) {
            connectingModel.setRequiredAction(ConnectingRequiredAction.ACCEPT_PERMISSION)
            Bluetooth.requestConnectPermission()
        } else if (!Bluetooth.isBluetoothEnabled) {
            connectingModel.setRequiredAction(ConnectingRequiredAction.TURN_ON_BLUETOOTH)
            Bluetooth.openEnableBluetoothDialog()
        } else {
            suspend fun doConnect(dev: Bluetooth.Device) {
                connectingModel.setPopupText("Connecting to ${dev.name}")
                setBluetoothDevice(dev)
                connectingModel.setTryAgainDisabled(true)
                val rc = tryConnectBluetooth(dev, saved) { job ->
                    connectCallback(job)
                    if (dev.isConnected) {
                        connectingModel.setPopupText(null)
                    }
                }
                connectingModel.setTryAgainDisabled(false)
                if (rc.success) {
                    setIsConnected()
                } else {
                    setBluetoothDevice(null)
                    disconnect("Failed to connect", rc)
                }
            }

            if (chosenConnectableDevice is ConnectableDevice.ConnectableBtDevice && chosenConnectableDevice.macAddress != null) {
                val dev = Bluetooth.fromAddress(chosenConnectableDevice.macAddress)
                if (dev.isBonded) debugLog("Connecting to a bonded device")
                doConnect(dev)
                return
            }

            if (saved?.bluetoothMacAddress != null) {
                doConnect(Bluetooth.fromAddress(saved.bluetoothMacAddress))
                return
            }

            val filters: List<Bluetooth.BtFilter> = info.map {
                it.toBluetoothDeviceFilter()
            }

            val callback = object : Bluetooth.ScanCallback() {
                override fun onFound(device: Bluetooth.Device) {
                    CoroutineScope(Dispatchers.IO).launch { doConnect(device) }
                }
                override fun onFailure(reason: String) {
                    debugLog("System error: ${reason}")
                    connectingModel.setPopupText(null)
                }
                override fun onCancel() {
                    debugLog("Cancelled")
                }
            }
            connectingModel.setTryAgainDisabled(true)
            // TODO GAME_CONTROLLER
            val rc = Bluetooth.pairWithDeviceCompanion(filters, companionName, null,callback, true)
            if (rc.failed) {
                debugLog("<error>Association error: ${rc}")
            }
            connectingModel.setTryAgainDisabled(false)
            connectingModel.setPopupText(null)
        }
    }

    private suspend fun initConnection() {
        val savedDeviceInfo = request.getSavedDeviceEntity()
        val option = request.getSetupOption()
        val transport = when {
            chosenConnectableDevice is ConnectableDevice.ConnectableUSBDevice -> ModuleManifest.Transport.USB
            chosenConnectableDevice is ConnectableDevice.ConnectableBtDevice -> ModuleManifest.Transport.BLUETOOTH
            option != null -> option.transport
            target.wifiFilters.isNotEmpty() -> ModuleManifest.Transport.WIFI_AP
            target.bluetoothFilters.isNotEmpty() -> ModuleManifest.Transport.BLUETOOTH
            target.usbFilters.isNotEmpty() -> ModuleManifest.Transport.USB
            else -> null
        }
        connectingModel.reset(target, transport)
        if (target.usbFilters.isNotEmpty() && transport == ModuleManifest.Transport.USB) {
            if (chosenConnectableDevice is ConnectableDevice.ConnectableUSBDevice) {
                val rc = tryConnectUSB(chosenConnectableDevice.device, savedDeviceInfo,{ job -> connectCallback(job) })
                if (rc.success) {
                    setIsConnected()
                    return
                } else {
                    disconnect("Failed to connect", rc)
                }
            } else {
                debugLog("TODO")
            }
        } else if (target.wifiFilters.isNotEmpty() && transport == ModuleManifest.Transport.WIFI_AP) {
            if (!WiFi.checkPermission()) {
                connectingModel.setRequiredAction(ConnectingRequiredAction.ACCEPT_PERMISSION)
                WiFi.requestConnectPermission()
                return
            }
            wifiConnectRoutine(target.wifiFilters, savedDeviceInfo)
        } else if (target.bluetoothFilters.isNotEmpty() && transport == ModuleManifest.Transport.BLUETOOTH) {
            bluetoothConnectRoutine(target.bluetoothFilters, savedDeviceInfo)
        } else if (transport == ModuleManifest.Transport.LOCAL_NETWORK_UDP) {
            if (!WiFi.checkPermission()) {
                connectingModel.setRequiredAction(ConnectingRequiredAction.ACCEPT_PERMISSION)
                WiFi.requestConnectPermission()
                return
            }

            val primaryAdapter = WiFi.primaryAdapter
            if (primaryAdapter != null) {
                val rc = tryConnectWiFi(primaryAdapter, savedDeviceInfo, { job -> connectCallback(job) })
                if (rc.success) {
                    setIsConnected()
                    return
                } else {
                    disconnect("Failed to connect", rc)
                }
            }
        } else {
            val rc = findConnection({ job -> connectCallback(job) })
            if (rc.success) {
                setIsConnected()
            } else {
                disconnect("Failed to connect", rc)
            }
        }
    }

    fun initThread() {
        initJob = CoroutineScope(Dispatchers.IO).launch {
            initConnection()
            initJob = null
        }
    }

    fun startMainLoop() {
        val module = this
        mainLoopJob = CoroutineScope(Dispatchers.IO).launch {
            val job = mainLoopJob
            var curr = TimeSource.Monotonic.markNow()
            while (job != null && !job.isCancelled) {
                module.onIdleTick(curr.elapsedNow().toInt(DurationUnit.MICROSECONDS))
                curr = TimeSource.Monotonic.markNow()
                delay(module.currentTickIntervalUs.toLong().microseconds)
            }
        }
    }

    private fun disconnect(reason: String, code: PakReturnCode) {
        disconnectReason = reason
        debugLog("<error>Disconnected: ${reason}")
        disconnectedErrorCode = code
        homeModelView.goToScreen(Screen.DISCONNECTED)
    }

    @CalledFromNative
    fun forceDisconnect(reason: String, code: Int) { forceDisconnect(reason, PakReturnCode.fromCode(code)) }
    fun forceDisconnect(reason: String, code: PakReturnCode = PakReturnCode.NONE) {
        if (isConnected) {
            isConnected = false
            CoroutineScope(Dispatchers.IO).launch {
                stopAllThreads()
                withJob({}) {
                    onDisconnect()
                }
                disconnect(reason, code)
            }
        }
    }

    private suspend fun switchScreen(prev: Screen, new: Screen, callback: JobUpdateCallback): PakReturnCode {
        val skip = (prev == Screen.DASHBOARD && new == Screen.LIVE_FEED) || (prev == Screen.LIVE_FEED && new == Screen.DASHBOARD)
        val rc = if (skip) PakReturnCode.NONE else withJob(callback) { job ->
            onSwitchScreen(prev.id, new.id, job.id)
        }
        if (new != prev) {
            // This is done in place of ViewModel init/onCleared
            when (new) {
                Screen.FILE_GALLERY -> galleryViewModel.start()
                Screen.LIVEVIEW -> liveviewWorker.setPaused(false)
                else -> {}
            }
            when (prev) {
                Screen.FILE_GALLERY -> galleryViewModel.setPaused(true)
                Screen.INTERVALOMETER -> intervalometerModel.onShutdown()
                Screen.LIVEVIEW -> liveviewWorker.setPaused(true)
                else -> {}
            }
        }
        return rc
    }

    suspend fun switchScreen(screen: Screen, isInNavBar: Boolean, callback: JobUpdateCallback = {}): PakReturnCode {
        if (!isNavigating) {
            isNavigating = true
            if (currentScreen != screen) {
                homeModelView.goToScreen(screen, isInNavBar)
            }
            val rc = switchScreen(currentScreen, screen, callback)
            currentScreen = screen
            isNavigating = false
            return rc
        }
        return PakReturnCode.NONE
    }

    suspend fun goBack(previous: Screen, isInNavBar: Boolean, callback: JobUpdateCallback = {}) {
        if (!isNavigating) {
            isNavigating = true
            homeModelView.back(isInNavBar)
            switchScreen(currentScreen, previous, callback)
            currentScreen = previous
            isNavigating = false
        }
    }
}