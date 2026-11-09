package com.mujimujimuji.odo.engine

sealed interface AchievementRule {
    /** 지오펜스 중 하나에 들어가 기어 P로 10분 이상 머물면 해금. */
    data class Place(val fences: List<Geofence>) : AchievementRule

    /** 한 트립 안에서 게이트를 순서대로 지나면 해금. */
    data class Road(val gates: List<Geofence>) : AchievementRule

    data class SafeDistance(val km: Int) : AchievementRule
    data class NightSafeDistance(val km: Int) : AchievementRule
    data class RainSafeDistance(val km: Int) : AchievementRule

    /** 위험 이벤트 0개로 한 트립에서 안전 km를 채우면 해금. */
    data class CleanTrip(val km: Int) : AchievementRule
    data class TripCount(val count: Int) : AchievementRule
    data class StreakDays(val days: Int) : AchievementRule
    data class Level(val level: Int) : AchievementRule
}

/** [description]은 딴 뒤에 보이는 한 줄(과거형), [condition]은 잠겨 있을 때 보이는 조건 한 줄(비면 진행도만). 판정에는 쓰지 않는다. */
data class AchievementDef(
    val id: String,
    val title: String,
    val description: String,
    val rule: AchievementRule,
    val condition: String = "",
)

/** 누적 업적 판정에 쓰는 프로필 통계. */
data class AchievementStats(
    val totalSafeKm: Int,
    val nightSafeKm: Int,
    val rainSafeKm: Int,
    val tripCount: Int,
    val streakDays: Int,
    val level: Int,
)

/** 글랜스 화면의 "다음 업적까지" 게이지. */
data class Goal(val achievement: AchievementDef, val progress: Float)

/**
 * 업적 판정(기획서 업적 표). 위치 판정은 모두 기기 안에서 한다.
 * 홈존 반경 1 km 안에 걸친 장소 업적은 판정하지 않는다(1차는 홈존 학습이 없어 빈 목록).
 */
class AchievementEngine(
    catalog: List<AchievementDef>,
    unlocked: Set<String>,
    homeZones: List<LatLng> = emptyList(),
) {
    private val catalog = catalog.filterNot { def ->
        val rule = def.rule
        rule is AchievementRule.Place && rule.fences.any { fence ->
            homeZones.any { home ->
                Geo.distanceMeters(home, fence.center) < Thresholds.HOME_ZONE_EXCLUDE_METERS + fence.radiusMeters
            }
        }
    }
    private val unlocked = unlocked.toMutableSet()
    private val dwellStartMs = mutableMapOf<String, Long>()
    private val roadProgress = mutableMapOf<String, Int>()
    private var lastLocation: LatLng? = null

    val unlockedIds: Set<String> get() = unlocked

    fun onTripStart() {
        roadProgress.clear()
    }

    /** 위치·기어 샘플마다 부른다. 장소 업적은 트립이 끝난 뒤 정차 중에도 판정한다. */
    fun onSample(s: VehicleSnapshot, inTrip: Boolean): List<AchievementDef> {
        val loc = s.location ?: return emptyList()
        lastLocation = loc
        val newly = mutableListOf<AchievementDef>()
        for (def in locked()) {
            when (val rule = def.rule) {
                is AchievementRule.Place -> {
                    val parkedInside = s.gear == Gear.PARK && s.speedKmh == 0.0 &&
                        rule.fences.any { it.contains(loc) }
                    if (!parkedInside) {
                        dwellStartMs.remove(def.id)
                    } else {
                        val since = dwellStartMs.getOrPut(def.id) { s.timeMs }
                        if (s.timeMs - since >= Thresholds.PLACE_DWELL_MS) newly += def
                    }
                }
                is AchievementRule.Road -> if (inTrip) {
                    val next = roadProgress[def.id] ?: 0
                    if (rule.gates[next].contains(loc)) {
                        roadProgress[def.id] = next + 1
                        if (next + 1 == rule.gates.size) newly += def
                    }
                }
                else -> Unit
            }
        }
        return unlock(newly)
    }

    fun onStats(stats: AchievementStats): List<AchievementDef> = unlock(
        locked().filter { def ->
            when (val rule = def.rule) {
                is AchievementRule.SafeDistance -> stats.totalSafeKm >= rule.km
                is AchievementRule.NightSafeDistance -> stats.nightSafeKm >= rule.km
                is AchievementRule.RainSafeDistance -> stats.rainSafeKm >= rule.km
                is AchievementRule.TripCount -> stats.tripCount >= rule.count
                is AchievementRule.StreakDays -> stats.streakDays >= rule.days
                is AchievementRule.Level -> stats.level >= rule.level
                else -> false
            }
        },
    )

    fun onTripEnd(tripSafeKm: Int, tripEventCount: Int): List<AchievementDef> = unlock(
        locked().filter { def ->
            val rule = def.rule
            rule is AchievementRule.CleanTrip && tripEventCount == 0 && tripSafeKm >= rule.km
        },
    )

    /**
     * 다음 업적까지 진행률. 이번 트립에서 시작 게이트를 지난 도로 업적이 있으면 그 진행률(끝 게이트까지 남은 직선거리),
     * 없으면 가장 가까운 누적 안전 거리 업적.
     */
    fun nextGoal(stats: AchievementStats): Goal? {
        val loc = lastLocation
        if (loc != null) {
            for (def in locked()) {
                val rule = def.rule as? AchievementRule.Road ?: continue
                if ((roadProgress[def.id] ?: 0) == 0) continue
                val start = rule.gates.first().center
                val end = rule.gates.last().center
                val total = Geo.distanceMeters(start, end)
                if (total <= 0.0) continue
                val progress = 1.0 - Geo.distanceMeters(loc, end) / total
                return Goal(def, progress.coerceIn(0.0, 1.0).toFloat())
            }
        }
        return locked()
            .mapNotNull { def -> (def.rule as? AchievementRule.SafeDistance)?.let { def to it.km } }
            .minByOrNull { it.second }
            ?.let { (def, km) -> Goal(def, (stats.totalSafeKm.toFloat() / km).coerceIn(0f, 1f)) }
    }

    private fun locked() = catalog.filter { it.id !in unlocked }

    private fun unlock(defs: List<AchievementDef>): List<AchievementDef> {
        defs.forEach {
            unlocked += it.id
            dwellStartMs.remove(it.id)
        }
        return defs
    }
}
