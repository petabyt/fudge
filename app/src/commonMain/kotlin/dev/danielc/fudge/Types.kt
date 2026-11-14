package dev.danielc.fudge
import androidx.compose.ui.graphics.ImageBitmap
import dev.danielc.fudge.ModuleThreadPolicy.NO_CONCURRENT_JOBS
import fantasyfudge.app.generated.resources.*
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import kotlin.time.TimeSource

enum class ModuleThreadPolicy(val code: Int) {
    NO_CONCURRENT_JOBS(0),
    CONCURRENT_JOBS(1);
    companion object {
        fun fromCode(code: Int): ModuleThreadPolicy = ModuleThreadPolicy.entries.find { it.code == code } ?: NO_CONCURRENT_JOBS
    }
}

fun longToFileSize(bytes: Long): String {
    if (bytes <= 0) return "0b"

    val units = arrayOf("b", "kb", "mb", "gb", "tb", "pb", "eb")
    var size = bytes.toDouble()
    var unitIndex = 0

    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }

    return "${size.toInt()} ${units[unitIndex]}"
}

enum class PakReturnCode(val code: Int) {
    NONE(0),
    NON_FATAL(-1),
    IO(-2),
    UNDEFINED(-3),
    PERMISSION(-4),
    UNSUPPORTED(-5),
    UNIMPLEMENTED(-6),
    DISCONNECTED(-7),
    NO_CONNECTION(-8),
    CANCELLED(-9),
    DEFERRED(-10);
    val failed get() = this != CANCELLED && this != NONE
    val success get() = this == NONE
    companion object {
        fun fromCode(code: Int): PakReturnCode = entries.find { it.code == code } ?: UNDEFINED
    }
}

enum class Device(val id: String) {
    // Photo class
    PROFESSIONAL_CAMERA("professional-camera"),
    ACTION_CAMERA("action-camera"),
    DASHCAM("dashcam"),
    GENERIC_CAMERA("generic-camera"),
    WIFI_SD_CARD("wifi-sd-card"),
    DOORBELL("doorbell"),

    // Home class
    GENERIC_HOME_DEVICE("generic-home-device"),
    DESK("desk"),
    GENERIC_FURNITURE("generic-furniture"),
    PRINTER_3D("3d-printer"),

    // Accessory class
    HEADPHONES("headphones"),
    EARBUDS("earbuds"),
    SPEAKERS("speakers"),
    GENERIC_AUDIO("generic-audio"),
    SMART_GLASSES("smart-glasses"),
    SMART_TV("smart-tv"),
    SMARTWATCH("smartwatch"),
    GENERIC_MEDICAL_WEARABLE("generic-medical-wearable"),
    GENERIC_EXERCISE_MACHINE("generic-exercise-machine"),

    // Non-photo gadget class
    POWER_TOOL("power-tool"),
    GAME_CONTROLLER("game-controller"),
    DRONE("drone"),
    GENERIC_REMOTE_CONTROL("generic-remote-control"),
    SCOOTER("scooter"),
    BICYCLE("bicycle"),
    GENERIC_RIDEABLE("generic-rideable"),
    AUTOMOTIVE_INFOTAINMENT("automotive-infotainment"),
    AUTOMOTIVE_DIAGNOSTIC("automotive-diagnostic"),
    GENERIC_AUTOMOTIVE("generic-automotive");

    companion object {
        fun fromId(id: String?): Device? {
            return entries.find { it.id == id }
        }
    }

    fun getIcon(): DrawableResource {
        return when (this) {
            PROFESSIONAL_CAMERA, GENERIC_CAMERA -> Res.drawable.outline_photo_camera_24
            ACTION_CAMERA -> Res.drawable.outline_videocam_24
            DASHCAM -> Res.drawable.outline_camera_video_24
            WIFI_SD_CARD -> Res.drawable.outline_sd_card_24
            DOORBELL -> Res.drawable.outline_general_device_24
            GENERIC_HOME_DEVICE -> Res.drawable.outline_general_device_24
            DESK -> Res.drawable.outline_general_device_24
            GENERIC_FURNITURE -> Res.drawable.outline_general_device_24
            PRINTER_3D -> Res.drawable.outline_general_device_24
            HEADPHONES -> Res.drawable.outline_headphones_24
            EARBUDS -> Res.drawable.outline_earbuds_2_24
            SPEAKERS -> Res.drawable.outline_speaker_24
            GENERIC_AUDIO -> Res.drawable.outline_speaker_24
            SMART_GLASSES -> Res.drawable.outline_eyeglasses_2_24
            SMART_TV -> Res.drawable.outline_connected_tv_24
            SMARTWATCH -> Res.drawable.outline_watch_24
            GENERIC_MEDICAL_WEARABLE -> Res.drawable.outline_general_device_24
            GENERIC_EXERCISE_MACHINE -> Res.drawable.outline_general_device_24
            POWER_TOOL -> Res.drawable.outline_tools_power_drill_24
            GAME_CONTROLLER -> Res.drawable.outline_videogame_asset_24
            DRONE -> Res.drawable.outline_general_device_24
            GENERIC_REMOTE_CONTROL -> Res.drawable.outline_general_device_24
            SCOOTER -> Res.drawable.outline_general_device_24
            BICYCLE -> Res.drawable.outline_general_device_24
            GENERIC_RIDEABLE -> Res.drawable.outline_general_device_24
            AUTOMOTIVE_INFOTAINMENT -> Res.drawable.outline_directions_car_24
            AUTOMOTIVE_DIAGNOSTIC -> Res.drawable.outline_car_repair_24
            GENERIC_AUTOMOTIVE -> Res.drawable.outline_directions_car_24
        }
    }

    fun getReadableName(): StringResource {
        return when (this) {
            PROFESSIONAL_CAMERA -> Res.string.camera
            DASHCAM -> Res.string.dashcam
            EARBUDS -> Res.string.pair_of_earbuds
            else -> Res.string.device
        }
    }
}

@Suppress("ArrayInDataClass")
data class SavedDeviceInfo(
    val uniqueIdentifier: String,
    val name: String,
    val privateData: ByteArray?,
)

/**
 * PakModule defined setting or widget that can be updated by the user or the module
  */
sealed interface Widget {
    val args: Properties
    enum class Group(val id: Int) {
        DEFAULT(0),
        LIVEVIEW(1),
        AUDIO(2);
        companion object {
            fun fromInt(id: Int): Group? { return Group.entries.find { it.id == id } }
        }
    }
    data class Properties(
        val name: String,
        val title: String,
        val group: Group = Group.DEFAULT,
        val disabled: Boolean = false,
    ) {
        constructor(name: String, title: String?, group: Int, disabled: Boolean) : this(name, title ?: "?", Group.fromInt(group) ?: Group.DEFAULT, disabled)
    }
    data class Button(override val args: Properties): Widget
    data class BooleanWidget(override val args: Properties, val value: Boolean): Widget
    data class IntWidget(override val args: Properties, val value: Int): Widget
    data class SliderWidget(override val args: Properties, val value: Int, val min: Int, val max: Int): Widget
    data class ComboboxWidget(override val args: Properties, val index: Int, val options: List<String>): Widget {
        constructor(args: Properties, index: Int, options: Array<String>) : this(args, index, options.toList())
    }
    @Suppress("ArrayInDataClass")
    data class GraphWidget(
        override val args: Properties,
        val points: IntArray,
    ): Widget
    data class MarkdownWidget(
        override val args: Properties,
        val text: String
    ): Widget
}

enum class Command(val cmd: String) {
    PAK_CMD_SHUTTER_DOWN("shutter-down"),
    PAK_CMD_SHUTTER_UP("shutter-up"),
    PAK_CMD_FOCUS_DOWN("focus-down"),
    PAK_CMD_FOCUS_UP("focus-up"),
}

data class Location(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val satellites: Int,
)

@Serializable
enum class Screen(val strId: String, val id: Int) {
    NONE("none", 0),
    CONNECT("connect", 1),
    CONNECT_SECONDARY("connecting-secondary", 1),
    SETUP("setup", 10),

    CONSOLE("console", 100),
    DASHBOARD("dashboard", 101),
    FILE_GALLERY("filegallery", 102),
    FILE_VIEWER("fileviewer", 103),
    GEOTAGGING("geotagging", 104),
    LIVEVIEW("liveview", 105),
    LIVE_FEED("livefeed", 106),
    INTERVALOMETER("intervalometer", 107),
    DISCONNECTED("disconnected", 200);

    companion object {
        fun fromId(id: Int?): Screen? {
            return entries.find { it.id == id }
        }
        fun fromStrId(id: String): Screen? {
            return entries.find { it.strId == id }
        }
    }

    fun getIcon(): DrawableResource {
        return when (this) {
            CONNECT -> Res.drawable.baseline_wifi_tethering_24
            CONSOLE -> Res.drawable.baseline_terminal_24
            DASHBOARD -> Res.drawable.outline_home_24
            FILE_GALLERY -> Res.drawable.outline_photo_library_24
            FILE_VIEWER -> Res.drawable.outline_photo_library_24
            GEOTAGGING -> Res.drawable.outline_globe_location_pin_24
            LIVEVIEW -> Res.drawable.outline_smart_display_24
            LIVE_FEED -> Res.drawable.outline_dynamic_feed_24
            INTERVALOMETER -> Res.drawable.outline_timelapse_24
            NONE -> Res.drawable.baseline_question_mark_24
            else -> Res.drawable.baseline_question_mark_24
        }
    }

    fun getName(): StringResource {
        return when (this) {
            CONNECT -> Res.string.screen_connect
            CONSOLE -> Res.string.screen_console
            DASHBOARD -> Res.string.screen_dashboard
            FILE_GALLERY -> Res.string.screen_gallery
            FILE_VIEWER -> Res.string.screen_viewer
            GEOTAGGING -> Res.string.screen_geotagging
            LIVEVIEW -> Res.string.screen_liveview
            LIVE_FEED -> Res.string.screen_live_feed
            INTERVALOMETER -> Res.string.screen_intervalometer
            else -> Res.string.screen_unknown
        }
    }
}

/**
 * struct PakObjectHandle
 */
data class ObjectHandle(
    val index: Int = 0,
    val storageName: String? = null,
    val path: String? = null,
)

/**
 * struct PakFileMetadata
 */
data class FileMetadata(
    var filename: String? = null,
    val mimeType: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val filesize: Int = 0,
    val createdDate: String? = null,
    val updatedDate: String? = null,
    val orientation: Int = 0,
) {
    val createdTimestamp = if (createdDate == null) null else Timestamp.fromString(createdDate)
    fun getMimeType(): MimeType {
        return MimeType.fromString(this.mimeType.orEmpty())
    }
}

/**
 * Property IDs for the module instance
 */
enum class ModuleProperty(val id: String) {
    TEMPERATURE("temperature"),
    HUMIDITY("humidity"),
    NAME_OF_DEVICE("name"),
    FIRMWARE_VERSION("firmware-version"),
    BATTERY_MAIN("battery-main"),
    BATTERY_LEFT("battery-left"),
    BATTERY_RIGHT("battery-right");
    companion object {
        fun fromId(id: String?): ModuleProperty? {
            return entries.find { it.id == id }
        }
    }
}

enum class ModulePermission(val id: String) {
    WIFI("wifi"),
    BLUETOOTH("bluetooth"),
    SOCKETS("sockets"),
    INTERNET("internet"),
}

enum class SortBy(val id: Int) {
    DEFAULT(0),
    NEWEST_FIRST(1),
    OLDEST_FIRST(2),
    LARGEST_FIRST(3),
    SMALLEST_FIRST(4);

    companion object {
        fun fromId(id: Int): SortBy? {
            return entries.find { it.id == id }
        }
    }
}

enum class MimeType(val mediaTypeString: String, val alts: Array<String>? = null) {
    FILE("application/octet-stream"),
    FOLDER("inode/directory"),
    JPEG("image/jpeg"),
    PNG("image/png"),
    MOV("video/quicktime"),
    IMAGE("image"),
    VIDEO("video"),
    RAW("image/x-dcraw", arrayOf(
      "image/dng",
      "image/x-adobe-dng",
      "image/x-arri-ari",
      "image/x-blackmagic-braw",
      "image/x-canon-cr2",
      "image/x-canon-cr3",
      "image/x-canon-crw",
      "image/x-epson-erf",
      "image/x-fuji-raf",
      "image/x-hasselblad-3fr",
      "image/x-hasselblad-fff",
      "image/x-kodak-dcr",
      "image/x-kodak-k25",
      "image/x-kodak-kdc",
      "image/x-leaf-mos",
      "image/x-leica-rwl",
      "image/x-mamiya-mef",
      "image/x-minolta-mrw",
      "image/x-nikon-nef",
      "image/x-nikon-nrw",
      "image/x-olympus-orf",
      "image/x-panasonic-raw",
      "image/x-panasonic-rw2",
      "image/x-pentax-pef",
      "image/x-pentax-ptx",
      "image/x-phaseone-iiq",
      "image/x-samsung-srw",
      "image/x-sigma-x3f",
      "image/x-sony-arw",
      "image/x-sony-sr2",
      "image/x-sony-srf"
    ));
    fun isImage(): Boolean {
        return when (this) {
            JPEG, PNG, IMAGE -> true
            else -> false
        }
    }
    fun isVideo(): Boolean {
        return when (this) {
            MOV, VIDEO -> true
            else -> false
        }
    }
    companion object {
        fun getIcon(type: MimeType?): DrawableResource {
            return when (type) {
                FOLDER -> Res.drawable.baseline_folder_open_24
                JPEG -> Res.drawable.baseline_landscape_24
                PNG -> Res.drawable.baseline_landscape_24
                MOV -> Res.drawable.baseline_movie_24
                else -> Res.drawable.outline_files_24
            }
        }
        fun toString(t: MimeType?): String {
            return (t ?: FILE).mediaTypeString
        }
        fun fromString(str: String?): MimeType {
            return MimeType.entries.find { it.mediaTypeString == str || (it.alts?.contains(str) ?: false) } ?: FILE
        }
    }
}
enum class WidgetNames(val id: String) {
    SHUTTER_SPEED("shutter-speed"),
    ISO("iso"),
    APERTURE("aperture"),
    WHITE_BALANCE("white-balance"),
    IMAGE_FORMAT("image-format");
    companion object {
        fun fromString(str: String?): MimeType {
            return MimeType.entries.find { it.mediaTypeString == str } ?: MimeType.FILE
        }
    }
}

data class Timestamp (
	val year: Int,
	val month: Int,
	val day: Int,
	val hour: Int,
	val minute: Int,
	val second: Int,
	val centisecond: Int,
) {
    companion object {
        private val ISO_DATE_REGEX = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(?:\.(\d+))?$""")
        fun fromString(input: String): Timestamp? {
            val matchResult = ISO_DATE_REGEX.matchEntire(input) ?: return null
            val (year, month, day, hour, minute, second, subsecond) = matchResult.destructured

            return Timestamp(
                year = year.toInt(),
                month = month.toInt(),
                day = day.toInt(),
                hour = hour.toInt(),
                minute = minute.toInt(),
                second = second.toInt(),
                centisecond = (if (subsecond.isEmpty()) 0 else subsecond.toInt()) * 10
            )
        }
    }
}

data class StorageInfo(
    val storageName: String,
    val nFiles: Int = objects.size,
    val sizeBytes: Long? = null,
    val usedBytes: Long? = null,
    val type: PakStorageType = PakStorageType.PAK_FILESYSTEM,
    val currentStatus: String? = null,
    val currentProgress: Int? = null,
    val objectListSortedOrder: SortBy = SortBy.DEFAULT,
    val objects: List<FilesystemObject> = emptyList(),
    var nSelectedObjects: Int = 0
) {
    constructor(
        name: String,
        nFiles: Int,
        itemsSortedBy: Int,
        sizeBytes: Long,
        usedBytes: Long,
        type: Int,
    ) : this(
        storageName = name,
        nFiles = nFiles,
        objectListSortedOrder = SortBy.fromId(itemsSortedBy)!!,
        sizeBytes = if (sizeBytes == 0L) null else sizeBytes,
        usedBytes = if (usedBytes == 0L) null else usedBytes,
        type = PakStorageType.fromCode(type)
    )
}

data class FilesystemObject(
    val metadata: FileMetadata? = null,
    val thumbnail: ImageBitmap? = null,
    var invalidMetadata: Boolean = false,
    var invalidThumbnail: Boolean = false,
    var lastUpdated: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow(),
    val hasSaved: Boolean = false,
    val selected: Boolean = false,
)

enum class PakStorageType(val code: Int) {
    PAK_FILESYSTEM(0),
    PAK_LIVE_QUEUE(1),
    PAK_BACKUPS(2);
    companion object {
        fun fromCode(code: Int): PakStorageType = PakStorageType.entries.find { it.code == code } ?: PAK_FILESYSTEM
    }
}