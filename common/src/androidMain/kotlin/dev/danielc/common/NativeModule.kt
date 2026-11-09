package dev.danielc.common

import android.view.Surface
import dev.danielc.common.SavedDeviceEntity.WiFiInfo

actual open class NativeModule {
    var struct: ByteArray? = null

    private var takenAssociationId: Boolean = false
    var connectedBluetoothDevice: Bluetooth.Device? = null
    private var connectedWiFiAdapter: WiFi.Adapter? = null

    actual fun setBluetoothDevice(dev: Bluetooth.Device?) { connectedBluetoothDevice = dev }
    actual fun setWiFiDevice(dev: WiFi.Adapter?) { connectedWiFiAdapter = dev }
    actual fun takeAndroidAssocationId(): Int? {
        takenAssociationId = true
        connectedBluetoothDevice?.let {
            return it.associationId
        }
        connectedWiFiAdapter?.let {
            return it.associationId
        }
        return -1
    }
    actual fun getConnectedMacAddress(): String? {
        connectedBluetoothDevice?.let {
            return it.address
        }
        connectedWiFiAdapter?.let {
            return it.macAddress
        }
        return null
    }
    actual fun getWiFiInfo(): WiFiInfo? {
        if (connectedWiFiAdapter == null) return null
        return WiFiInfo(
            ssid = connectedWiFiAdapter?.apScanResult?.SSID,
            bssid = connectedWiFiAdapter?.apScanResult?.BSSID,
            password = connectedWiFiAdapter?.savedPassword,
        )
    }
    actual fun close() {
        // TODO: Auto disassociate if not saved?
//        if (!takenAssociationId) {
//            connectedBluetoothDevice?.let {
//                Pak.disassociate(it.address)
//            }
//            connectedWiFiAdapter?.let {
//                Pak.disassociate(it.macAddress)
//            }
//        }
    }

    // TODO: @Synchronized is a temporary solution for thread safety
    @Synchronized actual external fun onFindConnection(job: Int): Int
    @Synchronized actual external fun onTryConnectUSB(device: USB.Device, saved: SavedDeviceInfo?, job: Int): Int
    @Synchronized actual external fun onTryConnectWiFi(adapter: WiFi.Adapter, saved: SavedDeviceInfo?, job: Int): Int
    @Synchronized actual external fun onTryConnectBluetooth(adapter: Bluetooth.Device, saved: SavedDeviceInfo?, job: Int): Int
    @Synchronized actual external fun onIdleTick(usSinceLastTick: Int): Int
    @Synchronized actual external fun onDisconnect(): Int
    @Synchronized actual external fun onSwitchScreen(oldScreen: Int, newScreen: Int, job: Int): Int
    @Synchronized actual external fun onRequestFileContents(job: Int, file: FileHandle): Int
    @Synchronized actual external fun onRequestFileThumbnail(job: Int, file: FileHandle): Int
    @Synchronized actual external fun onRequestFileMetadata(job: Int, file: FileHandle): Int
    @Synchronized actual external fun onRunCommand(job: Int, arg0: String?, arg1: String?, arg2: String?, arg3: String?): Int
    @Synchronized actual external fun onPropChanged(job: Int, pane: Widget): Int
    @Synchronized actual external fun init(): Int
    @Synchronized actual external fun free()
    @Synchronized actual external fun setSetupOptionName(name: String)
    external fun updateNativeLiveview(view: Surface?, isPaused: Boolean)
    // Blocking render loop
    actual external fun nativeLiveviewThread()
    // Get pak_verbose_log dump
    actual external fun getVerboseLog(): String
}