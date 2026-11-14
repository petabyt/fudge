package dev.danielc.fudge

import android.view.Surface
import dev.danielc.fudge.SavedDeviceEntity.WiFiInfo

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
    actual external fun onFindConnection(job: Int): Int
    actual external fun onTryConnectUSB(device: USB.Device, saved: SavedDeviceInfo?, job: Int): Int
    actual external fun onTryConnectWiFi(adapter: WiFi.Adapter, saved: SavedDeviceInfo?, job: Int): Int
    actual external fun onTryConnectBluetooth(adapter: Bluetooth.Device, saved: SavedDeviceInfo?, job: Int): Int
    actual external fun onIdleTick(usSinceLastTick: Int): Int
    actual external fun onDisconnect(): Int
    actual external fun onSwitchScreen(oldScreen: Int, newScreen: Int, job: Int): Int
    actual external fun onRequestFileContents(job: Int, file: ObjectHandle): Int
    actual external fun onRequestFileThumbnail(job: Int, file: ObjectHandle): Int
    actual external fun onRequestFileMetadata(job: Int, file: ObjectHandle): Int
    actual external fun onSwitchStorageDevice(job: Int, name: String): Int
    actual external fun onRunCommand(job: Int, arg0: String?, arg1: String?, arg2: String?, arg3: String?): Int
    actual external fun onPropChanged(job: Int, pane: Widget): Int
    actual external fun init(): Int
    actual external fun free()
    actual external fun setSetupOptionName(name: String)
    external fun updateNativeLiveview(view: Surface?, isPaused: Boolean)
    // Blocking render loop
    actual external fun nativeLiveviewThread()
    // Get pak_verbose_log dump
    actual external fun getVerboseLog(): String
}