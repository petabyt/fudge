// Basic wifi-priority socket interface for camlib
// Copyright Daniel Cook - Apache License
package camlib;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;

import dev.danielc.fujiapp.Backend;
import libui.LibUI;

public class WiFiComm {
    public static final String TAG = "camlib";

    public boolean killSwitch = true;

    static Network wifiDevice = null;

    static Socket tryConnectToSocket(Network net, String ip, int port) throws Exception {
        Socket sock;
        try {
            // Create and connect to socket
            sock = new Socket();

            // Bind socket to the network device we selected
            net.bindSocket(sock);
            
            //sock.setKeepAlive(true);
            sock.setTcpNoDelay(true);
            sock.setReuseAddress(true);

            sock.connect(new InetSocketAddress(ip, port), 1000);
        } catch (SocketTimeoutException e) {
            Log.d(TAG, e.toString());
            throw new Exception("Connection timed out");
        } catch (Exception e) {
            Log.d(TAG, e.toString());
            throw new Exception("Failed to connect.");
        }

        return sock;
    }

    public static void startNetworkListeners(ConnectivityManager connectivityManager) {
        NetworkRequest.Builder requestBuilder = new NetworkRequest.Builder();
        requestBuilder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "Wifi network is available");
                wifiDevice = network;
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
        };

        connectivityManager.requestNetwork(requestBuilder.build(), networkCallback);
    }

    public static Socket connectWiFiSocket(ConnectivityManager connectivityManager, String ip, int port) throws Exception {
        NetworkInfo wifiInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        if (!wifiInfo.isAvailable()) {
            throw new Exception("WiFi is not available.");
        } else if (!wifiInfo.isConnected()) {
            throw new Exception("Not connected to a WiFi network.");
        }

        if (wifiDevice == null) {
            throw new Exception("Not connected to WiFi.");
        }

        return tryConnectToSocket(wifiDevice, ip, port);
    }
}
