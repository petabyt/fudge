// Android WiFi interface
package dev.danielc.fudge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.companion.WifiDeviceFilter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.NetworkSpecifier
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.PatternMatcher
import android.provider.Settings
import android.util.Log
import dev.danielc.fudge.Pak.activity
import dev.danielc.fudge.Pak.companionAssociateGetResultBlocking
import dev.danielc.fudge.Pak.requirePermissionBlocking
import java.util.concurrent.Semaphore
import java.util.regex.Pattern
import androidx.core.net.toUri

actual object WiFi {
    const val TAG: String = "wifi"
    private val cancellableRunnable = Pak.CancellableRunnable()
    actual fun interruptAll() {
        cancellableRunnable.cancelAll()
    }

    actual val requiresManualAccessPointConnection: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    actual fun checkPermission(): Boolean {
        // New Android 17 permissions:
        // https://developer.android.com/privacy-and-security/local-network-permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            if (activity.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) return false
        }
        // https://developer.android.com/develop/connectivity/wifi/wifi-permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (activity.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }

    actual suspend fun requestConnectPermission() {
        // TODO: 'Can request only one set of permissions at a time'
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            requirePermissionBlocking(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requirePermissionBlocking(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    @JvmStatic
    actual val primaryAdapter: Adapter?
        get() {
            if (primaryNetworkDevice == null) return null
            return Adapter(primaryNetworkDevice!!)
        }

    actual val isWiFiEnabled: Boolean
        get() {
            val wifiMgr =
                activity.getSystemService(Context.WIFI_SERVICE) as WifiManager
            return wifiMgr.isWifiEnabled
        }

    private var primaryNetworkDevice: Network? = null
    private var lastFoundWiFiDevice: Network? = null

    /** Opens an Android 10+ popup to prompt the user to select a WiFi network
     * When bssid is provided in the filter, there will be no prompt at all.
     */
    suspend fun connectToAccessPoint(filter: ApFilter, callback: WiFiDiscoveryCallback): PakReturnCode {
        val ctx: Context = activity
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return PakReturnCode.UNSUPPORTED
        }

        Log.d(TAG, filter.toString())

        val connectivityManager =
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val builder = WifiNetworkSpecifier.Builder()
        if (filter.ssidPattern != null) {
            if (filter.hidden) {
                builder.setSsid(filter.ssidPattern)
            } else {
                builder.setSsidPattern(
                    PatternMatcher(
                        filter.ssidPattern,
                        PatternMatcher.PATTERN_ADVANCED_GLOB
                    )
                )
            }
        }
        if (filter.bssid != null) {
            builder.setBssid(MacAddress.fromString(filter.bssid))
        }
        if (filter.password != null) {
            if (filter.securityType.startsWith("wpa2")) {
                builder.setWpa2Passphrase(filter.password)
            } else {
                builder.setWpa3Passphrase(filter.password)
            }
        }
        builder.setIsHiddenSsid(filter.hidden)
        val specifier: NetworkSpecifier = builder.build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val maxAttempts = 2

        // NOTE: This callback will stick around for the lifetime of the connection, long after this function returns.
        class MyCallback : ConnectivityManager.NetworkCallback() {
            val waitForCallback: Semaphore = Semaphore(0, true)
            var attempts: Int = 0
            var onAvailableCalled: Boolean = false
            var adapter: Adapter? = null
            override fun onAvailable(network: Network) {
                lastFoundWiFiDevice = network
                Log.d(TAG, "Network available")
                if (!onAvailableCalled) {
                    onAvailableCalled = true
                    adapter = Adapter(network)
                    adapter!!.callback = this
                    callback.onConnected(adapter!!)
                }
                waitForCallback.release()
            }

            override fun onUnavailable() {
                if (++attempts > maxAttempts || onAvailableCalled) {
                    callback.failed("Network is not available", -1)
                    waitForCallback.release()
                } else {
                    if (filter.hidden || filter.bssid != null) {
                        connectivityManager.requestNetwork(request, this)
                    } else {
                        connectivityManager.requestNetwork(request, this, 25000)
                    }
                }
            }

            override fun onLost(network: Network) {
                if (adapter != null) adapter!!.invalidateNetwork()
                Log.d(TAG, "onLost")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                Log.d(TAG, "onCapabilitiesChanged")
            }
        }

        val networkCallback = MyCallback()

        return cancellableRunnable.run {
            // Stock Android seems to cut off the dialog at 30s and never calls onUnavailable,
            // so set the time limit a bit short of that to try and make sure onUnavailable gets called
            if (filter.hidden || filter.bssid != null) {
                connectivityManager.requestNetwork(request, networkCallback)
            } else {
                connectivityManager.requestNetwork(request, networkCallback, 25000)
            }

            try {
                synchronized(networkCallback.waitForCallback) {
                    networkCallback.waitForCallback.acquire()
                }
            } catch (_: InterruptedException) {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                return@run PakReturnCode.CANCELLED
            }
            PakReturnCode.NONE
        }
    }

    private fun probablyIsRegularExpression(s: String): Boolean {
        return s.contains("*") || s.contains(".") || s.contains("!") || s.contains("^")
    }

    /// Opens a dialog to save an access point as a companion device
    /// Then uses NetworkRequest to connect to the access point
    actual suspend fun connectToAccessPointCompanion(
        apFilters: MutableList<ApFilter>,
        companionName: String,
        wifiCallback: WiFiDiscoveryCallback,
        dontAssociate: Boolean,
    ): PakReturnCode {
        if (apFilters.isEmpty()) return PakReturnCode.UNDEFINED
        val ctx: Context = activity
        var associationInfo: AssociationInfo? = null
        var scanResult: ScanResult? = null

        var skipCompanionDialog = false
        for (i in apFilters.indices) {
            // Skip companion dialog if AP BSSID was already associated
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !apFilters[i].hidden) {
                val deviceManager =
                    activity.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
                for (address in deviceManager.getAssociations()) {
                    if (address == apFilters[i].bssid) {
                        skipCompanionDialog = true
                        break
                    }
                }

                // Skip companion dialog and set BSSID if already associated
                // TODO: This is probably not necessary
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    for (a in deviceManager.myAssociations) {
                        try {
                            val r = a.associatedDevice!!.wifiDevice
                            if (r!!.SSID == apFilters[i].ssidPattern) {
                                apFilters[i] = apFilters[i].copy(bssid = r.BSSID)
                                skipCompanionDialog = true
                                break
                            }
                        } catch (_: NullPointerException) { }
                    }
                }
            }
        }

        // Not great assumption
        var chosenAp = apFilters[0]

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !skipCompanionDialog) {
            val deviceManager = ctx.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
            val associationBuilder = AssociationRequest.Builder()
            for (filter in apFilters) {
                val builder = WifiDeviceFilter.Builder()
                if (filter.ssidPattern != null) {
                    builder.setNamePattern(Pattern.compile(filter.ssidPattern))
                }
                if (filter.bssid != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        builder.setBssid(MacAddress.fromString(filter.bssid))
                    }
                }
                associationBuilder.addDeviceFilter(builder.build())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                associationBuilder.setDisplayName(companionName)
            }
            //            if (!probablyIsRegularExpression(apFilter.ssidPattern)) {
//                Log.d(TAG, apFilter.ssidPattern + " doesn't look like regex, setting singleDevice to true");
//                associationBuilder.setSingleDevice(true);
//            }
            // Probably not looking for more than 1 device. Also uses newer android dialog
            associationBuilder.setSingleDevice(true)
            val request = associationBuilder.build()
            try {
                val intent = companionAssociateGetResultBlocking(deviceManager, request)
                if (intent == null) {
                    wifiCallback.onUserCancelled()
                    return PakReturnCode.CANCELLED // User did not select a device
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    associationInfo =
                        intent.getParcelableExtra<AssociationInfo?>(CompanionDeviceManager.EXTRA_ASSOCIATION)
                    //Pak.deleteDuplicateAssociations(associationInfo);
                }
                scanResult =
                    intent.getParcelableExtra<ScanResult?>(CompanionDeviceManager.EXTRA_DEVICE)
                // According to https://medium.com/@mike_21858/wifinetworkspecifier-prompts-and-localonlyhotspot-f596c7b84968
                // if the WiFi AP is saved as a companion device (via the BSSID) then NetworkRequest will not show
                // any system dialog and will automatically connect.
                if (scanResult != null) {
                    if (dontAssociate) {
                        //deviceManager.disassociate(scanResult.BSSID);
                    }

                    chosenAp = chosenAp.copy(
                        ssidPattern = scanResult.SSID,
                        bssid = scanResult.BSSID
                    )
                }
            } catch (e: Pak.CancelException) {
                wifiCallback.onUserCancelled()
                return PakReturnCode.CANCELLED
            } catch (e: SecurityException) {
                return PakReturnCode.PERMISSION
            } catch (e: Exception) {
                return PakReturnCode.UNDEFINED
            }
        }

        val finalScanResult = scanResult
        val finalAssociationInfo = associationInfo
        wifiCallback.onConnecting(if (scanResult == null) chosenAp.ssidPattern else scanResult.SSID)
        return connectToAccessPoint(chosenAp, object : WiFiDiscoveryCallback() {
            override fun onConnected(net: Adapter) {
                Log.d(TAG, "Connected to network")
                net.apAssociation = finalAssociationInfo
                net.savedPassword = chosenAp.password
                net.apScanResult = finalScanResult
                wifiCallback.onConnected(net)
            }

            override fun failed(reason: String, code: Int) {
                wifiCallback.failed(reason, code)
            }
        })
    }

    suspend fun connectToAccessPointCompanion(
        apFilter: MutableList<ApFilter>,
        companionName: String,
        wifiCallback: WiFiDiscoveryCallback,
    ): PakReturnCode {
        return connectToAccessPointCompanion(apFilter, companionName, wifiCallback, false)
    }

    /// Start listener to obtain primary network (internet access) handle
    fun startNetworkListeners(ctx: Context) {
        // TODO: If wifi isn't available, it should fall back to cellular
        val m = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val requestBuilder = NetworkRequest.Builder()
        requestBuilder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)

        val networkCallback: ConnectivityManager.NetworkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Wifi network is available: $network")
                    primaryNetworkDevice = network
                }

                override fun onLost(network: Network) {
                    Log.e(TAG, "Lost network\n")
                    primaryNetworkDevice = null
                }

                override fun onUnavailable() {
                    Log.e(TAG, "Network unavailable\n")
                    primaryNetworkDevice = null
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    Log.e(TAG, "capabilities changed")
                }
            }

        try {
            m.requestNetwork(requestBuilder.build(), networkCallback)
        } catch (e: Exception) {
            Log.d(TAG, e.toString())
        }
    }

    /** Determine if the device is handling two different WiFi connections at the same time, on the same band.
     * On Android 12+ devices, this causes a 2x rx/tx speed hit.
     * https://source.android.com/docs/core/connect/wifi-sta-sta-concurrency#local-only  */
    fun isHandlingConflictingConnections(a: Network?, b: Network?): Boolean {
        val connectivityManager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isNetworkValid(a) && isNetworkValid(b)) {
                val c1 = connectivityManager.getNetworkCapabilities(a) ?: return false
                val info1 = c1.transportInfo as? WifiInfo? ?: return false
                val mainBand = info1.frequency / 100
                val c2 = connectivityManager.getNetworkCapabilities(b) ?: return false
                val info2 = c2.transportInfo as? WifiInfo? ?: return false
                if (info1 == info2) return false // Android may sometimes give the same object in both listeners
                val secondBand = info2.frequency / 100
                return mainBand == secondBand
            }
        }
        return false
    }

    fun isNetworkValid(net: Network?): Boolean {
        val connectivityManager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager? ?: return false
        val wifiInfo = connectivityManager.getNetworkInfo(net ?: return false) ?: return false
        return wifiInfo.isAvailable()
    }

    // If we go through WifiNetworkSpecifier, then the device may be handling two concurrent connections.
    // This is up to a 2x performance hit.
    fun isWiFiModuleCapableOfHandlingTwoConnections(ctx: Context): Boolean {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Query whether the device supports concurrent station (STA) connections
            // for local-only connections using WifiNetworkSpecifier.
            return wm.isStaConcurrencyForLocalOnlyConnectionsSupported
        }
        // If below 31, then Android supposedly doesn't support concurrent connections at all
        return false
    }

    fun isHotSpotEnabled(ctx: Context): Boolean {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            @SuppressLint("PrivateApi") val m = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
            m.isAccessible = true
            if (m.invoke(wm) as Boolean == false) {
                return false
            }
        } catch (ignored: Exception) {
            return false
        }

        return true
    }

    fun addNetworkToSystem(filter: ApFilter): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val builder = WifiNetworkSuggestion.Builder()

            if (filter.ssidPattern != null) {
                builder.setSsid(filter.ssidPattern)
            }
            if (filter.bssid != null) {
                builder.setBssid(MacAddress.fromString(filter.bssid))
            }
            if (filter.password != null) {
                if (filter.securityType.startsWith("wpa2")) {
                    builder.setWpa2Passphrase(filter.password)
                } else {
                    builder.setWpa3Passphrase(filter.password)
                }
            }
            builder.setIsHiddenSsid(filter.hidden)

            val suggestionList = ArrayList<WifiNetworkSuggestion?>()
            suggestionList.add(builder.build())

            val bundle = Bundle()
            bundle.putParcelableArrayList(Settings.EXTRA_WIFI_NETWORK_LIST, suggestionList)

            val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
            intent.putExtras(bundle)

            activity.startActivity(intent)
        }
        return 0
    }

    fun connectToApLegacy(): Int {
        // Below Android 10 uses this API
//        WifiManager wifiManager = (WifiManager) Pak.getActivity().getSystemService(Context.WIFI_SERVICE);
//        int netId = wifiManager.addNetwork(wifiConfig);
//        if (netId != -1) {
//            wifiManager.disconnect()
//            wifiManager.enableNetwork(netId, true)
//            wifiManager.reconnect()
//        }
        return 0
    }

    @SuppressLint("MissingPermission")
    @Throws(Exception::class)
    fun scan(ctx: Context) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            wm.scanResults
            // ...
        } else {
            throw Exception("bad permission")
        }
    }

    fun openHotSpotSettings(ctx: Context) {
        val tetherSettings = Intent()
        tetherSettings.setClassName("com.android.settings", "com.android.settings.TetherSettings")
        ctx.startActivity(tetherSettings)
    }

    fun goToSettings(ctx: Context) {
        val goToSettings = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
        goToSettings.data = "package:${ctx.packageName}".toUri()
        ctx.startActivity(goToSettings)
    }

    fun openWiFiSettings(ctx: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ctx.startActivityForResult(Intent(Settings.Panel.ACTION_WIFI), 0)
        } else {
            ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    fun startAccessPoint(context: Activity?, filter: ApFilter?) {
        // Look into what PDANet+ does
    }

    actual class Adapter internal constructor(net: Network) {
        var savedPassword: String? = null
        var apScanResult: ScanResult? = null
        var apAssociation: AssociationInfo? = null
        var net: Network?
        var callback: ConnectivityManager.NetworkCallback? = null
        var handle: Long

        init {
            this.net = net
            this.handle = net.networkHandle
        }

        actual fun invalidateNetwork() {
            net = null
        }

        actual val isAvailable: Boolean
            get() = net != null

        actual fun disassociate() {
            val connectivityManager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(callback!!)
        }

        val associationId: Int?
            get() {
                if (apAssociation == null) return null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    return apAssociation!!.getId()
                } else {
                    return null
                }
            }
        val macAddress: String?
            get() {
                if (apScanResult == null) return null
                return apScanResult!!.BSSID
            }
    }

    actual data class ApFilter actual constructor(
        actual val ssidPattern: String?,
        actual val bssid: String?,
        // TODO: bssidFilter
        actual val password: String?,
        actual val band: Int,
        actual val hidden: Boolean,
    ) {
        actual val securityType: String = "wpa2"
    }

    actual abstract class WiFiDiscoveryCallback {
        actual abstract fun onConnected(net: Adapter)
        actual abstract fun failed(reason: String, code: Int)
        actual open fun onUserCancelled() {}

        /// On beginning to search for network
        actual open fun onConnecting(ssid: String?) {}
    }
}