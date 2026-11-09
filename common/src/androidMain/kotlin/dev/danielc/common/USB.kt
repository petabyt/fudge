package dev.danielc.common

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import dev.danielc.common.Pak.activity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds

actual object USB {
    private val cancellableRunnable = Pak.CancellableRunnable()
    actual val connectedDevices: Array<Device>
        get() {
            val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
            val deviceList = usbManager.deviceList
            return deviceList.map { Device(it.value) }.toTypedArray() ?: emptyArray()
        }

    actual class Device(val dev: UsbDevice) {
        actual val pid: Int = dev.productId
        actual val vid: Int = dev.vendorId
        actual val usbClass: Int = dev.deviceClass
        actual val name: String = dev.productName ?: "?"

        fun requestPermissionToOpen(): Int {
            return runBlocking {
                val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
                val packageName: String? = activity.packageName
                val permissionAction = "$packageName.USB_PERMISSION"

                val permissionIntent = Intent(permissionAction)
                val pendingIntent = PendingIntent.getBroadcast(activity, 0, permissionIntent, PendingIntent.FLAG_IMMUTABLE) ?: return@runBlocking -1

                usbManager.requestPermission(dev, pendingIntent)

                cancellableRunnable.run {
                    for (i in 0..9) {
                        if (usbManager.hasPermission(dev)) {
                            return@run PakReturnCode.NONE
                        }
                        delay(1000.milliseconds)
                    }
                    PakReturnCode.UNDEFINED
                }.code
            }
        }
    }

    actual fun interruptAll() {
    }
}
