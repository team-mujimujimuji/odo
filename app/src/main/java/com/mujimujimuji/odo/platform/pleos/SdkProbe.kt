package com.mujimujimuji.odo.platform.pleos

import android.content.Context
import android.util.Log
import com.mujimujimuji.odo.platform.LiveSignalSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 실기·에뮬레이터에서 신호가 들어오는지 logcat(태그 OdoProbe)으로 확인한다. 위치는 소수 3자리까지만 남긴다. */
object SdkProbe {
    fun start(context: Context, scope: CoroutineScope): Job = scope.launch {
        val source = LiveSignalSource(
            vehicle = PleosVehicleSignals(context, CarSpeed(context)),
            location = PleosLocationSource(context),
            navi = PleosNaviBridge(context),
        )
        launch {
            val voice = PleosVoice(context)
            delay(4_000)
            voice.speak("오도 음성 확인입니다.")
        }
        launch {
            CarUxDrivingRestriction(context).restricted.collect { Log.i(TAG, "uxRestricted=$it") }
        }
        source.snapshots
            .map { it.copy(timeMs = 0) }
            .distinctUntilChanged()
            .collect { s ->
                Log.i(
                    TAG,
                    "gear=${s.gear} speed=${"%.1f".format(s.speedKmh)} " +
                        "loc=${s.location?.let { "%.3f,%.3f".format(it.lat, it.lng) }} belt=${s.driverBelt} " +
                        "turn=${s.turnSignal} wiper=${s.wiperOn} odo=${s.odometerKm} limit=${s.speedLimitKmh}",
                )
            }
    }

    private const val TAG = "OdoProbe"
}
