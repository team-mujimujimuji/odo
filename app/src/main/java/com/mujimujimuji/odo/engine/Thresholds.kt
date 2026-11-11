package com.mujimujimuji.odo.engine

/**
 * 판정에 쓰는 모든 임계값. 기획서에 있는 숫자는 기획서와 같아야 하며, 바꾸기 전에 팀과 협의한다.
 * 위험 이벤트 기준은 한국교통안전공단 운행기록분석시스템 위험운전행동 정의에서 시작했다.
 */
object Thresholds {
    // 주행 상태 머신
    const val STATE_DEBOUNCE_MS = 2_000L

    // 1 km 구간과 XP
    const val SEGMENT_METERS = 1_000.0
    const val SAFE_SEGMENT_XP = 10
    const val DAILY_XP_CAP = 300

    /** 속도 적분으로 거리를 셀 때 이보다 긴 샘플 간격은 이 값까지만 센다(신호 끊김으로 거리가 튀지 않게). 구현 상수(기획서 숫자 아님). */
    const val MAX_DISTANCE_SAMPLE_GAP_MS = 10_000L

    /**
     * 주행거리 값이 샘플 하나에 이보다 많이 늘면 언제든 믿지 않고 속도로 센 뒤 그 값을 새 기준으로 삼는다
     * (계기 초기화처럼 크게 뛴 값). 위상이 맞은 뒤 직전 눈금 뒤 속도로 센 거리가 1 km보다 짧으면
     * [ODOMETER_STEP_SLACK_M]으로 더 좁게 본다. 구현 상수(기획서 숫자 아님).
     */
    const val MAX_ODOMETER_STEP_KM = 2.0

    /**
     * 위상이 맞은 주행거리가 샘플 하나에 "직전 눈금 뒤 속도로 센 거리 + 이 값(m)"보다 많이 늘면 튄 값으로 보고,
     * 그 샘플은 속도로 센 뒤 그 값을 새 기준으로 삼는다(한 프레임만 튀는 값으로 가짜 구간이 닫히지 않게).
     * 1,000 m(눈금 한 칸)인 이유: 1 km 눈금 계기의 정상 눈금과, 차속이 잠시 멈춰 속도 거리가 늘지 않은 동안 온 눈금도 믿는다.
     * 비율처럼 더 좁게 잡으면 그런 정상 눈금의 거리를 잃는다. 대신 이 안쪽의 튐은 걸러지지 않는다(1 km 눈금 계기처럼
     * 직전 눈금 뒤 속도 거리가 1 km 가까이 쌓이면 2 km 가까운 튐도 통과한다). 튄 값이 다음 눈금 전에 돌아오면 최대 1 km,
     * 다음 눈금까지 남으면(보정 등) 최대 [MAX_ODOMETER_STEP_KM](2 km)가 더해지고 되돌리지 않아 가짜 구간이 최대 2개 닫힐 수 있다.
     * 구현 상수(기획서 숫자 아님).
     */
    const val ODOMETER_STEP_SLACK_M = 1_000.0

    // 위험 이벤트 (속도 변화량은 초당 km/h)
    const val HARSH_ACCEL_KMH_PER_S = 11.0
    const val HARSH_DECEL_KMH_PER_S = 7.5
    const val SPEEDING_MARGIN_KMH = 20
    const val SEATBELT_MIN_SPEED_KMH = 10.0

    /** 샘플 간격이 이보다 길면 가속도 판정을 건너뛴다(신호 끊김 뒤 첫 값으로 오판하지 않게). 구현 상수(기획서 숫자 아님). */
    const val MAX_ACCEL_SAMPLE_GAP_MS = 3_000L

    /**
     * 급가속·급감속을 보는 시간 창. 기준이 "초당 km/h"라서 1초다(기획서 숫자가 아니라 구현 상수).
     * 차속이 초당 10번 들어와도 이웃 샘플이 아니라 1초 전 속도와 비교한다.
     */
    const val ACCEL_WINDOW_MS = 1_000L

    /**
     * 1초 틱이나 기어 변경이 마지막 차속을 다시 실은 스냅샷만 본다: 그 차속이 이보다 오래전에 얻은 값이면 "그때부터 지금까지
     * 같은 속도"라는 확인으로 보고 스냅샷 시각에 둔다. 이보다 최근이면 차속을 얻은 시각에 둔다. 새로 받은 차속은 늦게 왔어도
     * 늘 차량이 만든 시각에 둔다. 값이 바뀔 때만 오는 차속에서도 정속 뒤 1초 급제동을 잡게 하는 값이다. 구현 상수(기획서 숫자 아님).
     */
    const val SPEED_HOLD_CONFIRM_MS = 500L

    /**
     * 1초 전 시각을 사이에 둔 두 차속 샘플이 이보다 가파르게(초당 km/h) 바뀌었으면 계단으로 보고 그 사이를 직선으로 잇지 않는다.
     * 계단처럼 바뀌는 차속에서 1초 넘게 떨어진 두 계단을 한 창에 합쳐 완만한 가감속을 거는 일을 막는다.
     * 기준(11·7.5) 근처의 매끄러운 가감속보다 충분히 가팔라야 한다. 구현 상수(기획서 숫자 아님).
     */
    const val SPEED_STEP_KMH_PER_S = 20.0

    /**
     * 차량(VHAL)이 차속을 만든 시각을 믿는 최대 나이. 받은 순간 이보다 오래됐거나 미래 시각이면 시계 기준이 어긋난 것으로 보고
     * 받은 시각을 쓴다. 구현 상수(기획서 숫자 아님).
     */
    const val MAX_SPEED_SAMPLE_AGE_MS = 1_000L

    // 보너스 배수 (신호가 없으면 생략)
    const val NIGHT_START_HOUR = 22
    const val NIGHT_END_HOUR = 6
    const val NIGHT_MULTIPLIER = 1.5
    const val WIPER_MULTIPLIER = 1.5
    const val ALL_BELTS_MULTIPLIER = 1.1
    const val TURN_SIGNAL_MULTIPLIER = 1.1
    const val TURN_SIGNAL_RATE_MIN = 0.9

    // 연속 기록
    const val STREAK_MIN_SAFE_KM = 1.0
    const val STREAK_7_DAYS = 7
    const val STREAK_7_BONUS_XP = 50
    const val STREAK_30_DAYS = 30
    const val STREAK_30_BONUS_XP = 300

    // 업적
    const val PLACE_DWELL_MS = 10 * 60 * 1_000L
    const val HOME_ZONE_EXCLUDE_METERS = 1_000.0

    // TTS: 주행 중 10분에 2회
    const val TTS_DRIVING_WINDOW_MS = 10 * 60 * 1_000L
    const val TTS_DRIVING_MAX_IN_WINDOW = 2

    // 세이프티 스코어: 최근 30일, 100 km당 이벤트 1건마다 10점. 30일은 기획서 숫자이고, 1건마다 10점은 구현 상수(기획서 숫자 아님).
    const val SAFETY_SCORE_DAYS = 30
    const val SAFETY_SCORE_POINTS_PER_EVENT_PER_100KM = 10.0

    // 로드 레이더 (1차는 모의 데이터)
    const val RADAR_RADIUS_METERS = 2_000.0
    const val RADAR_GRID_METERS = 100.0
}
