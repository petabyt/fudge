package dev.danielc.libpak;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.util.HashMap;

public class USB {
    private static final Pak.CancellableRunnable cancellableRunnable = new Pak.CancellableRunnable();
    public static Device[] getConnectedDevices() {
        UsbManager usbManager = (UsbManager) Pak.getActivity().getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        return deviceList.values().stream().map(
                Device::new
        ).toArray(USB.Device[]::new);
    }
    public static class Device {
        public UsbDevice dev;
        public int pid;
        public int vid;
        public int usbClass;
        public String name;
        Device(UsbDevice dev) {
            this.dev = dev;
            this.vid = dev.getVendorId();
            this.pid = dev.getProductId();
            this.name = dev.getProductName();
            this.usbClass = dev.getDeviceClass();
        }

        int requestPermissionToOpen() {
            UsbManager usbManager = (UsbManager) Pak.getActivity().getSystemService(Context.USB_SERVICE);
            String packageName = Pak.getActivity().getPackageName();
            String permissionAction = packageName + ".USB_PERMISSION";

            Intent permissionIntent = new Intent(permissionAction);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                Pak.getActivity(),
                0,
                permissionIntent,
                PendingIntent.FLAG_IMMUTABLE
            );

            if (pendingIntent == null) {
                return -1;
            }

            usbManager.requestPermission(dev, pendingIntent);

            return cancellableRunnable.run(() -> {
                for (int i = 0; i < 10; i++) {
                    if (usbManager.hasPermission(dev)) {
                        return 0;
                    }

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return -1;
            });
        }
    }
}
