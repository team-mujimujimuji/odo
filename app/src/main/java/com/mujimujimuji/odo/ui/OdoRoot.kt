package com.mujimujimuji.odo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mujimujimuji.odo.OdoApp
import com.mujimujimuji.odo.R
import com.mujimujimuji.odo.cosmetic.CosmeticCatalog
import com.mujimujimuji.odo.cosmetic.Loadout
import com.mujimujimuji.odo.engine.AchievementStats
import com.mujimujimuji.odo.engine.LevelTable
import com.mujimujimuji.odo.engine.StreakRules
import com.mujimujimuji.odo.engine.Thresholds
import com.mujimujimuji.odo.engine.TripSummary
import com.mujimujimuji.odo.platform.PhoneLinkState
import com.mujimujimuji.odo.ui.components.OdoDurations
import com.mujimujimuji.odo.ui.components.OdoEasing
import com.mujimujimuji.odo.ui.components.rememberMotionScale
import com.mujimujimuji.odo.ui.components.scaledBy
import com.mujimujimuji.odo.ui.glance.GlanceScreen
import com.mujimujimuji.odo.ui.home.HomeScreen
import com.mujimujimuji.odo.ui.home.HomeStats
import com.mujimujimuji.odo.ui.map.rememberCorridorData
import com.mujimujimuji.odo.ui.phonelink.PhoneLinkScreen
import com.mujimujimuji.odo.ui.profile.AchievementsScreen
import com.mujimujimuji.odo.ui.profile.GarageScreen
import com.mujimujimuji.odo.ui.summary.SummaryScreen
import com.mujimujimuji.odo.ui.theme.LocalOdoColors
import com.mujimujimuji.odo.ui.theme.OdoTheme
import com.mujimujimuji.odo.ui.theme.rememberNightMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 정차 중 사용자가 고른 화면. */
private enum class Screen { HOME, ACHIEVEMENTS, GARAGE, PHONE }

/** 실제로 그리는 화면. 잠금(주행) → 글랜스, 트립 요약 있음 → 요약, 그 밖에는 [Screen]. */
private enum class Route { BLANK, GLANCE, SUMMARY, HOME, ACHIEVEMENTS, GARAGE, PHONE }

/**
 * 화면 고르기(설계서 §5.7)와 테마. 주행 잠금 ↔ 해제 전환은 0 ms(크로스페이드 없이 바로 바꾼다),
 * 정차 화면끼리는 200 ms 크로스페이드. 밤/낮은 잠금 중 바뀌지 않는다(rememberNightMode).
 * 잠금 중에는 DriveLockOverlay가 맨 위에서 모든 입력을 막는다. 휴대폰 연결 대기 중 주행이 시작되면 대기를 접고 홈으로 돌린다.
 */
@Composable
fun OdoRoot(app: OdoApp) {
    val ui by app.controller.ui.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val weekly by remember(today) { app.repo.weeklySafeKm(today) }.collectAsStateWithLifecycle(0)
    val yesterday by remember(today) { app.repo.yesterdaySafeKm(today) }.collectAsStateWithLifecycle(0)
    val unlocked by app.repo.unlockedFlow.collectAsStateWithLifecycle(emptySet())
    val rawLoadout by remember { app.repo.loadoutFlow }.collectAsStateWithLifecycle(Loadout.default)
    val phoneState by app.phoneLink.state.collectAsStateWithLifecycle()
    val data = rememberCorridorData()
    val scope = rememberCoroutineScope()

    val level = LevelTable.levelForXp(ui.profile.totalXp)
    // 잠긴 항목이 저장돼 있어도 보여 주는 로드아웃은 레벨에 맞게 기본값으로 내린다.
    val loadout = remember(rawLoadout, level) { rawLoadout.resolve(level) }
    // 구성 변경(분할 창 크기·글꼴 배율 등)으로 액티비티가 다시 만들어져도 보던 화면을 유지한다(휴대폰 화면이면 대기 코드도 접히지 않는다).
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    // 캡처용 강제 주야. 글꼴 배율 변경 등으로 액티비티가 다시 만들어져도 유지한다(주행 중 주야가 바뀌지 않게).
    var forcedNight by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var localNotice by remember { mutableStateOf<String?>(null) }
    var lastSummary by remember { mutableStateOf<TripSummary?>(null) }
    val night = rememberNightMode(ui.locked, forcedNight)
    val motion = rememberMotionScale()

    val summary = ui.summary
    // 요약이 닫혀도 크로스페이드가 끝날 때까지 마지막 요약을 그린다.
    LaunchedEffect(summary) { if (summary != null) lastSummary = summary }
    // 기어 P 직후: 잠금은 바로 풀리지만 엔진은 STATE_DEBOUNCE_MS 뒤에야 트립을 끝내고 요약을 낸다. 그 사이 홈이 잠깐 비쳐
    // 보상(요약 박자)이 먼저 새지 않게 글랜스 장면을 붙들어 둔다. 입력 잠금(DriveLockOverlay)은 ui.locked만 따른다.
    // 트립 중 기어 N 정차는 잠금이 유지되므로(DriveLock) 여기로 오지 않는다. 잠금이 풀린 채 트립이 남는 것은
    // 기어 P 직후와 기어 신호 없음(UNKNOWN) 정지뿐이다. 트립이 안 끝나면(UNKNOWN 정지) 디바운스 + 여유 뒤 홈으로 간다.
    // 붙들기 여부는 잠금이 풀린 그 구성에서 바로 정해야 한다. 이펙트 뒤에 켜면 한 프레임 홈으로 빠졌다 돌아오며
    // GlanceScreen이 새로 만들어지고(타일 캐시·미니카 플레이트가 비어 지도와 미니카가 잠깐 사라짐) 홈 장면을 헛되이 그린다.
    var holdExpired by remember { mutableStateOf(false) }
    LaunchedEffect(ui.locked, ui.tripActive) {
        holdExpired = false
        if (!ui.locked && ui.tripActive) {
            delay(Thresholds.STATE_DEBOUNCE_MS + GLANCE_HOLD_MARGIN_MS)
            holdExpired = true
        }
    }
    val holdGlance = !ui.locked && ui.tripActive && !holdExpired
    val route = when {
        !ui.ready -> Route.BLANK
        ui.locked || holdGlance -> Route.GLANCE
        summary != null -> Route.SUMMARY
        else -> when (screen) {
            Screen.HOME -> Route.HOME
            Screen.ACHIEVEMENTS -> Route.ACHIEVEMENTS
            Screen.GARAGE -> Route.GARAGE
            Screen.PHONE -> Route.PHONE
        }
    }

    val phoneAfterStop = stringResource(R.string.home_notice_phone_after_stop)
    val unlinked = stringResource(R.string.phone_unlinked_notice)
    LaunchedEffect(ui.locked) {
        if (ui.locked && screen == Screen.PHONE) {
            app.phoneLink.cancelPairing()
            screen = Screen.HOME
            // 대기가 실제로 접혔을 때(Idle)만 알린다. 이미 연결됨(Linked)이거나 폰이 확인을 마쳐 연결을 끝내는 중(Confirmed → Linked)이면
            // '다시 연결' 안내가 틀린 말이 된다. Compose 상태는 한 프레임 늦을 수 있어 플로 값을 바로 읽는다.
            if (app.phoneLink.state.value is PhoneLinkState.Idle) localNotice = phoneAfterStop
        }
    }
    // 요약의 '홈'과 뒤로: 요약을 닫고 홈으로 간다. 출발 전에 열어 둔 차고·업적으로 떨어지지 않게 screen도 홈으로 돌린다.
    val closeSummary = {
        app.controller.closeSummary()
        app.controller.dismissNotices()
        screen = Screen.HOME
    }
    // 정차 중 시스템 뒤로: 요약은 '홈'과 같이 닫고, 업적·차고·휴대폰은 홈으로 간다. 정차 직후 글랜스 붙들기 구간은 삼킨다.
    // 홈의 뒤로는 시스템 기본(앱이 뒤로 빠짐). 잠금 중에는 꺼 두고 DriveLockOverlay의 핸들러가 모두 삼킨다.
    BackHandler(enabled = !ui.locked && route != Route.HOME && route != Route.BLANK) {
        when (route) {
            Route.SUMMARY -> closeSummary()
            Route.GLANCE, Route.HOME, Route.BLANK -> Unit
            Route.ACHIEVEMENTS, Route.GARAGE, Route.PHONE -> screen = Screen.HOME
        }
    }
    // 폰 화면을 떠나면 대기 중인 코드를 접는다(연결됨은 그대로). 다시 들어오면 새 코드가 발급된다.
    LaunchedEffect(route) { if (route != Route.PHONE) app.phoneLink.cancelPairing() }
    // 알림 1줄은 홈에서 한 번만: 업적·차고·휴대폰으로 넘어가면 접어서 돌아왔을 때 다시 뜨지 않게 한다.
    // (잠금 때 남긴 "정차 후 다시 연결" 안내는 글랜스를 거쳐 홈에서 보여야 하므로 글랜스·요약 진입에서는 접지 않는다.)
    LaunchedEffect(route) {
        if (route == Route.ACHIEVEMENTS || route == Route.GARAGE || route == Route.PHONE) {
            localNotice = null
            app.controller.dismissNotices()
        }
    }

    val center = ui.position ?: if (ui.simulated) data?.routeStart else null
    val firstNotice = ui.notices.firstOrNull()
    val noticeText = localNotice ?: firstNotice?.let { stringResource(R.string.notice_achievement_unlocked, it.title) }
    val demoChipText = if (!ui.simulated) {
        null
    } else {
        stringResource(
            when {
                forcedNight == null -> R.string.demo_chip
                night -> R.string.demo_chip_night
                else -> R.string.demo_chip_day
            },
        )
    }
    val stats = HomeStats(
        weeklySafeKm = weekly,
        yesterdaySafeKm = yesterday,
        streakDays = StreakRules.currentDays(ui.profile.streak, today),
        unlockedCount = unlocked.size,
        achievementTotal = app.catalog.size,
        collected = CosmeticCatalog.collected(level),
        collectibleTotal = CosmeticCatalog.collectibleTotal,
        phoneLinked = phoneState is PhoneLinkState.Linked,
    )

    OdoTheme(night) {
        val c = LocalOdoColors.current
        Box(Modifier.fillMaxSize().background(c.ground)) {
            if (route == Route.GLANCE) {
                // 잠금 전환은 0 ms(§8): 크로스페이드를 거치지 않는다.
                GlanceScreen(ui, loadout)
            } else {
                Crossfade(
                    targetState = route,
                    animationSpec = tween(OdoDurations.SCREEN_MS.scaledBy(motion), easing = OdoEasing),
                    label = "route",
                ) { r ->
                    when (r) {
                        Route.BLANK, Route.GLANCE -> Unit
                        Route.SUMMARY -> {
                            val shown = summary ?: lastSummary
                            if (shown != null) {
                                SummaryScreen(
                                    summary = shown,
                                    later = ui.notices,
                                    loadout = loadout,
                                    data = data,
                                    path = ui.tripPath,
                                    totalXp = ui.profile.totalXp,
                                    onClose = closeSummary,
                                )
                            }
                        }
                        Route.ACHIEVEMENTS -> AchievementsScreen(
                            app.catalog,
                            unlocked,
                            AchievementStats(
                                totalSafeKm = ui.profile.totalSafeKm,
                                nightSafeKm = ui.profile.nightSafeKm,
                                rainSafeKm = ui.profile.rainSafeKm,
                                tripCount = ui.profile.tripCount,
                                streakDays = StreakRules.currentDays(ui.profile.streak, today),
                                level = level,
                            ),
                            data,
                            center,
                            onHome = { screen = Screen.HOME },
                        )
                        Route.GARAGE -> GarageScreen(
                            loadout = loadout,
                            level = level,
                            data = data,
                            center = center,
                            onSelect = { slot, id -> scope.launch { app.repo.setSlot(loadout.vehicle, slot, id) } },
                            onHome = { screen = Screen.HOME },
                        )
                        Route.PHONE -> PhoneLinkScreen(
                            state = phoneState,
                            loadout = loadout,
                            data = data,
                            center = center,
                            // 지금 화면이 폰일 때만 연결을 시작한다. 끊기·홈으로 나가는 크로스페이드 동안 나가는 폰 화면이
                            // Idle을 보고 다시 시작하면, 4.5 s 뒤 화면 밖에서 "연결됨"으로 바뀌던 버그가 있었다.
                            onStartPairing = { if (screen == Screen.PHONE) app.phoneLink.startPairing() },
                            onDisconnect = {
                                app.phoneLink.disconnect()
                                localNotice = unlinked
                                screen = Screen.HOME
                            },
                            onHome = { screen = Screen.HOME },
                        )
                        Route.HOME -> HomeScreen(
                            ui = ui,
                            loadout = loadout,
                            stats = stats,
                            data = data,
                            center = center,
                            demoChipText = demoChipText,
                            onDemoChipLongPress = if (ui.simulated) ({ forcedNight = !night }) else null,
                            onAchievements = { screen = Screen.ACHIEVEMENTS },
                            onGarage = { screen = Screen.GARAGE },
                            onPhone = { screen = Screen.PHONE },
                            onStartDemo = {
                                screen = Screen.HOME
                                app.controller.startDemo()
                            },
                            onResetDemo = {
                                forcedNight = null
                                // 폰이 확인을 마친 1.5 s(Confirmed)는 화면을 떠나도 접히지 않는다. 그 사이 초기화하면
                                // 남은 작업이 씨앗 위에 '연결됨'을 다시 쓰므로 연결 작업도 함께 접는다(ux-flow-4 검증자 (a)).
                                app.phoneLink.disconnect()
                                app.controller.resetDemo()
                            },
                            // 알림 1줄은 홈에서만(설계서 §5.1, 패널 위 4dp). 주행 중·요약·다른 패널에는 띄우지 않는다.
                            notice = noticeText,
                            onDismissNotice = { if (localNotice != null) localNotice = null else app.controller.dismissNotices() },
                        )
                    }
                }
            }
            if (ui.locked) DriveLockOverlay()
        }
    }
}

/** 기어 P 뒤 글랜스를 붙드는 여유(디바운스 2 s에 더함). 그 안에 요약이 안 오면 홈으로. */
private const val GLANCE_HOLD_MARGIN_MS = 1_000L
