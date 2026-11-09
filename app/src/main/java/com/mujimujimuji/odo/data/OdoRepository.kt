package com.mujimujimuji.odo.data

import com.mujimujimuji.odo.cosmetic.Loadout
import com.mujimujimuji.odo.cosmetic.Slot
import com.mujimujimuji.odo.cosmetic.VehicleType
import com.mujimujimuji.odo.engine.LevelTable
import com.mujimujimuji.odo.engine.ProfileState
import com.mujimujimuji.odo.engine.TripSummary
import com.mujimujimuji.odo.sim.DemoSeed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

/** 폰 연결(가상) 상태. label은 사용자가 고른 별칭 정도만, 실제 기기 식별자·연락처는 넣지 않는다. */
data class PhoneLinkRecord(val linked: Boolean = false, val linkedAtMs: Long? = null, val label: String? = null)

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

    /** "어제 안전 km": 어제 0시부터 오늘 0시 전까지 끝난 트립의 합(홈 §5.1). */
    fun yesterdaySafeKm(today: LocalDate): Flow<Int> =
        dao.safeKmBetween(
            today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            today.atStartOfDay(zone).toInstant().toEpochMilli(),
        )

    fun cosmetic(slot: String): Flow<String?> = dao.cosmetic(slot)
    suspend fun setCosmetic(slot: String, itemId: String) = dao.setCosmetic(CosmeticEntity(slot, itemId))

    /**
     * 선택 차종의 꾸미기 전체(설계서 §3.5). 차종은 cosmetic("vehicle"), 슬롯은 loadout_slot에서 읽는다.
     * 차종별 색이 아직 없으면 구 cosmetic("color") 값을 쓴다(v1 사용자의 색 선택 유지).
     * 잠긴 항목은 담지 않고 그대로 두므로 화면에서는 loadout.resolve(level)로 걸러 쓴다.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val loadoutFlow: Flow<Loadout> = dao.cosmetic(Slot.VEHICLE.id).flatMapLatest { vid ->
        val vehicle = VehicleType.byId(vid)
        combine(dao.loadoutSlotsFlow(vehicle.id), dao.cosmetic(Slot.COLOR.id)) { slots, legacyColor ->
            var l = Loadout(vehicle = vehicle, colorId = legacyColor ?: Loadout.default.colorId)
            for (s in slots) {
                val slot = Slot.byId(s.slot) ?: continue
                if (slot != Slot.VEHICLE) l = l.withItem(slot, s.itemId)
            }
            l
        }
    }

    suspend fun setVehicle(type: VehicleType) = dao.setCosmetic(CosmeticEntity(Slot.VEHICLE.id, type.id))

    /** 차종별 슬롯 저장. 색은 구 스키마의 cosmetic("color")에도 비춰 둔다(1차 데이터·테스트 호환, 화면은 loadoutFlow만 본다). */
    suspend fun setSlot(vehicle: VehicleType, slot: Slot, itemId: String) {
        if (slot == Slot.VEHICLE) return setVehicle(VehicleType.byId(itemId))
        dao.setLoadoutSlot(LoadoutSlotEntity(vehicle.id, slot.id, itemId))
        if (slot == Slot.COLOR) dao.setCosmetic(CosmeticEntity(Slot.COLOR.id, itemId))
    }

    val phoneLinkFlow: Flow<PhoneLinkRecord> =
        dao.phoneLinkFlow().map { e -> e?.let { PhoneLinkRecord(it.linked, it.linkedAtMs, it.label) } ?: PhoneLinkRecord() }

    suspend fun savePhoneLink(linked: Boolean, linkedAtMs: Long?, label: String?) =
        dao.savePhoneLink(PhoneLinkEntity(linked = linked, linkedAtMs = linkedAtMs, label = label))

    suspend fun clearPhoneLink() = dao.clearPhoneLink()

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
