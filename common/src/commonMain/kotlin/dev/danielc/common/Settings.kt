package dev.danielc.common

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

@Suppress("ArrayInDataClass")
@Entity(tableName = "saved_devices")
data class SavedDeviceEntity(
    @PrimaryKey val uniqueIdentifier: String,
    val lastSeenTimestamp: Long = Clock.System.now().toEpochMilliseconds(),
    val name: String,
    val manifestName: String,
    val targetIndex: Int,
    val setupOption: String? = null,
    val bluetoothMacAddress: String? = null,
    @Embedded val wifiInfo: WiFiInfo? = null,
    val androidAssociationId: Int? = null,
    val auxillaryData: ByteArray? = null,
) {
    data class WiFiInfo(
        val ssid: String? = null,
        val bssid: String? = null,
        val password: String? = null,
    )
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM saved_devices ORDER BY lastSeenTimestamp DESC")
    fun getAllDevices(): Flow<List<SavedDeviceEntity>>
    @Query("SELECT * FROM saved_devices ORDER BY lastSeenTimestamp DESC")
    fun getAllDevicesList(): List<SavedDeviceEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDevice(device: SavedDeviceEntity)
    @Delete
    suspend fun deleteDevice(device: SavedDeviceEntity)
}

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val id: Int = 1,
    val downloadsLocation: String = FileLayer.getDefaultDownloadDirectory(),
    val perDeviceSubFolder: Boolean = false,
    val firstTime: Boolean = true,
    val showWelcomeDialog: Boolean = true,
)

@Entity(tableName = "disabled_module")
data class DisabledModuleEntity(
    @PrimaryKey val name: String,
)

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(config: AppSettingEntity)
    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun get(): AppSettingEntity?
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun getFlow(): Flow<AppSettingEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addDisabledModule(e: DisabledModuleEntity)
    @Delete
    suspend fun removeDisabledModule(e: DisabledModuleEntity)
    @Query("SELECT * FROM disabled_module")
    fun getDisabledModulesFlow(): Flow<List<DisabledModuleEntity>>
}

@Database(entities = [
    DisabledModuleEntity::class,
    SavedDeviceEntity::class,
    AppSettingEntity::class
    ], version = 2,
    autoMigrations = [
        AutoMigration(from = 1, to = 2)
    ],
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun settingsDao(): SettingsDao
}