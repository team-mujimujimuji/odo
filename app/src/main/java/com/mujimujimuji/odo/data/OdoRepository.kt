package com.mujimujimuji.odo.data

import com.mujimujimuji.odo.engine.LevelTable
import com.mujimujimuji.odo.engine.ProfileState
import com.mujimujimuji.odo.engine.TripSummary
import com.mujimujimuji.odo.sim.DemoSeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

class OdoRepository(private val dao: OdoDao, private val zone: ZoneId) {

    suspend fun profile(): ProfileState = dao.profile()?.toState() ?: ProfileState()
    val profileFlow: Flow<ProfileState> = dao.profileFlow().map { it?.toState() ?: ProfileState() }

    suspend fun saveProfile(p: ProfileState) = dao.saveProfile(ProfileEntity.of(p))

    suspend fun unlockedIds(): Set<String> = dao.achievements().map { it.id }.toSet()
    val unlockedFlow: Flow<Set<String>> = dao.achievementsFlow().map { list -> list.map { it.id }.toSet() }

    suspend fun unlock(id: String, timeMs: Long) = dao.unlock(AchievementEntity(id, timeMs))

    suspend fun saveTrip(s: TripSummary) = dao.insertTrip(TripEntity.of(s))

    /** "이번 주 안전 km": 오늘 포함 최근 7일. */
    fun weeklySafeKm(today: LocalDate): Flow<Int> =
        dao.safeKmSince(today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli())

    fun cosmetic(slot: String): Flow<String?> = dao.cosmetic(slot)
    suspend fun setCosmetic(slot: String, itemId: String) = dao.setCosmetic(CosmeticEntity(slot, itemId))

    suspend fun isEmpty(): Boolean = dao.profile() == null

    suspend fun resetToSeed(today: LocalDate) {
        val (start, end) = DemoSeed.yesterdayTrip(today, zone)
        val trip = TripEntity(
            startTimeMs = start,
            endTimeMs = end,
            distanceKm = DemoSeed.YESTERDAY_TRIP_KM + 0.4,
            safeKm = DemoSeed.YESTERDAY_TRIP_KM,
            excludedSegments = 0,
            eventCount = 0,
            xpEarned = DemoSeed.YESTERDAY_TRIP_XP,
            levelBefore = DemoSeed.yesterdayLevelBefore,
            levelAfter = LevelTable.levelForXp(DemoSeed.profile(today).totalXp),
            achievementIds = "",
        )
        dao.replaceAll(
            ProfileEntity.of(DemoSeed.profile(today)),
            listOf(trip),
            DemoSeed.unlocked.map { AchievementEntity(it, end) },
        )
    }

    suspend fun resetEmpty() = dao.replaceAll(ProfileEntity.of(ProfileState()), emptyList(), emptyList())
}
