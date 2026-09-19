package dev.danielc.fudge

import android.view.Surface
import android.view.SurfaceHolder
import dev.danielc.common.Widget
import dev.danielc.common.FileHandle
import dev.danielc.common.SavedDeviceEntity.WiFiInfo
import dev.danielc.common.SavedDeviceInfo
import dev.danielc.libpak.Bluetooth
import dev.danielc.libpak.WiFi

open class NativeModule {
    var struct: ByteArray? = null

    private var takenAssociationId: Boolean = false
    private var connectedBluetoothDevice: Bluetooth.Device? = null
    private var connectedWiFiAdapter: WiFi.Adapter? = null

    fun setBluetoothDevice(dev: Bluetooth.Device?) { connectedBluetoothDevice = dev }
    fun setWiFiDevice(dev: WiFi.Adapter?) { connectedWiFiAdapter = dev }
    fun takeAndroidAssocationId(): Int? {
        takenAssociationId = true
        connectedBluetoothDevice?.let {
            return it.associationId
        }
        connectedWiFiAdapter?.let {
            return it.associationId
        }
        return -1
    }
    fun getConnectedMacAddress(): String? {
        connectedBluetoothDevice?.let {
            return it.address
        }
        connectedWiFiAdapter?.let {
            return it.macAddress
        }
        return null
    }
    fun getWiFiInfo(): WiFiInfo? {
        if (connectedWiFiAdapter == null) return null
        return WiFiInfo(
            ssid = connectedWiFiAdapter?.apScanResult?.SSID,
            bssid = connectedWiFiAdapter?.apScanResult?.BSSID,
            password = connectedWiFiAdapter?.savedPassword,
        )
    }
    fun close() {
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
    @Synchronized
    external fun onFindConnection(job: Int): Int
    @Synchronized
    external fun onTryConnectWiFi(adapter: WiFi.Adapter, job: Int): Int
    @Synchronized
    external fun onTryConnectBluetooth(adapter: Bluetooth.Device, saved: SavedDeviceInfo?, job: Int): Int
    @Synchronized
    external fun onIdleTick(usSinceLastTick: Int): Int
    @Synchronized
    external fun onDisconnect(): Int
    @Synchronized
    external fun onSwitchScreen(oldScreen: Int, newScreen: Int, job: Int): Int
    @Synchronized
    external fun onRequestFileContents(job: Int, file: FileHandle): Int
    @Synchronized
    external fun onRequestFileThumbnail(job: Int, file: FileHandle): Int
    @Synchronized
    external fun onRequestFileMetadata(job: Int, file: FileHandle): Int
    @Synchronized
    external fun onRunCommand(job: Int, arg0: String?, arg1: String?, arg2: String?, arg3: String?): Int
    @Synchronized
    external fun onPropChanged(job: Int, pane: Widget): Int
    @Synchronized
    external fun init(): Int
    @Synchronized
    external fun free()
    @Synchronized
    external fun setSetupOptionName(name: String)
    external fun updateNativeLiveview(view: Surface?, isPaused: Boolean)
    // Blocking render loop
    external fun nativeLiveviewThread()
    external fun getVerboseLog(): String
}