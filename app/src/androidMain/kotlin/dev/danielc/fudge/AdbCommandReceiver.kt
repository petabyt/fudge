package dev.danielc.fudge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AdbCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "dev.danielc.fudge.START_MODULE") {
            val module = intent.getStringExtra("module_name") ?: return
            val setupOption = intent.getStringExtra("setup_option") ?: return
            startModule(module, setupOption)
        } else {
            Log.d("receiver", intent.action.toString())
        }
    }
}