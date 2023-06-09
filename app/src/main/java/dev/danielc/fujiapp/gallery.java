package dev.danielc.fujiapp;
import org.json.JSONObject;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

public class gallery extends AppCompatActivity {
    private static gallery instance;

    public static gallery getInstance() {
        return instance;
    }

    Handler handler;

    public void setErrorText(String arg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                TextView error_msg = findViewById(R.id.gallery_logs);
                error_msg.setText(arg);
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);
        instance = this;

        handler = new Handler(Looper.getMainLooper());

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (Backend.cPtpFujiInit() == 0) {
                    Backend.jni_print("Successfully initialized command socket\n");
                } else {
                    Backend.jni_print("Failed to init socket\n");
                    return;
                }

                try {
                    Backend.run("ptp_open_session;");
                } catch (Exception e) {
                    Backend.jni_print("Failed to open session\n");
                }

                Backend.jni_print("Waiting for device access...\n");
                if (Backend.cPtpFujiWaitUnlocked() == 0) {
                    Backend.jni_print("Gained access to device.\n");
                } else {
                    Backend.jni_print("Failed to gain access to device.");
                }

                try {
                    Backend.run("ptp_set_property;\"Fuji_Mode\",2");
                    Backend.run("ptp_set_property;\"Fuji_TransferMode\",2");
                } catch (Exception e) {
                    Backend.jni_print("Failed to set modes\n");
                }

                try {
                    Backend.jni_print("Getting some stuff\n");
                    JSONObject jsonObject = Backend.run("ptp_get_storage_ids;");
                    int storageId = jsonObject.getJSONArray("resp").getInt(0);
                    jsonObject = Backend.run("ptp_get_storage_info;" + Integer.toString(storageId));
                    Backend.jni_print(jsonObject.toString() + "\n");
                } catch (Exception e) {
                    Backend.jni_print("Error getting storage info");
                }

                Backend.jni_print("Letting connection time out... \n");
            }
        });
        thread.start();
    }
}