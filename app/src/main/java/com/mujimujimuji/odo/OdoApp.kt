package com.mujimujimuji.odo

import android.app.Application
import com.mujimujimuji.odo.data.AchievementCatalog
import com.mujimujimuji.odo.data.OdoDatabase
import com.mujimujimuji.odo.data.OdoRepository
import com.mujimujimuji.odo.drive.DriveController
import com.mujimujimuji.odo.engine.AchievementDef
import com.mujimujimuji.odo.platform.LiveSignalSource
import com.mujimujimuji.odo.platform.fake.ScenarioSignalSource
import com.mujimujimuji.odo.platform.pleos.CarSpeed
import com.mujimujimuji.odo.platform.pleos.CarUxDrivingRestriction
import com.mujimujimuji.odo.platform.pleos.PleosLocationSource
import com.mujimujimuji.odo.platform.pleos.PleosNaviBridge
import com.mujimujimuji.odo.platform.pleos.PleosVehicleSignals
import com.mujimujimuji.odo.platform.pleos.PleosVoice
import com.mujimujimuji.odo.sim.Scenario
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.ZoneId

/** 앱 전역 객체. 시나리오 재생이 화면 회전·재생성과 상관없이 이어지도록 컨트롤러를 여기 둔다. */
class OdoApp : Application() {
    lateinit var repo: OdoRepository
        private set
    lateinit var controller: DriveController
        private set
    lateinit var catalog: List<AchievementDef>
        private set

    override fun onCreate() {
        super.onCreate()
        val zone = ZoneId.systemDefault()
        // 신호 구독이 실패해도(권한 거절 등) 앱은 살아 있게 한다.
        val onError = CoroutineExceptionHandler { _, e -> Log.e(TAG, "background job failed", e) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + onError)
        repo = OdoRepository(OdoDatabase.create(this).dao(), zone)
        catalog = AchievementCatalog.parse(assets.open("achievements.json").bufferedReader().use { it.readText() })
        controller = DriveController(
            scope = scope,
            repo = repo,
            catalog = catalog,
            voice = PleosVoice(this),
            zone = zone,
            fake = BuildConfig.USE_FAKE_SIGNALS,
            demoSource = { start ->
                val json = assets.open("scenarios/gangneung_trip.json").bufferedReader().use { it.readText() }
                ScenarioSignalSource(Scenario.parse(json), start)
            },
            liveSource = {
                LiveSignalSource(
                    vehicle = PleosVehicleSignals(this, CarSpeed(this)),
                    location = PleosLocationSource(this),
                    navi = PleosNaviBridge(this),
                )
            },
            restriction = CarUxDrivingRestriction(this),
        )
    }

    private companion object {
        const val TAG = "Odo"
    }
}
