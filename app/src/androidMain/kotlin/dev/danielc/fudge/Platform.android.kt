package dev.danielc.fudge

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Build

actual object BuildInfo {
    actual val time = SimpleDateFormat("MMMM dd yyyy", Locale.getDefault()).format(
        Date(
            BuildConfig.BUILD_TIME
        )
    )
    actual val isNightly = BuildConfig.FLAVOR == "nightly"
    actual val isDebug = BuildConfig.DEBUG
    actual val packageName = BuildConfig.APPLICATION_ID
    actual val version = BuildConfig.VERSION_NAME
    actual val osVersion = "Android ${Build.VERSION.SDK_INT}"
}

actual fun createClipEntry(label: String, text: String): ClipEntry {
    val clipData = ClipData.newPlainText(label, text)
    return ClipEntry(clipData)
}