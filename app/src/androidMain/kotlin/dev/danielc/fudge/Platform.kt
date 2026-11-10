package dev.danielc.fudge

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

//val b = BuildInfo(
//    time = SimpleDateFormat("MMMM dd yyyy", Locale.getDefault()).format(
//        Date(
//            BuildConfig.BUILD_TIME
//        )
//    ),
//    isNightly = BuildConfig.FLAVOR == "nightly",
//    isDebug = BuildConfig.DEBUG,
//    packageName = BuildConfig.LIBRARY_PACKAGE_NAME,
//    version = BuildConfig.VERSION_NAME,
//    osVersion = "Android ${Build.VERSION.SDK_INT}"
//)

actual fun createClipEntry(label: String, text: String): ClipEntry {
    val clipData = ClipData.newPlainText(label, text)
    return ClipEntry(clipData)
}