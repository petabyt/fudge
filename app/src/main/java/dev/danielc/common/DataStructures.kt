package dev.danielc.common
import dev.danielc.R
import dev.danielc.common.MimeType.FILE
import kotlinx.serialization.Serializable

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

    fun getIcon(): Int {
        return when (this) {
            PROFESSIONAL_CAMERA -> R.drawable.baseline_photo_camera_24
            ACTION_CAMERA -> R.drawable.outline_videocam_24
            DASHCAM -> R.drawable.outline_camera_video_24
            GENERIC_CAMERA -> R.drawable.baseline_photo_camera_24
            WIFI_SD_CARD -> R.drawable.outline_sd_card_24
            DOORBELL -> R.drawable.outline_general_device_24
            GENERIC_HOME_DEVICE -> R.drawable.outline_general_device_24
            DESK -> R.drawable.outline_general_device_24
            GENERIC_FURNITURE -> R.drawable.outline_general_device_24
            PRINTER_3D -> R.drawable.outline_general_device_24
            HEADPHONES -> R.drawable.outline_headphones_24
            EARBUDS -> R.drawable.outline_earbuds_2_24
            SPEAKERS -> R.drawable.outline_speaker_24
            GENERIC_AUDIO -> R.drawable.outline_speaker_24
            SMART_GLASSES -> R.drawable.outline_eyeglasses_2_24
            SMART_TV -> R.drawable.outline_connected_tv_24
            SMARTWATCH -> R.drawable.outline_watch_24
            GENERIC_MEDICAL_WEARABLE -> R.drawable.outline_general_device_24
            GENERIC_EXERCISE_MACHINE -> R.drawable.outline_general_device_24
            POWER_TOOL -> R.drawable.outline_tools_power_drill_24
            GAME_CONTROLLER -> R.drawable.outline_videogame_asset_24
            DRONE -> R.drawable.outline_general_device_24
            GENERIC_REMOTE_CONTROL -> R.drawable.outline_general_device_24
            SCOOTER -> R.drawable.outline_general_device_24
            BICYCLE -> R.drawable.outline_general_device_24
            GENERIC_RIDEABLE -> R.drawable.outline_general_device_24
            AUTOMOTIVE_INFOTAINMENT -> R.drawable.outline_directions_car_24
            AUTOMOTIVE_DIAGNOSTIC -> R.drawable.outline_car_repair_24
            GENERIC_AUTOMOTIVE -> R.drawable.outline_directions_car_24
        }
    }

    fun getReadableName(): String {
        return when (this) {
            PROFESSIONAL_CAMERA -> "Camera"
            DASHCAM -> "Dashcam"
            EARBUDS -> "Pair of Earbuds"
            else -> "Device"
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
    ) {
        constructor(name: String, title: String, group: Int) : this(name, title, Group.fromInt(group) ?: Group.DEFAULT)
    }
    data class Button(override val args: Properties): Widget
    data class BooleanSetting(override val args: Properties, val value: Boolean): Widget
    data class IntSetting(override val args: Properties, val value: Int): Widget
    data class SliderSetting(override val args: Properties, val value: Int, val min: Int, val max: Int): Widget
    data class DropdownSetting(override val args: Properties, val index: Int, val options: List<String>): Widget {
        constructor(args: Properties, index: Int, options: Array<String>) : this(args, index, options.toList())
    }
    @Suppress("ArrayInDataClass")
    data class Graph(
        override val args: Properties,
        val points: IntArray,
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

    fun getIcon(): Int {
        return when (this) {
            CONNECT -> R.drawable.baseline_wifi_tethering_24
            CONSOLE -> R.drawable.baseline_terminal_24
            DASHBOARD -> R.drawable.outline_home_24
            FILE_GALLERY -> R.drawable.outline_photo_library_24
            FILE_VIEWER -> R.drawable.outline_photo_library_24
            GEOTAGGING -> R.drawable.outline_globe_location_pin_24
            LIVEVIEW -> R.drawable.outline_smart_display_24
            LIVE_FEED -> R.drawable.outline_dynamic_feed_24
            INTERVALOMETER -> R.drawable.outline_timelapse_24
            NONE -> R.drawable.baseline_question_mark_24
            else -> R.drawable.baseline_question_mark_24
        }
    }

    fun getName(): String {
        return when (this) {
            CONNECT -> "Connect"
            CONSOLE -> "Console"
            DASHBOARD -> "Dashboard"
            FILE_GALLERY -> "Gallery"
            FILE_VIEWER -> "Viewer"
            GEOTAGGING -> "Geotagging"
            LIVEVIEW -> "Liveview"
            LIVE_FEED -> "Live feed"
            INTERVALOMETER -> "Intervalometer"
            NONE -> "None"
            else -> "?"
        }
    }
}

/**
 * struct PakFileHandle
 */
data class FileHandle(
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

data class StorageInfo(
    val name: String,
    val nFiles: Int,
    val itemsSortedBy: SortBy = SortBy.DEFAULT,
    val sizeBytes: Long? = null,
    val usedBytes: Long? = null,
    val isLiveFeedMedium: Boolean = false,
    val currentStatus: String? = null,
    val currentProgress: Int? = null,
) {
    constructor(
        name: String,
        nFiles: Int,
        itemsSortedBy: Int,
        sizeBytes: Long,
        usedBytes: Long,
        isLiveFeedMedium: Boolean,
    ) : this(name, nFiles, SortBy.fromId(itemsSortedBy)!!, if (sizeBytes == 0L) null else sizeBytes, if (usedBytes == 0L) null else usedBytes, isLiveFeedMedium)
}

enum class MimeType(val mediaTypeString: String) {
    FILE("application/octet-stream"),
    FOLDER("inode/directory"),
    JPEG("image/jpeg"),
    PNG("image/png"),
    IMAGE("image"),
    VIDEO("video"),
    MOV("video/quicktime");
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
        fun getIcon(type: MimeType?): Int {
            return when (type) {
                FOLDER -> R.drawable.baseline_folder_open_24
                JPEG -> R.drawable.baseline_landscape_24
                PNG -> R.drawable.baseline_landscape_24
                MOV -> R.drawable.baseline_movie_24
                else -> R.drawable.outline_files_24
            }
        }
        fun toString(t: MimeType?): String {
            return (t ?: FILE).mediaTypeString
        }
        fun fromString(str: String?): MimeType {
            return MimeType.entries.find { it.mediaTypeString == str } ?: FILE
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
            return MimeType.entries.find { it.mediaTypeString == str } ?: FILE
        }
    }
}