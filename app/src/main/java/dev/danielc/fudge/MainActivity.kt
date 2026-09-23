package dev.danielc.fudge

import android.app.ComponentCaller
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.compose.rememberNavController
import dev.danielc.BuildConfig
import dev.danielc.common.ModuleInstanceRequest
import dev.danielc.common.Runtime
import dev.danielc.common.screens.MainNav
import dev.danielc.libpak.Pak
import dev.danielc.libpak.WiFi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Event(
    val name: String,
    val setupOption: String?,
)
private val externalEvents = MutableSharedFlow<Event>(replay = 1)
private val externalEventsShared = externalEvents.asSharedFlow()

fun startModule(name: String, setupOption: String?) {
    CoroutineScope(Dispatchers.IO).launch {
        externalEvents.emit(Event(name, setupOption))
    }
}

object BuildInfo {
    val time = SimpleDateFormat("MMMM dd yyyy", Locale.getDefault()).format(
        Date(
            BuildConfig.BUILD_TIME
        )
    )
    val isNightly = BuildConfig.FLAVOR == "nightly"
    val isDebug = BuildConfig.DEBUG
    val packageName = BuildConfig.APPLICATION_ID
    val version = BuildConfig.VERSION_NAME
    val osVersion = Build.VERSION.SDK_INT
}

class MainActivity : ComponentActivity(), ComponentCallbacks2 {
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String?>,
        grantResults: IntArray,
        deviceId: Int
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults, deviceId)
        Pak.onPermissionResult(requestCode, permissions, grantResults);
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        caller: ComponentCaller
    ) {
        super.onActivityResult(requestCode, resultCode, data, caller)
        Pak.onActivityResult(requestCode, resultCode, data)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d("main", "onTrimMemory")
        for (e in Runtime.moduleInstances) {
            e.value.trimMemory()
        }
        CoroutineScope(Dispatchers.Default).launch {
            Runtime.emitMemoryTrimSignal()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AndroidRuntime.hasInited) {
            AndroidRuntime.hasInited = true
            Pak.setupAndroidContext(this)
            System.loadLibrary("fudge")
            AndroidRuntime.setup(this)
            WiFi.startNetworkListeners(this)
            CoroutineScope(Dispatchers.IO).launch {
                Runtime.refreshManifests()
                Runtime.refreshConnectableDevices()
            }
        }
        enableEdgeToEdge()
        setContent {
            val navController = rememberNavController()
            MainNav(navController)
            var hasLaunched by rememberSaveable { mutableStateOf(false) }
            if (!hasLaunched) {
                LaunchedEffect(Unit) {
                    hasLaunched = true
                    externalEventsShared.collect {
                        navController.navigate(ModuleInstanceRequest(it.name, targetIndex = 0, chosenSetupOption = it.setupOption))
                    }
                }
                if (false && BuildInfo.isDebug) {
                    LaunchedEffect(Unit) {
                        startModule("dummymod", "camera")
                    }
                }
            }
        }
    }
}