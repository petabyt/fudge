package dev.danielc.common
import dev.danielc.common.screens.ConnectingRequiredAction
import dev.danielc.common.screens.ConnectingScreenModel
import dev.danielc.common.screens.ConsoleModel
import dev.danielc.common.screens.DashboardModel
import dev.danielc.common.screens.FileDownloader
import dev.danielc.common.screens.GalleryObjectReference
import dev.danielc.common.screens.GalleryViewModel
import dev.danielc.common.screens.LiveFeedItem
import dev.danielc.common.screens.LiveFeedModel
import dev.danielc.common.screens.ModuleInstanceModel
import dev.danielc.common.screens.ModuleIntervalometerModel
import dev.danielc.common.screens.ViewerModel
import dev.danielc.fudge.AndroidRuntime
import dev.danielc.fudge.FileLayer
import dev.danielc.fudge.ModuleLiveviewModel
import dev.danielc.fudge.NativeModule
import dev.danielc.libpak.Bluetooth
import dev.danielc.libpak.Pak
import dev.danielc.libpak.WiFi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * Note that a Kotlin function is for use through JNI/C
 */
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
    fun addFileMetadata(file: FileHandle, v: FileMetadata?)  {
        setItem(LiveFeedItem(file, v))
    }
    fun setFileContents(file: FileHandle, data: ByteArray?, offset: Long, totalSize: Long) {
        val item = getItem(file)
        if (downloader?.file != file) {
            downloader = object : FileDownloader(
                file,
                item?.metadata?.filename ?: "unknown${file.index}.jpg",
                item?.metadata?.mimeType ?: MimeType.JPEG.mediaTypeString
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

class ModuleGalleryViewModel(val module: ModuleInstance, val viewerViewModel: ViewerModel): GalleryViewModel() {
    var downloader: FileDownloader? = null
    var viewerDownloadJob: ModuleJob? = null
    override fun fulfillThumbnail(file: GalleryObjectReference) {
        if (module.getFileThumbnail(file = FileHandle(file.index, null)) != 0) {
            updateThumbnail(file.index, thumbData = null)
        }
    }

    override fun fulfillMetadata(file: GalleryObjectReference) {
        if (module.getFileMetadata(file = FileHandle(file.index, null)) != 0) {
            updateMetadata(file.index, null)
        }
    }

    private fun updateDownloader(file: FileHandle) {
        val md = module.galleryViewModel.getMetadata(file)
        downloader = object : FileDownloader(file, md?.filename ?: "unknown${file.index}.jpg", MimeType.JPEG.mediaTypeString) {
            private var isNotUpdatingDownloadSpeed: Boolean = false
            override fun onFinished(buffer: ByteArray) {
                module.viewerViewModel.loadImage(buffer)
            }
            override fun onFinished(file: FileLayer.Handle) {
                module.viewerViewModel.loadImage(file)
            }
            override fun onSaved(handle: FileLayer.Handle) {
                module.viewerViewModel.setHasSaved(true)
                module.galleryViewModel.setHasSaved(file.index, true)
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

    fun goToViewer(file: FileHandle) {
        CoroutineScope(Dispatchers.IO).launch {
            val galleryState = uiState.value
            viewerViewModel.clear()
            viewerViewModel.update(file, galleryState.objects.size)
            viewerViewModel.updateMetadata(getMetadata(file))
            viewerViewModel.updateThumbnails(getThumbnail(file, -1), getThumbnail(file, 0), getThumbnail(file, 1))

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

            if (rc == Pak.Error.CANCELLED) {
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
                if (rc == Pak.Error.CANCELLED) {
                    onCancel()
                } else if (rc != 0) {
                    viewerViewModel.setError("Image download error: ${rc}")
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
        module.propChanged(pane)
    }
    override fun runCommand(line: String) {
        module.runCommand(line)
    }
    override fun save() {
        module.userSave()
    }
    override fun onStorageDeviceClicked(name: String) {
        val dev = module.storageDevices.value.find { it.name == name } ?: return
        if (dev.isLiveFeedMedium) {
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
    override fun onTryAgain() {
        if (isSecondaryConnection) {
            if (secondaryConnectionJob?.isCompleted ?: true) {
                module.addWiFiConnection(lastWiFiApFilter ?: return, lastWiFiSetupOption ?: return)
            }
        } else {
            if (module.initJob?.isCompleted ?: true) {
                module.initThread()
            }
        }
    }

    override fun onCancel(): Boolean {
        Pak.interruptAll();
        WiFi.interruptAll()
        return true
    }
}

abstract class ModuleBase: NativeModule() {
    private var jobs = mutableMapOf<Int, ModuleJob>()
    private var jobCounter = 0

    fun cancelAllJobs() {
        for (job in jobs) {
            job.value.isCancelled = true
        }
    }

    fun createJob(onUpdate: JobUpdateCallback): ModuleJob {
        synchronized(jobs) {
            val id = ++jobCounter
            val job = ModuleJob(
                id = id,
                onUpdate = onUpdate
            )
            jobs.put(id, job)
            return job
        }
    }

    fun closeJob(job: ModuleJob) {
        synchronized(jobs) {
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

    fun withJob(callback: JobUpdateCallback, block: (ModuleJob) -> Int): Int {
        val job = createJob(callback)
        var rc = block(job)
        if (job.isCancelled) rc = Pak.Error.CANCELLED
        //;job.progressBarValue = null
        job.isFinished = true
        callback(job)
        closeJob(job)
        return rc
    }

    fun findConnection(onUpdate: JobUpdateCallback = {}): Int {
        return withJob(onUpdate) { job ->
            onFindConnection(job.id)
        }
    }

    fun getFileMetadata(onUpdate: JobUpdateCallback = {}, file: FileHandle): Int {
        return withJob(onUpdate) { job ->
            onRequestFileMetadata(job.id, file)
        }
    }

    fun getFileThumbnail(onUpdate: JobUpdateCallback = {}, file: FileHandle): Int {
        return withJob(onUpdate) { job ->
            onRequestFileThumbnail(job.id, file)
        }
    }
    fun getFileContents(onUpdate: JobUpdateCallback = {}, file: FileHandle): Int {
        return withJob(onUpdate) { job ->
            onRequestFileContents(job.id, file)
        }
    }
    fun tryConnectWiFi(net: WiFi.Adapter, onUpdate: JobUpdateCallback = {}): Int {
        return withJob(onUpdate) { job ->
            onTryConnectWiFi(net, job.id)
        }
    }
    fun runCommand(cmd: String, vararg params: String): Int {
        return withJob({}) { job ->
            val list = listOf(cmd) + params
            onRunCommand(job.id, list.getOrNull(0), list.getOrNull(1), list.getOrNull(2), list.getOrNull(3))
        }
    }
    fun runCommand(cmd: Command): Int {
        return runCommand(cmd.cmd)
    }
    fun tryConnectBluetooth(device: Bluetooth.Device, saved: SavedDeviceEntity?, onUpdate: JobUpdateCallback = {}): Int {
        return withJob(onUpdate) { job ->
            onTryConnectBluetooth(device, if (saved == null) null else SavedDeviceInfo(
                saved.uniqueIdentifier,
                saved.name,
                saved.auxillaryData,
            ), job.id)
        }
    }
    fun propChanged(pane: Widget): Int {
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
    val deviceMacAddress: String? = null,
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
    fun getSavedDeviceEntity(): SavedDeviceEntity? {
        return if (savedDeviceUniqueId == null) null else Runtime.findSavedDeviceEntity(savedDeviceUniqueId)
    }
}

/**
 * Instance of a module with a single connection
 */
class ModuleInstance(val manifest: ModuleManifest, var request: ModuleInstanceRequest, val homeModelView: ModuleInstanceModel): ModuleBase() {
    val storageDevices = MutableStateFlow(emptyList<StorageInfo>())
    val liveFeedModel = ModuleLiveFeedModel(this)
    val viewerViewModel = ViewerModel()
    val galleryViewModel = ModuleGalleryViewModel(this, viewerViewModel)
    val intervalometerModel = ModuleIntervalometerModel(this)
    val debugLogModel = ConsoleModel()
    val connectingModel = ModuleConnectingScreenModel(this)
    val dashboardModel = ModuleDashboardModel(this)
    val liveviewWorker = ModuleLiveviewModel(this)
    val target = manifest.targets[request.targetIndex]
    private val companionName = "${target.company} ${target.deviceId.getReadableName()}"

    private var currentTickIntervalUs: Int = (100 * 1000)
    private var mainLoopJob: Job? = null
    var initJob: Job? = null
    private var currentScreen: Screen = Screen.CONNECT
    var isConnected = false
    var disconnectReason: String? = null
    var disconnectedErrorCode: Int? = null
    private var isNavigating = false
    var downloadedItemPaths: List<String> = emptyList()

    init {
        Runtime.addModuleInstance(this)
        if (request.savedDeviceUniqueId != null) {
            val savedEntry = Runtime.savedDevices.value.find { it.uniqueIdentifier == request.savedDeviceUniqueId }
            if (savedEntry != null) dashboardModel.setSaved()
            // TODO: Set saved/update timestamp
        }

        var rc = when (manifest.moduleType) {
            ModuleManifest.ModuleType.SHARED_LIBRARY -> {
                AndroidRuntime.setupSharedLibraryModule(this, manifest.getModulePath())
            }
            ModuleManifest.ModuleType.QUICKJS -> {
                val path = manifest.getModulePath()
                if (path == null) {
                    debugLog("<error>script path not included")
                    -1
                } else {
                    var fileContents = FileLayer.readFile(path)
                    if (fileContents == null) {
                        debugLog("Failed to read ${path}")
                        -1
                    } else {
                        fileContents += 0.toByte()
                        AndroidRuntime.setupJavascriptModule(this, fileContents)
                    }
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
    fun getStorageDevice(name: String): StorageInfo? { return storageDevices.value.find { it.name == name } }
    fun updateStorageDeviceStatus(name: String?, message: String?, percent: Int? = null) {
        storageDevices.update {
            it.map { item -> if (item.name == name) item.copy(currentStatus = message ?: item.currentStatus, currentProgress = percent) else item }
        }
    }

    @CalledFromNative
    fun saveDeviceSignature(info: SavedDeviceInfo) {
        CoroutineScope(Dispatchers.IO).launch {
            dashboardModel.setSaved()
            AndroidRuntime.getDatabase().deviceDao().saveDevice(SavedDeviceEntity(
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
        connectingModel.setUserInstruction(s)
    }
    @CalledFromNative
    fun getMetadata(file: FileHandle): FileMetadata? {
        return galleryViewModel.getMetadata(file)
    }
    @CalledFromNative
    fun setProperty(type: String, value: String) {
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
        dashboardModel.setDashboardPane(pane)
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
            // TODO: Shoud livefeed have stats?
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
    fun addFileMetadata(file: FileHandle, v: FileMetadata?) {
        galleryViewModel.updateMetadata(file.index, v)

        val dev = storageDevices.value.find { it.name == file.storageName }
        if (dev != null && dev.isLiveFeedMedium) {
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
    fun setFileContents(file: FileHandle, data: ByteArray?, offset: Long, totalSize: Long) {
        val dev = storageDevices.value.find { it.name == file.storageName }
        if (dev != null && dev.isLiveFeedMedium) {
            liveFeedModel.setFileContents(file, data, offset, totalSize)
        }
        galleryViewModel.downloader?.setFileContents(data, offset, totalSize)
    }
    @CalledFromNative
    fun addFileThumbnail(file: FileHandle, data: ByteArray?) {
        galleryViewModel.updateThumbnail(file.index, data)
    }
    @CalledFromNative
    fun setStorageInfo(info: StorageInfo) {
        if (storageDevices.value.find { it.name == info.name } == null) storageDevices.update { it + info }
        if (info.isLiveFeedMedium) {
            liveFeedModel.update(info)
        } else {
            galleryViewModel.setProperties(info)
        }
    }
    @CalledFromNative
    fun addWiFiConnection(filter: WiFi.ApFilter, setupOption: String) {
        connectingModel.isSecondaryConnection = true
        connectingModel.lastWiFiApFilter = filter
        connectingModel.lastWiFiSetupOption = setupOption
        //WiFi.addNetworkToSystem(filter)
        connectingModel.secondaryConnectionJob = CoroutineScope(Dispatchers.IO).launch {
            // todo: don't switch screen on try again
            homeModelView.goToScreen(Screen.CONNECT_SECONDARY, false)
            setSetupOptionName(setupOption)
            val callback = object : WiFi.WiFiDiscoveryCallback() {
                override fun failed(reason: String, code: Int) {
                    debugLog("<error>${reason}")
                }

                override fun onConnected(net: WiFi.Adapter) {
                    debugLog("Found network")
                    connectingModel.setPopupText(null)
                    request = request.copy(
                        chosenSetupOption = setupOption
                    )
                    val rc = tryConnectWiFi(net)
                    if (rc == 0) {
                        homeModelView.back(false)
                    } else {
                        debugLog("Failed to connect: ${rc}")
                    }
                }
            }
            connectingModel.setPopupText("Connecting to ${filter.ssidPattern ?: "?"}")
            connectingModel.setTryAgainDisabled(true)
            WiFi.connectToAccessPointCompanion(filter, companionName, callback, true)
            connectingModel.setTryAgainDisabled(false)
            connectingModel.setPopupText(null)
        }
    }
    private fun setIsConnected() {
        isConnected = true
        startMainLoop()
        liveFeedModel.start()
        switchScreen(Screen.DASHBOARD, false)
    }
    private fun wifiConnectRoutine(wifi: ModuleManifest.WiFiFilter, saved: SavedDeviceEntity?) {
        connectingModel.setTryAgainDisabled(true)
        val callback = object : WiFi.WiFiDiscoveryCallback() {
            override fun failed(reason: String, code: Int) {
                debugLog("<error>${reason}")
            }
            override fun onConnected(net: WiFi.Adapter) {
                connectingModel.setPopupText(null)
                if (tryConnectWiFi(net) == 0) {
                    setWiFiDevice(net)
                    setIsConnected()
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
            val primaryAdapter = WiFi.getPrimaryAdapter()
            // Try connection over primary adapter in case user connected to access point manually
            if (primaryAdapter != null) {
                connectingModel.setUserInstruction("Please connect to the WiFi Access point manually.")
                val rc = tryConnectWiFi(primaryAdapter, { job -> connectCallback(job) })
                if (rc == Pak.Error.CANCELLED) return
                if (rc == 0) {
                    setIsConnected()
                    return
                } else {
                    debugLog("<error>Failed to connect")
                }
            }
        } else {
            if (saved == null) {
                val filter = WiFi.ApFilter()
                filter.ssidPattern = wifi.ssidPattern
                filter.password = wifi.defaultPassword
                WiFi.connectToAccessPointCompanion(filter, companionName, callback)
            } else {
                if (saved.wifiInfo != null) {
                    val filter = WiFi.ApFilter()
                    filter.ssidPattern = saved.wifiInfo.ssid
                    filter.password = saved.wifiInfo.password
                    filter.bssid = saved.wifiInfo.bssid
                    WiFi.connectToAccessPointCompanion(filter, companionName, callback)
                }
            }
        }
        connectingModel.setTryAgainDisabled(false)
        connectingModel.setPopupText(null)
    }

    private fun connectCallback(job: ModuleJob) {
        connectingModel.setProgress(job.progressBarValue)
    }

    private fun bluetoothConnectRoutine(info: List<ModuleManifest.BluetoothFilter>, saved: SavedDeviceEntity?) {
        if (!Bluetooth.checkPermission()) {
            connectingModel.setRequiredAction(ConnectingRequiredAction.ACCEPT_PERMISSION)
            Bluetooth.requestConnectPermission()
        } else if (!Bluetooth.isBluetoothEnabled()) {
            connectingModel.setRequiredAction(ConnectingRequiredAction.TURN_ON_BLUETOOTH)
            Bluetooth.openEnableBluetoothDialog()
        } else {
            fun doConnect(dev: Bluetooth.Device) {
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
                if (rc == 0) {
                    setIsConnected()
                } else {
                    setBluetoothDevice(null)
                    disconnect("Failed to connect", rc)
                }
            }

            if (request.deviceMacAddress != null) {
                val dev = Bluetooth.fromAddress(request.deviceMacAddress!!) // wtf
                if (dev.isBonded) debugLog("Connecting to a bonded device")
                doConnect(dev)
                return
            }

            if (saved != null && saved.bluetoothMacAddress != null) {
                doConnect(Bluetooth.fromAddress(saved.bluetoothMacAddress))
                return
            }

            val filters: List<Bluetooth.BtFilter> = info.map {
                it.toBluetoothDeviceFilter()
            }

            val callback = object : Bluetooth.ScanCallback() {
                override fun onFound(device: Bluetooth.Device) {
                    doConnect(device)
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
            Bluetooth.pairWithDeviceCompanion(filters, companionName, null,callback, true)
            connectingModel.setTryAgainDisabled(false)
            connectingModel.setPopupText(null)
        }
    }

    private fun initConnection() {
        val savedDeviceInfo = request.getSavedDeviceEntity()
        val option = request.getSetupOption()
        val transport = when {
            option != null -> option.transport
            target.wifiFilter != null -> ModuleManifest.Transport.WIFI_AP
            target.bluetoothFilters.isNotEmpty() -> ModuleManifest.Transport.BLUETOOTH
            else -> null
        }
        connectingModel.reset(target, transport)
        if (target.wifiFilter != null && transport == ModuleManifest.Transport.WIFI_AP) {
            if (!WiFi.checkPermission()) {
                connectingModel.setRequiredAction(ConnectingRequiredAction.ACCEPT_PERMISSION)
                WiFi.requestConnectPermission()
                return
            }
            wifiConnectRoutine(target.wifiFilter, savedDeviceInfo)
        } else if (target.bluetoothFilters.isNotEmpty() && transport == ModuleManifest.Transport.BLUETOOTH) {
            bluetoothConnectRoutine(target.bluetoothFilters, savedDeviceInfo)
        } else if (transport == ModuleManifest.Transport.LOCAL_NETWORK_UDP) {
            if (!WiFi.checkPermission()) {
                connectingModel.setRequiredAction(ConnectingRequiredAction.ACCEPT_PERMISSION)
                WiFi.requestConnectPermission()
                return
            }

            val primaryAdapter = WiFi.getPrimaryAdapter()
            if (primaryAdapter != null) {
                val rc = tryConnectWiFi(primaryAdapter, { job -> connectCallback(job) })
                if (rc == 0) {
                    setIsConnected()
                    return
                } else {
                    disconnect("Failed to connect", rc)
                }
            }
        } else {
            val rc = findConnection({ job -> connectCallback(job) })
            if (rc == 0) {
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

    private fun disconnect(reason: String, code: Int) {
        disconnectReason = reason
        debugLog("<error>Disconnected: ${reason}")
        disconnectedErrorCode = code
        homeModelView.goToScreen(Screen.DISCONNECTED)
    }

    @CalledFromNative
    fun forceDisconnect(reason: String, code: Int = 0) {;
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

    private fun switchScreen(prev: Screen, new: Screen, callback: JobUpdateCallback): Int {
        val skip = (prev == Screen.DASHBOARD && new == Screen.LIVE_FEED) || (prev == Screen.LIVE_FEED && new == Screen.DASHBOARD)
        val rc = if (skip) 0 else withJob(callback) { job ->
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

    fun switchScreen(screen: Screen, isInNavBar: Boolean, callback: JobUpdateCallback = {}): Int {
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
        return 0
    }

    fun goBack(previous: Screen, isInNavBar: Boolean, callback: JobUpdateCallback = {}) {
        if (!isNavigating) {
            isNavigating = true
            homeModelView.back(isInNavBar)
            switchScreen(currentScreen, previous, callback)
            currentScreen = previous
            isNavigating = false
        }
    }
}