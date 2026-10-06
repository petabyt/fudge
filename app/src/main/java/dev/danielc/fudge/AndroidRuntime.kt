/** Bridge between non-android Kotlin code and native C runtime code
 * (allows kotlin code to be used through compose multiplatform) */
package dev.danielc.fudge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.room.Room
import dev.danielc.common.AppDatabase
import dev.danielc.common.AppSettingEntity
import dev.danielc.common.ModuleInstance
import dev.danielc.common.Runtime
import dev.danielc.common.Timestamp
import dev.danielc.libpak.Pak
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

fun Timestamp.toEpochMillis(): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, second)
        set(Calendar.MILLISECOND, centisecond * 100)
    }
    return cal.timeInMillis
}

fun Timestamp.hoursSince(other: Timestamp): Double {
    val diffMillis = this.toEpochMillis() - other.toEpochMillis()
    return diffMillis / (1000.0 * 60 * 60)
}

fun Timestamp.Companion.now(): Timestamp {
    val cal = Calendar.getInstance()
    return Timestamp(
        year = cal.get(Calendar.YEAR),
        month = cal.get(Calendar.MONTH) + 1,
        day = cal.get(Calendar.DAY_OF_MONTH),
        hour = cal.get(Calendar.HOUR_OF_DAY),
        minute = cal.get(Calendar.MINUTE),
        second = cal.get(Calendar.SECOND),
        centisecond = cal.get(Calendar.MILLISECOND) / 100
    )
}

object AndroidRuntime {
    val TAG = "AndroidRuntime"
    var hasInited: Boolean = false
    private var databaseInstance: AppDatabase? = null
    fun setup(ctx: Context) {
        databaseInstance = Room.databaseBuilder(
            ctx,
            AppDatabase::class.java,
            "app_database"
        )
        .fallbackToDestructiveMigration(true)
        .build()
        init()
    }
    fun getDatabase(): AppDatabase {
        return databaseInstance!!
    }
    fun getDatabaseNullable(): AppDatabase? {
        return databaseInstance
    }
    fun resetDatabase() {
        CoroutineScope(Dispatchers.IO).launch {
            getDatabase().clearAllTables()
        }
    }
    suspend fun updateAppSetting(block: (AppSettingEntity) -> AppSettingEntity) {
        getDatabase().settingsDao().save(block(Runtime.appSettings.value))
    }

    external fun init()
    external fun setupSharedLibraryModule(mod: ModuleInstance, path: String): Int
    external fun setupJavascriptModule(mod: ModuleInstance, fileContents: ByteArray): Int
    external fun setupWebassemblyModule(mod: ModuleInstance, fileContents: ByteArray): Int

    @JvmStatic
    fun logGlobalLine(s: String) {
        Log.d("pak_global_log", s)
        Runtime.logGlobalLine(s)
    }

    fun stringHelper(@StringRes id: Int): String {
        return Pak.getActivity().getString(id)
    }

    fun keepScreenOn(on: Boolean) {
        Pak.getActivity().window.setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, if (on) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0)
    }

    @JvmStatic
    fun getDeviceFriendlyName(): String {
        val deviceName = Settings.Global.getString(Pak.getActivity().contentResolver, "device_name")

        if (!deviceName.isNullOrBlank()) {
            return deviceName.replace(" ", "-") + "-fudge"
        }

        return "${Build.MANUFACTURER}-${Build.MODEL}" + "-fudge"
    }

    fun decodeImageContents(data: ByteArray, orientation: Int? = null): ImageBitmap? {
        var options = BitmapFactory.Options()

        options.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        val scaleX = options.outWidth / GL10.GL_MAX_TEXTURE_SIZE
        val scaleY = options.outHeight / GL10.GL_MAX_TEXTURE_SIZE
        val scale = max(scaleY + 1, scaleX + 1)

        try {
            options = BitmapFactory.Options()
            options.inSampleSize = scale
            options.inDensity = scale
            options.inTargetDensity = scale
            options.inScaled = true
            var bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null

            if (orientation != null) {
                val matrix = Matrix()
                matrix.postRotate(orientation.toFloat())
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            return bitmap.asImageBitmap()
        } catch (ignored: Exception) {
            return null
        }
    }

    fun decodeImageFile(data: FileLayer.Handle, orientation: Int? = null): ImageBitmap? {
        var options = BitmapFactory.Options()

        val resolver = Pak.getActivity().contentResolver
        val fd = resolver.openFileDescriptor(data.uri, "r")
        if (fd == null) {
            Log.e(TAG, "${data.uri} not found")
            return null
        }

        options.inJustDecodeBounds = true
        BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, options)
        val scaleX = options.outWidth / GL10.GL_MAX_TEXTURE_SIZE
        val scaleY = options.outHeight / GL10.GL_MAX_TEXTURE_SIZE
        val scale = max(scaleY + 1, scaleX + 1)

        try {
            options = BitmapFactory.Options()
            options.inSampleSize = scale
            options.inDensity = scale
            options.inTargetDensity = scale
            options.inScaled = true
            var bitmap = BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, options) ?: return null

            if (orientation != null) {
                val matrix = Matrix()
                matrix.postRotate(orientation.toFloat())
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            fd.close()
            return bitmap.asImageBitmap()
        } catch (ignored: Exception) {
            fd.close()
            return null
        }
    }

    fun getJsonManifestList(): List<String> {
        val assman = Pak.getActivity().assets
        try {
            val files = ArrayList<String>()
            val list = assman.list("") ?: return mutableListOf()
            for (s in list) {
                if (!s.endsWith(".json")) continue
                files.add("file:///android_asset/${s}")
            }
            return files
        } catch (e: Exception) {
            Log.e("NR", e.toString())
            return mutableListOf()
        }
    }
}