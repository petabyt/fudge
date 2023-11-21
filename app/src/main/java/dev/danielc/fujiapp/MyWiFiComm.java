// Native Java interface for JNI to use sockets - socket() doesn't work for some reason (probably thread issues)
// Copyright 2023 Daniel C - https://github.com/petabyt/fujiapp
package dev.danielc.fujiapp;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import dev.petabyt.camlib.*;

public class MyWiFiComm extends WiFiComm {
    public boolean isUsingEmulator = false;

    public final String TAG = "MyWiFiComm";
    private Socket cmdSocket = null;
    private InputStream cmdInputStream = null;
    private OutputStream cmdOutputStream = null;
    public boolean fujiConnectToCmd(ConnectivityManager m) {
        Backend.print("Connecting...");
        cmdSocket = connectWiFiSocket(m, Backend.FUJI_IP, Backend.FUJI_CMD_PORT);

        if (cmdSocket == null) {
            // Little slower, only for debug builds
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Trying emulator");
                cmdSocket = connectWiFiSocket(m, Backend.FUJI_EMU_IP, Backend.FUJI_CMD_PORT);
                if (cmdSocket == null) {
                    return true;
                }

                isUsingEmulator = true;
            } else {
                return true;
            }
        }

        try {
            cmdSocket.setSoTimeout(2000);
            cmdInputStream = cmdSocket.getInputStream();
            cmdOutputStream = cmdSocket.getOutputStream();
            killSwitch = false;
            Log.d(TAG, "Successful connection established");
        } catch (Exception e) {
            // TODO: Handle
            return true;
        }

        return false;
    }

    private Socket eventSocket = null;
    private Socket videoSocket = null;
    public boolean fujiConnectEventAndVideo(ConnectivityManager m) {
        String ip = Backend.FUJI_IP;
        if (isUsingEmulator) ip = Backend.FUJI_EMU_IP;
        eventSocket = connectWiFiSocket(m, ip, Backend.FUJI_EVENT_PORT);
        if (eventSocket == null) {
            Backend.print("Failed to connect to event socket: " + failReason);
            return true;
        }

        videoSocket = connectWiFiSocket(m, ip, Backend.FUJI_VIDEO_PORT);
        if (videoSocket == null) {
            Backend.print("Failed to connect to video socket: "  + failReason);
            return true;
        }

        return false;
    }

    public int cmdWrite(byte[] data) {
        if (killSwitch) {
            Log.d(TAG, "kill switch on, breaking request");
            return -1;
        }
        try {
            cmdOutputStream.write(data);
            cmdOutputStream.flush();
            return data.length;
        } catch (IOException e) {
            Backend.print("Write error: " + e.getMessage());
            return -1;
        }
    }

    public int cmdRead(byte[] buffer, int length) {
        int read = 0;
        while (true) {
            try {
                if (killSwitch) return -2;

                int rc = cmdInputStream.read(buffer, read, length - read);
                if (rc < 0) return rc;
                read += rc;
                if (read == length) return read;

                // Post progress percentage to progressBar
                if (!Viewer.inProgress) continue;
                final int progress = (int)((double)read / (double)length * 100.0);
                Viewer.handler.post(new Runnable() {
                    @Override
                    public void run() {
                        Viewer.progressBar.setProgress(progress);
                    }
                });
            } catch (IOException e) {
                Backend.print("Error reading " + length + " bytes: " + e.getMessage());
                return -1;
            }
        }
    }

    public synchronized void close() {
        killSwitch = true;
        try {
            // Suck the remaining bytes out of socket
            byte[] remaining = new byte[100];
            cmdInputStream.read(remaining);
        } catch (Exception e) {
            // I don't care
        }

        try {
            if (cmdSocket != null) {
                cmdSocket.close();
            }
            if (cmdInputStream != null) {
                cmdInputStream.close();
            }
            if (cmdOutputStream != null) {
                cmdOutputStream.close();
            }
        } catch (IOException e) {
            Backend.print("Socket close error: " + e.getMessage());
        }
    }
}