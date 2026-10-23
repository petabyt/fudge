package dev.danielc.common

import dev.danielc.common.screens.ConsoleModel
import dev.danielc.common.screens.LocalGalleryViewModel
import dev.danielc.fudge.AndroidRuntime
import dev.danielc.fudge.AndroidRuntime.getDatabase
import dev.danielc.fudge.FileLayer
import dev.danielc.libpak.Bluetooth
import dev.danielc.libpak.Pak
import dev.danielc.libpak.USB
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * This is used over ViewModel so that it can be easily stowed away and kept running in the background
 * without having to keep it bound to a composable
 */
open class BackgroundViewModel {
    val scope = CoroutineScope(Dispatchers.IO)
    open fun onTrimMemory() {
        println("TODO: Trim memory")
    }
    /** Similar to onCleared */
    open fun onShutdown() {
        println("TODO: Shut down")
    }
    open fun onResume() {
        println("TODO: Unpaused")
    }
}

@Suppress("ArrayInDataClass")
sealed interface ConnectableDevice {
    val name: String
    val target: ModuleManifest.Target
    val manifest: ModuleManifest

    fun getDefaultSetupOption(): String? {
        for (s in target.setupOptions) {
            if (this is ConnectableUSBDevice && s.transport == ModuleManifest.Transport.USB) return s.name
            if (this is ConnectableBtDevice && s.transport == ModuleManifest.Transport.BLUETOOTH) return s.name
        }
        return null
    }

    data class ConnectableLocalDevice(
        override val name: String, override val target: ModuleManifest.Target, override val manifest: ModuleManifest,
        val address: String,
        val port: Int,
        val datagram: ByteArray,
    ): ConnectableDevice

    data class ConnectableUSBDevice(
        override val name: String, override val target: ModuleManifest.Target, override val manifest: ModuleManifest,
        val device: USB.Device
    ): ConnectableDevice

    data class ConnectableBtDevice(
        override val name: String, override val target: ModuleManifest.Target, override val manifest: ModuleManifest,
        val macAddress: String?
    ): ConnectableDevice
}

object Runtime {
    private val _trimMemorySignal = MutableSharedFlow<Unit>()
    val trimMemorySignal = _trimMemorySignal.asSharedFlow()
    suspend fun emitMemoryTrimSignal() { _trimMemorySignal.emit(Unit) }
    var nearbyDevices = MutableStateFlow(mutableSetOf<String>())
    val nearbyDevicesFlow = nearbyDevices.asStateFlow()
    val mainLog = ConsoleModel()
    val localGalleryViewModel = LocalGalleryViewModel()
    var connectableDevices = MutableStateFlow<List<ConnectableDevice>>(emptyList())
    var moduleManifests = MutableStateFlow(listOf<ModuleManifest>())
    var savedDevices: StateFlow<List<SavedDeviceEntity>> = AndroidRuntime.getDatabaseNullable()?.deviceDao()?.getAllDevices()?.stateIn(
        CoroutineScope(Dispatchers.IO),
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000),
        emptyList()
    ) ?: MutableStateFlow(listOf()) // Compose preview fallback
    val appSettings: StateFlow<AppSettingEntity> = AndroidRuntime.getDatabaseNullable()?.settingsDao()?.getFlow()
    ?.map { it ?: AppSettingEntity() }
    ?.stateIn(
        CoroutineScope(Dispatchers.IO),
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000),
        AppSettingEntity()
    ) ?: MutableStateFlow(AppSettingEntity(1, "/home/daniel/Pictures")) // Compose preview fallback

    var moduleInstances = mutableMapOf<Int, ModuleInstance>()
    private var moduleCounter = 0

    val callbacks = object : Pak.Callbacks() {
        override fun onDeviceAppearance(address: String?, isNearby: Boolean) {
            if (address != null) {
                nearbyDevices.value = (if (isNearby) nearbyDevices.value + address else nearbyDevices.value - address).toMutableSet()
            }
        }
        init {
            Pak.callbacks = this
        }
    }

    fun deleteSavedDeviceEntity(dev: SavedDeviceEntity) {
        CoroutineScope(Dispatchers.IO).launch {
            AndroidRuntime.disassociateFromSavedDevice(dev)
            AndroidRuntime.getDatabaseNullable()?.deviceDao()?.deleteDevice(dev)
        }
    }

    fun findSavedDeviceEntity(id: String): SavedDeviceEntity? {
        val list = getDatabase().deviceDao().getAllDevicesList()
        return list.find { it.uniqueIdentifier == id }
    }

    fun errorCodeToString(rc: Int): String {
        return when (rc) {
            Pak.Error.IO -> "IO Error"
            Pak.Error.CANCELLED -> "Cancelled"
            Pak.Error.DISCONNECTED -> "Disconnected"
            Pak.Error.NO_CONNECTION -> "Can't connect"
            Pak.Error.PERMISSION -> "Permission error"
            Pak.Error.UNSUPPORTED -> "Unsupported"
            Pak.Error.UNIMPLEMENTED -> "Unimplemented method"
            Pak.Error.NON_FATAL -> "Non fatal error"
            else -> ""
        }
    }

    fun addModuleInstance(mod: ModuleInstance): Int {
        moduleInstances.put(++moduleCounter, mod)
        return moduleCounter
    }

    fun removeModuleInstance(mod: ModuleInstance) {
        val key = moduleInstances.entries.find { it.value == mod }?.key
        if (key != null) {
            moduleInstances.remove(key)
        }
    }

    // This doesn't work well in practice since per-app bonds are not available system-wide
    fun refreshAgainstBondedDevices() {
        // Match installed manifests with bonded bluetooth devices
        val devices = Bluetooth.getBondedDevices(Bluetooth.getDefaultAdapter())
        val list = mutableListOf<ConnectableDevice>()
        fun checkServiceUuids(b: ModuleManifest.BluetoothFilter, d: Bluetooth.Device): Boolean {
            // Will return true even if both sets are empty
            val serviceUuids = d.serviceUuids
            //if (serviceUuids.isEmpty() || b.serviceUuids.isEmpty()) return false
            for (f in b.serviceUuids) {
                if (!serviceUuids.contains(f)) return false
            }
            return true
        }
        fun matchAgainstManifest(m: ModuleManifest, d: Bluetooth.Device): Boolean {
            for (t in m.targets) {
                for (b in t.bluetoothFilters) {
                    // TODO: async try to fetch UUIDs over sdp
                    if (!checkServiceUuids(b, d)) continue
                    if (b.namePattern != null) {
                        if (!Regex(b.namePattern).matches(d.name)) continue
                    } else {
                        continue
                    }

                    list += ConnectableDevice.ConnectableBtDevice(d.name, t, m, d.address)
                    return true
                }
            }
            return false
        }
        for (d in devices) {
            //d.refreshSdpUuids()
            for (m in moduleManifests.value) {
                if (matchAgainstManifest(m, d)) break
            }
            //d.closeAll()
        }
        connectableDevices.value = list
    }

    fun refreshConnectableDevices() {
        val list = mutableListOf<ConnectableDevice>()
        fun matchAgainstManifest(m: ModuleManifest, d: USB.Device): Boolean {
            for (t in m.targets) {
                for (b in t.usbFilters) {
                    if (b.pid != null && b.pid != d.pid) continue
                    if (b.vid != null && b.vid != d.vid) continue
                    if (b.usbClass != null && b.usbClass != d.usbClass) continue
                    list += ConnectableDevice.ConnectableUSBDevice(d.name, t, m, d)
                    return true
                }
            }
            return false
        }
        val usbDevices = USB.getConnectedDevices()
        for (d in usbDevices) {
            for (m in moduleManifests.value) {
                if (matchAgainstManifest(m, d)) break
            }
        }
        connectableDevices.value = list
    }

    fun getManifestFromName(name: String): ModuleManifest? {
        for (m in moduleManifests.value) {
            if (m.name == name) return m
        }
        return null
    }

    fun refreshManifests() {
        val manifests = AndroidRuntime.getJsonManifestList()
        val list = mutableListOf<ModuleManifest>()
        for (filename in manifests) {
            val text = FileLayer.readFile(filename)
            if (text == null) {
                logGlobalLine("Failed to read ${filename}")
                continue
            }
            val manifest = manifestFromJson(String(text), filename) ?: continue
            list += manifest
        }
        moduleManifests.value = list
    }

    fun logGlobalLine(s: String) {
        mainLog.addLine(s)
    }
}