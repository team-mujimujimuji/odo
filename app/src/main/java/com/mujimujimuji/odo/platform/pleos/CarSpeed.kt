package com.mujimujimuji.odo.platform.pleos

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.mujimujimuji.odo.platform.SpeedSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs

/** Pleos SDK에 차속 API가 없어 AAOS 표준 차량 속성 PERF_VEHICLE_SPEED(m/s)를 쓴다. 권한: android.car.permission.CAR_SPEED */
class CarSpeed(context: Context) {
    /**
     * 차속 콜백을 받는 전용 스레드. Car를 Handler 없이 만들면 콜백이 메인 스레드로 와서, 화면을 그리느라 메인이 멈춘 동안
     * 온 차속이 한꺼번에 몰려 들어온다. 판정은 차량 시각으로 하지만 몰림 자체를 줄여 둔다.
     */
    private val thread = HandlerThread("OdoCarSpeed").apply { start() }

    private val car: Car? = runCatching { Car.createCar(context.applicationContext, Handler(thread.looper)) }
        .onFailure { Log.w(TAG, "Car.createCar failed", it) }
        .getOrNull()

    /**
     * 차속(km/h)과 차량이 그 값을 만든 시각. 앱이 받은 시각은 스레드 사정에 따라 수십~수백 ms 흔들려
     * 급가속·급감속 판정(1초 동안의 차)에 그대로 들어가므로, VHAL 시각(CarPropertyValue.getTimestamp, elapsedRealtimeNanos 기준)을 함께 싣는다.
     * 상태가 AVAILABLE이 아닌 값(신호 없음·오류)은 버린다. 그 값의 0을 실제 정차로 읽지 않게 하려는 것이다.
     */
    val samples: Flow<SpeedSample> = callbackFlow {
        val manager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        // 콜백은 [thread] 하나에서만 오므로 아래 진단 값은 잠금 없이 고친다.
        var badStatusLogged = 0
        var ageWindowStartMs = -1L
        var ageMaxMs = Long.MIN_VALUE
        var ageCount = 0
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (value.status != CarPropertyValue.STATUS_AVAILABLE) {
                    if (badStatusLogged++ < STATUS_LOG_COUNT) Log.w(TAG, "speed status ${value.status}: skipped")
                    return
                }
                val mps = (value.value as? Float) ?: return
                val madeMs = value.timestamp / 1_000_000
                // 진단용: 받은 순간의 나이(ms). 첫 값은 바로, 그 뒤로는 [AGE_LOG_INTERVAL_MS]마다 그동안의 최대 나이를 남긴다
                // (에뮬레이터·실차에서 VHAL 시각이 쓸 만한지, 주행 중 콜백이 얼마나 늦는지 확인).
                val nowMs = SystemClock.elapsedRealtime()
                val ageMs = nowMs - madeMs
                if (ageWindowStartMs < 0) {
                    Log.i(TAG, "speed age $ageMs ms (first)")
                    ageWindowStartMs = nowMs
                } else {
                    ageMaxMs = maxOf(ageMaxMs, ageMs)
                    ageCount++
                    if (nowMs - ageWindowStartMs >= AGE_LOG_INTERVAL_MS) {
                        Log.i(TAG, "speed age max $ageMaxMs ms over $ageCount samples")
                        ageWindowStartMs = nowMs
                        ageMaxMs = Long.MIN_VALUE
                        ageCount = 0
                    }
                }
                trySend(SpeedSample(abs(mps) * 3.6, madeMs))
            }

            override fun onErrorEvent(propertyId: Int, zone: Int) {
                Log.w(TAG, "speed error $propertyId")
            }
        }
        val ok = runCatching {
            manager?.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, SAMPLE_RATE_HZ) == true
        }.onFailure { Log.w(TAG, "speed register failed", it) }.getOrDefault(false)
        // 차속이 없으면 위험 판정을 할 수 없어 LiveSignalSource가 거리를 쌓지 않는다(트립 거리·XP 0).
        if (!ok) Log.e(TAG, "speed not available: trips record no distance or XP")
        awaitClose { runCatching { manager?.unregisterCallback(callback) } }
    }

    fun release() {
        runCatching { car?.disconnect() }
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "OdoSpeed"
        const val SAMPLE_RATE_HZ = 10f
        const val STATUS_LOG_COUNT = 3
        const val AGE_LOG_INTERVAL_MS = 10_000L
    }
}
