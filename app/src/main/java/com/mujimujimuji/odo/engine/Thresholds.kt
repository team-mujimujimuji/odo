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

    /** 속도 적분으로 거리를 셀 때 이보다 긴 샘플 간격은 이 값까지만 센다(신호 끊김으로 거리가 튀지 않게). */
    const val MAX_DISTANCE_SAMPLE_GAP_MS = 10_000L

    // 위험 이벤트 (속도 변화량은 초당 km/h)
    const val HARSH_ACCEL_KMH_PER_S = 11.0
    const val HARSH_DECEL_KMH_PER_S = 7.5
    const val SPEEDING_MARGIN_KMH = 20
    const val SEATBELT_MIN_SPEED_KMH = 10.0

    /** 샘플 간격이 이보다 길면 가속도 판정을 건너뛴다(신호 끊김 뒤 첫 값으로 오판하지 않게). */
    const val MAX_ACCEL_SAMPLE_GAP_MS = 3_000L

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

    // 세이프티 스코어: 최근 30일, 100 km당 이벤트 1건마다 10점
    const val SAFETY_SCORE_DAYS = 30
    const val SAFETY_SCORE_POINTS_PER_EVENT_PER_100KM = 10.0

    // 로드 레이더 (1차는 모의 데이터)
    const val RADAR_RADIUS_METERS = 2_000.0
    const val RADAR_GRID_METERS = 100.0
}
