// Android WiFi interface
package dev.danielc.libpak;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.companion.WifiDeviceFilter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiNetworkSuggestion;
import android.net.wifi.WifiSsid;
import android.os.Build;
import android.os.Bundle;
import android.os.PatternMatcher;
import android.provider.Settings;
import android.util.Log;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import androidx.annotation.NonNull;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

public class WiFi {
    public static final String TAG = "wifi";
    private static final Pak.CancellableRunnable cancellableRunnable = new Pak.CancellableRunnable();
    public static void interruptAll() {
        cancellableRunnable.cancelAll();
    }
    public static final boolean requiresManualAccessPointConnection = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q;

    public static class Adapter {
        Adapter(Network net) {
            this.net = net;
            this.handle = net.getNetworkHandle();
        }
        public String savedPassword;
        public ScanResult apScanResult;
        AssociationInfo apAssociation;
        Network net;
        long handle;

        public Integer getAssociationId() {
            if (apAssociation == null) return null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return apAssociation.getId();
            } else {
                return null;
            }
        }
        public String getMacAddress() {
            if (apScanResult == null) return null;
            return apScanResult.BSSID;
        }
    }

    public static boolean checkPermission() {
        // New Android 17 permissions:
        // https://developer.android.com/privacy-and-security/local-network-permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            if (Pak.getActivity().checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) return false;
        }
        // https://developer.android.com/develop/connectivity/wifi/wifi-permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            if (Pak.getActivity().checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    public static void requestConnectPermission() {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            Pak.requirePermissionBlocking(Manifest.permission.ACCESS_LOCAL_NETWORK);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Pak.requirePermissionBlocking(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
    }

    public static Adapter getPrimaryAdapter() {
        if (primaryNetworkDevice == null) return null;
        return new Adapter(primaryNetworkDevice);
    }

    public static class ApFilter {
        public ApFilter(String ssidPattern, String bssid, String password, int band, boolean hidden) {
            this.ssidPattern = ssidPattern;
            this.bssid = bssid;
            this.password = password;
            this.band = band;
            this.hidden = hidden;
        }
        @Override
        public @NonNull String toString() {
            return "ApFilter{" +
                    "ssidPattern='" + ssidPattern + '\'' +
                    ", bssid='" + bssid + '\'' +
                    ", password='" + password + '\'' +
                    ", securityType='" + securityType + '\'' +
                    ", band=" + band +
                    ", hidden=" + hidden +
                    '}';
        }
        public ApFilter() {
            this(null, null, null, -1, false);
        }
        public String ssidPattern;
        public String bssid;
        public String password;
        public String securityType = "wpa2";
        public int band;
        public boolean hidden;
    }

    public static boolean isWiFiEnabled() {
        WifiManager wifiMgr = (WifiManager) Pak.getActivity().getSystemService(Context.WIFI_SERVICE);
        return wifiMgr.isWifiEnabled();
    }

    static Network primaryNetworkDevice = null;
    static Network lastFoundWiFiDevice = null;

    public static abstract class WiFiDiscoveryCallback {
        public abstract void onConnected(@NonNull Adapter net);
        public abstract void failed(@NonNull String reason, int code);
        public void onUserCancelled() {}
        /// On beginning to search for network
        public void onConnecting(String ssid) {}
    }

    public static class NativeWiFiDiscoveryCallback extends WiFiDiscoveryCallback {
        byte[] struct;
        @Override
        public native void onConnected(@NonNull Adapter net);
        @Override
        public native void failed(@NonNull String reason, int code);
    }

    /** Opens an Android 10+ popup to prompt the user to select a WiFi network
     * When bssid is provided in the filter, there will be no prompt at all.
     * */
    public static int connectToAccessPoint(ApFilter filter, WiFiDiscoveryCallback callback) {
        Context ctx = Pak.getActivity();
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return Pak.Error.UNSUPPORTED;
        }

        Log.d(TAG, filter.toString());

        ConnectivityManager connectivityManager = (ConnectivityManager)ctx.getSystemService(Context.CONNECTIVITY_SERVICE);

        WifiNetworkSpecifier.Builder builder = new WifiNetworkSpecifier.Builder();
        if (filter.ssidPattern != null) {
            if (filter.hidden) {
                builder.setSsid(filter.ssidPattern);
            } else {
                builder.setSsidPattern(new PatternMatcher(filter.ssidPattern, PatternMatcher.PATTERN_ADVANCED_GLOB));
            }
        }
        if (filter.bssid != null) {
            builder.setBssid(MacAddress.fromString(filter.bssid));
        }
        if (filter.password != null) {
            if (filter.securityType == null || filter.securityType.startsWith("wpa2")) {
                builder.setWpa2Passphrase(filter.password);
            } else {
                builder.setWpa3Passphrase(filter.password);
            }
        }
        builder.setIsHiddenSsid(filter.hidden);
        NetworkSpecifier specifier = builder.build();

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();

        int maxAttempts = 2;

        class MyCallback extends ConnectivityManager.NetworkCallback {
            final Semaphore waitForCallback = new Semaphore(0, true);
            int attempts = 0;
            boolean onAvailableCalled = false;
            @Override
            public void onAvailable(@NonNull Network network) {
                lastFoundWiFiDevice = network;
                Log.d(TAG, "Network available");
                if (!onAvailableCalled) {
                    onAvailableCalled = true;
                    callback.onConnected(new Adapter(network));
                }
                waitForCallback.release();
            }
            @Override
            public void onUnavailable() {
                if (++attempts > maxAttempts || onAvailableCalled) {
                    callback.failed("Network is not available", -1);
                    waitForCallback.release();
                } else {
                    if (filter.hidden || filter.bssid != null) {
                        connectivityManager.requestNetwork(request, this);
                    } else {
                        connectivityManager.requestNetwork(request, this, 25000);
                    }
                }
            }
            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
                Log.e(TAG, "onCapabilitiesChanged");
            }
        }

        MyCallback networkCallback = new MyCallback();

        return cancellableRunnable.run(() -> {
            // Stock Android seems to cut off the dialog at 30s and never calls onUnavailable,
            // so set the time limit a bit short of that to try and make sure onUnavailable gets called
            if (filter.hidden || filter.bssid != null) {
                connectivityManager.requestNetwork(request, networkCallback);
            } else {
                connectivityManager.requestNetwork(request, networkCallback, 25000);
            }

            try {
                synchronized (networkCallback.waitForCallback) {
                    networkCallback.waitForCallback.acquire();
                }
            } catch (InterruptedException ignored) {
                connectivityManager.unregisterNetworkCallback(networkCallback);
                return Pak.Error.CANCELLED;
            }
            return 0;
        });
    }

    private static boolean probablyIsRegularExpression(String s) {
        return s.contains("*") || s.contains(".") || s.contains("!") || s.contains("^");
    }

    /// Opens a dialog to save an access point as a companion device
    /// Then uses NetworkRequest to connect to the access point
    public static int connectToAccessPointCompanion(ApFilter apFilter, String companionName, WiFiDiscoveryCallback wifiCallback, boolean dontAssociate) {
        Context ctx = Pak.getActivity();
        AssociationInfo associationInfo = null;
        ScanResult scanResult = null;
        boolean skipCompanionDialog = apFilter.hidden;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !apFilter.hidden) {
            // Skip companion dialog if AP BSSID was already associated
            CompanionDeviceManager deviceManager = (CompanionDeviceManager) Pak.getActivity().getSystemService(Context.COMPANION_DEVICE_SERVICE);
            for (String address : deviceManager.getAssociations()) {
                if (address.equals(apFilter.bssid)) {
                    skipCompanionDialog = true;
                    break;
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Skip companion dialog and set BSSID if already associated
                // TODO: This is probably not necessary
                for (AssociationInfo i : deviceManager.getMyAssociations()) {
                    try {
                        ScanResult r = i.getAssociatedDevice().getWifiDevice();
                        if (r.SSID.equals(apFilter.ssidPattern)) {
                            apFilter.bssid = r.BSSID;
                            skipCompanionDialog = true;
                            break;
                        }
                    } catch (NullPointerException ignored) {
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !skipCompanionDialog) {
            CompanionDeviceManager deviceManager = (CompanionDeviceManager)ctx.getSystemService(Context.COMPANION_DEVICE_SERVICE);

            WifiDeviceFilter.Builder builder = new WifiDeviceFilter.Builder();
            if (apFilter.ssidPattern != null) {
                builder.setNamePattern(Pattern.compile(apFilter.ssidPattern));
            }
            if (apFilter.bssid != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setBssid(MacAddress.fromString(apFilter.bssid));
                }
            }
            WifiDeviceFilter filter = builder.build();

            AssociationRequest.Builder associationBuilder = new AssociationRequest.Builder();
            associationBuilder.addDeviceFilter(filter);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                associationBuilder.setDisplayName(companionName);
            }
//            if (!probablyIsRegularExpression(apFilter.ssidPattern)) {
//                Log.d(TAG, apFilter.ssidPattern + " doesn't look like regex, setting singleDevice to true");
//                associationBuilder.setSingleDevice(true);
//            }
            associationBuilder.setSingleDevice(true); // Uses newer android dialog
            AssociationRequest request = associationBuilder.build();
            try {
                Intent intent = Pak.companionAssociateGetResultBlocking(deviceManager, request);
                if (intent == null) {
                    wifiCallback.onUserCancelled();
                    return Pak.Error.CANCELLED; // User did not select a device
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    associationInfo = intent.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION);
                    Pak.deleteDuplicateAssociations(associationInfo);
                }
                scanResult = intent.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE);
                // According to https://medium.com/@mike_21858/wifinetworkspecifier-prompts-and-localonlyhotspot-f596c7b84968
                // if the WiFi AP is saved as a companion device (via the BSSID) then NetworkRequest will not show
                // any system dialog and will automatically connect.
                if (scanResult != null) {
                    if (dontAssociate) {
                        //deviceManager.disassociate(scanResult.BSSID);
                    }

                    apFilter.ssidPattern = scanResult.SSID;
                    apFilter.bssid = scanResult.BSSID;
                }
            } catch (Pak.CancelException e) {
                wifiCallback.onUserCancelled();
                return Pak.Error.CANCELLED;
            } catch (SecurityException e) {
                return Pak.Error.PERMISSION;
            }
        }

        ScanResult finalScanResult = scanResult;
        AssociationInfo finalAssociationInfo = associationInfo;
        wifiCallback.onConnecting(scanResult == null ? apFilter.ssidPattern : scanResult.SSID);
        return connectToAccessPoint(apFilter, new WiFiDiscoveryCallback() {
            @Override
            public void onConnected(@NonNull Adapter net) {
                Log.d(TAG, "Connected to network");
                net.apAssociation = finalAssociationInfo;
                net.savedPassword = apFilter.password;
                net.apScanResult = finalScanResult;
                wifiCallback.onConnected(net);
            }

            @Override
            public void failed(@NonNull String reason, int code) {
                wifiCallback.failed(reason, code);
            }
        });
    }
    public static int connectToAccessPointCompanion(ApFilter apFilter, String companionName, WiFiDiscoveryCallback wifiCallback) {
        return connectToAccessPointCompanion(apFilter, companionName, wifiCallback, false);
    }

    /// Start listener to obtain primary network (internet access) handle
    public static void startNetworkListeners(Context ctx) {
        // TODO: If wifi isn't available, it should fall back to cellular
        ConnectivityManager m = (ConnectivityManager)ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest.Builder requestBuilder = new NetworkRequest.Builder();
        requestBuilder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);

        ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "Wifi network is available: " + network.toString());
                primaryNetworkDevice = network;
            }
            @Override
            public void onLost(@NonNull Network network) {
                Log.e(TAG, "Lost network\n");
                primaryNetworkDevice = null;
            }
            @Override
            public void onUnavailable() {
                Log.e(TAG, "Network unavailable\n");
                primaryNetworkDevice = null;
            }
            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
                Log.e(TAG, "capabilities changed");
            }
        };

        try {
            m.requestNetwork(requestBuilder.build(), networkCallback);
        } catch (Exception e) {
            Log.d(TAG, e.toString());
        }
    }

    /** Determine if the device is handling two different WiFi connections at the same time, on the same band.
     * On Android 12+ devices, this causes a 2x rx/tx speed hit.
     * https://source.android.com/docs/core/connect/wifi-sta-sta-concurrency#local-only */
    public static boolean isHandlingConflictingConnections(Network a, Network b) {
        ConnectivityManager connectivityManager = (ConnectivityManager)Pak.getActivity().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (isNetworkValid(a) && isNetworkValid(b)) {
                NetworkCapabilities c1 = connectivityManager.getNetworkCapabilities(a);
                if (c1 == null) return false;
                WifiInfo info1 = (WifiInfo) c1.getTransportInfo();
                if (info1 == null) return false;
                int mainBand = info1.getFrequency() / 100;
                NetworkCapabilities c2 = connectivityManager.getNetworkCapabilities(b);
                if (c2 == null) return false;
                WifiInfo info2 = (WifiInfo) c2.getTransportInfo();
                if (info2 == null) return false;
                if (info1.equals(info2)) return false; // Android may sometimes give the same object in both listeners
                int secondBand = info2.getFrequency() / 100;
                return mainBand == secondBand;
            }
        }
        return false;
    }

    public static boolean isNetworkValid(Network net) {
        ConnectivityManager connectivityManager = (ConnectivityManager)Pak.getActivity().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return false;
        NetworkInfo wifiInfo = connectivityManager.getNetworkInfo(net);
        if (net == null) return false;
        if (wifiInfo == null) return false;
        return wifiInfo.isAvailable();
    }

    // If we go through WifiNetworkSpecifier, then the device may be handling two concurrent connections.
    // This is up to a 2x performance hit.
    public static boolean isWiFiModuleCapableOfHandlingTwoConnections(Context ctx) {
        WifiManager wm = (WifiManager)ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Query whether the device supports concurrent station (STA) connections
            // for local-only connections using WifiNetworkSpecifier.
            return wm.isStaConcurrencyForLocalOnlyConnectionsSupported();
        }
        // If below 31, then Android supposedly doesn't support concurrent connections at all
        return false;
    }

    public static boolean isHotSpotEnabled(Context ctx) {
        WifiManager wm = (WifiManager)ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            @SuppressLint("PrivateApi") Method m = wm.getClass().getDeclaredMethod("isWifiApEnabled");
            m.setAccessible(true);
            if ((boolean)m.invoke(wm) == false) {
                return false;
            }
        } catch (Exception ignored) {
            return false;
        }

        return true;
    }

    public static int addNetworkToSystem(ApFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder();

            if (filter.ssidPattern != null) {
                builder.setSsid(filter.ssidPattern);
            }
            if (filter.bssid != null) {
                builder.setBssid(MacAddress.fromString(filter.bssid));
            }
            if (filter.password != null) {
                if (filter.securityType == null || filter.securityType.startsWith("wpa2")) {
                    builder.setWpa2Passphrase(filter.password);
                } else {
                    builder.setWpa3Passphrase(filter.password);
                }
            }
            builder.setIsHiddenSsid(filter.hidden);

            ArrayList<WifiNetworkSuggestion> suggestionList = new ArrayList<>();
            suggestionList.add(builder.build());

            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(Settings.EXTRA_WIFI_NETWORK_LIST, suggestionList);

            Intent intent = new Intent(Settings.ACTION_WIFI_ADD_NETWORKS);
            intent.putExtras(bundle);

            Pak.getActivity().startActivity(intent);
        }
        return 0;
    }

    public static int connectToApLegacy() {
        // Below Android 10 uses this API
//        WifiManager wifiManager = (WifiManager) Pak.getActivity().getSystemService(Context.WIFI_SERVICE);
//        int netId = wifiManager.addNetwork(wifiConfig);
//        if (netId != -1) {
//            wifiManager.disconnect()
//            wifiManager.enableNetwork(netId, true)
//            wifiManager.reconnect()
//        }
        return 0;
    }

    @SuppressLint("MissingPermission")
    public static void scan(Context ctx) throws Exception {
        WifiManager wm = (WifiManager)ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            wm.getScanResults();
            // ...
        } else {
            throw new Exception("bad permission");
        }
    }

    public static void openHotSpotSettings(Context ctx) {
        Intent tetherSettings = new Intent();
        tetherSettings.setClassName("com.android.settings", "com.android.settings.TetherSettings");
        ctx.startActivity(tetherSettings);
    }

    public static void goToSettings(Context ctx) {
        Intent goToSettings = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
        goToSettings.setData(Uri.parse("package:" + ctx.getPackageName()));
        ctx.startActivity(goToSettings);
    }

    public static void openWiFiSettings(Activity ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ctx.startActivityForResult(new Intent(Settings.Panel.ACTION_WIFI), 0);
        } else {
            ctx.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        }
    }
}