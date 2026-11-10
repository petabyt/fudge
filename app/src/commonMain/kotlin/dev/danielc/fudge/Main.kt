package dev.danielc.fudge
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ClipEntry
import dev.danielc.fudge.SavedDeviceEntity.WiFiInfo
import dev.danielc.fudge.screens.LiveviewModel

fun getBuildInfo(): BuildInfo {
    return mainBuildInfo!!
}
var mainBuildInfo: BuildInfo? = null
data class BuildInfo(
    val time: String,
    val isNightly: Boolean,
    val isDebug: Boolean,
    val packageName: String,
    val version: String,
    val osVersion: String,
)

expect fun Timestamp.toEpochMillis(): Long
expect fun Timestamp.hoursSince(other: Timestamp): Double
expect fun Timestamp.Companion.now(): Timestamp

expect object NativeRuntime {
    fun getDatabaseNullable(): AppDatabase?
    fun resetEverything()
    fun getJsonManifestList(): List<String>
    fun keepScreenOn(on: Boolean)
    fun decodeImageContents(data: ByteArray, orientation: Int? = null): ImageBitmap?
    fun decodeImageFile(data: FileLayer.Handle, orientation: Int?): ImageBitmap?
    suspend fun updateAppSetting(block: (AppSettingEntity) -> AppSettingEntity)
    fun disassociateFromSavedDevice(saved: SavedDeviceEntity)

    fun init()
    fun setupSharedLibraryModule(mod: ModuleInstance, path: String): Int
    fun setupJavascriptModule(mod: ModuleInstance, fileContents: ByteArray): Int
    fun setupWebassemblyModule(mod: ModuleInstance, fileContents: ByteArray): Int
}

@Composable
expect fun WebView(url: String)

expect fun createClipEntry(label: String, text: String): ClipEntry

expect object Http {
    class Response {
        val code: Int
        val body: String
        val isSuccessful: Boolean
    }
    fun post(urlString: String, body: String, headers: Map<String, String> = emptyMap()): Response
    fun get(urlString: String, headers: Map<String, String> = emptyMap()): Response
}

expect object FileLayer {
    class MediaStoreFile {
        val metadata: FileMetadata
    }
    class Handle {
        fun write(byteArray: ByteArray)
        fun close()
        fun getPath(): String
    }
    class Directory(
        folder: String = "fudge",
        subfolder: String? = null,
        customFullPath: String? = null,
    ) {
        val folder: String
        val subfolder: String?
        val customFullPath: String?
        fun getPath(): String
    }
    fun getDefaultDownloadDirectory(): String
    fun requestLegacyPermissions()
    fun readFile(path: String): ByteArray?
    fun deleteFile(file: Handle)
    fun openImageInDefaultApp(file: MediaStoreFile)
    fun openFileForReading(ref: MediaStoreFile): Handle?
    fun doesFileExist(filename: String, dir: Directory = Directory()): Boolean
    fun openFileForWriting(filename: String, mimeType: String?, dir: Directory = Directory(), mode: String = "w"): Handle?
    fun getMediaThumbnail(file: MediaStoreFile): ImageBitmap?
    fun requestExternalImagesPermission()
    fun getDownloadedMediaFiles(subfolder: String = "fudge"): List<MediaStoreFile>
}

expect open class NativeModule() {
    fun onFindConnection(job: Int): Int
    fun onTryConnectUSB(device: USB.Device, saved: SavedDeviceInfo?, job: Int): Int
    fun onTryConnectWiFi(adapter: WiFi.Adapter, saved: SavedDeviceInfo?, job: Int): Int
    fun onTryConnectBluetooth(adapter: Bluetooth.Device, saved: SavedDeviceInfo?, job: Int): Int
    fun onIdleTick(usSinceLastTick: Int): Int
    fun onDisconnect(): Int
    fun onSwitchScreen(oldScreen: Int, newScreen: Int, job: Int): Int
    fun onRequestFileContents(job: Int, file: FileHandle): Int
    fun onRequestFileThumbnail(job: Int, file: FileHandle): Int
    fun onRequestFileMetadata(job: Int, file: FileHandle): Int
    fun onRunCommand(job: Int, arg0: String?, arg1: String?, arg2: String?, arg3: String?): Int
    fun onPropChanged(job: Int, pane: Widget): Int
    fun init(): Int
    fun free()
    fun setSetupOptionName(name: String)
    fun nativeLiveviewThread()
    fun getVerboseLog(): String
    fun close()

    fun getConnectedMacAddress(): String?
    fun getWiFiInfo(): WiFiInfo?
    fun setBluetoothDevice(dev: Bluetooth.Device?)
    fun setWiFiDevice(dev: WiFi.Adapter?)
    fun takeAndroidAssocationId(): Int?
}

expect object Bluetooth {
    class BtFilter(
        namePattern: String? = null,
        isClassic: Boolean,
        serviceUuids: Array<String>?,
        manufacData: ByteArray?,
        manufacDataMask: ByteArray?
    ) {
        val namePattern: String?
        val isClassic: Boolean
        val serviceUuids: Array<String>?
        val manufacData: ByteArray?
        val manufacDataMask: ByteArray?
    }
    class Device {
        val name: String
        val address: String
        fun getServiceUuids(): List<String>
        val isConnected: Boolean
        val isBonded: Boolean
    }
    abstract class ScanCallback() {
        abstract fun onFound(device: Device)
        abstract fun onFailure(reason: String)
        abstract fun onCancel()
    }

    fun getBondedDevices(): Array<Device>
    fun interruptAll()
    fun checkPermission(): Boolean
    suspend fun requestConnectPermission()
    fun fromAddress(address: String): Device
    suspend fun pairWithDeviceCompanion(filters: List<BtFilter>, companionName: String, deviceType: dev.danielc.fudge.Device?, scanCallback: ScanCallback, deleteOldBond: Boolean): PakReturnCode
    fun openEnableBluetoothDialog()
    val isBluetoothEnabled: Boolean
}

expect object USB {
    class Device {
        val pid: Int
        val vid: Int
        val usbClass: Int
        val name: String
    }
    fun interruptAll()
    val connectedDevices: Array<Device>
}

expect object WiFi {
    class Adapter {
        val isAvailable: Boolean
        fun disassociate()
        fun invalidateNetwork()
    }
    fun interruptAll()
    fun checkPermission(): Boolean
    suspend fun requestConnectPermission()
    suspend fun connectToAccessPointCompanion(
        apFilters: MutableList<ApFilter>,
        companionName: String,
        wifiCallback: WiFiDiscoveryCallback,
        dontAssociate: Boolean = false,
    ): PakReturnCode
    val primaryAdapter: Adapter?
    val isWiFiEnabled: Boolean
    val requiresManualAccessPointConnection: Boolean


    class ApFilter(
        ssidPattern: String? = null,
        bssid: String? = null,
        password: String? = null,
        band: Int = -1,
        hidden: Boolean = false,
    ) {
        val ssidPattern: String?
        val bssid: String?
        val password: String?
        val band: Int
        val hidden: Boolean
        val securityType: String
    }

    abstract class WiFiDiscoveryCallback() {
        abstract fun onConnected(net: Adapter)
        abstract fun failed(reason: String, code: Int)
        open fun onUserCancelled()

        /// On beginning to search for network
        open fun onConnecting(ssid: String?)
    }
}

expect object Pak {
    fun interruptAll()
    var callbacks: Callbacks?
    abstract class Callbacks() {
        abstract fun onDeviceAppearance(address: String?, isNearby: Boolean)
    }
}

expect class ModuleLiveviewModel(mod: ModuleInstance): LiveviewModel {
    fun setPaused(v: Boolean)
    suspend fun stopThread()
    fun update(w: Int, h: Int)
    fun clear()
    val mod: ModuleInstance
}

@Composable
expect fun FramebufferSurface(modifier: Modifier = Modifier, model: LiveviewModel)