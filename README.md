# 오도 (ODO)

**안전하게 달린 거리와 내 차로 가 본 곳이 미니카의 성장으로 쌓이는 드라이버 RPG.**
Pleos Connect(Android Automotive OS) 차량 신호로 트립을 자동으로 시작·종료하고, 1 km 구간마다 안전 XP를 판정하며, 주행 중에는 앱 스스로 터치를 막는다.

현대자동차그룹 차량용 앱 개발 공모전(Code your App into Pleos App Market) 출품작 · 팀 무지무지무지

## 화면

아래 APK를 Pleos Connect 에뮬레이터에 설치해 시나리오 재생으로 찍었다. 멀티윈도우 오른쪽 창(658×552 px)의 앱 화면만 잘랐고, 데모 프로필이라 개인정보는 없다.

| 홈 (정차) | 글랜스 (주행 중) |
|---|---|
| <img src="docs/screenshots/01_home.png" width="400" alt="홈: 오프라인 내비 지도 위의 3D 미니카, 이번 주 안전 주행 42 km, Lv 4"> | <img src="docs/screenshots/02_glance.png" width="400" alt="글랜스: 진행 방향이 위인 기울인 지도, 안전 주행 33 km와 게이지 1개"> |
| 북쪽이 위인 오프라인 내비 지도 위에 내 미니카(3D 렌더). 이번 주 안전 km·레벨·다음 업적, 아래 키 열(업적·차고·휴대폰·데모 재생·초기화) | 진행 방향이 위로 오게 58° 기울인 지도에 내 차와 모의 미니카 점 2개. 숫자 1개(오늘 안전 km)와 다음 업적 게이지 1개만 보이고 터치는 막힌다 |
| **트립 요약 (정차 직후)** | **업적** |
| <img src="docs/screenshots/03_summary.png" width="400" alt="트립 요약: 안전 65 km, +300 XP, Lv 4에서 5, 업적 2개"> | <img src="docs/screenshots/04_achievements.png" width="400" alt="업적: 12개 중 5개 달성"> |
| 안전 km·XP → 레벨 → 업적 순서로 세 박자에 걸쳐 뜬다. 급감속·제외 구간 같은 위험 기록은 정차 후 이 화면에서만 보인다 | 12개 중 연 업적이 먼저 오고, 남은 업적은 진행도(예: 107 / 300 km)를 보여 준다 |
| **차고** | **휴대폰 연결** |
| <img src="docs/screenshots/05_garage.png" width="400" alt="차고: 색 탭에서 체리색을 고른 소형 미니카"> | <img src="docs/screenshots/06_phone.png" width="400" alt="휴대폰 연결: QR 코드, 8자 코드, 공유 범위 두 줄"> |
| 차종 5종(소형·중형·대형·세단·2륜)과 색·휠·램프·루프·데칼·번호판 탭. 잠긴 항목에는 여는 레벨이 적혀 있다 | 기기 안에서 그린 QR 코드와 8자 코드, 공유 범위 두 줄. 1차는 폰 쪽 화면이 목업이다 |

## APK

- 저장소 [`apk/무지무지무지_오도_release.apk`](apk/무지무지무지_오도_release.apk) (release 빌드, 서명됨, 약 43 MB)
- 이 APK는 **시나리오 재생 모드**(`USE_FAKE_SIGNALS=true`)로 빌드했다. 에뮬레이터에 차량 신호를 따로 넣지 않아도 시연 흐름 전체를 볼 수 있다.

## 실행 방법

### APK로 바로 실행

```bash
adb install -r -g apk/무지무지무지_오도_release.apk
adb shell am start -n com.mujimujimuji.odo/.MainActivity
```

`-g`는 차량 속도·Pleos 차량 신호·위치·TTS 권한을 설치 때 한 번에 준다. 앱 서랍의 **오도** 아이콘으로 실행해도 된다.

다른 키로 서명한 빌드(예: 소스에서 만든 debug)가 이미 깔려 있으면 서명이 달라 `-r` 설치가 실패한다. 그때는 `adb uninstall com.mujimujimuji.odo`로 먼저 지운다. 데모 프로필은 첫 실행 때 다시 만들어진다.

설치된 APK의 권한 목록에는 `INTERNET`도 보인다. 앱 매니페스트에는 없고, Pleos TextToSpeech·SpeechToText SDK(3.3.1)의 라이브러리 매니페스트가 빌드 때 병합해 넣은 것이다. 앱 코드(`app/src/main/java`)에는 네트워크 호출이 없고, 지도 서버·타일도 부르지 않는다.

### 소스에서 빌드

```bash
./gradlew testDebugUnitTest          # 단위 테스트 102개 (engine·sim·cosmetic·drive, ui/components·map·theme·vehicle·text)
./gradlew assembleDebug              # app/build/outputs/apk/debug/
./gradlew assembleRelease            # app/build/outputs/apk/release/
```

- JDK 17, Android SDK 34가 필요하다. Pleos Connect SDK 저장소 설정은 [개발 환경 설정 문서](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)를 따른다.
- release 서명 정보는 저장소에 없다. 루트에 `keystore.properties`(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`)를 두면 그 키로 서명하고, 없으면 서명 없이 빌드된다.

## 테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect 에뮬레이터 `release_connect_v2.1.0` (Android 14, API 34, arm64-v8a) |
| 화면 | 1024×768, 160 dpi. 앱은 멀티윈도우 오른쪽 창(658×552 px)에서 동작 확인 |
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

1. 앱을 열면 데모 프로필(레벨 4, 이번 주 안전 42 km, 업적 3/12)이 들어 있다. 홈 위에는 북쪽이 위인 내비 지도와 내 미니카(3D 렌더)가 보인다.
2. 홈 아래 키 열의 **데모 재생**을 누르면 여주 → 횡성휴게소 영동고속도로 주행(약 66 km, 실제 51분)을 20배속으로 재생한다. 휴게소 도착까지 약 2분 걸린다.
   - 기어 D가 되면 화면이 글랜스 모드(진행 방향이 위인 지도 장면 + 숫자 1개 + 게이지 1개)로 바뀌고 터치가 막힌다.
   - 안전 30 km에서 TTS "안전 주행 30 km, 오늘 상한을 채웠어요."
   - 45 km 지점에서 급감속 1회. 주행 중 화면에는 아무 표시도 하지 않는다.
3. 휴게소에서 기어 P가 되면 트립 요약이 세 박자(안전 km·XP → 레벨 5 → 업적)로 뜬다: 안전 65 km, +300 XP, 레벨 5, "영동고속도로 첫 주행", "1 km 구간 1개 제외". 화면을 탭하면 남은 박자를 건너뛴다.
4. 그대로 두면 휴게소 체류 10분(재생 기준 약 30초) 뒤 "강원도 첫 방문" 업적이 열리고 TTS가 나온다. 홈에 있으면 알림 1줄로도 보인다.
5. **홈** → **차고**의 **색** 탭에서 레벨 5로 열린 색(체리·민트·라벤더·귤·펄 화이트) 중 하나를 고르면 미니카 색이 바뀐다. 같은 화면에 차종(소형·중형·대형·세단·2륜)·휠·램프·루프·데칼·번호판 탭이 있고, 잠긴 항목에는 여는 레벨이 적혀 있다.
6. **초기화**를 누르면 데모 프로필로 돌아가 처음부터 다시 볼 수 있다.
7. 시나리오 재생 빌드에서는 오른쪽 위 **데모** 칩을 길게 누르면 야간/주간 테마를 강제로 바꿀 수 있다(기본은 시계 기준 18:30~06:30 야간). 주행 중에는 테마가 바뀌지 않는다.

TTS가 나간 기록은 `adb logcat -s OdoVoice`로 볼 수 있다.

> 에뮬레이터 참고: 시스템 앱 `ai.umos.drivingview`가 반복해서 죽은 뒤 화면 입력을 가로채 터치가 안 먹는 경우가 있다. 그럴 때 `adb shell am force-stop --user 10 ai.umos.drivingview`를 실행하면 된다.

## 구조

```
app/src/main/java/com/mujimujimuji/odo/
  platform/   Pleos SDK 어댑터 (SDK import는 여기서만) · pleos/ 실제 구현 · fake/ 시나리오 재생
  engine/     순수 Kotlin 판정 로직: 주행 상태, 1 km 구간, 위험 이벤트, XP·상한·연속 기록, 레벨, 업적
  data/       Room (프로필, 트립, 업적)
  drive/      신호 → 엔진 → 저장·음성·화면 상태
  ui/         Compose 화면: home(홈), glance(주행), summary(트립 요약), profile(업적·차고), phonelink(휴대폰 연결)
              map(오프라인 코리도 지도), vehicle(3D 미니카 합성), components, text(조사 처리), theme(색·글자·간격 토큰, 밤/낮)
  cosmetic/   차종·색·루프 카탈로그와 로드아웃
  sim/        데모 시드
app/src/main/assets/
  scenarios/  데모 시나리오 JSON · map/corridor.json 오프라인 지도 · minicar/ 미니카 WebP 레이어
tools/
  scenario/   시나리오 JSON 생성 · map/ OSM 추출→코리도 변환 · minicar/ Blender 미니카 렌더
  audio/      이어콘 합성 · design/ 색 대비표 계산 · phone-mockup/ 폰 쪽 목업(글꼴은 내려받기, 저장소 제외)
```

모든 임계값은 `engine/Thresholds.kt`, XP·레벨 표는 `engine/LevelTable.kt`에 있다.

## 안전·프라이버시 원칙

- 기어 D/R 또는 속도 > 0이면 앱의 모든 터치를 막는다. 이 잠금을 끄는 설정은 없다.
- 주행 중에는 숫자 1개, 게이지 1개, 내비 지도 장면(내 차 + 모의 미니카 점 2개)만 보인다. 문단·목록·버튼은 없다(글자는 '안전 주행' 라벨과 단위 km뿐). 위험 이벤트·감점 알림은 정차 후 트립 요약에서만 보여 준다.
- 휴대폰 연결 화면은 정차 중에만 열리고, 주행이 시작되면 대기 중인 코드를 접고 홈으로 돌아간다.
- 주행 중 TTS는 10분에 2회, 1회 1문장.
- XP는 깎이지 않는다. 위험 이벤트가 있는 구간은 0 XP일 뿐이다.
- 차량 신호와 위치 기록은 기기 밖으로 보내지 않는다(1차 버전은 서버 통신이 없다).

## 오픈소스와 데이터 출처

| 이름 | 용도 | 라이선스 |
|---|---|---|
| [OpenStreetMap](https://www.openstreetmap.org/copyright) | 데모 경로·휴게소(Nominatim 검색)·업적 좌표와 오프라인 코리도 지도 (© OpenStreetMap contributors). 자세한 내용은 [NOTICE.md](NOTICE.md) | ODbL 1.0 |
| [OSRM](https://project-osrm.org/) 공개 데모 서버 | 데모 경로 계산 (빌드 때만, 앱에 포함 안 됨) | BSD-2-Clause |
| [OSM Overpass API](https://overpass-api.de/) | 정차·주행 화면의 오프라인 코리도 지도 `assets/map/corridor.json` 원본 추출 (빌드 때 한 번, 스냅숏 osm_base 2026-11-09T03:48:56Z, 앱은 지도 서버·타일을 호출하지 않음) | ODbL 1.0 (데이터) |
| [ZXing core](https://github.com/zxing/zxing) 3.5.3 | 휴대폰 연결 QR 코드를 기기 안에서 그림 (네트워크 없음) | Apache 2.0 |
| [Blender](https://www.blender.org/) 4.5.9 LTS | 미니카 3D 레이어 렌더 (`tools/minicar/`, 빌드 도구로만 사용, 앱에 포함 안 됨) | GPL-2.0+ (도구), 산출물은 팀 저작 |
| [libwebp](https://chromium.googlesource.com/webm/libwebp) `cwebp` 1.6.0 | 미니카 PNG → WebP 변환 (`tools/minicar/postprocess.py`, 도구로만 사용) | BSD-3-Clause |
| [FFmpeg](https://ffmpeg.org/) | 이어콘 WAV → OGG 변환 (`tools/audio/make_earcons.py`, 도구로만 사용, 앱에 포함 안 됨) | LGPL-2.1+ 또는 GPL-2.0+ (빌드 옵션에 따름, 도구) |
| [Noto Sans CJK KR](https://github.com/notofonts/noto-cjk) Sans2.004 | 폰 목업 PNG의 한글 (`tools/phone-mockup/fetch_fonts.sh`로 내려받음, 저장소·앱에 포함 안 됨) | SIL OFL 1.1 |
| [Roboto](https://github.com/googlefonts/roboto-2) v2.138 | 폰 목업 PNG의 숫자·로마자 (위와 같음) | Apache 2.0 |
| [Pillow](https://python-pillow.org/) | 폰 목업 PNG 그리기(`tools/phone-mockup/render.py`), 미니카 그림자 알파 처리(`tools/minicar/postprocess.py`). 도구로만 사용 | MIT-CMU (HPND) |
| AndroidX (Core, Lifecycle, Activity, Room) | 앱 기반, 로컬 저장 | Apache 2.0 |
| Jetpack Compose, Material 3 | UI | Apache 2.0 |
| Kotlin, kotlinx.coroutines | 언어, 비동기 처리 | Apache 2.0 |
| JUnit 4 | 단위 테스트 | EPL 1.0 |
| org.json | 단위 테스트에서 시나리오 JSON 읽기 | Public Domain |
| Pleos Connect SDK | 차량 신호, 위치, 내비, 음성 | Pleos 개발자 약관 |

아이콘·미니카 그림·이어콘(요약 화면 효과음 3개)은 모두 이 저장소의 스크립트로 팀이 직접 만들었다. 앱에 번들한 글꼴은 없다(시스템 글꼴 사용). `tools/phone-mockup/`의 목업 도구만 OFL·Apache 글꼴을 내려받아 쓰며 글꼴 파일은 저장소에 넣지 않는다.

## 에셋 재현

| 산출물 | 스크립트 | 비고 |
|---|---|---|
| `assets/scenarios/gangneung_trip.json` | `tools/scenario/make_gangneung_trip.py` | OSRM 경로 → 20배속 샘플 |
| `assets/map/corridor.json` | `tools/map/fetch_overpass.py` → `tools/map/build_corridor.py` | [tools/map/README.md](tools/map/README.md) |
| `assets/minicar/**` (WebP 120장) | `tools/minicar/build_minicar.py` → `postprocess.py` | [tools/minicar/README.md](tools/minicar/README.md), 두 번 실행해도 바이트 동일 |
| `res/raw/earcon_*.ogg` | `tools/audio/make_earcons.py` | 사인파 합성, 외부 음원 없음 |
| 색 대비표 (Markdown 표, 표준 출력) | `tools/design/contrast.py` | 토큰을 바꾸면 다시 실행 |
| `docs/phone-mockup/*.png` (폰 동반 화면 6장) | `tools/phone-mockup/fetch_fonts.sh` → `render.py` | [tools/phone-mockup/README.md](tools/phone-mockup/README.md), 앱 소스의 토큰·문구·미니카를 읽음, 두 번 실행해도 바이트 동일 |

### 3D 미니카 (`tools/minicar/`)

- 외부 모델·텍스처 없이 `vehicles.json`의 치수 값만으로 Blender가 차종 5종(소형·중형·대형·세단·2륜)을 bmesh로 절차 생성한다. 현대차 디자인 언어에서 영감을 받은 자체 스타일라이즈드 모델이고, 엠블럼·차명·그릴은 없다. `check_names.py`가 저장소에 실제 차명 같은 금지어가 0건인지 검사한다.
- 뷰 2개(홈·차고의 hero 576×384 px, 글랜스 지도 위 navi 192×192 px)마다 레이어 12장(접지 그림자, 흰 차체, 반사광, 유리·타이어 같은 고정부, 휠 3종, 램프, 투톤 루프, 루프박스, 스트라이프, 황금 번호판)을 알파 PNG로 렌더한다. `postprocess.py`가 이것을 WebP 120장과 합성 정보 `compose.json`(합계 약 0.7 MB)으로 묶는다.
- 색·램프·투톤·데칼은 렌더하지 않는다. 앱(`ui/vehicle/OdoVehicle.kt`)이 흰 레이어에 Modulate·Screen 블렌드로 틴트를 얹어 합성하므로, 색을 늘려도 에셋은 늘지 않는다.
- 맥미니(M4 CPU)에서 120장 렌더에 약 47초 걸리고, 두 번 돌려도 PNG가 바이트까지 같다.

```sh
Blender -b -P tools/minicar/build_minicar.py -- --vehicle all --view all --layers all --out <렌더 폴더> --samples 128
python3 -B tools/minicar/postprocess.py --src <렌더 폴더> --cwebp <cwebp 경로>   # Pillow 필요, assets/minicar/에 씀
python3 -B tools/minicar/check_names.py                                         # 0 hits여야 한다
```

### 오프라인 지도 (`tools/map/`)

- `fetch_overpass.py`가 데모 경로(여주 → 횡성) 둘레의 OSM 데이터를 Overpass API로 한 번 받는다. `build_corridor.py`가 경로 양옆 4 km만 남기고 Douglas-Peucker 10 m로 단순화해 정수 로컬 미터 JSON(`assets/map/corridor.json`, 0.40 MB: 도로 5등급·수역·숲·시가지·강·휴게소)을 만든다. 두 스크립트 모두 표준 라이브러리만 쓰고, 이름·주소 문자열은 결과에 넣지 않는다.
- 앱은 이 파일만 읽어 기기 안에서 그린다(`ui/map/`). 정차 화면은 북쪽이 위, 글랜스는 진행 방향이 위인 58° 기울인 시점이다. 런타임에 지도 서버·타일을 부르지 않는다.
- 재현 기준 원본은 Overpass `osm_base` 2026-11-09T03:48:56Z 스냅숏(sha256 `eb979c50…`, 전체 값은 [tools/map/README.md](tools/map/README.md))이고, 이 원본으로 다시 만들면 `corridor.json`이 바이트까지 같다. 받은 원본(12 MB)은 신뢰하지 않는 데이터라 저장소 밖 빈 폴더에 두고 `python3 -I`로 읽는다.

```sh
RAW=<저장소 밖 빈 폴더>
python3 -I tools/map/fetch_overpass.py --out "$RAW/overpass_raw.json"
python3 -I tools/map/build_corridor.py --raw "$RAW/overpass_raw.json" \
  --route tools/scenario/route_yeoju_hoengseong.json --out app/src/main/assets/map/corridor.json
```

## 휴대폰 연결 (1차)

홈의 **휴대폰** 키에서 QR 코드와 8자 코드(4-4)를 보여 준다. 폰 쪽 화면은 아직 목업이다([docs/phone-mockup/](docs/phone-mockup/README.md)). 1차 빌드의 `FakePhoneLink`는 3초 뒤 연결을 자동 확인해 흐름만 시연하며, 실제 폰 앱과 서버는 2차 범위다. 1차 빌드는 연결돼도 차 밖으로 나가는 데이터가 없다. 연결 화면(연결 전·연결 후)에 적힌 공유 범위는 다음 두 줄이다([NOTICE.md](NOTICE.md)와 같은 문구).

- 공유함: 트립 요약·업적·미니카 꾸미기
- 공유 안 함: 차량 신호 원본·위치 기록
