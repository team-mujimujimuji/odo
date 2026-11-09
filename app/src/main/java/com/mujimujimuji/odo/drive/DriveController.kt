package com.mujimujimuji.odo.drive

import com.mujimujimuji.odo.data.OdoRepository
import com.mujimujimuji.odo.engine.AchievementDef
import com.mujimujimuji.odo.engine.DriveState
import com.mujimujimuji.odo.engine.DriveStateMachine
import com.mujimujimuji.odo.engine.EngineEvent
import com.mujimujimuji.odo.engine.Goal
import com.mujimujimuji.odo.engine.LatLng
import com.mujimujimuji.odo.engine.ProfileState
import com.mujimujimuji.odo.engine.TripSession
import com.mujimujimuji.odo.engine.TripSummary
import com.mujimujimuji.odo.engine.VehicleSnapshot
import com.mujimujimuji.odo.engine.VoicePolicy
import com.mujimujimuji.odo.platform.DrivingRestriction
import com.mujimujimuji.odo.platform.SignalSource
import com.mujimujimuji.odo.platform.Voice
import com.mujimujimuji.odo.sim.DemoSeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId

/** 화면이 보는 상태 하나. 주행 중 화면은 [locked]만 보고 글랜스로 바뀐다. */
data class DriveUi(
    val ready: Boolean = false,
    val simulated: Boolean = false,
    /** 기어 D/R 또는 속도 > 0, 또는 시스템 운전 제한. 즉시 반영(디바운스 없음). */
    val locked: Boolean = false,
    val tripActive: Boolean = false,
    val todaySafeKm: Int = 0,
    /** 엔진의 다음 목표 그대로(홈 게이지·캡션용). */
    val goal: Goal? = null,
    /** 글랜스 게이지: 주행 중에는 [GlanceGaugeLatch]로 뒤로 가지 않게 잡은 값, 정차 중에는 [goal] 그대로. */
    val glance: GlanceGauge = GlanceGauge(),
    val profile: ProfileState = ProfileState(),
    val summary: TripSummary? = null,
    /** 정차 중 새로 연 업적(트립 요약 밖). 정차 화면에서만 보인다. */
    val notices: List<AchievementDef> = emptyList(),
    val demoPlaying: Boolean = false,
    /** 지도 장면(설계서 §4.4): 마지막 위치. null 이면 지도는 계기 모드. */
    val position: LatLng? = null,
    /** 저역통과(α 0.3)한 진행방향(북 0°, 시계방향). 3 m 이상 움직인 뒤부터 값이 생긴다. */
    val headingDeg: Float? = null,
    val speedKmh: Float = 0f,
    /** 이번 트립의 지난 길(≈50 m 간격). 트립 시작에 비우고, 요약 화면이 bbox 맞춤에 쓴다. */
    val tripPath: List<LatLng> = emptyList(),
)

/**
 * 신호 → 엔진 → 저장·음성·화면 상태를 잇는다. 엔진 상태는 이 클래스만 바꾼다.
 * 주행 중 음성은 [VoicePolicy](10분 2회)를 지나야 나간다.
 */
class DriveController(
    private val scope: CoroutineScope,
    private val repo: OdoRepository,
    private val catalog: List<AchievementDef>,
    private val voice: Voice,
    private val zone: ZoneId,
    private val fake: Boolean,
    private val demoSource: (startTimeMs: Long) -> SignalSource,
    private val liveSource: () -> SignalSource,
    restriction: DrivingRestriction?,
) {
    private val _ui = MutableStateFlow(DriveUi(simulated = fake))
    val ui: StateFlow<DriveUi> = _ui.asStateFlow()

    private val lock = Mutex()
    /**
     * 데모는 매번 같은 시각(오늘 10:00 + t)부터 재생된다. 그래서 재생·초기화 때 새로 만든다.
     * 그러지 않으면 앞 재생의 주행 중 발화가 10분 창에 남아 같은 날 세 번째 재생부터 상한 문장이 빠진다.
     * 실주행(startLive)은 한 번만 만들어 규칙(10분 2회)을 그대로 지킨다.
     */
    private var voicePolicy = VoicePolicy()
    private var session: TripSession? = null
    private var savedProfile: ProfileState? = null
    private var systemRestricted = false
    private var moving = false
    private var sourceJob: Job? = null
    private val tracker = HeadingTracker()
    private val glanceLatch = GlanceGaugeLatch()
    private var lastSpeedKmh = 0f

    init {
        scope.launch {
            lock.withLock {
                if (repo.isEmpty()) {
                    if (fake) repo.resetToSeed(today()) else repo.saveProfile(ProfileState())
                }
                newSession()
            }
        }
        if (restriction != null) {
            scope.launch {
                restriction.restricted.collect { r ->
                    systemRestricted = r
                    _ui.update { it.copy(locked = moving || systemRestricted) }
                }
            }
        }
    }

    /** fake 빌드: 강릉 드라이브 시나리오를 처음부터 재생한다. */
    fun startDemo() {
        if (!fake || _ui.value.demoPlaying) return
        sourceJob?.cancel()
        _ui.update { it.copy(demoPlaying = true, summary = null, notices = emptyList()) }
        sourceJob = scope.launch {
            lock.withLock {
                voicePolicy = VoicePolicy()
                newSession()
            }
            try {
                demoSource(DemoSeed.scenarioStart(today(), zone)).snapshots.collect(::onSnapshot)
            } finally {
                _ui.update { it.copy(demoPlaying = false) }
            }
        }
    }

    /** 실차·에뮬레이터 SDK 신호 구독. 권한을 받은 뒤 한 번 부른다. */
    fun startLive() {
        if (fake || sourceJob != null) return
        sourceJob = scope.launch { liveSource().snapshots.collect(::onSnapshot) }
    }

    fun resetDemo() {
        if (!fake) return
        sourceJob?.cancel()
        sourceJob = null
        scope.launch {
            lock.withLock {
                repo.resetToSeed(today())
                moving = false
                tracker.reset()
                lastSpeedKmh = 0f
                voicePolicy = VoicePolicy()
                _ui.update { DriveUi(simulated = fake, locked = systemRestricted) }
                newSession()
            }
        }
    }

    fun closeSummary() = _ui.update { it.copy(summary = null) }

    fun dismissNotices() = _ui.update { it.copy(notices = emptyList()) }

    private suspend fun newSession() {
        val profile = repo.profile()
        savedProfile = profile
        session = TripSession(catalog, repo.unlockedIds(), profile, zone)
        glanceLatch.reset()
        publish()
    }

    private suspend fun onSnapshot(s: VehicleSnapshot) = lock.withLock {
        val session = session ?: return@withLock
        moving = DriveStateMachine.isMoving(s.gear, s.speedKmh)
        val events = session.onSample(s)
        tracker.update(s)
        lastSpeedKmh = s.speedKmh.toFloat()
        val driving = moving || session.state == DriveState.DRIVING
        for (e in events) {
            when (e) {
                is EngineEvent.TripStarted -> {
                    // 지난 길은 트립 단위. 시작 샘플부터 다시 쌓는다.
                    tracker.reset()
                    tracker.update(s)
                    _ui.update { it.copy(summary = null, notices = emptyList()) }
                }
                is EngineEvent.DailyCapReached ->
                    say(e.timeMs, driving, "안전 주행 ${e.todaySafeKm} km, 오늘 상한을 채웠어요.")
                is EngineEvent.AchievementUnlocked -> {
                    repo.unlock(e.achievement.id, e.timeMs)
                    glanceLatch.onUnlocked(e.achievement.id)
                    // 트립 중 연 업적은 요약에서 보여 준다. 정차 중(장소 체류)에 연 것만 바로 알린다.
                    if (!e.duringTrip) {
                        _ui.update { it.copy(notices = it.notices + e.achievement) }
                        say(e.timeMs, driving, "${e.achievement.title} 업적을 열었어요.")
                    }
                }
                is EngineEvent.TripEnded -> {
                    repo.saveTrip(e.summary)
                    _ui.update { it.copy(summary = e.summary) }
                }
                else -> Unit
            }
        }
        if (session.profile != savedProfile) {
            repo.saveProfile(session.profile)
            savedProfile = session.profile
        }
        publish()
    }

    private fun say(timeMs: Long, driving: Boolean, sentence: String) {
        if (voicePolicy.tryAcquire(timeMs, driving)) voice.speak(sentence)
    }

    private fun publish() {
        val s = session ?: return
        val p = s.profile
        val goal = s.nextGoal()
        val glance = glanceLatch.update(moving || s.state == DriveState.DRIVING, goal)
        _ui.update {
            it.copy(
                ready = true,
                locked = moving || systemRestricted,
                tripActive = s.state == DriveState.DRIVING,
                todaySafeKm = if (p.dayDate == today()) p.daySafeKm else 0,
                goal = goal,
                glance = glance,
                profile = p,
                position = tracker.position,
                headingDeg = tracker.headingDeg,
                speedKmh = lastSpeedKmh,
                tripPath = tracker.path,
            )
        }
    }

    private fun today(): LocalDate = LocalDate.now(zone)
}
