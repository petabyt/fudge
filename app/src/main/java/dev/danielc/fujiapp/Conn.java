package dev.danielc.fujiapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class Conn {
    private static Socket socket;
    private static InputStream inputStream;
    private static OutputStream outputStream;

    public static boolean connect(String ipAddress, int port, int timeout) {
        try {
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(ipAddress, port), timeout);
            socket.setSoTimeout(timeout);
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();
            return false;
        } catch (SocketTimeoutException e) {
            Backend.jni_print("Connection timed out.\n");
        } catch (IOException e) {
            Backend.jni_print("Error connecting to the server: " + e.getMessage() + "\n");
        }
        return true;
    }

    public static int write(byte[] data) {
        try {
            outputStream.write(data);
            outputStream.flush();
            return data.length;
        } catch (IOException e) {
            Backend.jni_print("Error writing to the server: " + e.getMessage() + "\n");
            return -1;
        }
    }

    public static int read(byte[] buffer, int length) {
        try {
            int rc = inputStream.read(buffer, 0, length);
            return rc;
        } catch (IOException e) {
            Backend.jni_print("Error reading from the server: " + e.getMessage() + "\n");
            return -1;
        }
    }

    public static void close() {
        try {
            if (inputStream != null) {
                inputStream.close();
            }
            if (outputStream != null) {
                outputStream.close();
            }
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            Backend.jni_print("Error closing the socket: " + e.getMessage() + "\n");
        }
    }
}
