# 오도 (ODO)

**안전하게 달린 거리와 내 차로 가 본 곳이 미니카의 성장으로 쌓이는 드라이버 RPG.**
Pleos Connect(Android Automotive OS) 차량 신호로 트립을 자동으로 시작·종료하고, 1 km 구간마다 안전 XP를 판정하며, 주행 중에는 앱 스스로 터치를 막는다.

현대자동차그룹 차량용 앱 개발 공모전(Code your App into Pleos App Market) 출품작 · 팀 무지무지무지

## APK

- 저장소 [`apk/무지무지무지_오도_release.apk`](apk/무지무지무지_오도_release.apk) (release 빌드, 서명됨, 약 41 MB)
- 이 APK는 **시나리오 재생 모드**(`USE_FAKE_SIGNALS=true`)로 빌드했다. 에뮬레이터에 차량 신호를 따로 넣지 않아도 시연 흐름 전체를 볼 수 있다.

## 실행 방법

### APK로 바로 실행

```bash
adb install -r -g apk/무지무지무지_오도_release.apk
adb shell am start -n com.mujimujimuji.odo/.MainActivity
```

`-g`는 차량 속도·Pleos 차량 신호·위치·TTS 권한을 설치 때 한 번에 준다. 앱 서랍의 **오도** 아이콘으로 실행해도 된다.

### 소스에서 빌드

```bash
./gradlew testDebugUnitTest          # engine 단위 테스트 (42개)
./gradlew assembleDebug              # app/build/outputs/apk/debug/
./gradlew assembleRelease            # app/build/outputs/apk/release/
```

- JDK 17, Android SDK 34가 필요하다. Pleos Connect SDK 저장소 설정은 [개발 환경 설정 문서](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)를 따른다.
- release 서명 정보는 저장소에 없다. 루트에 `keystore.properties`(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`)를 두면 그 키로 서명하고, 없으면 서명 없이 빌드된다.

## 테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect 에뮬레이터 `release_connect_v2.1.0` (Android 14, API 34, arm64-v8a) |
| 화면 | 1024×768, 160 dpi. 앱은 멀티윈도우 오른쪽 창에서 동작 확인 |
| 빌드 | AGP 8.7.3, Gradle 8.11.1, Kotlin 2.0.21, minSdk 28, targetSdk 34 |
| Pleos SDK | Vehicle 2.3.2, FusedLocation 1.1.3, NaviHelper 3.0.5, TextToSpeech 3.3.1, SpeechToText 3.3.1 |

### `USE_FAKE_SIGNALS` 전환

빌드 속성 `useFakeSignals`가 `BuildConfig.USE_FAKE_SIGNALS`로 들어간다. 기본값은 `true`다.

| 모드 | 빌드 명령 | 신호 출처 |
|---|---|---|
| 시나리오 재생 (기본, 제출 APK) | `./gradlew assembleRelease` | `assets/scenarios/gangneung_trip.json` |
| 실제 SDK | `./gradlew assembleRelease -PuseFakeSignals=false` | Vehicle(기어·벨트·지시등·와이퍼·주행거리), AAOS 속도, FusedLocation, CarUxRestrictions |

실제 SDK 모드에서 에뮬레이터에 신호를 넣는 방법:
- 기어·속도: 에뮬레이터 Virtual Console(또는 그 HTTP API `PUT /vehicle/gear?gear=Drive`, `PUT /vehicle/controls?speed=60`)
- 위치: `adb emu geo fix <경도> <위도>`

### 시나리오 재생 방법

1. 앱을 열면 데모 프로필(레벨 4, 이번 주 안전 42 km, 업적 3/12)이 들어 있다.
2. 홈의 **데모 주행 시작**을 누르면 여주 → 횡성휴게소 영동고속도로 주행(약 66 km, 실제 51분)을 20배속으로 재생한다. 휴게소 도착까지 약 2분 걸린다.
   - 기어 D가 되면 화면이 글랜스 모드로 바뀌고 터치가 막힌다.
   - 안전 30 km에서 TTS "안전 주행 30 km, 오늘 상한을 채웠어요."
   - 45 km 지점에서 급감속 1회. 주행 중 화면에는 아무 표시도 하지 않는다.
3. 휴게소에서 기어 P가 되면 트립 요약이 뜬다: 안전 65 km, +300 XP, 레벨 5, "영동고속도로 첫 주행", "1 km 구간 1개 제외".
4. 그대로 두면 휴게소 체류 10분(재생 기준 약 30초) 뒤 "강원도 첫 방문" 업적이 열리고 TTS가 나온다.
5. **홈으로** → **꾸미기**에서 레벨 5로 열린 색으로 미니카 색을 바꾼다.
6. **초기화**를 누르면 데모 프로필로 돌아가 처음부터 다시 볼 수 있다.

TTS가 나간 기록은 `adb logcat -s OdoVoice`로 볼 수 있다.

> 에뮬레이터 참고: 시스템 앱 `ai.umos.drivingview`가 반복해서 죽은 뒤 화면 입력을 가로채 터치가 안 먹는 경우가 있다. 그럴 때 `adb shell am force-stop --user 10 ai.umos.drivingview`를 실행하면 된다.

## 구조

```
app/src/main/java/com/mujimujimuji/odo/
  platform/   Pleos SDK 어댑터 (SDK import는 여기서만) · pleos/ 실제 구현 · fake/ 시나리오 재생
  engine/     순수 Kotlin 판정 로직: 주행 상태, 1 km 구간, 위험 이벤트, XP·상한·연속 기록, 레벨, 업적
  data/       Room (프로필, 트립, 업적)
  drive/      신호 → 엔진 → 저장·음성·화면 상태
  ui/         Compose 화면: home, glance, summary, profile
  sim/        데모 시드
tools/scenario/  시나리오 JSON 생성 스크립트
```

모든 임계값은 `engine/Thresholds.kt`, XP·레벨 표는 `engine/LevelTable.kt`에 있다.

## 안전·프라이버시 원칙

- 기어 D/R 또는 속도 > 0이면 앱의 모든 터치를 막는다. 이 잠금을 끄는 설정은 없다.
- 주행 중에는 숫자 1개, 게이지 1개, 레이더만 보인다. 위험 이벤트·감점 알림은 정차 후 트립 요약에서만 보여 준다.
- 주행 중 TTS는 10분에 2회, 1회 1문장.
- XP는 깎이지 않는다. 위험 이벤트가 있는 구간은 0 XP일 뿐이다.
- 차량 신호와 위치 기록은 기기 밖으로 보내지 않는다(1차 버전은 서버 통신이 없다).

## 오픈소스와 데이터 출처

| 이름 | 용도 | 라이선스 |
|---|---|---|
| [OpenStreetMap](https://www.openstreetmap.org/copyright) | 데모 경로와 업적 좌표 (© OpenStreetMap contributors). 자세한 내용은 [NOTICE.md](NOTICE.md) | ODbL 1.0 |
| [OSRM](https://project-osrm.org/) 공개 데모 서버 | 데모 경로 계산 (빌드 때만, 앱에 포함 안 됨) | BSD-2-Clause |
| AndroidX (Core, Lifecycle, Activity, Room) | 앱 기반, 로컬 저장 | Apache 2.0 |
| Jetpack Compose, Material 3 | UI | Apache 2.0 |
| Kotlin, kotlinx.coroutines | 언어, 비동기 처리 | Apache 2.0 |
| JUnit 4 | 단위 테스트 | EPL 1.0 |
| org.json | 단위 테스트에서 시나리오 JSON 읽기 | Public Domain |
| Pleos Connect SDK | 차량 신호, 위치, 내비, 음성 | Pleos 개발자 약관 |

이미지와 아이콘은 팀이 직접 만들었고, 외부 글꼴이나 음원은 쓰지 않는다.
