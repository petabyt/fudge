package dev.danielc.libpak;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.BluetoothLeDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.BroadcastReceiver;
import android.content.Intent;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.NonNull;

public class Bluetooth {
    public static final String TAG = "bt";
    public static final boolean verbose = true;
    /// TODO: Hold one per bluetooth context
    private static final Pak.CancellableRunnable cancellableRunnable = new Pak.CancellableRunnable();

    public static void interruptAll() {
        cancellableRunnable.cancelAll();
    }

//    public static abstract class Listener {
//        public abstract void onUpdate(Boolean bluetoothEnabled);
//        public abstract void onAvailableDevices(@NonNull Device[] devices);
//    }

    public static boolean checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return Pak.getActivity().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public static void requestConnectPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Pak.requirePermissionBlocking(Manifest.permission.BLUETOOTH_CONNECT);
        }
    }

    public static @NonNull Device[] getBondedDevices(BluetoothAdapter adapter) {
        if (Pak.getActivity().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return new Device[]{};
        }

        return adapter.getBondedDevices().stream().map(
                d -> new Bluetooth.Device(adapter, d)
        ).toArray(Device[]::new);
    }

    public static BluetoothAdapter getDefaultAdapter() {
        return BluetoothAdapter.getDefaultAdapter();
    }

    public static boolean isBluetoothEnabled() {
        if (getDefaultAdapter() == null) return false;
        return getDefaultAdapter().isEnabled();
    }

//    public static void setupListeners(Listener listener) {
//        BroadcastReceiver receiver = new BroadcastReceiver() {
//            @Override
//            public void onReceive(Context context, Intent intent) {
//                final String action = intent.getAction();
//                if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
//                    final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
//                    switch (state) {
//                    case BluetoothAdapter.STATE_OFF:
//                        listener.onUpdate(false);
//                        break;
//                    case BluetoothAdapter.STATE_ON:
//                        listener.onUpdate(true);
//                        break;
//                    }
//                }
//            }
//        };
//    }

    public static Device fromAddress(@NonNull String address) {
        return new Device(getDefaultAdapter(), getDefaultAdapter().getRemoteDevice(address));
    }

    public static class Device {
        private final Object bondSignal = new Object();
        private Boolean keyMissingFromBonding = false;
        @NonNull public final BluetoothAdapter adapter;
        @NonNull public final BluetoothDevice dev;
        @NonNull public final String name;
        @NonNull public final String address;
        public boolean isGattConnected = false;
        private ScanResult scanResult = null;
        private AssociationInfo associationInfo = null;
        @NonNull private ParcelUuid[] serviceUuids;
        public BluetoothGatt gatt = null;
        BroadcastReceiver receiver = null;
        MyBluetoothGattCallback callback = null;

        @SuppressLint("MissingPermission")
        Device(@NonNull BluetoothAdapter adapter, @NonNull BluetoothDevice dev) {
            this.adapter = adapter;
            this.dev = dev;
            this.name = Optional.ofNullable(dev.getName()).orElse("?");
            this.serviceUuids = Optional.ofNullable(dev.getUuids()).orElse(new ParcelUuid[]{});
            this.address = dev.getAddress();
        }
        Device(@NonNull BluetoothAdapter adapter, @NonNull BluetoothDevice dev, ScanResult scanResult) {
            this(adapter, dev);
            this.scanResult = scanResult;
        }
        Device(@NonNull BluetoothAdapter adapter, @NonNull BluetoothDevice dev, @NonNull ScanResult scanResult, AssociationInfo info) {
            this(adapter, dev, scanResult);
            this.associationInfo = info;
        }

        public @NonNull List<String> getServiceUuids() {
            return Arrays.stream(serviceUuids).map(ParcelUuid::toString).collect(Collectors.toList());
        }

        public Integer getAssociationId() {
            if (associationInfo == null) return null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return associationInfo.getId();
            } else {
                return null;
            }
        }

        public boolean isBonded() {
            try {
                return dev.getBondState() == BluetoothDevice.BOND_BONDED;
            } catch (SecurityException ignored) {
                return false;
            }
        }

        public int removePreviousBond() {
            if (!Bluetooth.checkPermission()) return Pak.Error.PERMISSION;
            setupListener();
            if (dev.getBondState() == BluetoothDevice.BOND_NONE) return 0;
            Log.d(TAG, "Removing bond...");
            // Remove existing bond from system (requires association) and Android 15+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                if (associationInfo != null && dev.getBondState() != BluetoothDevice.BOND_NONE) {
                    CompanionDeviceManager deviceManager = (CompanionDeviceManager)Pak.getActivity().getSystemService(Context.COMPANION_DEVICE_SERVICE);
                    try {
                        if (!deviceManager.removeBond(associationInfo.getId())) {
                            Log.e(TAG, "removeBond failure");
                            return Pak.Error.NON_FATAL;
                        }
                        synchronized (bondSignal) {
                            bondSignal.wait(10000);
                        }
                    } catch (Exception ignored) {

                    }
                }
            } else {
                try {
                    Method m = dev.getClass().getMethod("removeBond", (Class[]) null);
                    m.invoke(dev, (Object[]) null);
                } catch (Exception e) { Log.e(TAG, e.getMessage()); }
            }
            if (dev.getBondState() == BluetoothDevice.BOND_NONE) {
                Log.d(TAG, "Bond was removed");
            } else {
                Log.e(TAG, "Bond was not removed");
            }
            return 0;
        }

        public byte[] getManufacturerData(int index) {
            if (scanResult == null) return null;
            try {
                SparseArray<byte[]> set = scanResult.getScanRecord().getManufacturerSpecificData();
                if (set.size() == 0) return null;
                byte[] val = set.valueAt(index);
                int mfgId = set.keyAt(index);
                byte[] newBuf = new byte[val.length + 2];
                newBuf[0] = (byte)(mfgId & 0xff);
                newBuf[1] = (byte)((mfgId >> 8) & 0xff);
                System.arraycopy(val, 0, newBuf, 2, val.length);
                if (verbose) Log.d(TAG, "mfgdata: " + Arrays.toString(newBuf));
                return newBuf;
            } catch (NullPointerException e) {
                return null;
            }
        }

        public boolean isConnected() {
            // This method was added in 2014 (probably API 21+) so it should be okay to use.
            try {
                Method m = dev.getClass().getMethod("isConnected");
                return (boolean)m.invoke(dev);
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
                return false;
            }
        }

        public void setupListener() {
            if (receiver != null) return;
            receiver = new BroadcastReceiver() {
                @SuppressLint("MissingPermission")
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action == null) return;
                    if (action.equals(BluetoothDevice.ACTION_UUID)) {
                        serviceUuids = Optional.ofNullable(dev.getUuids()).orElse(new ParcelUuid[]{});
                        synchronized (waitForUuid) {
                            waitForUuid.release();
                        }
                    } else if (action.equals(BluetoothDevice.ACTION_BOND_STATE_CHANGED)) {
                        if (dev.getBondState() == BluetoothDevice.BOND_BONDED || dev.getBondState() == BluetoothDevice.BOND_NONE) {
                            synchronized (bondSignal) {
                                bondSignal.notify();
                            }
                        }
                        Log.d(TAG, "Bond state: " + dev.getBondState());
                    } else if (action.equals(BluetoothDevice.ACTION_KEY_MISSING)) {
                        keyMissingFromBonding = true;
                        synchronized (bondSignal) {
                            bondSignal.notify();
                        }
                    }
                    Log.d(TAG, "bt-action: " + action);
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            filter.addAction(BluetoothDevice.ACTION_UUID);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                filter.addAction(BluetoothDevice.ACTION_KEY_MISSING);
                filter.addAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE);
                Pak.getActivity().registerReceiver(receiver, filter);
            } else {
                Pak.getActivity().registerReceiver(receiver, filter);
            }
        }

        public void closeAll() {
            try {
                if (receiver != null) Pak.getActivity().unregisterReceiver(receiver);
                receiver = null;
                if (gatt != null) gatt.close();
                gatt = null;
            } catch (SecurityException ignored) {}
        }

        UUID findServiceUuid(String uuid) {
            for (ParcelUuid u: serviceUuids) {
                Log.d(TAG, u.getUuid().toString());
                if (u.getUuid().toString().equals(uuid)) return u.getUuid();
            }
            return null;
        }
        private final Semaphore waitForUuid = new Semaphore(0, true);
        public int refreshSdpUuids() {
            return cancellableRunnable.run(() -> {
                if (!Bluetooth.checkPermission()) return Pak.Error.PERMISSION;
                setupListener();
                if (!dev.fetchUuidsWithSdp()) return Pak.Error.NON_FATAL;
                try {
                    waitForUuid.acquire();
                } catch (InterruptedException e) {
                    return Pak.Error.CANCELLED;
                }
                return 0;
            });
        }

        public BluetoothSocket connectToServiceChannel(String uuid) throws Exception {
            int rc = refreshSdpUuids();
            if (rc == Pak.Error.CANCELLED) {
                throw new InterruptedException();
            } else if (rc != 0) {
                throw new Exception();
            }
            UUID u = findServiceUuid(uuid);
            if (u == null) {
                Log.e(TAG, "UUID not found");
                throw new Exception("UUID not found");
            }
            try {
                return dev.createInsecureRfcommSocketToServiceRecord(u);
            } catch (IOException e) {
                Log.e(TAG, e.toString());
                throw e;
            }
        }

        public BluetoothGattService getService(int index) {
            if (gatt == null) return null;
            try {
                return gatt.getServices().get(index);
            } catch (Exception e) {
                Log.e(TAG, "getServices fail: " + e.getMessage());
                return null;
            }
        }

        public int connectGattNative(byte[] struct) {
            NativeBluetoothGattCallback callback = new NativeBluetoothGattCallback(this);
            callback.struct = struct;
            return connectGatt(Pak.getActivity(), callback);
        }

        public int createBond() {
            setupListener();
            return cancellableRunnable.run(() -> {
                try {
                    keyMissingFromBonding = false;
                    if (dev.getBondState() == BluetoothDevice.BOND_NONE) {
                        if (!dev.createBond()) {
                            Log.e(TAG, "createBond failed");
                            return Pak.Error.IO;
                        }
                        if (verbose) Log.d(TAG, "Waiting for bond callback");
                        synchronized (bondSignal) {
                            bondSignal.wait(20000);
                        }
                        if (keyMissingFromBonding) {
                            return Pak.Error.UNDEFINED;
                        } else if (dev.getBondState() != BluetoothDevice.BOND_BONDED) {
                            return Pak.Error.NON_FATAL;
                        }
                    }
                    return 0;
                } catch (SecurityException ignored) {
                    return Pak.Error.PERMISSION;
                } catch (InterruptedException ignored) {
                    return Pak.Error.CANCELLED;
                }
            });
        }

        public int connectGatt(Context ctx, MyBluetoothGattCallback callback) {
            return cancellableRunnable.run(() -> {
                try {
                    setupListener();
                    gatt = dev.connectGatt(ctx, false, callback);
                    this.callback = callback;
                    synchronized (callback.connectSignal) {
                        callback.connectSignal.wait(5000);
                    }
                    if (!isGattConnected) {
                        Log.e(TAG, "connectGatt failed after timeout");
                        return Pak.Error.NO_CONNECTION;
                    }
                    //gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    //gatt.setPreferredPhy(BluetoothDevice.PHY_LE_1M_MASK, BluetoothDevice.PHY_LE_1M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED);
                    //gatt.requestMtu(185);
                    //Thread.sleep(6000);
                    gatt.discoverServices();
                    synchronized (callback.gattServicesDiscovered) {
                        callback.gattServicesDiscovered.wait(10000);
                    }
                    return 0;
                } catch (SecurityException ignored) {
                    return Pak.Error.PERMISSION;
                } catch (InterruptedException ignored) {
                    return Pak.Error.CANCELLED;
                }
            });
        }

        public BluetoothGattDescriptor getDescriptor(BluetoothGattCharacteristic characteristic, int index) {
            return characteristic.getDescriptors().get(index);
        }
        public byte[] getCachedValue(BluetoothGattCharacteristic characteristic) {
            Bluetooth.MyBluetoothGattCallback.CachedCharUpdates val = callback.checkCachedUpdate(characteristic, true);
            if (val != null) return val.value;
            return characteristic.getValue();
        }
        public int setCccd(BluetoothGattCharacteristic characteristic, int value) {
            try {
                BluetoothGattDescriptor desc = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                if (desc == null) return -1;
                desc.setValue(new byte[]{(byte) value, 0});
                if (gatt.writeDescriptor(desc)) {
                    synchronized (callback.gattDescriptorWrote) {
                        callback.gattDescriptorWrote.wait(1000);
                    }
                    return 0;
                }
                return -1;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (SecurityException e) {
                return Pak.Error.PERMISSION;
            }
        }
        public int setNotification(BluetoothGattCharacteristic characteristic, boolean v) {
            try {
                if (gatt.setCharacteristicNotification(characteristic, v)) {
                    return 0;
                }
                return -1;
            } catch (SecurityException e) {
                return Pak.Error.PERMISSION;
            }
        }
        public int waitBlocking(BluetoothGattCharacteristic characteristic, int ms) {
            if (callback.checkCachedUpdate(characteristic, false) != null) {
                return 0;
            }
            try {
                synchronized (callback.gattCharacteristicChanged) {
                    callback.gattCharacteristicChanged.wait(ms);
                }
                if (callback.checkCachedUpdate(characteristic, false) == null) return -1;
                return 0;
            } catch (SecurityException e) {
                return Pak.Error.PERMISSION;
            } catch (InterruptedException e) {
                return Pak.Error.UNDEFINED;
            }
        }
        public int readAsync(BluetoothGattCharacteristic characteristic) {
            if (verbose) Log.d(TAG, "Read " + characteristic.getUuid().toString());
            try {
                if (gatt.readCharacteristic(characteristic)) {
                    synchronized (callback.gattCharacteristicRead) {
                        callback.gattCharacteristicRead.wait(10000);
                    }
                    return 0;
                }
                Log.e(TAG, "readCharacteristic");
                return -1;
            } catch (SecurityException e) {
                return Pak.Error.PERMISSION;
            } catch (InterruptedException e) {
                return Pak.Error.UNDEFINED;
            }
        }
        public int writeAsync(BluetoothGattCharacteristic characteristic, byte[] data) {
            if (verbose) Log.d(TAG, "Write " + Arrays.toString(data) + " to " + characteristic.getUuid().toString());
            try {
                if (!characteristic.setValue(data)) {
                    Log.e(TAG, "setValue");
                    return Pak.Error.UNDEFINED;
                }
                if (gatt.writeCharacteristic(characteristic)) {
                    synchronized (callback.gattCharacteristicWrote) {
                        callback.gattCharacteristicWrote.wait(5000);
                    }
                    return 0;
                }
                return -1;
            } catch (SecurityException e) {
                return Pak.Error.PERMISSION;
            } catch (InterruptedException e) {
                return Pak.Error.UNDEFINED;
            }
        }
    }

    public static class NativeBluetoothGattCallback extends MyBluetoothGattCallback {
        byte[] struct;
        NativeBluetoothGattCallback(Device device) {
            super(device);
        }
        public native void onEvent(int code, BluetoothGattCharacteristic characteristic);
    }

    public abstract static class MyBluetoothGattCallback extends BluetoothGattCallback {
        final Bluetooth.Device device;
        final Object connectSignal = new Object();
        final Object gattServicesDiscovered = new Object();
        final Object gattCharacteristicWrote = new Object();
        final Object gattCharacteristicRead = new Object();
        final Object gattCharacteristicChanged = new Object();
        final Object gattDescriptorWrote = new Object();
        public static class CachedCharUpdates {
            BluetoothGattCharacteristic chr;
            byte[] value;
            CachedCharUpdates(BluetoothGattCharacteristic chr, byte[] value) {
                this.chr = chr;
                this.value = value;
            }
        }
        final private HashSet<CachedCharUpdates> cachedUpdates = new HashSet<>();
        public CachedCharUpdates checkCachedUpdate(BluetoothGattCharacteristic chr, boolean clear) {
            for (CachedCharUpdates e: cachedUpdates) {
                if (e.chr.equals(chr)) {
                    if (clear) cachedUpdates.remove(e);
                    return e;
                }
            }
            return null;
        }
        MyBluetoothGattCallback(Bluetooth.Device device) {
            this.device = device;
        }

        static final int EVENT_CONNECTED = 1;
        static final int EVENT_DISCONNECTED = 2;
        static final int EVENT_GATT_UUID_WRITTEN = 3;
        static final int EVENT_GATT_UUID_READ = 4;
        static final int EVENT_GATT_UUID_CHANGED = 5;
        static final int EVENT_GATT_DESC_WRITTEN = 6;
        static final int EVENT_SERVICES_DISCOVERED = 7;
        public abstract void onEvent(int code, BluetoothGattCharacteristic characteristic);

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (verbose) Log.d(TAG, "Connection state change: " + status);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onEvent(EVENT_CONNECTED, null);
                device.isGattConnected = true;
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onEvent(EVENT_DISCONNECTED, null);
                device.isGattConnected = false;
            }
            synchronized (connectSignal) {
                connectSignal.notify();
            }
        }
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (verbose) Log.d(TAG, "Discovered GATT services");
            if (status != BluetoothGatt.GATT_SUCCESS) Log.d(TAG, "onServicesDiscovered reports failure");
            //device.serviceUuids = device.dev.getUuids();
            onEvent(EVENT_SERVICES_DISCOVERED, null);
            synchronized (gattServicesDiscovered) {
                gattServicesDiscovered.notify();
            }
        }
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            Log.d(TAG, "characteristicRead");
            cachedUpdates.add(new CachedCharUpdates(characteristic, characteristic.getValue()));
            synchronized (gattCharacteristicRead) {
                gattCharacteristicRead.notify();
            }
            onEvent(EVENT_GATT_UUID_READ, characteristic);
        }
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            synchronized (gattCharacteristicWrote) {
                gattCharacteristicWrote.notify();
            }
            onEvent(EVENT_GATT_UUID_WRITTEN, characteristic);
        }
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            if (verbose) Log.d(TAG, "Characteristic changed: " + characteristic.getUuid().toString() + " to " + Arrays.toString(characteristic.getValue()));
            cachedUpdates.add(new CachedCharUpdates(characteristic, characteristic.getValue()));
            synchronized (gattCharacteristicChanged) {
                gattCharacteristicChanged.notify();
            }
            onEvent(EVENT_GATT_UUID_CHANGED, characteristic);
        }
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorRead(gatt, descriptor, status);
            synchronized (gattDescriptorWrote) {
                gattDescriptorWrote.notify();
            }
        }
        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorRead(gatt, descriptor, status);
        }
    }

    public static class BtFilter {
        public String namePattern;
        public boolean isClassic;
        public String[] serviceUuids;
        public byte[] manufacData;
        public byte[] manufacDataMask;
    }

    public static abstract class ScanCallback {
        public abstract void onFound(@NonNull Device device);
        public abstract void onFailure(@NonNull String reason);
        public abstract void onCancel();
    }

    /// Opens a dialog to save an access point as a companion device
    @SuppressLint("WrongConstant")
    public static int pairWithDeviceCompanion(@NonNull List<BtFilter> filters, String companionName, String deviceType, @NonNull ScanCallback scanCallback, boolean deleteOldBond) {
        Context ctx = Pak.getActivity();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return Pak.Error.UNSUPPORTED;
        }

        CompanionDeviceManager deviceManager = (CompanionDeviceManager)ctx.getSystemService(Context.COMPANION_DEVICE_SERVICE);

        AssociationRequest.Builder associationBuilder = new AssociationRequest.Builder();
        for (BtFilter filter: filters) {
            if (filter.isClassic) {
                BluetoothDeviceFilter.Builder builder = new BluetoothDeviceFilter.Builder();
                if (filter.serviceUuids != null) {
                    for (String uuid : filter.serviceUuids) {
                        builder.addServiceUuid(ParcelUuid.fromString(uuid), null);
                    }
                }
                associationBuilder.addDeviceFilter(builder.build());
            } else {
                BluetoothLeDeviceFilter.Builder builder = new BluetoothLeDeviceFilter.Builder();
                ScanFilter.Builder scanFilter = new ScanFilter.Builder();

                if (filter.manufacData != null) {
                    byte[] trimmed = Arrays.copyOfRange(filter.manufacData, 2, filter.manufacData.length);
                    int companyIdentifier = (filter.manufacData[0] & 0xff) | ((filter.manufacData[1] & 0xff) << 8);
                    try {
                        if (filter.manufacDataMask != null) {
                            byte[] maskTrimmed = Arrays.copyOfRange(filter.manufacDataMask, 2, filter.manufacDataMask.length);
                            if (maskTrimmed.length < trimmed.length) {
                                trimmed = Arrays.copyOfRange(trimmed, 0, maskTrimmed.length);
                            }
                            scanFilter.setManufacturerData(companyIdentifier, trimmed, maskTrimmed);
                        } else {
                            scanFilter.setManufacturerData(companyIdentifier, trimmed);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, e.getMessage());
                        return Pak.Error.UNDEFINED;
                    }
                }

                for (String uuid: filter.serviceUuids) {
                    scanFilter.setServiceUuid(ParcelUuid.fromString(uuid));
                    Log.d(TAG, uuid);
                }

                builder.setScanFilter(scanFilter.build());
                associationBuilder.addDeviceFilter(builder.build());
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            associationBuilder.setDisplayName(companionName);
        }

        // Automatically exits prompt when device is found
        //associationBuilder.setSingleDevice(true);

        if (Objects.equals(deviceType, "smartwatch")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                associationBuilder.setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH);
            }
        } else if (Objects.equals(deviceType, "smart-glasses")) {
            if (Build.VERSION.SDK_INT >= 34) {
                associationBuilder.setDeviceProfile(AssociationRequest.DEVICE_PROFILE_GLASSES);
            }
        } else if (Objects.equals(deviceType, "generic-medical-wearable")) {
            if (Build.VERSION.SDK_INT >= 36) {
                associationBuilder.setDeviceProfile("android.app.role.COMPANION_DEVICE_MEDICAL");
            }
        }

        AssociationRequest request = associationBuilder.build();

        try {
            Intent intent = Pak.companionAssociateGetResultBlocking(deviceManager, request);
            if (intent == null) {
                scanCallback.onCancel();
                return Pak.Error.CANCELLED;
            }
            AssociationInfo associationInfo = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                associationInfo = intent.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION);
                Log.d(TAG, "Associated with " + associationInfo.getDeviceMacAddress());
                Pak.deleteDuplicateAssociations(associationInfo);
            }
            ScanResult result = intent.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE);
            if (result == null) return Pak.Error.NON_FATAL;

            Bluetooth.Device dev = new Bluetooth.Device(getDefaultAdapter(), result.getDevice(), result, associationInfo);
            if (dev.isBonded() && deleteOldBond) {
                // This causes a repeat dialog but is necessary since removing bonds seems to cause devices to refresh advertisement
                dev.removePreviousBond();
                int rc = pairWithDeviceCompanion(filters, companionName, deviceType, scanCallback, false);
                deviceManager.disassociate(result.getDevice().getAddress());
                return rc;
            } else {
                scanCallback.onFound(new Bluetooth.Device(getDefaultAdapter(), result.getDevice(), result, associationInfo));
            }
        } catch (Pak.CancelException e) {
            return Pak.Error.CANCELLED;
        }
        return 0;
    }

    public static void openEnableBluetoothDialog() {
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        Pak.startActivity(intent);
    }

    public static int leScanner() {
        if (Pak.getActivity().checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return Pak.Error.PERMISSION;
        }

        BluetoothLeScanner scanner = getDefaultAdapter().getBluetoothLeScanner();

        List<ScanFilter> filters = new ArrayList<>();

        ScanFilter.Builder builder = new ScanFilter.Builder();
        ScanFilter filter = builder.build();
        filters.add(filter);

        ScanSettings.Builder scanSettingsBuilder = new ScanSettings.Builder();
        ScanSettings scanSettings = scanSettingsBuilder.build();

        scanner.startScan(filters, scanSettings, new android.bluetooth.le.ScanCallback() {
            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                super.onBatchScanResults(results);
            }

            @Override
            public void onScanFailed(int errorCode) {
                super.onScanFailed(errorCode);
            }

            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
            }
        });

        return 0;
    }
}
