package com.mujimujimuji.odo.platform.fake

import com.mujimujimuji.odo.data.AchievementEntity
import com.mujimujimuji.odo.data.CosmeticEntity
import com.mujimujimuji.odo.data.LoadoutSlotEntity
import com.mujimujimuji.odo.data.OdoDao
import com.mujimujimuji.odo.data.OdoRepository
import com.mujimujimuji.odo.data.PhoneLinkEntity
import com.mujimujimuji.odo.data.ProfileEntity
import com.mujimujimuji.odo.data.TripEntity
import com.mujimujimuji.odo.platform.PhoneLinkState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 가상 휴대폰 연결. "확인했어요"가 뜬 뒤에는 화면을 떠나도 연결이 저장돼야 한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class FakePhoneLinkTest {
    /** 휴대폰 연결 줄만 메모리에 둔다. 나머지 DAO 메서드는 이 테스트에서 쓰지 않는다. */
    private class PhoneOnlyDao : OdoDao {
        val phone = MutableStateFlow<PhoneLinkEntity?>(null)
        override fun phoneLinkFlow(): Flow<PhoneLinkEntity?> = phone
        override suspend fun savePhoneLink(e: PhoneLinkEntity) { phone.value = e }
        override suspend fun clearPhoneLink() { phone.value = null }

        override suspend fun profile(): ProfileEntity? = null
        override fun profileFlow(): Flow<ProfileEntity?> = flowOf(null)
        override suspend fun saveProfile(p: ProfileEntity) = Unit
        override suspend fun insertTrip(t: TripEntity): Long = 0L
        override fun safeKmSince(sinceMs: Long): Flow<Int> = flowOf(0)
        override fun safeKmBetween(fromMs: Long, toMs: Long): Flow<Int> = flowOf(0)
        override suspend fun unlock(a: AchievementEntity) = Unit
        override suspend fun achievements(): List<AchievementEntity> = emptyList()
        override fun achievementsFlow(): Flow<List<AchievementEntity>> = flowOf(emptyList())
        override suspend fun setCosmetic(c: CosmeticEntity) = Unit
        override fun cosmetic(slot: String): Flow<String?> = flowOf(null)
        override fun loadoutSlotsFlow(vehicle: String): Flow<List<LoadoutSlotEntity>> = flowOf(emptyList())
        override suspend fun setLoadoutSlot(e: LoadoutSlotEntity) = Unit
        override suspend fun clearProfile() = Unit
        override suspend fun clearTrips() = Unit
        override suspend fun clearAchievements() = Unit
        override suspend fun clearCosmetics() = Unit
        override suspend fun clearLoadout() = Unit
    }

    private val confirmDelayMs = 3_000L

    private fun TestScope.link(dao: OdoDao) = FakePhoneLink(
        scope = backgroundScope,
        repo = OdoRepository(dao, ZoneId.of("Asia/Seoul")),
        clock = { testScheduler.currentTime },
        confirmDelayMs = confirmDelayMs,
    )

    @Test
    fun `확인했어요가 뜬 뒤 화면을 떠나도 연결을 저장한다`() = runTest {
        val dao = PhoneOnlyDao()
        val link = link(dao)
        link.startPairing()
        advanceTimeBy(confirmDelayMs)
        runCurrent()
        assertEquals(PhoneLinkState.Confirmed, link.state.value)

        link.cancelPairing() // 1.5초 안에 홈 키
        advanceTimeBy(FakePhoneLink.CONFIRMED_HOLD_MS)
        runCurrent()

        assertTrue("${link.state.value}", link.state.value is PhoneLinkState.Linked)
        assertEquals(true, dao.phone.value?.linked)
    }

    @Test
    fun `코드를 보여 주는 중에 떠나면 연결하지 않는다`() = runTest {
        val dao = PhoneOnlyDao()
        val link = link(dao)
        link.startPairing()
        runCurrent()
        assertTrue(link.state.value is PhoneLinkState.Presenting)

        link.cancelPairing()
        assertEquals(PhoneLinkState.Idle, link.state.value)
        advanceTimeBy(confirmDelayMs + FakePhoneLink.CONFIRMED_HOLD_MS + 1_000L)
        runCurrent()

        assertEquals(PhoneLinkState.Idle, link.state.value)
        assertNull(dao.phone.value)
    }

    @Test
    fun `확인했어요 중에 연결을 끊으면 저장하지 않는다`() = runTest {
        val dao = PhoneOnlyDao()
        val link = link(dao)
        link.startPairing()
        advanceTimeBy(confirmDelayMs)
        runCurrent()
        assertEquals(PhoneLinkState.Confirmed, link.state.value)

        link.disconnect()
        advanceTimeBy(FakePhoneLink.CONFIRMED_HOLD_MS + 1_000L)
        runCurrent()

        assertEquals(PhoneLinkState.Idle, link.state.value)
        assertNull(dao.phone.value)
    }
}
