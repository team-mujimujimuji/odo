package com.mujimujimuji.odo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementEngineTest {

    private val restArea = LatLng(37.4630841, 128.1348680)
    private val gateA = LatLng(37.2400, 127.5800)
    private val gateB = LatLng(37.4500, 128.1200)

    private val place = AchievementDef("place", "장소", "", AchievementRule.Place(listOf(Geofence(restArea, 400.0))))
    private val road = AchievementDef(
        "road", "도로", "",
        AchievementRule.Road(listOf(Geofence(gateA, 300.0), Geofence(gateB, 300.0))),
    )
    private val km100 = AchievementDef("km100", "100 km", "", AchievementRule.SafeDistance(100))
    private val km300 = AchievementDef("km300", "300 km", "", AchievementRule.SafeDistance(300))
    private val clean = AchievementDef("clean", "무사고", "", AchievementRule.CleanTrip(30))

    private fun stats(safeKm: Int = 0) = AchievementStats(safeKm, 0, 0, 0, 0, 1)

    @Test
    fun `장소 업적은 기어 P로 10분 이상 머물러야 열린다`() {
        val e = AchievementEngine(listOf(place), emptySet())
        assertTrue(e.onSample(snap(0.0, 0.0, location = restArea), false).isEmpty())
        assertTrue(e.onSample(snap(599.0, 0.0, location = restArea), false).isEmpty())
        assertEquals(listOf(place), e.onSample(snap(600.0, 0.0, location = restArea), false))
        assertTrue(e.onSample(snap(1200.0, 0.0, location = restArea), false).isEmpty())
    }

    @Test
    fun `체류 중 기어를 바꾸면 처음부터 다시 잰다`() {
        val e = AchievementEngine(listOf(place), emptySet())
        e.onSample(snap(0.0, 0.0, location = restArea), false)
        e.onSample(snap(300.0, 0.0, gear = Gear.DRIVE, location = restArea), false)
        e.onSample(snap(301.0, 0.0, location = restArea), false)
        assertTrue(e.onSample(snap(800.0, 0.0, location = restArea), false).isEmpty())
        assertEquals(listOf(place), e.onSample(snap(901.0, 0.0, location = restArea), false))
    }

    @Test
    fun `지오펜스 밖 주차는 세지 않는다`() {
        val e = AchievementEngine(listOf(place), emptySet())
        val far = LatLng(37.47, 128.14)
        e.onSample(snap(0.0, 0.0, location = far), false)
        assertTrue(e.onSample(snap(900.0, 0.0, location = far), false).isEmpty())
    }

    @Test
    fun `홈존 1 km 안의 장소 업적은 판정하지 않는다`() {
        val home = LatLng(37.4680, 128.1348) // 약 550 m
        val e = AchievementEngine(listOf(place), emptySet(), homeZones = listOf(home))
        e.onSample(snap(0.0, 0.0, location = restArea), false)
        assertTrue(e.onSample(snap(900.0, 0.0, location = restArea), false).isEmpty())
    }

    @Test
    fun `홈존 제외 경계는 펜스 가장자리에서 1 km`() {
        // 펜스 반지름이 400 m라 중심에서 1,400 m가 경계다. 휴게소 정북 1,390 m와 1,410 m에 집을 둔다.
        val inside = LatLng(37.4755847, 128.1348680)
        val outside = LatLng(37.4757645, 128.1348680)
        assertEquals(1_390.0, Geo.distanceMeters(inside, restArea), 0.5)
        assertEquals(1_410.0, Geo.distanceMeters(outside, restArea), 0.5)

        val near = AchievementEngine(listOf(place), emptySet(), homeZones = listOf(inside))
        near.onSample(snap(0.0, 0.0, location = restArea), false)
        assertTrue(near.onSample(snap(900.0, 0.0, location = restArea), false).isEmpty())

        val far = AchievementEngine(listOf(place), emptySet(), homeZones = listOf(outside))
        far.onSample(snap(0.0, 0.0, location = restArea), false)
        assertTrue(far.onSample(snap(599.0, 0.0, location = restArea), false).isEmpty())
        assertEquals(listOf(place), far.onSample(snap(600.0, 0.0, location = restArea), false))
    }

    @Test
    fun `도로 업적은 게이트를 순서대로 지나야 열린다`() {
        val e = AchievementEngine(listOf(road), emptySet())
        e.onTripStart()
        assertTrue(e.onSample(snap(0.0, 100.0, location = gateB), true).isEmpty())
        assertTrue(e.onSample(snap(1.0, 100.0, location = gateA), true).isEmpty())
        assertEquals(listOf(road), e.onSample(snap(2.0, 100.0, location = gateB), true))
    }

    @Test
    fun `도로 진행은 트립마다 새로 시작한다`() {
        val e = AchievementEngine(listOf(road), emptySet())
        e.onTripStart()
        e.onSample(snap(0.0, 100.0, location = gateA), true)
        e.onTripStart()
        assertTrue(e.onSample(snap(1.0, 100.0, location = gateB), true).isEmpty())
    }

    @Test
    fun `트립이 끝나면 도로 진행을 지워 다음 업적이 누적 거리 업적으로 돌아온다`() {
        val e = AchievementEngine(listOf(road, km300), emptySet())
        e.onTripStart()
        e.onSample(snap(0.0, 100.0, location = gateA), true)
        assertEquals("road", e.nextGoal(stats(150))!!.achievement.id)

        e.onTripEnd(tripSafeKm = 5, tripEventCount = 0)
        assertEquals(Goal(km300, 0.5f), e.nextGoal(stats(150)))
        // 정차 중(트립 밖)에 끝 게이트 안에 있어도 진행하지 않는다.
        assertTrue(e.onSample(snap(1.0, 0.0, location = gateB), false).isEmpty())
        assertEquals(Goal(km300, 0.5f), e.nextGoal(stats(150)))
    }

    @Test
    fun `누적 거리와 무사고 트립 업적`() {
        val e = AchievementEngine(listOf(km100, clean), setOf())
        assertTrue(e.onStats(stats(99)).isEmpty())
        assertEquals(listOf(km100), e.onStats(stats(100)))
        assertTrue(e.onTripEnd(tripSafeKm = 40, tripEventCount = 1).isEmpty())
        assertEquals(listOf(clean), e.onTripEnd(tripSafeKm = 30, tripEventCount = 0))
    }

    @Test
    fun `다음 업적 게이지`() {
        val e = AchievementEngine(listOf(road, km100, km300), setOf("km100"))
        assertEquals(Goal(km300, 0.5f), e.nextGoal(stats(150)))

        e.onTripStart()
        e.onSample(snap(0.0, 100.0, location = gateA), true)
        val atStart = e.nextGoal(stats(150))!!
        assertEquals("road", atStart.achievement.id)
        assertEquals(0f, atStart.progress, 0.01f)

        val mid = LatLng((gateA.lat + gateB.lat) / 2, (gateA.lng + gateB.lng) / 2)
        e.onSample(snap(1.0, 100.0, location = mid), true)
        assertEquals(0.5f, e.nextGoal(stats(150))!!.progress, 0.02f)

        val done = AchievementEngine(listOf(km100), setOf("km100"))
        assertNull(done.nextGoal(stats(150)))
    }
}
