package dev.danielc.common

import android.content.ClipData
import android.os.Build
import androidx.compose.ui.platform.ClipEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.danielc.common.BuildConfig

val b = BuildInfo(
    time = SimpleDateFormat("MMMM dd yyyy", Locale.getDefault()).format(
        Date(
            BuildConfig.BUILD_TIME
        )
    ),
    isNightly = BuildConfig.FLAVOR == "nightly",
    isDebug = BuildConfig.DEBUG,
    packageName = BuildConfig.LIBRARY_PACKAGE_NAME,
    version = BuildConfig.VERSION_NAME,
    osVersion = "Android ${Build.VERSION.SDK_INT}"
)

actual fun createClipEntry(label: String, text: String): ClipEntry {
    val clipData = ClipData.newPlainText(label, text)
    return ClipEntry(clipData)
}