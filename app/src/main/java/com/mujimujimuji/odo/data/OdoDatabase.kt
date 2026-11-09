package com.mujimujimuji.odo.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mujimujimuji.odo.engine.ProfileState
import com.mujimujimuji.odo.engine.Streak
import com.mujimujimuji.odo.engine.TripSummary
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** 프로필은 한 줄(id 0). 위치·원시 신호는 저장하지 않는다(절대 규칙 7). */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = 0,
    val totalXp: Int,
    val totalSafeKm: Int,
    val nightSafeKm: Int,
    val rainSafeKm: Int,
    val tripCount: Int,
    val streakDays: Int,
    val streakLastDate: String?,
    val dayDate: String?,
    val dayXp: Int,
    val daySafeKm: Int,
) {
    fun toState() = ProfileState(
        totalXp = totalXp,
        totalSafeKm = totalSafeKm,
        nightSafeKm = nightSafeKm,
        rainSafeKm = rainSafeKm,
        tripCount = tripCount,
        streak = Streak(streakDays, streakLastDate?.let(LocalDate::parse)),
        dayDate = dayDate?.let(LocalDate::parse),
        dayXp = dayXp,
        daySafeKm = daySafeKm,
    )

    companion object {
        fun of(p: ProfileState) = ProfileEntity(
            totalXp = p.totalXp,
            totalSafeKm = p.totalSafeKm,
            nightSafeKm = p.nightSafeKm,
            rainSafeKm = p.rainSafeKm,
            tripCount = p.tripCount,
            streakDays = p.streak.days,
            streakLastDate = p.streak.lastDate?.toString(),
            dayDate = p.dayDate?.toString(),
            dayXp = p.dayXp,
            daySafeKm = p.daySafeKm,
        )
    }
}

/** 트립 집계값만 저장한다. */
@Entity(tableName = "trip")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val distanceKm: Double,
    val safeKm: Int,
    val excludedSegments: Int,
    val eventCount: Int,
    val xpEarned: Int,
    val levelBefore: Int,
    val levelAfter: Int,
    /** 쉼표로 이은 업적 ID. */
    val achievementIds: String,
) {
    companion object {
        fun of(s: TripSummary) = TripEntity(
            startTimeMs = s.startTimeMs,
            endTimeMs = s.endTimeMs,
            distanceKm = s.distanceKm,
            safeKm = s.safeKm,
            excludedSegments = s.excludedSegments,
            eventCount = s.eventCount,
            xpEarned = s.xpEarned,
            levelBefore = s.levelBefore,
            levelAfter = s.levelAfter,
            achievementIds = s.achievements.joinToString(",") { it.id },
        )
    }
}

@Entity(tableName = "achievement")
data class AchievementEntity(
    @PrimaryKey val id: String,
    val unlockedAtMs: Long,
)

/** 꾸미기 선택값(차종 공통). slot "color"는 구 색 선택, slot "vehicle"은 선택 차종. */
@Entity(tableName = "cosmetic")
data class CosmeticEntity(
    @PrimaryKey val slot: String,
    val itemId: String,
)

/** 차종별 꾸미기 슬롯 선택값(v2). vehicle·slot은 cosmetic 패키지의 id 문자열. */
@Entity(tableName = "loadout_slot", primaryKeys = ["vehicle", "slot"])
data class LoadoutSlotEntity(
    val vehicle: String,
    val slot: String,
    val itemId: String,
)

/** 폰 연결(가상) 상태 한 줄(id 0). 연락처·계정 같은 개인정보는 저장하지 않는다. */
@Entity(tableName = "phone_link")
data class PhoneLinkEntity(
    @PrimaryKey val id: Int = 0,
    val linked: Boolean,
    val linkedAtMs: Long?,
    val label: String?,
)

@Dao
interface OdoDao {
    @Query("SELECT * FROM profile WHERE id = 0")
    suspend fun profile(): ProfileEntity?

    @Query("SELECT * FROM profile WHERE id = 0")
    fun profileFlow(): Flow<ProfileEntity?>

    @Upsert
    suspend fun saveProfile(p: ProfileEntity)

    @Insert
    suspend fun insertTrip(t: TripEntity): Long

    @Query("SELECT COALESCE(SUM(safeKm), 0) FROM trip WHERE endTimeMs >= :sinceMs")
    fun safeKmSince(sinceMs: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(safeKm), 0) FROM trip WHERE endTimeMs >= :fromMs AND endTimeMs < :toMs")
    fun safeKmBetween(fromMs: Long, toMs: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun unlock(a: AchievementEntity)

    @Query("SELECT * FROM achievement")
    suspend fun achievements(): List<AchievementEntity>

    @Query("SELECT * FROM achievement")
    fun achievementsFlow(): Flow<List<AchievementEntity>>

    @Upsert
    suspend fun setCosmetic(c: CosmeticEntity)

    @Query("SELECT itemId FROM cosmetic WHERE slot = :slot")
    fun cosmetic(slot: String): Flow<String?>

    @Query("SELECT * FROM loadout_slot WHERE vehicle = :vehicle")
    fun loadoutSlotsFlow(vehicle: String): Flow<List<LoadoutSlotEntity>>

    @Upsert
    suspend fun setLoadoutSlot(e: LoadoutSlotEntity)

    @Query("SELECT * FROM phone_link WHERE id = 0")
    fun phoneLinkFlow(): Flow<PhoneLinkEntity?>

    @Upsert
    suspend fun savePhoneLink(e: PhoneLinkEntity)

    @Query("DELETE FROM profile") suspend fun clearProfile()
    @Query("DELETE FROM trip") suspend fun clearTrips()
    @Query("DELETE FROM achievement") suspend fun clearAchievements()
    @Query("DELETE FROM cosmetic") suspend fun clearCosmetics()
    @Query("DELETE FROM loadout_slot") suspend fun clearLoadout()
    @Query("DELETE FROM phone_link") suspend fun clearPhoneLink()

    @Transaction
    suspend fun replaceAll(profile: ProfileEntity, trips: List<TripEntity>, achievements: List<AchievementEntity>) {
        clearProfile()
        clearTrips()
        clearAchievements()
        clearCosmetics()
        clearLoadout()
        clearPhoneLink()
        saveProfile(profile)
        trips.forEach { insertTrip(it) }
        achievements.forEach { unlock(it) }
    }
}

@Database(
    entities = [
        ProfileEntity::class, TripEntity::class, AchievementEntity::class, CosmeticEntity::class,
        LoadoutSlotEntity::class, PhoneLinkEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class OdoDatabase : RoomDatabase() {
    abstract fun dao(): OdoDao

    companion object {
        /** v1 → v2: 표 두 개를 추가만 한다(기존 표·데이터 그대로). 설계서 §3.5의 SQL과 동일. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `loadout_slot` (`vehicle` TEXT NOT NULL, `slot` TEXT NOT NULL, " +
                        "`itemId` TEXT NOT NULL, PRIMARY KEY(`vehicle`, `slot`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `phone_link` (`id` INTEGER NOT NULL, `linked` INTEGER NOT NULL, " +
                        "`linkedAtMs` INTEGER, `label` TEXT, PRIMARY KEY(`id`))",
                )
            }
        }

        fun create(context: Context): OdoDatabase =
            Room.databaseBuilder(context.applicationContext, OdoDatabase::class.java, "odo.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
