package dev.danielc.fudge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.util.isEmpty
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Semaphore
import kotlin.time.Duration.Companion.milliseconds
import dev.danielc.fudge.Device as PakDevice

actual object Bluetooth {
    const val TAG: String = "bt"
    const val VERBOSE: Boolean = true

    /// TODO: Hold one per bluetooth context
    private val cancellableRunnable = Pak.CancellableRunnable()

    actual fun interruptAll() {
        cancellableRunnable.cancelAll()
    }

    actual fun checkPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return Pak.activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    actual suspend fun requestConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Pak.requirePermissionBlocking(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    @JvmStatic
    actual fun getBondedDevices(): Array<Device> {
        val adapter = defaultAdapter ?: return emptyArray()
        if (Pak.activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return arrayOf()
        return adapter.bondedDevices.map { Device(adapter, it) }.toTypedArray()
    }

    @JvmStatic
    val defaultAdapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    actual val isBluetoothEnabled: Boolean
        get() {
            return defaultAdapter != null && defaultAdapter!!.isEnabled
        }

    actual fun fromAddress(address: String): Device {
        return Device(defaultAdapter!!, defaultAdapter!!.getRemoteDevice(address))
    }

    /// Opens a dialog to save an access point as a companion device
    @SuppressLint("WrongConstant")
    actual suspend fun pairWithDeviceCompanion(filters: List<BtFilter>, companionName: String, deviceType: PakDevice?, scanCallback: ScanCallback, deleteOldBond: Boolean): PakReturnCode {
        val ctx = Pak.activity
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return PakReturnCode.UNSUPPORTED
        }

        val deviceManager = ctx.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

        val associationBuilder = AssociationRequest.Builder()
        for (filter in filters) {
            if (filter.isClassic) {
                val builder = BluetoothDeviceFilter.Builder()
                if (filter.serviceUuids != null) {
                    for (uuid in filter.serviceUuids) {
                        builder.addServiceUuid(ParcelUuid.fromString(uuid), null)
                    }
                }
                associationBuilder.addDeviceFilter(builder.build())
            } else {
                val builder = BluetoothLeDeviceFilter.Builder()
                val scanFilter = ScanFilter.Builder()

                val manufacData = filter.manufacData
                if (manufacData != null) {
                    var trimmed = manufacData.copyOfRange(2, manufacData.size)
                    val companyIdentifier = (manufacData[0].toInt() and 0xff) or ((manufacData[1].toInt() and 0xff) shl 8)
                    try {
                        val manufacDataMask = filter.manufacDataMask
                        if (manufacDataMask != null) {
                            val maskTrimmed = manufacDataMask.copyOfRange(2, manufacDataMask.size)
                            if (maskTrimmed.size < trimmed.size) {
                                trimmed = trimmed.copyOfRange(0, maskTrimmed.size)
                            }
                            scanFilter.setManufacturerData(companyIdentifier, trimmed, maskTrimmed)
                        } else {
                            scanFilter.setManufacturerData(companyIdentifier, trimmed)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, e.message!!)
                        return PakReturnCode.UNDEFINED
                    }
                }

                for (uuid in filter.serviceUuids!!) {
                    scanFilter.setServiceUuid(ParcelUuid.fromString(uuid))
                    Log.d(TAG, uuid)
                }

                builder.setScanFilter(scanFilter.build())
                associationBuilder.addDeviceFilter(builder.build())
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            associationBuilder.setDisplayName(companionName)
        }

        // Automatically exits prompt when device is found
        //associationBuilder.setSingleDevice(true);
        when (deviceType) {
            PakDevice.SMARTWATCH -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    associationBuilder.setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH)
                }
            }
            PakDevice.SMART_GLASSES -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    associationBuilder.setDeviceProfile(AssociationRequest.DEVICE_PROFILE_GLASSES)
                }
            }
            PakDevice.GENERIC_MEDICAL_WEARABLE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                    associationBuilder.setDeviceProfile(AssociationRequest.DEVICE_PROFILE_MEDICAL)
                }
            }
            else -> {}
        }
        val request = associationBuilder.build()

        try {
            val intent = Pak.companionAssociateGetResultBlocking(deviceManager, request)
            if (intent == null) {
                scanCallback.onCancel()
                return PakReturnCode.CANCELLED
            }
            var associationInfo: AssociationInfo? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                associationInfo = intent.getParcelableExtra<AssociationInfo?>(CompanionDeviceManager.EXTRA_ASSOCIATION)
                Log.d(TAG, "Associated with " + associationInfo!!.deviceMacAddress)
                //Pak.deleteDuplicateAssociations(associationInfo);
            }
            val result = intent.getParcelableExtra<ScanResult?>(CompanionDeviceManager.EXTRA_DEVICE) ?: return PakReturnCode.NON_FATAL

            val dev = Device(defaultAdapter!!, result.device, result, associationInfo)
            if (dev.isBonded && deleteOldBond) {
                // This causes a repeat dialog but is necessary since removing bonds seems to cause devices to refresh advertisement
                dev.removePreviousBond()
                val rc = pairWithDeviceCompanion(
                    filters,
                    companionName,
                    deviceType,
                    scanCallback,
                    false
                )
                deviceManager.disassociate(result.device.address)
                return rc
            } else {
                scanCallback.onFound(Device(defaultAdapter!!, result.device, result, associationInfo))
            }
        } catch (_: Pak.CancelException) {
            return PakReturnCode.CANCELLED
        }
        return PakReturnCode.NONE
    }

    actual fun openEnableBluetoothDialog() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        Pak.startActivity(intent)
    }

    actual class Device @SuppressLint("MissingPermission") internal constructor(
        val adapter: BluetoothAdapter,
        val dev: BluetoothDevice,
    ) {
        private val bondSignal = Channel<Unit>()
        private var keyMissingFromBonding = false
        actual val name: String
        actual val address: String
        var isGattConnected: Boolean = false
        private var scanResult: ScanResult? = null
        private var associationInfo: AssociationInfo? = null
        private var serviceUuids: Array<ParcelUuid>
        var gatt: BluetoothGatt? = null
        var receiver: BroadcastReceiver? = null
        var callback: NativeBluetoothGattCallback? = null

        internal constructor(
            adapter: BluetoothAdapter,
            dev: BluetoothDevice,
            scanResult: ScanResult?,
        ) : this(adapter, dev) {
            this.scanResult = scanResult
        }

        internal constructor(
            adapter: BluetoothAdapter,
            dev: BluetoothDevice,
            scanResult: ScanResult,
            info: AssociationInfo?,
        ) : this(adapter, dev, scanResult) {
            this.associationInfo = info
        }

        actual fun getServiceUuids(): List<String> {
            return serviceUuids.map { it.toString() }
        }

        val associationId: Int?
            get() {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    associationInfo?.id
                } else {
                    null
                }
            }

        actual val isBonded: Boolean
            get() {
                return try {
                    dev.bondState == BluetoothDevice.BOND_BONDED
                } catch (_: SecurityException) {
                    false
                }
            }

        suspend fun removePreviousBond(): PakReturnCode {
            if (!checkPermission()) return PakReturnCode.PERMISSION
            setupListener()
            if (dev.bondState == BluetoothDevice.BOND_NONE) return PakReturnCode.NONE
            Log.d(TAG, "Removing bond...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                if (associationInfo != null && dev.bondState != BluetoothDevice.BOND_NONE) {
                    val deviceManager = Pak.activity.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
                    try {
                        if (!deviceManager.removeBond(associationInfo!!.id)) {
                            Log.e(TAG, "removeBond failure")
                            return PakReturnCode.NON_FATAL
                        }
                        withTimeout(10000.milliseconds) { bondSignal.receive() }
                    } catch (_: Exception) { } catch (_: TimeoutCancellationException) {
                        PakReturnCode.NON_FATAL.code
                    }
                }
            } else {
                try {
                    val m = dev.javaClass.getMethod("removeBond")
                    m.invoke(dev)
                } catch (e: Exception) { Log.e(TAG, "removeBond: ${e.message}") }
            }
            if (dev.bondState == BluetoothDevice.BOND_NONE) {
                Log.d(TAG, "Bond was removed")
            } else {
                Log.e(TAG, "Bond was not removed")
            }
            return PakReturnCode.NONE
        }

        fun getManufacturerData(index: Int): ByteArray? {
            if (scanResult == null) return null
            try {
                val set = scanResult!!.scanRecord!!.manufacturerSpecificData
                if (set.isEmpty()) return null
                val `val` = set.valueAt(index)
                val mfgId = set.keyAt(index)
                val newBuf = ByteArray(`val`.size + 2)
                newBuf[0] = (mfgId and 0xff).toByte()
                newBuf[1] = ((mfgId shr 8) and 0xff).toByte()
                System.arraycopy(`val`, 0, newBuf, 2, `val`.size)
                if (VERBOSE) Log.d(TAG, "mfgdata: " + newBuf.contentToString())
                return newBuf
            } catch (_: NullPointerException) {
                return null
            }
        }

        actual val isConnected: Boolean
            get() {
                // This method was added in 2014 (probably API 21+) so it should be okay to use.
                try {
                    val m = dev.javaClass.getMethod("isConnected")
                    return m.invoke(dev) as Boolean
                } catch (e: Exception) {
                    Log.e(TAG, e.message!!)
                    return false
                }
            }

        fun setupListener() {
            if (receiver != null) return
            receiver = object : BroadcastReceiver() {
                @SuppressLint("MissingPermission")
                override fun onReceive(context: Context?, intent: Intent) {
                    val action = intent.action ?: return
                    if (action == BluetoothDevice.ACTION_UUID) {
                        serviceUuids = dev.uuids ?: emptyArray()
                        synchronized(waitForUuid) {
                            waitForUuid.release()
                        }
                    } else if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                        if (dev.bondState == BluetoothDevice.BOND_BONDED || dev.bondState == BluetoothDevice.BOND_NONE) {
                            bondSignal.trySend(Unit)
                        }
                        Log.d(TAG, "Bond state: ${dev.bondState}")
                    } else if (action == BluetoothDevice.ACTION_KEY_MISSING) {
                        keyMissingFromBonding = true
                        bondSignal.trySend(Unit)
                    }
                    Log.d(TAG, "Bt Event: $action")
                }
            }
            val filter = IntentFilter()
            filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            filter.addAction(BluetoothDevice.ACTION_UUID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                filter.addAction(BluetoothDevice.ACTION_KEY_MISSING)
                filter.addAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE)
                Pak.activity.registerReceiver(receiver, filter)
            } else {
                Pak.activity.registerReceiver(receiver, filter)
            }
        }

        fun closeAll() {
            callback!!.struct = null
            try {
                if (receiver != null) Pak.activity.unregisterReceiver(receiver)
                receiver = null
                if (gatt != null) gatt!!.disconnect()
                gatt = null
            } catch (_: SecurityException) { }
        }

        fun findServiceUuid(uuid: String?): UUID? {
            for (u in serviceUuids) {
                Log.d(TAG, u.uuid.toString())
                if (u.uuid.toString() == uuid) return u.uuid
            }
            return null
        }

        private val waitForUuid = Semaphore(0, true)

        init {
            this.name = dev.name ?: "?"
            this.serviceUuids = dev.uuids ?: emptyArray()
            this.address = dev.address
        }

        suspend fun refreshSdpUuids(): PakReturnCode {
            return cancellableRunnable.run {
                if (!checkPermission()) return@run PakReturnCode.PERMISSION
                setupListener()
                if (!dev.fetchUuidsWithSdp()) return@run PakReturnCode.NON_FATAL
                try {
                    waitForUuid.acquire()
                } catch (e: InterruptedException) {
                    return@run PakReturnCode.CANCELLED
                }
                PakReturnCode.NONE
            }
        }

        @Throws(Exception::class)
        fun connectToServiceChannel(uuid: String?): BluetoothSocket? {
            return runBlocking {
                val rc = refreshSdpUuids()
                if (rc == PakReturnCode.CANCELLED) {
                    throw InterruptedException()
                } else if (rc != PakReturnCode.NONE) {
                    throw Exception()
                }
                val u = findServiceUuid(uuid)
                if (u == null) {
                    Log.e(TAG, "UUID not found")
                    throw Exception("UUID not found")
                }
                try {
                    return@runBlocking dev.createInsecureRfcommSocketToServiceRecord(u)
                } catch (e: IOException) {
                    Log.e(TAG, e.toString())
                    throw e
                }
            }
        }

        fun getService(index: Int): BluetoothGattService? {
            if (gatt == null) return null
            try {
                return gatt!!.services[index]
            } catch (e: Exception) {
                Log.e(TAG, "getServices fail: " + e.message)
                return null
            }
        }

        @CalledFromNative
        fun connectGattNative(struct: ByteArray?): Int {
            return runBlocking {
                val callback = NativeBluetoothGattCallback(this@Device)
                callback.struct = struct
                connectGatt(Pak.activity, callback)
            }
        }

        fun createBond(): Int {
            return runBlocking {
                setupListener()
                cancellableRunnable.run {
                    try {
                        keyMissingFromBonding = false
                        if (dev.bondState == BluetoothDevice.BOND_NONE) {
                            if (!dev.createBond()) {
                                Log.e(TAG, "createBond failed")
                                return@run PakReturnCode.IO
                            }
                            if (VERBOSE) Log.d(TAG, "Waiting for bond callback")
                            withTimeout(20000.milliseconds) { bondSignal.receive() }
                            if (keyMissingFromBonding) {
                                return@run PakReturnCode.UNDEFINED
                            } else if (dev.bondState != BluetoothDevice.BOND_BONDED) {
                                return@run PakReturnCode.NON_FATAL
                            }
                        }
                        return@run PakReturnCode.NONE
                    } catch (_: SecurityException) {
                        return@run PakReturnCode.PERMISSION
                    } catch (_: InterruptedException) {
                        return@run PakReturnCode.CANCELLED
                    } catch (_: TimeoutCancellationException) {
                        PakReturnCode.NON_FATAL
                    }
                }.code
            }
        }

        suspend fun connectGatt(ctx: Context?, callback: NativeBluetoothGattCallback): Int {
            return cancellableRunnable.run {
                try {
                    setupListener()
                    gatt = dev.connectGatt(ctx, false, callback)
                    this.callback = callback
                    withTimeout(5000.milliseconds) { callback.connectSignal.receive() }
                    if (!isGattConnected) {
                        Log.e(TAG, "connectGatt failed after timeout")
                        PakReturnCode.NO_CONNECTION
                    } else {

                        //gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                        //gatt.setPreferredPhy(BluetoothDevice.PHY_LE_1M_MASK, BluetoothDevice.PHY_LE_1M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED);
                        //gatt.requestMtu(185);
                        //Thread.sleep(6000);
                        gatt!!.discoverServices()
                        withTimeout(10000.milliseconds) { callback.gattServicesDiscovered.receive() }
                        PakReturnCode.NONE
                    }
                } catch (_: SecurityException) {
                    PakReturnCode.PERMISSION
                } catch (_: InterruptedException) {
                    PakReturnCode.CANCELLED
                } catch (_: TimeoutCancellationException) {
                    PakReturnCode.NON_FATAL
                }
            }.code
        }

        fun getDescriptor(
            characteristic: BluetoothGattCharacteristic,
            index: Int,
        ): BluetoothGattDescriptor? {
            return characteristic.descriptors[index]
        }

        fun getCachedValue(characteristic: BluetoothGattCharacteristic): ByteArray? {
            val value = callback!!.checkCachedUpdate(characteristic, true)
            if (value != null) return value.value
            return characteristic.getValue()
        }

        fun setCccd(characteristic: BluetoothGattCharacteristic, value: Int): Int {
            return runBlocking {
                try {
                    val desc = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) ?: return@runBlocking -1
                    desc.value = byteArrayOf(value.toByte(), 0)
                    if (gatt!!.writeDescriptor(desc)) {
                        withTimeout(10000.milliseconds) { callback!!.gattDescriptorWrote.receive() }
                        PakReturnCode.NONE.code
                    } else PakReturnCode.UNDEFINED.code
                } catch (e: InterruptedException) {
                    throw RuntimeException(e)
                } catch (_: SecurityException) {
                    PakReturnCode.PERMISSION.code
                } catch (_: TimeoutCancellationException) {
                    PakReturnCode.NON_FATAL.code
                }
            }
        }

        fun setNotification(characteristic: BluetoothGattCharacteristic?, v: Boolean): Int {
            try {
                if (gatt!!.setCharacteristicNotification(characteristic, v)) {
                    return 0
                }
                return -1
            } catch (_: SecurityException) {
                return PakReturnCode.PERMISSION.code
            }
        }

        fun waitBlocking(characteristic: BluetoothGattCharacteristic?, ms: Int): Int {
            return runBlocking {
                if (callback!!.checkCachedUpdate(characteristic, false) != null) {
                    0
                } else {
                    try {
                        withTimeout(ms.milliseconds) { callback!!.gattCharacteristicChanged.receive() }
                        if (callback!!.checkCachedUpdate(characteristic, false) == null) -1 else 0
                    } catch (_: SecurityException) {
                        PakReturnCode.PERMISSION.code
                    } catch (_: InterruptedException) {
                        PakReturnCode.UNDEFINED.code
                    } catch (_: TimeoutCancellationException) {
                        PakReturnCode.NON_FATAL.code
                    }
                }
            }
        }

        fun readAsync(characteristic: BluetoothGattCharacteristic): Int {
            return runBlocking {
                if (VERBOSE) Log.d(TAG, "Read ${characteristic.uuid}")
                try {
                    if (gatt!!.readCharacteristic(characteristic)) {
                        withTimeout(10000.milliseconds) { callback!!.gattCharacteristicRead.receive() }
                        0
                    } else {
                        Log.e(TAG, "readCharacteristic")
                        -1
                    }
                } catch (_: SecurityException) {
                    PakReturnCode.PERMISSION.code
                } catch (_: InterruptedException) {
                    PakReturnCode.UNDEFINED.code
                } catch (_: TimeoutCancellationException) {
                    PakReturnCode.NON_FATAL.code
                }
            }
        }

        fun writeAsync(characteristic: BluetoothGattCharacteristic, data: ByteArray?): Int {
            return runBlocking {
                if (VERBOSE) Log.d(TAG, "Write ${data.contentToString()} to ${characteristic.uuid}")
                try {
                    if (!characteristic.setValue(data)) {
                        Log.e(TAG, "setValue")
                        PakReturnCode.UNDEFINED.code
                    } else {
                        if (gatt!!.writeCharacteristic(characteristic)) {
                            withTimeout(10000.milliseconds) { callback!!.gattCharacteristicWrote.receive() }
                            0
                        } else {
                            -1
                        }
                    }
                } catch (_: SecurityException) {
                    PakReturnCode.PERMISSION.code
                } catch (_: InterruptedException) {
                    PakReturnCode.UNDEFINED.code
                } catch (_: TimeoutCancellationException) {
                    PakReturnCode.NON_FATAL.code
                }
            }
        }
    }

    class NativeBluetoothGattCallback internal constructor(val device: Device) :
        BluetoothGattCallback() {
        var struct: ByteArray? = null
        external fun onEvent(code: Int, characteristic: BluetoothGattCharacteristic?)
        val connectSignal = Channel<Unit>()
        val gattServicesDiscovered = Channel<Unit>()
        val gattCharacteristicWrote = Channel<Unit>()
        val gattCharacteristicRead = Channel<Unit>()
        val gattCharacteristicChanged = Channel<Unit>()
        val gattDescriptorWrote = Channel<Unit>()

        class CachedCharUpdates internal constructor(
            var chr: BluetoothGattCharacteristic,
            var value: ByteArray?,
        )

        private val cachedUpdates = HashSet<CachedCharUpdates>()
        fun checkCachedUpdate(
            chr: BluetoothGattCharacteristic?,
            clear: Boolean,
        ): CachedCharUpdates? {
            for (e in cachedUpdates) {
                if (e.chr == chr) {
                    if (clear) cachedUpdates.remove(e)
                    return e
                }
            }
            return null
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (VERBOSE) Log.d(TAG, "Connection state change: $status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onEvent(EVENT_CONNECTED, null)
                device.isGattConnected = true
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                onEvent(EVENT_DISCONNECTED, null)
                device.isGattConnected = false
            }
            connectSignal.trySend(Unit)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (VERBOSE) Log.d(TAG, "Discovered GATT services")
            if (status != BluetoothGatt.GATT_SUCCESS) Log.d(TAG, "onServicesDiscovered reports failure")
            //device.serviceUuids = device.dev.getUuids();
            onEvent(EVENT_SERVICES_DISCOVERED, null)
            gattServicesDiscovered.trySend(Unit)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            Log.d(TAG, "characteristicRead")
            cachedUpdates.add(CachedCharUpdates(characteristic, characteristic.getValue()))
            gattCharacteristicRead.trySend(Unit)
            onEvent(EVENT_GATT_UUID_READ, characteristic)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int,
        ) {
            gattCharacteristicWrote.trySend(Unit)
            onEvent(EVENT_GATT_UUID_WRITTEN, characteristic)
        }

        @Suppress("deprecated")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            if (VERBOSE) Log.d(TAG, "Characteristic changed: ${characteristic.uuid} to ${characteristic.getValue().contentToString()}")
            cachedUpdates.add(CachedCharUpdates(characteristic, characteristic.getValue()))
            gattCharacteristicChanged.trySend(Unit)
            onEvent(EVENT_GATT_UUID_CHANGED, characteristic)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int,
        ) {
            super.onDescriptorRead(gatt, descriptor, status)
            gattDescriptorWrote.trySend(Unit)
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int,
        ) {
            super.onDescriptorRead(gatt, descriptor, status)
            // TODO
        }

        companion object {
            const val EVENT_CONNECTED: Int = 1
            const val EVENT_DISCONNECTED: Int = 2
            const val EVENT_GATT_UUID_WRITTEN: Int = 3
            const val EVENT_GATT_UUID_READ: Int = 4
            const val EVENT_GATT_UUID_CHANGED: Int = 5
            const val EVENT_GATT_DESC_WRITTEN: Int = 6
            const val EVENT_SERVICES_DISCOVERED: Int = 7
        }
    }

    @Suppress("ArrayInDataClass")
    actual data class BtFilter actual constructor(
        actual val namePattern: String?,
        actual val isClassic: Boolean,
        actual val serviceUuids: Array<String>?,
        actual val manufacData: ByteArray?,
        actual val manufacDataMask: ByteArray?,
    )

    actual abstract class ScanCallback {
        actual abstract fun onFound(device: Device)
        actual abstract fun onFailure(reason: String)
        actual abstract fun onCancel()
    }

    @Deprecated("TODO: Finish this")
    fun leScanner(): PakReturnCode {
        if (Pak.activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return PakReturnCode.PERMISSION
        }

        val scanner: BluetoothLeScanner? = defaultAdapter!!.bluetoothLeScanner

        val filters: MutableList<ScanFilter?> = ArrayList()

        val builder = ScanFilter.Builder()
        val filter = builder.build()
        filters.add(filter)

        val scanSettingsBuilder = ScanSettings.Builder()
        val scanSettings = scanSettingsBuilder.build()

        scanner!!.startScan(filters, scanSettings, object : android.bluetooth.le.ScanCallback() {
            override fun onBatchScanResults(results: MutableList<ScanResult?>?) {
                super.onBatchScanResults(results)
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
            }

            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                super.onScanResult(callbackType, result)
            }
        })

        return PakReturnCode.NONE
    }
}
