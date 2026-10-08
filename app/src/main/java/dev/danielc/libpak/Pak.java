/// Small helper class to help with permissions and Android native APIs
package dev.danielc.libpak;

import android.app.Activity;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.companion.CompanionDeviceService;
import android.companion.DevicePresenceEvent;
import android.companion.ObservingDevicePresenceRequest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

public class Pak {
    static final String TAG = "pak";
    /// Hold a weak reference to main activity for permission requests
    public static WeakReference<Activity> weakCtx = null;
    public static Callbacks callbacks = null;
    public static void setupAndroidContext(Activity ctx) {
        weakCtx = new WeakReference<>(ctx);

        if (Build.VERSION.SDK_INT >= 36) {
            CompanionDeviceManager deviceManager = (CompanionDeviceManager)getActivity().getSystemService(Context.COMPANION_DEVICE_SERVICE);
            List<AssociationInfo> associations = deviceManager.getMyAssociations();
            for (AssociationInfo m: associations) {
                Log.d(TAG, "Observing: " + m.getDisplayName() + " " + m.getDeviceMacAddress());
                deviceManager.startObservingDevicePresence(new ObservingDevicePresenceRequest.Builder().setAssociationId(m.getId()).build());
            }
        }
    }
    private static final Semaphore perm = new Semaphore(0, true);
    private static int permissionResult = 0;
    static Intent lastIntent = null;
    private static final Pak.CancellableRunnable cancellableRunnable = new Pak.CancellableRunnable();
    public static void interruptAll() {
        cancellableRunnable.cancelAll();
    }

    public static class CancelException extends Exception {
        //public CancelException(String reason) {}
    }

    /// For cancelling blocking routines
    /// Can be used like new CancellableRunnable().run(() => {return 0;})
    public static class CancellableRunnable {
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        int run(Supplier<Integer> block) {
            final int[] rc = new int[1];
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Integer i = block.get();
                    if (i == null) i = 0;
                    rc[0] = i;
                }
            });
            threads.add(thread);
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException e) {
                // This thread shouldn't be interrupted
            }
            threads.remove(thread);
            return rc[0];
        }
        void cancelAll() {
            for (Thread t: threads) {
                t.interrupt();
            }
        }
    }

    public static abstract class Callbacks {
        public abstract void onDeviceAppearance(String address, boolean isNearby);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public static class MyCompanionDeviceService extends CompanionDeviceService {
        @Override
        public void onCreate() {
            super.onCreate();
            Log.d(TAG, "Companion Device Service Created");
        }
        @Override
        public void onDeviceAppeared(@NonNull String address) {
            super.onDeviceAppeared(address);
            Log.d(TAG, "onDeviceAppeared: " + address);
            if (callbacks != null) callbacks.onDeviceAppearance(address, true);
        }
        @Override
        public void onDeviceDisappeared(@NonNull String address) {
            super.onDeviceDisappeared(address);
            Log.d(TAG, "onDeviceDisappeared");
            if (callbacks != null) callbacks.onDeviceAppearance(address, false);
        }
        @Override
        public void onDevicePresenceEvent(@NonNull DevicePresenceEvent event) {
            super.onDevicePresenceEvent(event);
            Log.d(TAG, "onDevicePresenceEvent");
        }
    }

    @SuppressWarnings("unused")
    public static Activity getActivity() {
        return weakCtx.get();
    }

    @SuppressWarnings("unused")
    public static boolean requestPermissionBlocking(String permission) {
        return false;
    }

    public static void startActivity(Intent intent) {
        Pak.getActivity().startActivity(intent);
    }

    public static void onPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        Log.d(TAG, "onPermissionResult");
        if (grantResults.length == 0) {
            permissionResult = PackageManager.PERMISSION_DENIED;
        } else {
            permissionResult = grantResults[0];
        }
        perm.release();
    }

    public static void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "onActivityResult");
        lastIntent = data;
        perm.release();
    }

    public static void waitForActivityResult() {
        try {
            perm.acquire();
        } catch (Exception ignored) {}
    }

    public static void startActivityForResult(IntentSender sender) throws Exception {
        Activity ctx = getActivity();
        ctx.startIntentSenderForResult(sender, 1001, null, 0, 0, 0);
    }

    public static boolean requirePermissionBlocking(String permission) {
        Activity ctx = getActivity();
        if (ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true;
        new Handler(ctx.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                ctx.requestPermissions(new String[]{permission}, 1);
            }
        });

        cancellableRunnable.run(() -> {
            try {
                perm.acquire();
            } catch (InterruptedException ignored) {}
            return 0;
        });
        return permissionResult == PackageManager.PERMISSION_GRANTED;
    }

    public static void startListeningToDevicePresence(@NonNull String macAddress) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            CompanionDeviceManager deviceManager = (CompanionDeviceManager)Pak.getActivity().getSystemService(Context.COMPANION_DEVICE_SERVICE);
            if (Pak.getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
                Log.d(TAG, "Start observing " + macAddress);
                deviceManager.startObservingDevicePresence(macAddress);
            }
        }
    }

    public static void startListeningToDevicePresence(int associationId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            CompanionDeviceManager deviceManager = (CompanionDeviceManager)Pak.getActivity().getSystemService(Context.COMPANION_DEVICE_SERVICE);
            if (Pak.getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
                Log.d(TAG, "Start observing " + associationId);
                deviceManager.startObservingDevicePresence(new ObservingDevicePresenceRequest.Builder().setAssociationId(associationId).build());
            }
        }
    }

    public static void deleteDuplicateAssociations(AssociationInfo info) {
        // TODO: Use mac address instead of association ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            CompanionDeviceManager deviceManager = (CompanionDeviceManager)getActivity().getSystemService(Context.COMPANION_DEVICE_SERVICE);
            List<AssociationInfo> list = deviceManager.getMyAssociations();
            for (AssociationInfo i: list) {
                try {
                    if (i.getDeviceMacAddress().toString().equals(info.getDeviceMacAddress().toString()) && i.getId() != info.getId()) {
                        deviceManager.disassociate(i.getId());
                    }
                } catch (NullPointerException ignored) { }
            }
        }
    }

    public static void disassociate(@NonNull String macAddress) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CompanionDeviceManager deviceManager = (CompanionDeviceManager)Pak.getActivity().getSystemService(Context.COMPANION_DEVICE_SERVICE);
            if (Pak.getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
                deviceManager.disassociate(macAddress);
            }
        }
    }

    public static Intent companionAssociateGetResultBlocking(CompanionDeviceManager manager, AssociationRequest request) throws SecurityException, CancelException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw new SecurityException("Unsupported");
        }

        class MyCallback extends CompanionDeviceManager.Callback {
            final Semaphore waitForCallback = new Semaphore(0, true);
            public int returnCode = 0;
            public boolean waitForActivity = false;
            @Override
            public void onFailure(CharSequence error) {
                if (error != null && error.equals("canceled")) {
                    returnCode = Pak.Error.CANCELLED;
                } else {
                    returnCode = Pak.Error.NON_FATAL;
                }
                waitForCallback.release();
            }

            @Override
            public void onAssociationCreated(@NonNull AssociationInfo associationInfo) {
                super.onAssociationCreated(associationInfo);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    manager.startObservingDevicePresence(new ObservingDevicePresenceRequest.Builder().setAssociationId(associationInfo.getId()).build());
                }
            }

            @Override
            public void onDeviceFound(@NonNull IntentSender intentSender) {
                super.onDeviceFound(intentSender);
                try {
                    Pak.startActivityForResult(intentSender);
                    waitForActivity = true;
                } catch (Exception e) {
                    Log.d(TAG, e.getMessage());
                    returnCode = Pak.Error.CANCELLED;
                    return;
                }

                waitForCallback.release();
            }
        }

        MyCallback callback = new MyCallback();
        manager.associate(request, callback, null);

        int rc = cancellableRunnable.run(() -> {
            try {
                callback.waitForCallback.acquire();
            } catch (InterruptedException ignored) {
                return Error.CANCELLED;
            }
            if (callback.waitForActivity) {
                waitForActivityResult();
                if (callback.returnCode != 0) return Error.CANCELLED;
                if (lastIntent == null) return null;
                return 0;
            }
            return Error.CANCELLED;
        });
        if (rc == Error.CANCELLED) throw new CancelException();
        return lastIntent;
    }

    /// C libpak error codes
    public static class Error {
        public static final int NON_FATAL = -1;
        public static final int IO = -2;
        public static final int UNDEFINED = -3;
        public static final int PERMISSION = -4;
        public static final int UNSUPPORTED = -5;
        public static final int UNIMPLEMENTED = -6;
        public static final int DISCONNECTED = -7;
        public static final int NO_CONNECTION = -8;
        public static final int CANCELLED = -9;
        public static final int DEFERRED = -10;
    }
}
