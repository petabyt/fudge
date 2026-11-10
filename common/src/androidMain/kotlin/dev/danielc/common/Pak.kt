/// Small helper class to help with permissions and Android native APIs
package dev.danielc.common

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

actual object Pak {
    const val TAG: String = "pak"

    /// Hold a weak reference to main activity for permission requests
    var weakCtx: WeakReference<Activity>? = null
    actual var callbacks: Callbacks? = null

    fun setupAndroidContext(ctx: Activity?) {
        weakCtx = WeakReference<Activity>(ctx)

        if (Build.VERSION.SDK_INT >= 36) {
            val deviceManager = activity.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
            val associations = deviceManager.myAssociations
            for (m in associations) {
                Log.d(TAG, "Observing: ${m.displayName} ${m.deviceMacAddress}")
                deviceManager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(m.getId()).build())
            }
        }
    }

    private val perm = Semaphore(0, true)
    private var permissionResult = 0
    var lastIntent: Intent? = null
    private val cancellableRunnable = CancellableRunnable()
    actual fun interruptAll() {
        cancellableRunnable.cancelAll()
    }

    @get:Suppress("unused")
    val activity: Activity get() = weakCtx!!.get()!!

    @Suppress("unused")
    fun requestPermissionBlocking(permission: String?): Boolean {
        return false
    }

    fun startActivity(intent: Intent?) {
        activity.startActivity(intent)
    }

    fun onPermissionResult(requestCode: Int, permissions: Array<out String?>, grantResults: IntArray) {
        Log.d(TAG, "onPermissionResult")
        if (grantResults.size == 0) {
            permissionResult = PackageManager.PERMISSION_DENIED
        } else {
            permissionResult = grantResults[0]
        }
        perm.release()
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d(TAG, "onActivityResult")
        lastIntent = data
        perm.release()
    }

    fun waitForActivityResult() {
        try {
            perm.acquire()
        } catch (_: Exception) { }
    }

    @Throws(Exception::class)
    fun startActivityForResult(sender: IntentSender?) {
        val ctx: Activity = activity
        ctx.startIntentSenderForResult(sender, 1001, null, 0, 0, 0)
    }

    suspend fun requirePermissionBlocking(permission: String?): Boolean {
        val ctx: Activity = activity
        if (ctx.checkSelfPermission(permission!!) == PackageManager.PERMISSION_GRANTED) return true
        Handler(ctx.mainLooper).post {
            ctx.requestPermissions(arrayOf<String?>(permission), 1)
        }

        cancellableRunnable.run {
            try {
                perm.acquire()
            } catch (_: InterruptedException) { }
            PakReturnCode.NONE
        }
        return permissionResult == PackageManager.PERMISSION_GRANTED
    }

    fun startListeningToDevicePresence(macAddress: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val deviceManager = activity.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
            if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
                Log.d(TAG, "Start observing $macAddress")
                deviceManager.startObservingDevicePresence(macAddress)
            }
        }
    }

    fun startListeningToDevicePresence(associationId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val deviceManager = activity.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
            if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
                Log.d(TAG, "Start observing $associationId")
                deviceManager.startObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(associationId).build()
                )
            }
        }
    }

    fun resetAllAssociations() {
        // TODO: Use mac address instead of association ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val deviceManager = activity.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
            val list = deviceManager.getAssociations()
            for (i in list) {
                try {
                    deviceManager.disassociate(i)
                } catch (ignored: NullPointerException) {
                }
            }
        }
    }

    fun deleteDuplicateAssociations(info: AssociationInfo) {
        // TODO: Use mac address instead of association ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val deviceManager = activity.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
            val list = deviceManager.myAssociations
            for (i in list) {
                try {
                    if (i.deviceMacAddress.toString() == info.deviceMacAddress.toString() && i.id != info.id) {
                        deviceManager.disassociate(i.id)
                    }
                } catch (_: NullPointerException) { }
            }
        }
    }

    fun disassociate(macAddress: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val deviceManager = activity.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
            if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
                deviceManager.disassociate(macAddress)
            }
        }
    }

    @Throws(SecurityException::class, CancelException::class)
    suspend fun companionAssociateGetResultBlocking(manager: CompanionDeviceManager, request: AssociationRequest): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw SecurityException("Unsupported")
        }

        class MyCallback : CompanionDeviceManager.Callback() {
            val waitForCallback: Semaphore = Semaphore(0, true)
            var returnCode = PakReturnCode.NONE
            var waitForActivity: Boolean = false
            override fun onFailure(error: CharSequence?) {
                if (error != null && error == "canceled") {
                    returnCode = PakReturnCode.CANCELLED
                } else {
                    returnCode = PakReturnCode.NON_FATAL
                }
                waitForCallback.release()
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                super.onAssociationCreated(associationInfo)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    manager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(associationInfo.id).build())
                }
            }

            override fun onDeviceFound(intentSender: IntentSender) {
                super.onDeviceFound(intentSender)
                try {
                    startActivityForResult(intentSender)
                    waitForActivity = true
                } catch (e: Exception) {
                    Log.d(TAG, e.message!!)
                    returnCode = PakReturnCode.CANCELLED
                    return
                }

                waitForCallback.release()
            }
        }

        val callback = MyCallback()
        manager.associate(request, callback, null)

        val rc = cancellableRunnable.run {
            try {
                callback.waitForCallback.acquire()
            } catch (_: InterruptedException) {
                return@run PakReturnCode.CANCELLED
            }
            if (callback.waitForActivity) {
                waitForActivityResult()
                if (callback.returnCode != PakReturnCode.NONE) return@run PakReturnCode.CANCELLED
                if (lastIntent == null) return@run PakReturnCode.UNDEFINED
                return@run PakReturnCode.NONE
            }
            PakReturnCode.CANCELLED
        }
        if (rc == PakReturnCode.CANCELLED) throw CancelException()
        return lastIntent
    }

    class CancelException : Exception()

    /// For cancelling blocking routines
    /// Can be used like new CancellableRunnable().run(() => {return 0;})
    class CancellableRunnable {
        private val activeJobs = ConcurrentHashMap.newKeySet<Job>()
        suspend fun run(block: suspend () -> PakReturnCode): PakReturnCode = coroutineScope {
            val job = launch(Dispatchers.IO) {}
            activeJobs.add(job)
            try {
                return@coroutineScope withContext(Dispatchers.IO) { block() }
            } finally {
                activeJobs.remove(job)
            }
        }
        fun cancelAll() {
            for (job in activeJobs) { job.cancel() }
            activeJobs.clear()
        }
    }

    actual abstract class Callbacks {
        actual abstract fun onDeviceAppearance(address: String?, isNearby: Boolean)
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    class MyCompanionDeviceService : CompanionDeviceService() {
        override fun onCreate() {
            super.onCreate()
            Log.d(TAG, "Companion Device Service Created")
        }

        override fun onDeviceAppeared(address: String) {
            super.onDeviceAppeared(address)
            Log.d(TAG, "onDeviceAppeared: " + address)
            if (callbacks != null) callbacks!!.onDeviceAppearance(address, true)
        }

        override fun onDeviceDisappeared(address: String) {
            super.onDeviceDisappeared(address)
            Log.d(TAG, "onDeviceDisappeared")
            if (callbacks != null) callbacks!!.onDeviceAppearance(address, false)
        }

        override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
            super.onDevicePresenceEvent(event)
            Log.d(TAG, "onDevicePresenceEvent")
        }
    }
}
