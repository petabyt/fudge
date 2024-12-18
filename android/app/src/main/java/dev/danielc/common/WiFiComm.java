// Android WiFi helper and utility library
// Copyright Daniel Cook - Apache License
package dev.danielc.common;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.PatternMatcher;
import android.provider.Settings;
import android.util.Log;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

public class WiFiComm {
    public static final String TAG = "wifi";

    private static ConnectivityManager cm = null;
    public void setConnectivityManager(ConnectivityManager cm) {
        WiFiComm.cm = cm;
    }

    // Error codes
    public static final int NOT_AVAILABLE = -101;
    public static final int NOT_CONNECTED = -102;
    public static final int UNSUPPORTED_SDK = -103;

    static Network wifiDevice = null;
    static Network foundWiFiDevice = null;

    // Non-static event handlers since a frontend may need to spawn two listeners
    public Handler handler = null;
    public Runnable onAvailable = null;
    public Runnable onWiFiSelectAvailable = null;
    public Runnable onWiFiSelectCancel = null;
    public boolean blockEvents = false;

    void run(Runnable r) {
        if (blockEvents) return;
        if (r != null) {
            if (handler == null) {
                r.run();
            } else {
                handler.post(r);
            }
        }
    }

    ConnectivityManager.NetworkCallback lastCallback = null;

    /** Opens an Android 10+ popup to prompt the user to select a WiFi network */
    public int connectToAccessPoint(Context ctx, String password, PatternMatcher pattern) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ConnectivityManager connectivityManager = (ConnectivityManager)ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (lastCallback != null) {
                connectivityManager.unregisterNetworkCallback(lastCallback);
            }

            WifiNetworkSpecifier.Builder builder = new WifiNetworkSpecifier.Builder();
            builder.setSsidPattern(pattern);

            if (password != null) {
                builder.setWpa2Passphrase(password);
                Log.d(TAG, String.format("password: %s", password));
            }
            NetworkSpecifier specifier = builder.build();

            NetworkRequest request = new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .setNetworkSpecifier(specifier)
                            .build();

            lastCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Log.d(TAG, "Network selected by user: " + network);
                    foundWiFiDevice = network;
                    run(onWiFiSelectAvailable);
                    isHandlingConflictingConnections();
                }
                @Override
                public void onUnavailable() {
                    Log.d(TAG, "Network unavailable, not selected by user");
                    foundWiFiDevice = null;
                    run(onWiFiSelectCancel);
                }
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                    Log.e(TAG, "capabilities changed");
                }
            };
            connectivityManager.requestNetwork(request, lastCallback);
            return 0;
        } else {
            return UNSUPPORTED_SDK;
        }
    }

    public void startNetworkListeners(Context ctx) {
        ConnectivityManager m = (ConnectivityManager)ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest.Builder requestBuilder = new NetworkRequest.Builder();
        requestBuilder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);

        ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
            Intent settings = null;
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "Wifi network is available: " + network.getNetworkHandle());
                if (settings != null) {
                    ((Activity)ctx).finish();
                }

                wifiDevice = network;
                run(onAvailable);
            }
            @Override
            public void onLost(Network network) {
                Log.e(TAG, "Lost network\n");
                wifiDevice = null;
            }
            @Override
            public void onUnavailable() {
                Log.e(TAG, "Network unavailable\n");
                wifiDevice = null;
            }
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                Log.e(TAG, "capabilities changed");
            }
        };

        // TODO: Gracefully handle this
        try {
            m.requestNetwork(requestBuilder.build(), networkCallback);
        } catch (Exception e) {
            Intent goToSettings = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
            goToSettings.setData(Uri.parse("package:" + ctx.getPackageName()));
            ctx.startActivity(goToSettings);
        }
    }

    /** Determine if the device is handling two different WiFi connections at the same time, on the same band.
     * On Android 12+ devices, this causes a 2x rx/tx speed hit.
     * https://source.android.com/docs/core/connect/wifi-sta-sta-concurrency#local-only */
    public static boolean isHandlingConflictingConnections() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (isNetworkValid(wifiDevice) && isNetworkValid(foundWiFiDevice)) {
                NetworkCapabilities c1 = cm.getNetworkCapabilities(wifiDevice);
                if (c1 == null) return false;
                WifiInfo info1 = (WifiInfo) c1.getTransportInfo();
                if (info1 == null) return false;
                int mainBand = info1.getFrequency() / 100;
                NetworkCapabilities c2 = cm.getNetworkCapabilities(foundWiFiDevice);
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
        if (cm == null) return false;
        NetworkInfo wifiInfo = cm.getNetworkInfo(net);
        if (net == null) return false;
        if (wifiInfo == null) return false;
        return wifiInfo.isAvailable();
    }

    // If we go through WifiNetworkSpecifier, then the device may be handling two concurrent connections.
    // This is up to a 2x performance hit.
    public static boolean isWiFiModuleCapableOfHandlingTwoConnections(Context ctx) {
        WifiManager wm = (WifiManager)ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 'Query whether or not the device supports concurrent station (STA) connections
            // for local-only connections using WifiNetworkSpecifier.'
            return wm.isStaConcurrencyForLocalOnlyConnectionsSupported();
        }
        // If below 31, then Android supposedly doesn't support concurrent connections at all
        return false;
    }

    public static boolean isHotSpotEnabled(Context ctx) {
        WifiManager wm = (WifiManager)ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            Method m = wm.getClass().getDeclaredMethod("isWifiApEnabled");
            m.setAccessible(true);
            if ((boolean)m.invoke(wm) == false) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }

        return true;
    }

    @SuppressLint("ObsoleteSdkInt")
    public static long getNetworkHandle() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return UNSUPPORTED_SDK;
        }

        // Prefer user-selected network
        if (isNetworkValid(foundWiFiDevice)) {
            Log.d(TAG, "Returning found wifi device");
            return foundWiFiDevice.getNetworkHandle();
        }

        if (isNetworkValid(wifiDevice)) {
            Log.d(TAG, "Returning default WiFi");
            return wifiDevice.getNetworkHandle();
        }

        Log.d(TAG, "WiFi network not available");
        return NOT_AVAILABLE;
    }

    public static void openHotSpotSettings(Context ctx) {
        Intent tetherSettings = new Intent();
        tetherSettings.setClassName("com.android.settings", "com.android.settings.TetherSettings");
        ctx.startActivity(tetherSettings);
    }

    public static void openWiFiSettings(Activity ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ctx.startActivityForResult(new Intent(Settings.Panel.ACTION_WIFI), 0);
        } else {
            ctx.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        }
    }
}
