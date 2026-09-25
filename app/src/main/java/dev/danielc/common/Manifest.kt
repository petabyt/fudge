package dev.danielc.common
import dev.danielc.common.Runtime.logGlobalLine
import dev.danielc.libpak.Bluetooth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Constructed manually or loaded from JSON in modules folder.
 *
 * This may have 'discovery info', which can be used to dynamically match
 * available devices (advertisements, paired devices) to modules without having
 * to initialize a module instance and use a lot of ram
 */
data class ModuleManifest(
    val name: String,
    val description: String? = null,
    val website: String? = null,
    val author: String? = null,
    val version: Int = 0,
    val requiredRuntimeVersion: Int? = null,
    val runtimeVersion: Int = 0,
    val publicKey: String? = null,
    val isDraft: Boolean = false,
    val moduleType: ModuleType = ModuleType.WEBASSEMBLY,
    val scriptPath: String = "",
    val targets: List<Target> = emptyList(),
    val manifestFilePath: String? = null,
) {
    enum class Transport(val value: Int) {
        BLUETOOTH(1),
        USB(2),
        WIFI_AP(3),
        USB_DEVICE_MODE(4),
        HOST_WIFI_AP(5),
        LOCAL_NETWORK_UDP(6),
        INTERNET(7);
        companion object {
            fun fromValue(value: Int): Transport? {
                return entries.find { it.value == value }
            }
        }
    }
    enum class ModuleType {
        QUICKJS,
        WEBASSEMBLY,
        SHARED_LIBRARY;
        fun getDesc(): String {
            return when (this) {
                QUICKJS -> "Javascript"
                WEBASSEMBLY -> "Webassembly"
                SHARED_LIBRARY -> "Shared library (internal)"
            }
        }
    }
    data class SetupOption(
        val name: String,
        val title: String,
        val transport: Transport? = null,
    )
    data class Target(
        val deviceId: Device = Device.PROFESSIONAL_CAMERA,
        val company: String = "",
        val summary: String? = null,
        val products: List<String> = emptyList(),
        val setupOptions: List<SetupOption> = emptyList(),
        val wifiFilter: WiFiFilter? = null,
        val bluetoothFilters: List<BluetoothFilter> = emptyList(),
    )
    data class WiFiFilter(
        val ssidPattern: String,
        val defaultPassword: String? = null,
    )
    @Suppress("ArrayInDataClass")
    data class BluetoothFilter(
        val namePattern: String? = null,
        val isClassic: Boolean = false,
        val mfgData: ByteArray? = null,
        val mfgDataMask: ByteArray? = null,
        val serviceUuids: List<String> = emptyList(),
    ) {
        fun toBluetoothDeviceFilter(): Bluetooth.BtFilter {
            val filter = Bluetooth.BtFilter()
            filter.serviceUuids = this.serviceUuids.toTypedArray()
            filter.isClassic = this.isClassic
            filter.manufacData = this.mfgData
            filter.manufacDataMask = this.mfgDataMask
            return filter
        }
    }
    data class UsbFilter(
        val pid: Int? = null,
        val vid: Int? = null,
        val usbClass: Int? = null,
    )
    fun getModulePath(): String {
        if (manifestFilePath == null || moduleType == ModuleType.SHARED_LIBRARY) return scriptPath
        return manifestFilePath.replaceAfterLast("/", scriptPath)
    }
}

fun manifestFromJson(text: String, filename: String): ModuleManifest? {
    try {
        val obj: JsonElement = Json.parseToJsonElement(text)
        val root = obj.jsonObject

        val jsonTarget = root["targets"]?.jsonArray

        val targets = mutableListOf<ModuleManifest.Target>()
        if (jsonTarget != null) {
            for (target in jsonTarget) {
                var t = ModuleManifest.Target(
                    company = target.jsonObject["company"]?.jsonPrimitive?.content ?: throw Error("Missing company field"),
                    summary = target.jsonObject["summary"]?.jsonPrimitive?.content,
                    products = Json.decodeFromJsonElement<List<String>>(target.jsonObject["products"] ?: JsonArray(emptyList())),
                    deviceId = Device.fromId(target.jsonObject["deviceType"]?.jsonPrimitive?.content) ?: throw Error("Missing deviceType field")
                )

                val jsonWiFiFilter = target.jsonObject["wifiFilter"]?.jsonObject
                if (jsonWiFiFilter != null) {
                    t = t.copy(
                        wifiFilter = ModuleManifest.WiFiFilter(
                            ssidPattern = jsonWiFiFilter.jsonObject["ssidPattern"]?.jsonPrimitive?.content ?: throw Error("Missing ssidPattern field"),
                            defaultPassword = jsonWiFiFilter.jsonObject["defaultPassword"]?.jsonPrimitive?.content
                        )
                    )
                }

                fun toByteArray(e: JsonElement?): ByteArray? {
                    if (e == null) return null
                    var a = ByteArray(0)
                    if (e is JsonArray) {
                        for (b in e.jsonArray) {
                            a += b.jsonPrimitive.int.toByte()
                        }
                    } else if (e is JsonPrimitive) {
                        return e.jsonPrimitive.content.split(",").map { it.replace("0x", "").toInt(16).toByte() }.toByteArray()
                    }
                    return a
                }

                val jsonBtFilters = target.jsonObject["bluetoothFilters"]?.jsonArray
                if (jsonBtFilters != null) {
                    for (filter in jsonBtFilters) {
                        val svcUuids: MutableList<String> = mutableListOf()
                        for (s in filter.jsonObject["serviceUuids"]?.jsonArray.orEmpty()) {
                            svcUuids += s.jsonPrimitive.content
                        }
                        t = t.copy(
                            bluetoothFilters = t.bluetoothFilters + ModuleManifest.BluetoothFilter(
                                mfgData = toByteArray(filter.jsonObject["mfgData"]),
                                serviceUuids = svcUuids,
                            ),
                        )
                    }
                }

                target.jsonObject["setupOptions"]?.jsonArray?.let { setupOptions ->
                    for (filter in setupOptions) {
                        t = t.copy(setupOptions = t.setupOptions + ModuleManifest.SetupOption(
                            name = filter.jsonObject["name"]?.jsonPrimitive?.content ?: throw Error("Missing name field"),
                            title = filter.jsonObject["title"]?.jsonPrimitive?.content ?: throw Error("Missing title field"),
                            transport = ModuleManifest.Transport.fromValue(filter.jsonObject["transport"]?.jsonPrimitive?.int ?: throw Error("Missing transport field")),
                        ))
                    }
                }

                targets += t
            }
        }
        val manifest = ModuleManifest(
            manifestFilePath = filename,
            name = root["name"]?.jsonPrimitive?.content ?: throw Error("missing name field"),
            description = root["description"]?.jsonPrimitive?.content,
            author = root["author"]?.jsonPrimitive?.content,
            website = root["website"]?.jsonPrimitive?.content,
            scriptPath = root["modulePath"]?.jsonPrimitive?.content ?: throw Error("missing modulePath field"),
            moduleType = when (root["moduleType"]?.jsonPrimitive?.content ?: throw Error("missing moduleType field")) {
                "js" -> ModuleManifest.ModuleType.QUICKJS
                "wasm" -> ModuleManifest.ModuleType.WEBASSEMBLY
                else -> ModuleManifest.ModuleType.SHARED_LIBRARY
            },
            version = root["version"]?.jsonPrimitive?.int ?: throw Error("Missing version field"),
            isDraft = root["isDraft"]?.jsonPrimitive?.booleanOrNull == true,
            targets = targets,
        )

        return manifest
    } catch (e: Error) {
        logGlobalLine("Error parsing manifest $filename")
        logGlobalLine(e.message ?: "")
        return null
    } catch (e: Exception) {
        logGlobalLine(e.message ?: "")
        return null
    }
}