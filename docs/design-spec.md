# 오도(ODO) UI 전면 개편: 최종 디자인 명세 (design-spec.md)

> 이 문서는 오도(ODO)의 UI 설계서다. 코드 주석의 "설계서 §N"은 이 문서의 절 번호를 가리킨다.

작성 2026-11-09, 해금 레벨은 2026-11-11 기준. 숫자가 코드와 다르면 코드(`engine/LevelTable.kt`, `engine/Thresholds.kt`)가 기준이다.

이 문서만으로 구현할 수 있도록 수치·이름·파일 경로를 모두 적었다. 세 디자인 안(§0.2)을 미적 관점과 안전 관점에서 각각 따져 한 안으로 합쳤고, 두 관점이 어긋나는 곳은 §0.3에 결정 근거를 적었다.

---

## 0. 요약과 결정

### 0.1 콘셉트 한 줄
**"내비 위에 놓인 다이캐스트 미니카."** 화면의 세계는 어두운 도로 지도(풀블리드)이고, 그 위에 Pleos식 패널이 한 장 놓인다. 색은 역할로만 쓴다. 앰버 1개는 "운전자가 쌓은 것"(게이지 채움·선택·레벨업·열린 메달), 루트 블루 1개는 "길"(지도 콘텐츠 전용). 공예는 미니카 렌더(에나멜 + 클리어코트, 구운 접지 그림자)에 몰아넣고, UI는 헤어라인·탭룰러 숫자·여백으로 조용하게 둔다. 보상은 전부 정차 후, 주행 중 화면은 라벨·숫자·단위 세 글자 덩어리와 게이지 하나뿐이다.

### 0.2 세 안에서 가져온 것
| 출처 | 가져온 것 |
|---|---|
| C 내비 네이티브 (골격) | 풀블리드 지도(주행 heading-up 58° / 정차 north-up), 화면당 패널 1장, 모달 없음, 앵커 전용 `cosmeticUnlocks`, 구분선 행 업적, 7셀 균일 램프·그릴 없음, 모델명·공식 색상명 전면 금지, fps 폴백, ODbL 3곳, 차종 5종 전부 Lv1 (v2에서 램프는 렌즈 램프(주간등·프로젝터 렌즈 + 붉은 후미등)로 바뀜, §2.2) |
| A 정밀 계기 (규율) | ink 바탕 + 1dp 헤어라인, tnum 숫자 우측 정렬, 앰버(UI ≤10%)/루트 블루(지도 전용) 역할 분리, 빨강·그림자·글로우 0, 추가형 Room v2, hero/navi 2사이즈 에셋 + 레이어 합성(Modulate/Screen), 모션 8줄, 카운트다운 없음, 시스템 글꼴 (v2에서 에셋은 hero/navi/top 3뷰로 바뀌고, 미니카에 붉은 후미등·밤 글로가 들어감. 빨강·그림자·글로우 0은 UI 규칙이라 미니카 렌더는 예외, §1.1·§2.3) |
| B 디오라마 (재질·치수) | 다이캐스트 문법(바퀴 +15%, 오버행 ≈0, 두꺼운 필러, 원피스 스모크 글라스, 1베벨), 에나멜 roughness 0.18 + clearcoat 0.6, 쇼룸 라이팅, 주행 라벨·단위 32sp, 게이지 12dp + 트랙 ≥3:1, `compose.json` 레이어 매니페스트, 채움/외곽선 메달, 지난 길 스티치, (차종, 슬롯) 기억, 나무토막 이어콘 3톤, 어두운 모듈 QR, 트립 경로는 drive/ 메모리만 (v2에서 모서리는 1베벨 대신 Subdivision Surface로 둥글게 바뀜, §2.2) |

### 0.3 미적·안전 관점이 어긋난 곳과 결정
| 쟁점 | 미적 관점 | 안전 관점 | 결정 |
|---|---|---|---|
| 글랜스 패널 | 왼쪽 가장자리에 붙인 92% 패널, 3면만 보임 | 불투명 패널, 움직이는 지도 위에 글자 금지 | **불투명(alpha 1.0) `surface` 패널을 왼쪽 가장자리에 붙이고 오른쪽만 둥글게(0/16/16/0), 상·우·하 3면에 1dp `line`**. 92%→100%는 대비가 13.8→15.2로 거의 같고(§1.4), 글자 밑에서 지도가 움직이는 것을 막는 쪽이 운전 안전에 맞다. "상자"로 읽히는지는 첫 캡처에서 재검증(§15 확인 항목 ②). |
| 게이지 옆 실제 값 글자 | 게이지 옆에 값 병기 | 주행 중 글자는 라벨·숫자·단위만 | **주행 중 게이지에는 글자 없음.** 게이지 오른쪽 끝에 36dp 메달 외곽선 아이콘(다음 거리 업적)을 두어 "메달을 향해 차오른다"로 읽히게 한다. 값 병기는 정차 홈 화면 게이지에서만. |
| 게이지 두께 | 4dp 헤어라인 | ≥10dp, 트랙 ≥3:1 | **주행 12dp(트랙 `track` 4.1:1), 정차 홈·요약은 4dp 헤어라인(값 글자 병기).** |
| 나침반 틱 링(경로 주변 지도 밖) | 넣자 | 2차로 미루자 | **2차.** 경로 주변 지도 밖·GPS 없음은 ground + 1 km 격자선 + 미니카만. |
| 주야 전환 | 캡처로 확인 | 정차 중에만 | **정차 중에만**(잠금 해제 시점에 적용). fake 빌드는 데모 칩 길게 누르기로 강제 전환(캡처용). |
| QR | 어두운 모듈 224–272dp | ≤240dp, 반전 금지, 밝은 회색 판 | **240dp, 어두운 모듈 `#0B0F14` on 판 `#D0D4DA`(12.9:1), 16dp 여백.** |
| 2륜 | 라이더 복장(A) | 라이더 없는 스쿠터 | **라이더 없는 스쿠터.** "캐릭터 의상 1세트"는 투톤 루프로 해석(§15 확인 ①). |

### 0.4 절대 규칙 요약(이 문서 전체에 적용)
`engine/` 로직·`Thresholds.kt`·`LevelTable.kt` 숫자 불변(해금 항목 추가만) · SDK import는 `platform/`만 · 단위 테스트 전부 통과 유지(작성 당시 42개) · 주행 잠금(기어 D/R 또는 속도>0, 트립 중 기어 N 정차) 끄는 설정 없음 · 주행 중 숫자 1 + 게이지 1 + 장면만, 문장·목록·버튼·위험/감점·시간 압박·XP 감소·신원·전체 순위 없음 · TTS 10분 2회 1문장(로직 그대로) · 런타임 지도 타일·서버 호출 0, 데이터는 빌드 전 `assets/`에 번들(OSM→ODbL 표기) · 생성 에셋은 재현 스크립트가 `tools/`에 · 로고·엠블럼·차명·실차 복제 금지 · 내비 UI 흉내(회전 화살표·ETA·제한속도·차로) 금지 · 폰 페어링에 계정·이름·전화번호 입력 없음, QR에는 랜덤 토큰만 · 소스·커밋·스크린샷에 개인정보 없음 · 글꼴·이미지·음원은 허가된 것만(OFL→README).

---

## 1. 토큰 (Compose 이름 그대로)

파일: `ui/theme/OdoTokens.kt`(색·간격·모양), `ui/theme/OdoType.kt`(글자), `ui/theme/OdoTheme.kt`(M3 매핑). 기존 `Road` 오브젝트는 삭제하고 모든 참조를 아래로 옮긴다.

### 1.1 색: `OdoColors` (야간이 기본, 주간은 명도 1단계↑, 둘 다 어두운 지도)
```kotlin
@Immutable data class OdoColors(
    val ground: Color, val surface: Color, val line: Color,
    val ink: Color, val inkDim: Color, val track: Color,
    val amber: Color, val onAmber: Color,
    val route: Color, val routeOutline: Color, val routePassed: Color,
    val mapWater: Color, val mapForest: Color, val mapUrban: Color,
    val roadCasing: Color, val roadCore: Color,
    val qrPlate: Color, val qrModule: Color,
)
val OdoNight = OdoColors(
    ground = Color(0xFF0B0F14), surface = Color(0xFF121821), line = Color(0xFF2E3845),
    ink = Color(0xFFF4F6F8), inkDim = Color(0xFF8D9AAB), track = Color(0xFF6E7A8B),
    amber = Color(0xFFF0A83A), onAmber = Color(0xFF231B00),
    route = Color(0xFF3D86FF), routeOutline = Color(0xFF0E2E6B), routePassed = Color(0xFF4A5566),
    mapWater = Color(0xFF0E1826), mapForest = Color(0xFF0E1711), mapUrban = Color(0xFF141920),
    roadCasing = Color(0xFF06080B), roadCore = Color(0xFF4A5563),
    qrPlate = Color(0xFFD0D4DA), qrModule = Color(0xFF0B0F14),
)
val OdoDay = OdoNight.copy(
    ground = Color(0xFF171C23), surface = Color(0xFF1F262F), line = Color(0xFF3E4857),
    ink = Color(0xFFFFFFFF), inkDim = Color(0xFFA3AEBC), track = Color(0xFF7E8A9C),
    routePassed = Color(0xFF5C6778),
    mapWater = Color(0xFF1B2838), mapForest = Color(0xFF1A241C), mapUrban = Color(0xFF20262E),
    roadCasing = Color(0xFF0F1317), roadCore = Color(0xFF5A6573),
)
val LocalOdoColors = staticCompositionLocalOf { OdoNight }
```
- ink 알파 단계는 `ink.copy(alpha = …)`로만 만든다: `0.96`(1차 글자·숫자) · `0.88`(제목) · `0.72`(본문 보조) · `0.56`(비활성 글자) · `0.38`(장식선·잠긴 스와치 링). 솔리드 `inkDim`은 캡션·라벨 전용(알파 글자가 겹칠 때 색이 바뀌는 문제 회피).
- **앰버 사용처 4곳 고정**: 게이지 채움, 선택 상태(칩·탭 밑줄 2dp), 레벨업 카드 1dp 프레임, 열린 메달 채움. 화면 면적 ≤10%. 경고로 읽히면 `brass #D8B46A`로 전량 교체(토큰 1줄; §15 확인 ③).
- **루트 블루는 지도 안에서만.** 버튼·링크·선택에 쓰지 않는다. 기본 버튼은 `ink(0.96)` 채움 + `ground` 글자(16.4:1).
- 빨강·초록·노랑·그라데이션·그림자(elevation)·글로우·비네트 0. 유일한 "그림자"는 미니카 플레이트에 구운 접지 그림자. 이 규칙은 UI에 적용한다. 미니카 렌더는 콘텐츠라 붉은 후미등 렌즈와 밤 정적 글로(`glow_head`·`glow_tail`, §2.3)를 둔다.
- 페인트(미니카 콘텐츠 색, UI 아님): sky `#5AA9E6`(기본), cherry `#E5484D`, mint `#3CC7A6`, lavender `#9B8CFF`, tangerine `#FF9F43`, pearl `#F1F3F6`(이상 기존 id 유지), graphite `#3A3F47`, sand `#D8C8A4`(Lv30 한정 페인트). 스와치 칩은 모두 1dp `ink(0.38)` 안쪽 링(graphite 1.7:1 대비 보완).

### 1.2 글자: `OdoType` (7역할, 500/700만, 화면당 ≤4역할)
```kotlin
object OdoType {
    private val base = TextStyle(
        fontFamily = FontFamily.Default,           // 시스템 Roboto + 기기 Noto CJK. 번들 0(§15 확인 ④)
        fontFeatureSettings = "tnum",
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase),
    )
    val caption  = base.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
    val body     = base.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
    val title    = base.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
    val label    = base.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium)  // 주행 라벨·단위
    val headline = base.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
    val numeralM = base.copy(fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
    val numeralL = base.copy(fontSize = 88.sp, lineHeight = 92.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp)
}
```
- 주행 화면: `label`(32) + `numeralL`(88) 2역할만. 정차 화면 본문 24, 캡션 20(AAOS 1차 32dp / 2차 24dp 충족). 128sp 역할은 폐기(88 안에 3자리 "999"가 패널 안에 들어가고 font_scale 1.3에서도 192dp < 216dp).
- 숫자는 항상 `tnum`, 열 안에서 **우측 정렬**(`TextAlign.End`). 숫자와 단위 사이 공백 1칸(`"42 km"`), 단위는 숫자보다 작은 역할.
- `maxLines = 1, softWrap = false`를 숫자·라벨·칩에 전부 지정, 넘치면 `TextOverflow.Clip`이 아니라 레이아웃을 줄인다(캡처로 확인).

### 1.3 간격·모양·크기: `OdoSpace`, `OdoShape`, `OdoSize`
```kotlin
object OdoSpace { val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp; val xxxl = 48.dp; val margin = 32.dp }
object OdoShape {
    val panel = RoundedCornerShape(16.dp)                                    // 정차 패널
    val panelEdgeLeft = RoundedCornerShape(0.dp, 16.dp, 16.dp, 0.dp)         // 글랜스 패널(왼쪽 가장자리 부착)
    val chip = RoundedCornerShape(12.dp); val key = RectangleShape          // KeyStrip 셀은 각지게, 구분선으로만 나눔
    val hairline = 1.dp
}
object OdoSize { val touch = 76.dp; val touchGap = 23.dp; val icon = 32.dp; val picto = 36.dp; val medal = 36.dp; val gaugeDrive = 12.dp; val gaugeParked = 4.dp; val heroW = 288.dp; val heroH = 192.dp; val navi = 96.dp; val qr = 240.dp }
```
- `heroW`·`heroH`는 코드에서 쓰지 않는다. hero 그림은 화면마다 상자에 맞춰 줄인다: 차고 228×152dp(§5.5), 휴대폰 연결 272×181dp(§5.6).
- `navi`(96dp)는 지도 미니카의 표시 상자(`NaviMinicar`)다. 글랜스·홈·요약이 함께 쓰고, 상자 가운데가 기준점이다. 그림 크기는 상자와 무관하게 원래 dp × `markerScale` × `naviScale`이다(§2.3·§2.5).
- 엘리베이션은 1dp `line` 헤어라인 하나뿐. 패널 안에 패널(카드 속 카드) 금지. 아이콘은 벡터 1세트(stroke 2dp, 32dp, 픽토 36dp), 이모지·유니코드 글리프 0.

### 1.4 대비표 (WCAG 2.x, `tools/design/contrast.py` 출력 그대로. 토큰을 바꾸면 다시 돌려 이 표를 갱신. 마지막 재실행 2026-11-10)
| 조합 | 야간 | 주간 | 기준 |
|---|---|---|---|
| ink 96% on surface / on ground | 15.18 / 16.38 | 14.13 / 15.84 | 글자 4.5 ✓ |
| ink 88% on surface | 12.85 | 12.09 | ✓ |
| ink 72% on surface | 8.95 | 8.55 | ✓ |
| ink 56% on surface(비활성 글자) | 5.86 | 5.79 | ✓ |
| ink 38% on surface(장식선) | 3.38 | 3.44 | 비글자 3 ✓ |
| inkDim(캡션) on surface / ground | 6.23 / 6.72 | 6.78 / 7.61 | ✓ |
| track(게이지 트랙) on surface / ground | 4.09 / 4.41 | 4.36 / 4.89 | 비글자 3 ✓ |
| line(헤어라인) on surface | 1.50 | 1.65 | 장식 전용, 정보 전달 금지 |
| amber on surface / on ground | 8.80 / 9.49 | 7.53 / 8.45 | 비글자 3 ✓, 글자로도 ✓ |
| onAmber `#231B00` on amber | 8.44 | 8.44 | ✓ |
| amber(채움) vs track(미채움) | 2.15 | 1.73 | 경계 식별은 채도 차 + 채움/surface 8.8:1로 충분. 캡처에서 경계가 흐리면 채움 끝에 2dp `surface` 간격 추가(폴백) |
| route on ground / water / forest / urban | 5.54 / 5.14 / 5.27 / 5.09 | 4.93 / 4.30 / 4.61 / 4.39 | 지도 그래픽 3 ✓ |
| route on roadCore | 2.18 | 1.71 | 경로선은 도로 위에 그려지므로 2dp `routeOutline`으로 분리 |
| routePassed on ground | 2.55 | 2.99 | 장식 스티치, 의도적으로 낮음 |
| roadCore on ground / urban | 2.54 / 2.33 | 2.89 / 2.57 | 지도 바탕(글자 없음) |
| ink on route(흰 글자 on 파란 버튼) | 3.20 | 3.47 | **실패 → 파란 버튼 폐기** |
| 출처 캡션 inkDim on ground 60 % 스크림 over roadCore | 4.99 | 5.18 | 글자 4.5 ✓ |
| ground 글자 on ink96 채움(기본 버튼) | 16.38 | 15.84 | ✓ (초판 주간 17.12는 ink 100 %로 잰 값) |
| qrModule on qrPlate | 12.92 | 12.92 | ✓ |
| 페인트 스와치 on surface(sky/cherry/mint/lavender/tangerine/pearl/graphite/sand) | 7.0/4.6/8.4/6.4/8.7/16.0/**1.7**/10.8 | 6.0/3.9/7.2/5.5/7.5/13.7/**1.4**/9.2 | 1dp ink38 링으로 전 스와치 보완 |
| graphite 견본 링 on surface / 링 vs graphite / 잠김 링 on surface | 4.80 / 2.85 / 3.89 | 4.29 / 2.98 / 3.77 | 비글자 3 ✓(원 경계는 링이 surface와 대비). 링 = ink 38 % over 페인트, 잠김 = 페인트 38 % 합성 위 |
| 글랜스 패널 alpha 1.0 / 0.96 / 0.92, 밑에 route가 있을 때 ink96 | 15.18 / 14.56 / 13.80 | 야간만 측정 | 1.0 채택(§0.3) |

### 1.5 M3 colorScheme 매핑 (모든 역할을 정의해 기본값이 새어 나오지 않게)
`background/surface/surfaceContainer* = ground`(화면) · `surfaceVariant/surfaceContainerHigh = surface`(패널) · `outline/outlineVariant = line` · `onBackground/onSurface = ink` · `onSurfaceVariant = inkDim` · `primary = amber, onPrimary = onAmber, primaryContainer = amber, onPrimaryContainer = onAmber` · `secondary/secondaryContainer = surface, onSecondary* = ink` · `tertiary = route(지도 외 사용 금지 주석), onTertiary = ink` · `error = inkDim, onError = ground, errorContainer = surface, onErrorContainer = ink`(빨강 없음) · `inverseSurface = ink, inverseOnSurface = ground, inversePrimary = amber` · `scrim = #000000` · `surfaceTint = Color.Transparent`. 주야는 `OdoTheme(night: Boolean)`이 `LocalOdoColors` + colorScheme을 함께 바꾼다.

### 1.6 주야 규칙
- 기기 시각 18:30–06:30 → 야간, 그 외 주간. `ui/theme/NightMode.kt`의 `rememberNightMode(locked: Boolean): Boolean`. 1분마다 평가하되 **`locked == true`인 동안은 값을 바꾸지 않는다**(주행 중 전환 금지). 주행 중 18:30이 지나면 다음 정차에 바뀐다.
- fake 빌드: 홈 데모 칩 길게 누르기(700 ms) → 강제 전환 토글(캡처용, 정차 중만, release에는 없음).

---

## 2. 3D 미니카: 에셋 파이프라인

### 2.1 결정(색 × 뷰로 굽는 대신 "차종 × 색 × 부품" 조합에 맞게)
"색 6 × 뷰 3 = 18장"으로 굽는 방식으로는 조합(차종 5 × 색 8 × 휠 3 × 램프 3 × 루프 3 × 데칼 2 × 번호판 2)은 렌더로 다 구울 수 없다. → **색·램프·투톤·데칼은 렌더하지 않고 앱에서 틴트**한다. 뷰는 3개다.
- hero: 정면 3/4. 차고·휴대폰 연결 화면.
- navi: 차 뒤 위 45°. 글랜스(heading-up) 지도. 차 앞이 늘 위라 돌리지 않는다.
- top: 위에서 본 정사영. 홈·요약(north-up) 지도. 진행방향으로 돌린다. 후방 45° 그림을 north-up 지도에서 돌리면 원근이 지도와 어긋나서 따로 굽는다.

라이팅 리그는 **중립 1개**다. 야간은 Compose에서 바디·투톤·데칼을 15% 어둡게 하고, 램프 레이어 알파를 1.0으로 올리고, 정적 밤 글로 레이어(`glow_head`·`glow_tail`)를 더한다. 주간 램프 알파는 0.55다. 주야 리그 2개로 두 번 굽는 대신 165장을 한 번에 굽는다(실측 208 s, §2.4).

### 2.2 차종 5종: 형태 명세 (치수 m, L×W×H / 휠베이스 / 바퀴 반지름(다이캐스트 +15% 포함))
공통 다이캐스트 문법: 오버행 ≈0.1, 바디 폭 = 트랙 − 0.05(바퀴가 살짝 튀어나옴), 필러 두께 ≥0.12, 원피스 스모크 글라스(실내 없음), 캐릭터 라인 ≤1, 차체는 로프트 케이지 + Subdivision Surface(crease)로 모서리를 둥글리고 휠 아치를 잘라 어두운 라이너를 넣는다, 그릴 없음(막힌 전면 + 램프 렌즈 + 어두운 흡기 패치), 램프는 **렌즈 패치**(앞: 어두운 하우징 + 주간등 띠 + 프로젝터 렌즈, 뒤: 붉은 후미등 렌즈; 일반화한 모양, 특정 차종 배열 복제 금지), 엠블럼·배지·문자·번호판 글자 0(번호판 자리는 글자 없는 판: 승용 뒤는 어두운 번호판 자리, 2륜 뒤는 밝은 빈 번호판).

| id / 표시 | 치수 | 실루엣·면 | 램프 | 바퀴 | 재질 포인트 | 현대 디자인 언어 참고점(일반화) |
|---|---|---|---|---|---|---|
| `compact` 소형 | 3.4×1.75×1.55 / 2.30 / 0.40 | 박시 해치, 평평한 후드, 수직 테일게이트, 짧은 오버행, 둥근 아치 | 전 둥근 사각 하우징 + 주간등 테 + 프로젝터 렌즈, 둥근 안개등 / 후 하우징 안 붉은 렌즈 | 14" 느낌, 노출 큼 | 에나멜 바디, 하부 클래딩 띠 0.12 | 직선적 박스 비례·평면 후드 |
| `suv` 중형 | 3.9×2.00×1.70 / 2.50 / 0.46 | 각진 사각 휠아치, 두꺼운 C필러, 루프레일 | 전 넓은 하우징 + ㄱ자 주간등 + 프로젝터 렌즈, 둥근 안개등 / 후 세로 붉은 렌즈 | 큰 바퀴, 클래딩이 아치 감쌈 | 클래딩 roughness 0.8 무광, 앞뒤 스키드 플레이트 | 각진 휠아치·클래딩·두꺼운 C필러 중 **2개 이상**만 일반화 |
| `large` 대형 | 4.4×2.05×1.85 / 2.90 / 0.46 | 원박스 MPV, 수직 A필러, 긴 루프, 넓은 글라스, 둥근 아치 | 전 **전폭 바** + 전폭 주간등 + 양끝 프로젝터 렌즈 / 후 전폭 바 + 양끝 붉은 렌즈 | 중간 | 글라스 면적 큼 | 원박스 비례·수직 A필러·전폭 램프 바 |
| `sedan` 세단 | 4.3×1.90×1.25 / 2.95 / 0.39 | 패스트백 루프라인, 낮은 카울, 짧은 데크, 둥근 아치 | 전 얇은 하우징 + 얇은 주간등 + 프로젝터 렌즈 / 후 전폭 어두운 바 + 양끝 붉은 렌즈 | 낮고 넓음 | 캐릭터 라인 1개(옆면 어깨) | 패스트백 루프·뒤 전폭 바·낮은 카울 |
| `scooter` 2륜 | 1.75×0.70×1.10 / 1.30 / 0.32 | 스텝스루 전동 스쿠터, 평평한 발판, 시트, 핸들바, **라이더 없음** | 전 핸들 커버 코의 둥근 렌즈 1(어두운 베젤) / 후 꼬리 모서리를 감싸는 붉은 렌즈 1 | 2개, 작은 지름 | 시트 무광, 바디 에나멜 | 미래형 소형 모빌리티 비례·둥근 단일 램프 |

Blender 재질(공통): 바디 Principled: 베이스 **백색 1.0**(틴트용), roughness 0.18, clearcoat 0.6, clearcoat roughness 0.08 · 글라스 `#0D1117` roughness 0.05, transmission 0, specular 0.4 · 타이어 `#2A2622` roughness 0.9 · 휠 허브 웜 그레이 `#9A948C` metallic 0.6 roughness 0.35(크롬 없음; 밝은·어두운 변형 `hub_bright`·`hub_dark`, 허브 바탕 `#3A3F47`) · 클래딩 `#1C2126` roughness 0.8 · 휠하우스 라이너 `#0E1013` roughness 0.95 · 사이드미러 거울면 `#B4BCC6` metallic 1.0 roughness 0.06(승용은 몸통이 차체 색 `body`, 받침이 클래딩. 2륜은 몸통이 클래딩, 대가 `hub_dark`) · 시트(2륜) `#8A5A3C` roughness 0.7 · 램프 하우징 `#1C2126` roughness 0.3 · 램프(주간등·안개등) Emission 2.0 백색(틴트용) · 2륜 전조등 렌즈 백색 roughness 0.15 Emission 0.55 · 프로젝터 렌즈 `#8E98A4` metallic 0.8 roughness 0.16(발광 없음) · 후미등 렌즈 `#FF2B1C` roughness 0.25 Emission 1.6 · 후미등 하우징 `#6A1218` clearcoat 0.6 · 루프(투톤) 백색 1.0 · 데칼 백색 1.0 · 루프박스 `#1C2126` roughness 0.85 · 번호판 자리: 승용 뒤는 램프 하우징 재질, 2륜 뒤는 빈 번호판 `#E6E3DB` roughness 0.45 · 황금 번호판 `#C9A24A` metallic 0.9 roughness 0.3, 글자 없는 민무늬 판(앞·뒤) · 바닥 shadow catcher.
라이팅(중립 쇼룸, HDRI 없음): 월드는 노드 그라데이션, 뷰마다 면광원 리그(키·필·림·백 등). 유리 거울 방향에는 유리만 비추는 그라데이션 발광 판(`glass_card`, Cycles light linking)을 둔다. top 리그는 좌우 대칭. 값은 `vehicles.json`에 있다.
카메라(`vehicles.json`): hero 원근 70 mm, 고도 18°, 방위 52°(정면 3/4), 거리 11 m · navi 원근 200 mm, 고도 45°, 방위 180°(차 뒤 위, 차 머리가 이미지 위), 56 px/m. 고도는 38°·45°·52°를 비교해 45°로 정했다 · top 정사영 56 px/m(차 머리가 이미지 위). 스쿠터는 navi·top을 마커 줌 1.6, hero를 줌 1.8로 굽고, 앱에서 지도 미니카를 `naviScale` 1.25배로 그린다(얇게 안 보이게).

### 2.3 레이어(플레이트): 차종 5 × 뷰 3 × 레이어 14 → 165장
| 레이어 파일 | 내용 | 합성 |
|---|---|---|
| `shadow` | shadow catcher 접지 그림자만(알파) | SrcOver, alpha 0.9 |
| `body` | 백색 바디 디퓨즈+간접(글로시 제외), 투톤 영역 포함 | `ColorFilter.tint(paint, BlendMode.Modulate)` |
| `spec` | 글로시 직접광 패스(하이라이트만) | `BlendMode.Screen` |
| `fixed` | 글라스·타이어·클래딩·허브 공통부·휠하우스 라이너·램프 하우징·사이드미러 받침과 거울면(승용 미러 몸통은 `body`)·번호판 자리·**붉은 후미등 렌즈**·시트. navi·top은 `wheel_steel`도 여기에 굽는다 | SrcOver |
| `wheel_steel` / `wheel_dia` / `wheel_aero` | 휠 디자인 3종(허브 캡 영역), hero만 | SrcOver, 선택 1장 |
| `lamp` | 앞 램프: 주간등·안개등(2륜은 전조등 렌즈) Emission 백색 + 프로젝터 렌즈(금속 회색, 발광 없음) | `tint(lampColor, Modulate)`, 야간 alpha 1.0 / 주간 0.55 |
| `roof_twotone` | 루프(스쿠터: 레그실드) 영역 백색 | `tint(roofColor, Modulate)` |
| `roof_box` | 루프박스(스쿠터: 탑케이스) 무광 `#1C2126` | SrcOver |
| `decal_stripe` | 후드→루프→테일 오프셋 스트라이프 백색 | `tint(decalColor, Modulate)` |
| `plate_gold` | 황금 번호판(전·후) | SrcOver |
| `glow_head` | 밤 전조등 빛(렌즈 번짐 + 앞 바닥 빛 웅덩이) | `tint(lampColor, Modulate)` 뒤 `BlendMode.Screen`, 야간만 |
| `glow_tail` | 밤 후미등 붉은 번짐 + 뒤 바닥 빛 | `BlendMode.Screen`, 야간만 |

- 레이어 패스마다 순서상 앞 레이어 객체는 holdout, 뒤 레이어 객체는 숨겨서 렌더한다(겹친 곳의 알파가 정확하다).
- 글로 2장은 빛 색이 RGB, 알파 = max(R, G, B)인 정적 그림이다(애니메이션 없음). 매니페스트 `night`에 있는 레이어라 야간에만 읽고 그린다.
- 뷰마다 굽지 않는 레이어(`skip`): hero `glow_tail`(뒤가 안 보인다) · navi `wheel_*`·`lamp`(앞 램프가 안 보여 램프 색은 밤 `glow_head`로만 보인다) · top `wheel_*`·`plate_gold`. 그래서 차종당 hero 13 + navi 10 + top 10 = 33장, 5종 165장(글로 25장 포함).
- 해상도(@2×): hero **576×384 px**(288×192dp) lossy WebP `-q 85 -alpha_q 90 -sharp_yuv` · navi **256×320 px**(128×160dp) · top **256×384 px**(128×192dp) lossless WebP `-lossless -z 9 -exact` · 글로는 lossless와 q90 중 작은 쪽. 실측 바이트 합 885,867(hero 339,482 · navi 252,760 · top 291,838, 이 가운데 글로 25장 73,254 · `compose.json` 1,787), `du -sk` **1216 KiB ≤ 4096 KiB**, 가장 큰 파일 19 KiB. 경로 `app/src/main/assets/minicar/<vehicle>/<hero|navi|top>/<layer>.webp`.
- 매니페스트 `app/src/main/assets/minicar/compose.json`(v2; 앱은 v1 매니페스트도 읽고 top이 없으면 navi로 대신한다):
```json
{"version":2,
 "views":{"hero":{"px":[576,384],"dp":[288,192],"groundY":336,"skip":["glow_tail"]},
          "navi":{"px":[256,320],"dp":[128,160],"anchor":[128,200],"skip":["wheel_steel","wheel_dia","wheel_aero","lamp"]},
          "top":{"px":[256,384],"dp":[128,192],"anchor":[128,192],"skip":["wheel_steel","wheel_dia","wheel_aero","plate_gold"]}},
 "order":["shadow","body","spec","roof_twotone","decal_stripe","fixed","wheel","lamp","roof_box","plate_gold","glow_head","glow_tail"],
 "blend":{"body":"modulate","spec":"screen","lamp":"modulate","roof_twotone":"modulate","decal_stripe":"modulate","glow_head":"screen","glow_tail":"screen"},
 "night":["glow_head","glow_tail"],
 "vehicles":{"compact":{"label":"소형","naviScale":1.0},"suv":{"label":"중형","naviScale":1.0},"large":{"label":"대형","naviScale":1.0},"sedan":{"label":"세단","naviScale":1.0},"scooter":{"label":"2륜","naviScale":1.25}},
 "layers":["shadow","body","spec","fixed","wheel_steel","wheel_dia","wheel_aero","lamp","roof_twotone","roof_box","decal_stripe","plate_gold","glow_head","glow_tail"],
 "provenance":"현대차 디자인 언어에서 영감을 받아 자체 제작한 스타일라이즈드 모델. 특정 차종·엠블럼·명칭을 재현하지 않음.",
 "generator":"tools/minicar/build_minicar.py","blender":"4.5.9","samples":128}
```
- 금지: 파일명·레이어명·sidecar·주석·커밋 메시지에 실제 모델명(한/영)·공식 색상명·"~풍". 검사: `tools/minicar/check_names.py`가 `tools/minicar/denylist.txt`(현대차그룹 현행·단종 모델명 한/영 + 공식 색상명; 검사 전용 파일이라는 머리말)로 `app/ tools/ docs/ NOTICE.md`를 grep → 0건이어야 한다.

### 2.4 스크립트(재현 가능, Blender 4.5.9)
```
tools/minicar/
  vehicles.json          # §2.2 치수·단면 표(shape)·램프 장식·재질·뷰·카메라·조명(차종당 1객체). 외부 .blend 없음
  models.py              # 차 4종 형상: 로프트 케이지 + Subdivision Surface(bmesh + numpy), 아치·라이너·렌즈 패치
  models_scooter.py      # 2륜 형상
  build_minicar.py       # blender -b -t 6 --python-exit-code 1 -P build_minicar.py -- --vehicle all --view all --layers all --out <dir> --samples 128
  postprocess.py         # PNG→WebP(Pillow + cwebp), compose.json 생성, 총량 ≤4 MB 검사(--check: 빠진·남는 파일, 크기, 계약과 다른 compose.json)
  preview.py             # 앱과 같은 합성 규칙으로 보는 확인용 시트·지도 합성
  render_meta.json       # 마지막 렌더 기록(바닥 원점 등, postprocess가 기준점과 대조)
  check_names.py, denylist.txt
  README.md              # 실행법·소요 시간·출처 문구
```
- Cycles CPU 128 samples + OpenImageDenoise, film transparent, 레이어 패스마다 holdout(§2.3)으로 렌더(알파 정확). 실측 165장 **208.0 s / 210.5 s**(두 번 실행, `-t 6`), 변환 12 s. ≤ 10분. 실측은 README에 적는다.
- 한 번 더 돌리면 바이트 동일(시드 고정 `seed=7`, 디노이즈 결정적): 따로 두 번 렌더한 PNG 165장이 `diff -rq`로 같았고, 변환한 166파일도 바이트까지 같았다. 실행 명령을 `NOTICE.md`와 `tools/minicar/README.md`에 둔다.

### 2.5 앱 API: `ui/vehicle/`
```kotlin
enum class VehicleView(val dir: String) { Hero("hero"), Navi("navi"), Top("top") }

/** 지도 미니카(Navi·Top) 크기 배율. 그림 원래 크기 × 이 배율 × 차종 naviScale. */
object VehicleMarkerScale { const val HOME = 1f; const val GLANCE = 1f; const val SUMMARY = 0.8f }

/** 미니카 1대. 레이어 WebP를 매니페스트 order대로 합성한 정지 그림. */
@Composable
fun OdoVehicle(
    loadout: Loadout,
    view: VehicleView,
    modifier: Modifier = Modifier,
    headingDeg: Float = 0f,          // Top만: 북 0°, 시계 방향
    night: Boolean = LocalOdoNight.current,
    markerScale: Float = 1f,         // Navi·Top만
)
```
- 호환 오버로드 `Minicar`는 모든 화면을 `OdoVehicle`로 옮긴 뒤 지웠다.
- 배치: Hero는 그림 중심을 상자 중심에 두고 상자에 맞춰 줄인다(3:2). Navi는 기준점(`anchor`)을 상자 중심에 두고 돌리지 않는다(차 앞이 늘 위). Top은 차 중심을 상자 중심에 두고 `headingDeg`만큼 돌린다. Navi·Top 크기는 상자와 무관하다(원래 dp × `markerScale` × `naviScale`).
- `VehicleAssets`(싱글턴): `compose.json` 파싱(`VehicleManifest`, v1 매니페스트도 읽는다), `(vehicle, view, layer)` → `ImageBitmap` 바이트 LRU 20 MiB, `ColorFilter`는 (색, 합성 모드)별 캐시(프레임마다 새로 만들지 않음), `onTrimMemory`에 비운다. 로드는 `Dispatchers.IO`. 밤 레이어(`night`)는 야간에만 읽는다. 준비 전에는 아무것도 그리지 않는다(플레이스홀더 상자 금지).
- 그리기 순서는 매니페스트 `order`. 투톤 루프 색: 바디 페인트 휘도 > 0.35 → graphite, 아니면 pearl. 데칼 색: 같은 규칙(바디 밝으면 graphite 스트라이프, 어두우면 pearl). 야간에는 바디·루프·데칼 색을 15% 어둡게 한다. `glow_head`는 램프 색으로 물들인 뒤 Screen으로 더한다. 램프 색: white `#FFFFFF` / warm `#FFD6A5` / ice `#CFE6FF`(UI 앰버와 다른 값이라 역할이 섞이지 않는다).
- 트레일(Lv10, 앱 표시 이름은 영어 차용어 대신 "지난 길 리본"): 에셋이 아니라 지도 렌더(§4.2)다. 지나온 경로를 대시 대신 바디 페인트색 45% 6dp 리본으로 그린다(도로 코어보다 어두운 색이면 `ink` 45%, `CorridorMap.kt` `ribbonColor`).

---

## 3. 커스텀 요소 · 데이터 모델 · 해금

### 3.1 모델: 새 패키지 `com.mujimujimuji.odo.cosmetic/`(순수 Kotlin, Android 의존 0, JVM 테스트 대상; `engine/`은 건드리지 않음)
```kotlin
enum class VehicleType(val id: String, val label: String) {
    COMPACT("compact", "소형"), SUV("suv", "중형"), LARGE("large", "대형"), SEDAN("sedan", "세단"), SCOOTER("scooter", "2륜");
    companion object { fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: COMPACT }
}
enum class Slot(val id: String) { VEHICLE("vehicle"), COLOR("color"), WHEEL("wheel"), LAMP("lamp"), ROOF("roof"), DECAL("decal"), PLATE("plate"), TRAIL("trail") }

data class CosmeticItem(val id: String, val slot: Slot, val label: String, val argb: Long? = null) {
    val key get() = "${slot.id}:$id"
    val unlockLevel: Int get() = LevelTable.cosmeticUnlocks[key] ?: 1
}
data class Loadout(
    val vehicle: VehicleType = VehicleType.COMPACT,
    val colorId: String = "sky", val wheelId: String = "steel", val lampId: String = "white",
    val roofId: String = "none", val decalId: String = "none", val plateId: String = "none", val trailId: String = "none",
) {
    /** 잠긴 항목이 저장돼 있으면(레벨 되돌림 없음이라 데모 초기화 때만 가능) 기본값으로 읽는다. */
    fun resolve(level: Int): Loadout
    companion object { val default = Loadout() }
}
object CosmeticCatalog {
    val items: List<CosmeticItem>          // 아래 표 24개 + none 4개
    fun bySlot(slot: Slot): List<CosmeticItem>
    fun item(slot: Slot, id: String): CosmeticItem   // 모르는 id → 그 슬롯 기본
    fun colorIdFor(argb: Color): String
    val collectibleTotal: Int               // 24
    fun collected(level: Int): Int          // unlockLevel <= level 인 수집 항목 수
}
```
- `ui/profile/MinicarPalette.kt`는 `CosmeticCatalog.bySlot(COLOR)`를 감싸는 얇은 래퍼로 바꾸고 `SLOT = "color"`, `byId()`는 유지(기존 호출부·Room 호환).

### 3.2 항목표 (수집 N = 24; `none`은 수집에 세지 않음)
| 슬롯 | id · 표시 | 해금(앵커만) |
|---|---|---|
| vehicle | compact 소형 · suv 중형 · large 대형 · sedan 세단 · scooter 2륜 | **Lv 1**(차종은 보상이 아님, 데모에 5종 다 보임) |
| color | sky 하늘 | Lv 1 |
| color | cherry 체리 · mint 민트 · lavender 라벤더 · tangerine 귤 · pearl 펄 화이트 | **Lv 5**(기존 `colorUnlockLevel`) |
| color | graphite 그래파이트 · sand 샌드 | **Lv 30** "한정 페인트" |
| wheel | steel 스틸 | Lv 1 |
| wheel | dia 다이아 | **Lv 10** |
| wheel | aero 에어로 | Lv 20 |
| lamp | white 화이트 | Lv 1 |
| lamp | warm 웜 | **Lv 10** |
| lamp | ice 아이스 | Lv 20 |
| roof | twotone 투톤 루프 | **Lv 10** "캐릭터 의상 1세트" 해석 ※확인 ① |
| roof | box 루프박스(스쿠터 탑케이스) | **Lv 20** "루프박스" |
| decal | stripe 스트라이프 | **Lv 20** "데칼" |
| trail | ribbon 지난 길 리본 | **Lv 10** "트레일 효과" |
| plate | gold 황금 번호판 | **Lv 50** "황금 번호판(가상)" |

### 3.3 `LevelTable.kt` 추가(숫자·칭호·unlock 문구 불변, 아래 블록만 덧붙임)
```kotlin
/** 꾸미기 항목 해금 레벨. 키는 "slot:id". 값은 반드시 anchors의 레벨 중 하나(테스트로 고정). */
val cosmeticUnlocks: Map<String, Int> = buildMap {
    val lv5 = anchors[1].level; val lv10 = anchors[2].level; val lv20 = anchors[3].level; val lv30 = anchors[4].level; val lv50 = anchors[5].level
    listOf("cherry", "mint", "lavender", "tangerine", "pearl").forEach { put("color:$it", lv5) }
    put("color:graphite", lv30); put("color:sand", lv30)
    put("wheel:dia", lv10); put("wheel:aero", lv20)       // Lv 5는 색 5종만(§15 ⑤)
    put("lamp:warm", lv10); put("lamp:ice", lv20)
    put("roof:twotone", lv10); put("roof:box", lv20)
    put("decal:stripe", lv20); put("trail:ribbon", lv10); put("plate:gold", lv50)
}
fun unlockLevelOf(itemKey: String): Int = cosmeticUnlocks[itemKey] ?: 1
```
- 테스트 추가(`LevelTableTest`): ① `cosmeticUnlocks.values`가 모두 `anchors.map { it.level }` 안에 있다 ② `anchors` 리스트가 기존 6행과 동일(스냅샷). `cosmetic/LoadoutTest`: 잠긴 id → 기본값, 모르는 id → 기본값, 수집 수 Lv4 = 8, Lv5 = 13, Lv10 = 17.

### 3.4 데모 프로필 해금 상태
- 시드(Lv 4, 820 XP, `DemoSeed` 불변): 차종 5 + sky + steel + white = **8 / 24**, 차고 헤더 "수집 8 / 24".
- 트립 후(Lv 5, 1,120 XP): +색 5 = **13 / 24** → 요약 레벨업 카드 "꾸미기 5개가 열렸어요". `resetDemo(showcase)` 시드는 만들지 않는다(Lv5 해금만으로 충분).

### 3.5 Room: version 2, 추가형(파괴적 마이그레이션 금지, debug/release 동일)
```kotlin
@Entity(tableName = "loadout_slot", primaryKeys = ["vehicle", "slot"])
data class LoadoutSlotEntity(val vehicle: String, val slot: String, val itemId: String)

@Entity(tableName = "phone_link")
data class PhoneLinkEntity(@PrimaryKey val id: Int = 0, val linked: Boolean, val linkedAtMs: Long?, val label: String?)

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `loadout_slot` (`vehicle` TEXT NOT NULL, `slot` TEXT NOT NULL, `itemId` TEXT NOT NULL, PRIMARY KEY(`vehicle`, `slot`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `phone_link` (`id` INTEGER NOT NULL, `linked` INTEGER NOT NULL, `linkedAtMs` INTEGER, `label` TEXT, PRIMARY KEY(`id`))")
    }
}
// @Database(version = 2, exportSchema = true) → app/schemas/.../2.json 커밋 대상. builder에 .addMigrations(MIGRATION_1_2). fallbackToDestructiveMigration 호출 금지.
```
- 선택 차종은 기존 `cosmetic` 테이블의 `slot = "vehicle"` 행에 저장(테이블 추가 없이 호환). 기존 `slot = "color"` 행은 **폴백 체인**으로 계속 읽는다: `loadout_slot(vehicle, color)` → `cosmetic("color")` → 기본. 데이터 복사 마이그레이션 없음.
- `OdoDao` 추가: `loadoutSlotsFlow(vehicle): Flow<List<LoadoutSlotEntity>>`, `@Upsert setLoadoutSlot`, `phoneLinkFlow(): Flow<PhoneLinkEntity?>`, `@Upsert savePhoneLink`, `clearLoadout()`, `clearPhoneLink()`; `replaceAll`은 두 테이블도 비운다(데모 초기화 = 시드 상태).
- `OdoRepository` 추가: `val loadoutFlow: Flow<Loadout>`(선택 차종 + 그 차종의 슬롯들 결합), `suspend fun setVehicle(type)`, `suspend fun setSlot(vehicle, slot, itemId)`, `val phoneLinkFlow`, `suspend fun savePhoneLink(linked, linkedAtMs, label)`. 기존 `cosmetic(slot)/setCosmetic(slot,id)`는 그대로 둔다.
- 테스트: JVM `LoadoutTest`(위) + 가능하면 `androidTest/MigrationTest.kt`(room-testing `MigrationTestHelper`, 1.json→2.json; 에뮬레이터에서 1회 실행, 로그 파일로 저장).
- 트립 경로(LatLng 목록)는 **Room에 절대 넣지 않는다**(절대 규칙: 차량 신호 원본과 위치 궤적은 차 밖으로 내보내지 않는다). `DriveUi.tripPath`(메모리)만.

### 3.6 차고(꾸미기) 흐름
차종 탭에서 차종을 고르면 그 차종에 **기억된 슬롯**(loadout_slot)으로 미리보기가 바뀐다 → 색/휠/램프/루프/데칼/번호판 탭에서 칩 선택 → `setSlot(현재 차종, 슬롯, id)` → 미리보기 150 ms 크로스페이드(정차 중만). 잠긴 칩은 1dp `inkDim` 외곽선 + 안에 "Lv 10" 캡션, 누르면 힌트 행에 "Lv 10 로드메이트에서 열려요" 1문장(선택 안 됨, 흔들림·진동 없음). 숨긴 "?" 항목은 없고 24개 전부 보인다.

---

## 4. 드라이브 맵 장면(경로 주변 오프라인 지도)

### 4.1 데이터(빌드 전 1회, 런타임 네트워크 0)
- 출처: OpenStreetMap Overpass API 1회 조회(bbox `37.20,127.54,37.50,128.17`, `User-Agent: odo-redesign/1.0 (contest build tool)` 필수, 없으면 406). 도로 `highway=motorway|trunk|primary|secondary|tertiary(+_link)`, 물 `natural=water|waterway=river`, 숲 `landuse=forest|natural=wood`, 시가지 `landuse=residential|commercial|industrial|retail`, 휴게소 `highway=services|rest_area`.
- 가공: 시나리오 경로(`tools/scenario/route_yeoju_hoengseong.json`) ±4 km 복도로 클립 → Douglas-Peucker 10 m → 약 29.5k 점 → 로컬 미터 정수 좌표(`x=(lon−lon0)·111320·cos lat0`, `y=(lat−lat0)·111320`, 원점 = bbox 남서) → `app/src/main/assets/map/corridor.json` ≈ 0.41 MB(APK 압축 후 ≈150 KB).
- 형식:
```json
{"version":1,"origin":{"lat":37.20,"lng":127.54},"attribution":"© OpenStreetMap contributors, ODbL 1.0",
 "cell_m":1000,
 "polys":{"water":[[x,y,x,y,...]],"forest":[...],"urban":[...]},
 "roads":[{"cls":"motorway","pts":[x,y,...]},{"cls":"trunk",...},{"cls":"primary"},{"cls":"secondary"},{"cls":"tertiary"}],
 "route":[x,y,...],
 "pois":[{"type":"rest","x":..,"y":..,"src":"OSM way 344480538"}]}
```
- 스크립트 `tools/map/fetch_overpass.py`(원본 JSON은 저장소 밖에 저장, 저장소에는 넣지 않음) · `tools/map/build_corridor.py`(클립·단순화·정수화·검증: 점 수·바이트·bbox 출력) · `tools/map/README.md`(명령·소요·ODbL). NOTICE.md "지도 데이터" 절에 corridor.json 줄 추가.

### 4.2 렌더: `ui/map/`
```kotlin
data class MapCamera(val center: LatLng, val headingDeg: Float, val metersPerDp: Float, val tiltDeg: Float, val northUp: Boolean)
class CorridorData(json: String)                // 셀(1 km) 색인, 레이어별 Path(로컬 m) 1회 생성, 이후 변환만
@Composable fun CorridorMap(data: CorridorData, camera: MapCamera, speedKmh: Float, passed: List<LatLng>, trail: Boolean,
                            modifier: Modifier, content: DrawScope.() -> Unit = {})   // content = 미니카·점 등 오버레이
```
- 그리기 순서: `ground` 채움 → urban → forest → water → 도로 케이싱(`roadCasing`, 코어+2dp) → 도로 코어(`roadCore`; 굵기 motorway 9 / trunk 7 / primary 6 / secondary 4 / tertiary 2.5 dp, 명도는 같은 색 알파 1.0/0.9/0.8/0.7/0.6) → 고속도로 중앙 파선(`ink` 12%, 1dp, 24/36dp) → 지난 길 대시(`inkDim` 50% 3dp, 8dp 선/8dp 틈; trail=true면 페인트색 45% 6dp 리본, 도로 코어보다 어두운 색은 `ink` 45%) → 경로(`routeOutline` 12dp 아래 + `route` 8dp 위) → 휴게소 픽토(36dp `surface` 원 + 1dp `line` + 20dp 컵 벡터, 1종만) → 오버레이(content) → 수평선 스크림(상단 0–80dp `ground`→투명, heading-up만).
- 글자: **지도 위 글자 0**(도로명·방패·ETA·제한속도·차로·카메라·교통색 없음). 정차 north-up도 글자 0(노선 방패는 2차).
- 속도 ≥80 km/h: tertiary 숨김, 휴게소 픽토만 유지.
- 1 km 격자 컬링: 뷰포트(틸트 시 상단 확장 1.8배)와 겹치는 셀만. 3×3 km 뷰포트 점 중앙값 448(최대 1,308)이라 Canvas로 충분하다(실측).
- 모의 미니카 2대(레이더 유지): `inkDim` 10dp 점 + 1dp `ground` 링, 경로 위 앞 500 m / 뒤 300 m(글랜스에서 미니카와 겹치지 않는 거리), **3 s마다 위치 갱신·보간 없음**(주행 중 트윈 0). 80 km/h 이상에서도 점 그대로(이미 점).

### 4.3 시점
| 화면 | 모드 | metersPerDp | 틸트 | 중심 | 미니카 |
|---|---|---|---|---|---|
| 글랜스 | heading-up | 6 | 58° | 현재 위치, 미니카가 화면 (460, 356)dp = 지도 영역 가로 중앙·세로 65% | navi 128×160dp(× `GLANCE` 1 × `naviScale`), 기준점 (460, 356)dp, 회전 없음(항상 위) |
| 홈 | north-up | 20 | 0 | 현재(없으면 시나리오 시작점)를 띠 가운데에 둔다. 띠 = 56dp 아래부터 알림 줄·패널 위까지, 656×548에서 (328, 172)dp(알림 줄 있으면 150) | top 128×192dp(× `HOME` 1), 띠 가운데, `headingDeg` = 주행 heading → 경로 방위 → 0(북) |
| 요약 | north-up | 트립 경로 bbox를 패널에 안 가리는 띠(짧은 변 ≥208dp)에 56dp 여백으로 맞춤. 띠가 없으면(표준 창) 끝점 중심 정지 시점 + `ground` 64% 스크림 | 0 | 띠 가운데(띠가 없으면 끝점) | top × `SUMMARY` 0.8 끝점, `headingDeg` = 마지막 진행방향(띠가 없으면 경로·미니카 없음) |
| 업적·차고·폰 | north-up 정적 | 20 | 0 | 홈과 동일 + `ground` 64% 스크림 | 없음 |
- heading-up 회전: `rx = dx·cos h − dy·sin h, ry = dx·sin h + dy·cos h`(h = 진행방향, 북 기준 시계방향). Kotlin에서 셀 Path를 `Matrix`로 변환. 진행방향 저역통과 α 0.3, 3 m 미만 이동은 방향 갱신 안 함.
- 틸트: `nativeCanvas.concat(Matrix().apply { setPolyToPoly(src=뷰포트 4모서리, dst=사다리꼴(상변 폭 45%, 하변 폭 100%, 하변 y 그대로)) })`. 틸트 각 58°는 상변 폭 비율로 근사(시험 미리보기와 비교해 0.40–0.50 안에서 조정). 선 굵기는 행렬에 따라 멀수록 얇아짐(의도).
- 성능 목표(에뮬레이터 swiftshader): 글랜스 프레임 **중앙값 ≤16 ms, 90p ≤33 ms** (`adb shell dumpsys gfxinfo <패키지> framestats`, 파일로 저장 후 grep). 앱 내 `FrameBudget`(창의 `FrameMetrics` `TOTAL_DURATION`, 실제로 그린 프레임만, RenderThread·GPU 대기 포함, 창 배치 뒤 첫 프레임 제외)이 2 s 창 중앙값 >50 ms(<20 fps)이면 그 트립 동안 `tiltDeg = 0`으로 폴백. 1 s 넘게 그린 프레임이 없으면 창을 비우고 다시 2 s를 본다. 재구성은 `camera`가 바뀔 때만(`derivedStateOf`), 셀 Path 캐시, `drawIntoCanvas` 1회.

### 4.4 위치·진행방향 배선(실제 코드 경로 확인 결과)
- 신호 경로: `ScenarioSignalSource.snapshots`(샘플 1초 간격·20배속 → 실제 50 ms마다 1장) → `DriveController.onSnapshot(s: VehicleSnapshot)` → `session.onSample(s)`. 샘플에 `lat/lng`가 있어 `s.location: LatLng?`로 들어온다(`Scenario.parse`). 실차는 `LiveSignalSource`가 `PleosLocationSource`를 합쳐 같은 필드로 준다. **엔진은 손대지 않고** 컨트롤러가 UI 상태에 실어 준다.
- `DriveUi` 추가 필드: `position: LatLng? = null`, `headingDeg: Float? = null`, `speedKmh: Float = 0f`, `tripPath: List<LatLng> = emptyList()`.
- 새 파일 `drive/HeadingTracker.kt`(순수 Kotlin, 테스트 1개): `update(s): Unit`, `position`, `headingDeg`(α 0.3, 각도 랩 처리, 3 m 미만 무시), `path`(50 m 간격 데시메이션, 최대 2,000점), `reset()`. `DriveController.onSnapshot`에서 `tracker.update(s)`를 `session.onSample(s)` 뒤에 호출, `EngineEvent.TripStarted`에서 `tracker.reset()`; `publish()`가 네 필드를 복사. `resetDemo()`에서도 `reset()`.
- 위치가 null이거나 경로 주변 지도 bbox(+2 km) 밖: 글랜스 지도 영역은 `ground` + 1 km 격자(`line` 1dp) + 미니카(알파 1.0)만, 글자 없음(계기 모드).

### 4.5 국내 내비 장면 참고표 ("문법만 참고, 값·그림 복제 금지")
| 레퍼런스 | 따르는 것(문법) | 피하는 것(복제·금지) |
|---|---|---|
| 국내 내비 앱의 주행 화면 | 어두운 바탕에서 도로를 명도 위계로만 구분, 글자 적은 주행 화면 | 경로선 색값·속도 카메라·제한속도 표지·ETA 바 |
| 주야 자동 전환을 하는 국내 내비 앱 | 주/야 자동 전환, 정차 시 지도 글자 허용 | 교통 혼잡 3색, 차로 안내 패널, 회전 화살표 |
| 국내 지도 앱의 경로 표시 | "지나온 구간/남은 구간" 두 색 문법 | 파랑 버튼·마커 그림자·라벨 밀도 |
| 국내 내비 앱의 고속도로 표현 | 고속도로 중앙선 파선 리듬 | 3D 건물·실사 교차로 그림 |
| 차량 순정 내비(미확인) | 차분한 저채도 바탕, 정보 밀도 낮음 | 색·시점 수치 복제(확인된 값 없음) |
| 해외 지도 앱의 조명 모드 | 시각 기반 조명 전환, 지도=환경이라는 태도 | ETA 필·추천 핀·광고 |
| 폰 연동 차량 화면의 지도 | 주행 중 크롬 최소, 지도 글자 ≥24 | 플랫폼 UI 흉내 |
| 도로표지규칙 제8조(노선 방패 청바탕 백글자) | 2차에 정차 화면 방패 1종(정적) | 주행 중 표시, 번호 외 글자 |

---

## 5. 화면별 최종 레이아웃 (656×548 dp, 1dp = 1px)

공통: 바깥 여백 32, 패널 1장/화면, 패널 안에 패널 없음, KeyStrip(§5.0)은 셀 76dp 높이(캡션 줄이 있으면 92dp) + 1dp 구분선, 아이콘 32 + 라벨 24sp(+ 캡션 20sp 1줄). `font_scale 1.3`에서도 1줄 유지.

### 5.0 공용 컴포넌트: `ui/components/`
- `OdoPanel(shape, modifier, content)`: `surface` 채움 + 1dp `line` 테두리, 그림자 0.
- `KeyStrip(keys: List<Key>)`: 가로 등분 셀, 셀 사이 1dp `line`, 셀 = 아이콘 32 위 + 라벨 24 + 캡션 20(선택), 눌림 = `ink` 8% 채움(리플 끔), 선택 = 하단 2dp `amber`. 차량 하드키 열처럼 읽힌다(필 버튼·텍스트 버튼 행 금지).
- `Gauge(progress, thickness, trackColor = track, fillColor = amber, endIcon: (@Composable)? = null)`: 트윈 없음(값이 바뀌면 즉시).
- `Medal(unlocked: Boolean, tier: Int = 0)`: 36dp 벡터, 열림 = `amber` 채움 + `onAmber` 1dp 안쪽 선, 잠김 = 1dp `inkDim` 외곽선, tier 1·2·3 = 밑 4dp 점 1–3개(거리 업적 10/100/1000 km).
- `Chip(label, selected, locked, swatch: Color?)`: 76dp 높이·최소 폭 120, 선택 = 2dp `amber` 테두리, 잠김 = 1dp `inkDim` 외곽선 + "Lv N" 캡션, 간격 23.
- `NoticeLine(text, onDismiss)`: 지도 하단 1줄 40dp, `surface` + 1dp `line`, 본문 24, 6 s 뒤 자동 해제·탭으로 즉시 해제. **홈에서만** 그린다. 요약에서는 업적 줄로 대신한다(업적·차고·폰 화면에서 겹치던 버그 수정).
- `DemoChip(text, onLongPress)`: fake 빌드 전용, `ground` 60% 바탕 + 1dp `line` 외곽선, 캡션 20, 36dp 높이, 글랜스에는 없음. `onLongPress`가 있으면 700 ms 길게 누르기에만 반응한다(주야 강제 전환, 캡처용).

### 5.1 홈(정차)
```
0                                                                          656
┌──────────────────────────────────────────────────────────────────────────┐ 0
│ [ODbL 출처 캡션, 좌상]   지도 north-up(20 m/dp)              [데모 20×] │ 24 (칩 우상, fake만)
│ ─ ─ ─ ─ ─ ─ ─ ─ ─ 지도 띠 위 끝 (HOME_BAND_TOP) ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ │ 56
│                                                                          │
│                         ▲ OdoVehicle(loadout, Top)                       │ 172 (띠 56–288 가운데,
│                           128×192dp, 현재 위치, 진행방향 회전             │      알림 줄 있으면 150)
│                                                                          │
│ ┌ NoticeLine 592×40 (있을 때만, 패널 위 4dp) ───────────────────────────┐│ 244–284
├─┴─ OdoPanel 656×260, 상단 1dp line, 모서리 0 ─────────────────────────┴┤ 288
│  이번 주 안전 주행 (body 24, inkDim) 42 (numeralM 48) km (title 28) │ Lv 4 (numeralM 48)│ 300–352
│  오늘 (body 24)          0 km (body 24, 우측 정렬)          │ 시동 (title 28, inkDim)│ 356–388 (오른쪽 352–388)
│  어제                   42 km                1일째예요(캡션) │ 다음 Lv 5까지 180 XP   │ 388–420
│  ▬▬▬▬▬▬▬▬▬░░░░░░░░ 4dp 게이지                              │ ▬▬▬░░░ 4dp XP 게이지   │ 420–424
│  다음 업적 안전 100 km까지 58 km (캡션 20)                  │                        │ 424–450
│ ─────────────────────────────────────────────────────────────────────────│ 455 (1dp)
│ │ 업적 3/12 │ 차고 8/24 │ 휴대폰 연결 안 됨 │ 데모 재생 │ 초기화 │ KeyStrip 92│ 456–548
└──────────────────────────────────────────────────────────────────────────┘
왼쪽 열 x 32–368, 오른쪽 열 x 400–624(폭 224). 행 격자 52 / 4 / 32 / 32 (왼쪽) · 52 / 36 / 32 (오른쪽) → 두 게이지가 같은 y 420.
KeyStrip은 셀마다 캡션 줄이 있어 92dp다(76dp에 아이콘·라벨·캡션 세 줄이 안 들어간다). 패널 = 통계 167 + 헤어라인 1 + KeyStrip 92 = 260.
```
- 한 화면 한 박자: "이번 주 안전 주행 42 km"(시연 대본의 홈 장면 숫자 유지). 비교 대상은 어제의 나(오늘/어제 두 줄). 스트릭은 "1일째예요" 캡션을 '어제' 줄 끝에(끊기면 "다시 1일째예요", 한 번도 시작하지 않은 새 프로필에는 캡션 없음, 위협 문구 없음).
- 데모 재생 중: "데모 재생" 셀 라벨이 "재생 중"으로 바뀌고 비활성(ink 56%), 필 버튼 아님. 데모 재생이 끝나면 복귀.
- 상태 변형: 트립 후(Lv 5, 이번 주 107 km, 오늘 65 km, 업적 5/12, 차고 13/24) · 라이브 빌드(데모 셀 2개 없음 → 셀 3개) · 위치 없음(지도 = ground + 격자) · 주간(토큰만 바뀜).
- 유지하는 기능(기존 화면 ↔ 새 위치): 미니카 → 지도 위 Top(현재 위치, 진행방향) / 레벨·칭호·XP 진행 → 우측 열 / 이번 주 안전 km → 1박자 / 업적 수 → KeyStrip 캡션 / 스트릭 → 캡션 / 데모 재생·초기화 → KeyStrip / `SimBadge` → `DemoChip`.

### 5.2 글랜스(주행, 잠금): `DriveLockOverlay` 최상위 유지
```
0        264                                                             656
┌────────┬─────────────────────────────────────────────────────────────────┐ 0
│        │ 수평선 스크림 0–80                                             │
│ ┌──────┐ ← OdoPanel 264×500, shape 0/16/16/0, 상·우·하 1dp line, alpha 1.0│ 24
│ │안전 주행│ label 32, inkDim, (24, 56)                                    │
│ │      │                      ·  (모의 미니카 점 10dp)                   │
│ │      │                                                                 │
│ │    35│ numeralL 88, ink96, 우측 정렬(패널 안쪽 x=240), 베이스라인 y≈300 │
│ │km    │ label 32, inkDim, 좌측, 숫자 베이스라인에 맞춤                  │
│ │      │               ┃ route 8dp + outline                             │
│ │      │                      ▲ navi 128×160dp, 기준점 (460, 356)        │
│ │▬▬▬▬░░ ◎│ 12dp 게이지 x 24–196, 메달 외곽선 36 @ 204–240, y 404–416     │
│ └──────┘                                                                 │ 524
└────────┴─────────────────────────────────────────────────────────────────┘ 548
```
- 글자는 **라벨·숫자·단위 3개뿐**(32/88/32). "데모", 도로명, "다음 업적 …", "열 수 있는 업적을 모두 열었어요" 전부 제거. 숫자 = `ui.todaySafeKm`(Int, 소수점 없음). 게이지 = `ui.glance`: 엔진 `nextGoal`(진행 중 도로 업적, 없으면 가장 가까운 누적 거리 업적)을 `GlanceGaugeLatch`로 거른 값. 주행 중에는 이번 주행에서 보인 값보다 내려가지 않고(목표가 바뀌어도, 도로 업적 직선거리 진행률이 굽은 길에서 줄어도), 보이던 목표가 주행 중 열리면 정차까지 꽉 찬 채로 둔다. 메달은 주행 중 새로 채우지 않는다(열림 소식은 정차 후 요약, P1). 정차하면 엔진 값 그대로: `goal?.progress ?: 1f`, 메달은 `goal == null`이면 채움.
- 트윈 0: 숫자·게이지·지도 회전·점 이동 모두 즉시 반영. 진입 전환 0 ms.
- 상태 변형: 경로 주변 지도 밖/위치 없음(계기 모드 §4.4) · 폴백 틸트 0 · 주간 토큰은 **정차 때 정해진 값 유지**.
- 유지: 숫자 1·게이지 1·레이더(모의 미니카 2대) ↔ 1차 글랜스 화면 요구. `RadarRing.kt`·`LaneGauge.kt`·`SimBadge.kt`는 삭제(대체: 지도 점·Gauge·DemoChip).

### 5.3 트립 요약(정차 직후): 3박자
```
┌─ 지도 north-up(띠가 있으면 경로 bbox 맞춤 + 스티치 + 끝점 Top 0.8×, 표준 창은 끝점 중심 + 스크림, §4.3) ┐
│ ┌ OdoPanel 592×484 @ (32,32) ────────┬──────────────────────────────────┐ │
│ │ 트립 요약 (title 28)       좌 272  │ 우 288                            │ │
│ │ 안전 주행              65 km (48) │ [박자 2] 레벨업 카드(1dp amber 프레임)│ │
│ │ XP                    +300 (48)   │  Lv 5 (numeralM) 초보 딱지 뗌 (28) │ │
│ │ 레벨               Lv 4 → 5 (24) │  ● ● ● ● ● 꾸미기 5개가 열렸어요   │ │
│ │ 1 km 구간 1개 제외 (캡션 20)      │ [박자 3] ◎ 영동고속도로 첫 주행 (24) │ │
│ │ 급감속 1 (캡션 20, 중립)          │       여주→횡성을 달렸어요 (20)     │ │
│ │ 오늘 상한 300 XP를 채웠어요 (20)  │     ◎ 강원도 첫 방문 (later 있을 때) │ │
│ │                                   │ ─────────────────────────────────  │ │
│ │ 주행 n km (캡션 20, 맨 아래)      │ │ 홈 (KeyStrip 1셀, 76)            │ │
│ └───────────────────────────────────┴──────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────┘
```
- 박자: 0 ms 숫자(즉시) → 600 ms 레벨업 카드 scale 0.96→1.0 + alpha 450 ms `CubicBezierEasing(0.16f, 1f, 0.3f, 1f)`(이 앱 유일의 연출) → 1,500 ms 업적 행들 **동시** 150 ms 페이드(스태거 없음). 탭 1회 = 끝 상태로 건너뜀(이어콘은 원래 박자 시각에 낸다). 레벨업 없으면 박자 2 생략(카드 자리에 "다음 Lv 6까지 480 XP" 캡션 + 4dp 게이지). 이어콘(§8) 박자마다 1회.
- 숫자는 뒤로 가지 않는다: "XP에서 뺐어요" 금지 → "1 km 구간 1개 제외" + "급감속 1"(중립, 캡션). 빨강 없음.
- 좌 열 캡션(위에서부터): "1 km 구간 n개 제외"(있을 때) · 위험 이벤트 줄(종류별 "이름 n"을 " · "로 잇는다. 아래 캡션이 있으면 1줄, 없으면 2줄까지) · 상한·연속 기록 보너스 캡션 1줄(상한만 "오늘 상한 300 XP를 채웠어요", 보너스만 "연속 기록 보너스 +N XP 포함", 둘 다 "상한 300 XP + 연속 보너스 N XP". 글꼴 1.3배에서 넘치면 짧은 꼴) · 맨 아래 "주행 n km".
- 레벨업 카드: 1dp `amber` 테두리 한 겹. "Lv N" + 칭호(넘치면 둘째 줄), 그 아래 열린 꾸미기를 사물로 늘어놓고(색 = 페인트 점, 휠·램프·루프·번호판 = 차고 글리프 28dp, 넘치면 줄바꿈, 이름 목록은 `contentDescription`으로) + "꾸미기 N개가 열렸어요"(1개면 "{이름}이/가 열렸어요"). 시연 트립(Lv 4 → 5)은 색 5개.
- 지도 위 트립 경로는 `ui.tripPath`(메모리). 요약 닫기(`closeSummary()` + `dismissNotices()`)는 홈 셀 1개뿐.
- 유지: `TripSummary` 전 필드(distanceKm은 좌 열 맨 아래 캡션 "주행 n km"), `later`(정차 중 업적) 행.

### 5.4 업적(진열장)
```
┌ 지도 정적 + 스크림 ┐
│ ┌ OdoPanel 592×484 ─────────────────────────────────────────────────────┐
│ │ 업적 (28)   3 / 12 (캡션)                               │ 홈 (76×120) │ 헤더 72
│ │ ◎ 첫 트립            첫 주행을 마쳤어요    │ ○ 밤길 10 km        조건 1줄 │ 행 64
│ │ ◎ 안전 10 km ·       10 km를 쌓았어요      │ ○ …                          │
│ │ ◎ 여주휴게소          …                    │ ○ …                          │
│ │ ○ 영동고속도로 첫 주행 조건 1줄             │ ○ …                          │
│ │ ○ 강원도 첫 방문      …                    │ ○ …                          │
│ │ ○ …                                         │ ○ …                          │
│ └───────────────────────────────────────────────────────────────────────┘
```
- 2열 × 6행 구분선 행(격자 카드 아님), 12개 **전부 보임**(스크롤 없음), 카탈로그 순서 고정(`achievements.json` 순). 행 = Medal 36 + 제목 24(잠김은 ink 56%) + 캡션 20 1줄(열린 업적은 `description` 그대로 "10 km를 쌓았어요", 잠긴 업적은 `condition` + 진행도 "107 / 300 km", 숨김 "?" 없음). 거리 업적은 tier 점 1·2·3.
- 상태: 해금 직후(요약에서 돌아오면 새 메달도 그냥 채움, 연출은 요약에서 끝나 여기는 없음) · 전부 해금.

### 5.5 차고(꾸미기 = 컨피규레이터)
```
┌ 지도 정적 + 스크림 ┐
│ ┌ OdoPanel 592×484 ─────────────────────────────────────────────────────┐
│ │ 차고 (28)   수집 8 / 24 (캡션)                          │ 홈 (76×120) │ 72
│ │ │ 차종 │ 색 │ 휠 │ 램프 │ 루프 │ 데칼 │ 번호판 │  KeyStrip 탭 76       │ 72–148
│ │ ┌ hero 228×152 ┐  항목 열 약 316dp(76 높이, 간격 23, 탭마다 배치)       │
│ │ │  미리보기     │  [하늘●] [체리● Lv 5] [민트● Lv 5]                    │ 164–240
│ │ │              │  [라벤더● Lv 5] [귤● Lv 5] [펄 화이트● Lv 5]          │ 263–339
│ │ └──────────────┘  [그래파이트● Lv 30] [샌드● Lv 30]                   │ 362–438
│ │ 소형 · 하늘 · 스틸 · 화이트 · 루프 없음                                │
│ │ (사양 줄, 캡션 20, 미리보기 아래. 잠긴 칸을 누르면 3 s 동안 힌트 1문장: │
│ │  Lv 5 초보 딱지 뗌에서 열려요)                                          │
│ └───────────────────────────────────────────────────────────────────────┘
```
- 미리보기는 hero **228×152dp**다. hero 288×192 옆에 120dp 칩 3열(3×120 + 2×23 = 406)을 두면 패널 안쪽 560dp를 넘는다(288 + 16 + 406 > 560). 그래서 미리보기를 줄이고, 남은 항목 열(약 316dp)에는 120dp보다 좁은 칸을 쓰며 배치를 탭마다 바꾼다.
- 탭 = KeyStrip(선택 셀 하단 2dp amber). 차종 탭 실루엣 칩 5개 2열(실루엣 48dp 벡터 + 라벨). 색 탭 이름표가 붙은 페인트 칸 8개 3열. 휠·램프·루프·번호판은 썸네일이 붙은 1열 칩. 데칼 탭은 데칼·트레일 두 묶음(그림 칸 3열). 글꼴 1.3에서 넘치면 항목 열만 세로로 스크롤한다. 고정 6행 레이아웃 금지.
- 선택 변경 → `repo.setVehicle()` / `repo.setSlot(vehicle, slot, id)` → hero 150 ms 크로스페이드. 잠긴 칩 탭 → 힌트 문장만.
- 상태: Lv 4(색 5종 잠김) · Lv 5(열림, 요약 후 돌아왔을 때) · 2륜 선택(루프 탭 라벨 "탑케이스"로) · 주간.

### 5.6 휴대폰 연결(정차 서브 화면)
```
┌ 지도 정적 + 스크림 ┐
│ ┌ OdoPanel 592×484 ─────────────────────────────────────────────────────┐
│ │ 휴대폰 연결 (28)                                        │ 홈 (76×120) │
│ │ ┌ QR 판 272×272 (#D0D4DA, r 16) ┐  WDJB-MJHT (numeralM 48, tnum)        │
│ │ │  QR 240×240 어두운 모듈        │  휴대폰 카메라로 QR을 찍어 주세요 (24)  │
│ │ │  여백 16                       │  또는 폰 앱에 코드를 넣어 주세요 (24)   │
│ │ └───────────────────────────────┘  공유함: 트립 요약·업적·미니카 꾸미기 (20)│
│ │                                    공유 안 함: 차량 신호 원본·위치 기록 (20)│
│ │ 폰 앱은 준비 중이에요 (캡션 20, inkDim)                                 │
│ └───────────────────────────────────────────────────────────────────────┘
```
- 상태 변형: `Presenting`(위) · `Confirmed` "휴대폰에서 확인했어요"(QR 자리에 36dp 체크 픽토, 1.5 s 뒤 Linked) · `Linked` "연결됨 · 11월 9일" + 공유 범위 2줄 + KeyStrip "끊기"(QR 판 자리에는 틀 없이 내 미니카 hero 272×181dp: 판 폭 272, 3:2, 앰버 없음) → 같은 자리에서 "연결을 끊을까요" + [끊기][취소] 인라인 2단계(모달 없음) · `Failed` "아직 연결되지 않았어요. 코드를 다시 보여 드릴게요"(빨강 없음) · 120 s 만료는 **표시 없이** 새 코드로 조용히 교체(카운트다운 금지).
- 잠금(`ui.locked`) 시 라우팅이 글랜스로 바꾸므로 자동 닫힘; 돌아오면 홈(코드 재발급). 홈 KeyStrip "휴대폰" 캡션 = "연결 안 됨 / 연결됨".

### 5.7 라우팅(`OdoRoot`) 변경
`locked → Glance` · `summary != null → Summary` · `ACHIEVEMENTS / GARAGE / PHONE → 각 화면` · `else → Home`. `Screen` enum에 `PHONE` 추가, `CUSTOMIZE`는 `GARAGE`로 개명(파일명도). `NoticeLine`은 홈(`route == HOME`)에서만 그린다. 요약에서는 업적 줄로 대신한다. `OdoTheme(night)`가 루트에서 감싸고, `DriveLockOverlay()`는 그대로 최상위.

---

## 6. 게이미피케이션 단계 배치 (원칙 P1–P10 ↔ 화면)
| 화면/순간 | 보이는 것 | 원칙 |
|---|---|---|
| 글랜스(주행) | 축적만: 오늘 안전 km 숫자, 메달을 향한 게이지. 보상·알림·점수 0 | P1 보상은 정차 후, P3 숫자는 뒤로 안 감 |
| 요약 박자 1 | 안전 km · +XP · Lv 전후 (즉시) | P5 한 화면 한 박자(요약은 3박자) |
| 요약 박자 2 | 레벨업 카드 1장 450 ms + 이어콘 "톡-톡" | P6 모션 1개·소리 1가족 |
| 요약 박자 3 | 열린 업적 메달 행 ≤3 동시 페이드 + 높은 "톡" | P7 잠긴 것도 보이게·조건 1줄 |
| 홈 | 이번 주 vs 오늘/어제, 다음 거리 업적까지 km, 다음 Lv까지 XP | P4 비교 대상은 어제의 나, P5 1박자 |
| 업적 | 12개 진열(채움/외곽선 메달, 段 1·2·3) | P7, P8 성장은 사물로(얼굴 캐릭터 없음) |
| 차고 | 수집 n / 24, 잠긴 칩에 Lv 표기, 차종 5종 Lv1 | P7, P9 컨피규레이터 칩 문법만 차용 |
| NoticeLine(정차) | "강원도 첫 방문 업적을 열었어요" 1줄 6 s | P1, P5 |
| 폰 연결 | 차는 요약·업적·꾸미기만 공유, 폰 없어도 차는 완결 | P10 차가 폰을 기다리지 않음 |
| 금지 | 룰렛·한정시간·스트릭 위협·"?" 숨김·전체 순위·감점 문구 | P2 화이트햇, 절대 규칙(시간 압박 금지·XP는 깎이지 않음·전체 순위표 없음) |

---

## 7. 폰 연동

### 7.1 흐름(ASCII)
```
[홈 KeyStrip 휴대폰] --잠금이 풀린 정차 중에만 진입--> [PhoneLink: Idle→startPairing()]
      │                                           │ Presenting(code, qrPayload, expiresAt)
      │                                           │  QR 240 + 자음 코드 4-4, 공유 범위 2줄
      │                              120 s 경과 → 조용히 재발급(표시 없음)
      │                                           │ (Fake: 3 s 뒤) Confirmed "휴대폰에서 확인했어요"
      │                                           ▼
      │                                     Linked(label="휴대폰", linkedAt) → Room phone_link 저장
      │                                           │ KeyStrip "끊기" → 인라인 2단계 → disconnect() → Idle, "연결을 끊었어요"
      └── 기어 D/R·속도>0·트립 중 N 정차 → locked → 라우팅이 글랜스로(자동 닫힘, cancelPairing()) → 정차 후 홈 "정차 후 다시 연결할 수 있어요"
```
### 7.2 인터페이스: `platform/PhoneLink.kt` + `platform/fake/FakePhoneLink.kt` (1차는 Fake만, SDK 없음)
```kotlin
sealed interface PhoneLinkState {
    data object Idle : PhoneLinkState
    data class Presenting(val code: String, val qrPayload: String, val expiresAtMs: Long) : PhoneLinkState
    data object Confirmed : PhoneLinkState
    data class Linked(val label: String, val linkedAtMs: Long) : PhoneLinkState
    data class Failed(val message: String) : PhoneLinkState
}
interface PhoneLink {
    val state: StateFlow<PhoneLinkState>
    fun startPairing(); fun cancelPairing(); fun disconnect()
}
class FakePhoneLink(scope: CoroutineScope, repo: OdoRepository, clock: () -> Long = System::currentTimeMillis) : PhoneLink
```
- 페이로드 `odo-link:v1:<base32(SecureRandom 16B)>`(URL 아님, 토큰 외 아무 정보 없음, 저장 안 함). 코드 RFC 8628식 자음 `BCDFGHJKLMNPQRSTVWXZ` 4-4(데모 고정값 아님, 매번 랜덤; 캡처 예시 WDJB-MJHT). QR은 `com.google.zxing:core`(Apache-2.0, README 표기) `QRCodeWriter` → `ImageBitmap`(런타임 네트워크 0). Room에는 `linked/linkedAtMs/label`만. 2차: Companion Device Manager + BLE.
- 공유 범위 문구는 3곳(연결 전·연결 후·NOTICE/README) 동일: "공유함: 트립 요약·업적·미니카 꾸미기 / 공유 안 함: 차량 신호 원본·위치 기록".

### 7.3 폰 쪽 목업 (≥5장, 발표·README용 PNG; 앱 번들 아님)
- 도구: `tools/phone-mockup/render.py`(Pillow, `python3 -I` 실행, 의존 Pillow만) + `tools/phone-mockup/fetch_fonts.sh`(Noto Sans KR OFL + Roboto Apache-2.0을 `tools/phone-mockup/fonts/`에 내려받음, 폴더는 `.gitignore`, 라이선스 링크는 `tools/phone-mockup/README.md`). 입력은 `tools/phone-mockup/screens.json`(문구·숫자), 출력 `docs/phone-mockup/phone_01_link.png … phone_05_garage.png` + `manifest.json`(크기·생성 커맨드) + `README.md`.
- 캔버스 390×844 dp @3× = **1170×2532 px**, 제네릭 프레임(둥근 모서리 r 48dp, 펀치홀 1개, 베젤 12dp, 브랜드 없음), 폰 토큰은 차 토큰과 같은 색, 글자 척도 caption 13 / body 16 / title 20 / headline 28 / numeral 44·64 sp, 터치 48dp. 폰에만 3열 격자 허용(업적). 미니카는 같은 hero 레이어를 앱과 같은 규칙(`compose.json` v2의 `order`·`blend`·`skip`·`night`, 루프·데칼 색 규칙)으로 합성(에셋 공유 → 일관성). 폰 화면에는 지도 위 미니카가 없어 hero 뷰만 쓴다.
```
01 연결                 02 연결됨 홈            03 트립 요약             04 업적 컬렉션          05 차고 미리보기
┌─────────────┐        ┌─────────────┐        ┌─────────────┐        ┌─────────────┐        ┌─────────────┐
│ 오도와 연결  │        │ Lv 5 초보 딱지 뗌│     │ 11월 9일 트립 │        │ 업적 5 / 12  │        │ 차고 13 / 24 │
│ [카메라 뷰]  │        │ [hero 미니카] │        │ 안전 65 km   │        │ ◎ ◎ ◎        │        │ [hero 미니카] │
│ QR을 비추세요│        │ 이번 주 107 km│        │ +300 XP      │        │ ◎ ◎ ○        │        │ 소형 · 체리  │
│ 또는 코드 입력│       │ 오늘 65 · 어제 42│    │ Lv 4 → 5     │        │ ○ ○ ○        │        │ 색 ● ● ● ●  │
│ [WDJB][MJHT] │        │ 다음 Lv 6까지 480│    │ ◎ 영동고속도로│        │ ○ ○ ○        │        │ 휠 ○ ○ ○    │
│ 공유함/안 함 │        │ 공유함/안 함  │        │ 1 km 구간 1개 제외│    │ 조건 1줄씩   │        │ 차에서 바꿀 수 있어요│
│ [연결하기]   │        │ [트립][업적][차고]│    │ [공유 카드 저장]│      │              │        │ (읽기 전용)   │
└─────────────┘        └─────────────┘        └─────────────┘        └─────────────┘        └─────────────┘
```
- 03의 "공유 카드"는 1080×1080 PNG 1장 추가(`phone_03b_share_card.png`, 숫자·메달·미니카만, 위치·지도 없음).

---

## 8. 모션·소리
| 상황 | 값 |
|---|---|
| 주행 중(글랜스 진입·숫자·게이지·지도 회전·점 이동·주야) | **0 ms**(즉시). `rememberInfiniteTransition` 금지, 스태거 금지 |
| 잠금 ↔ 해제 전환 | 0 ms |
| 정차 화면 전환(홈↔업적/차고/폰) | 크로스페이드 200 ms, `CubicBezierEasing(0.16f, 1f, 0.3f, 1f)` |
| 차고 미리보기 교체·칩 선택 | 150 ms 크로스페이드 |
| 요약 레벨업 카드(유일한 연출) | scale 0.96→1.0 + alpha 0→1, 450 ms, 같은 이징, 1회 |
| 요약 업적 행 | 150 ms 페이드, 동시 |
| NoticeLine | 페이드 150 ms, 6 s 뒤 해제 |
| `Settings.Global.ANIMATOR_DURATION_SCALE == 0` | 위 모든 값 0 |
| 이어콘(정차·요약만) | `tools/audio/make_earcons.py`(사인+감쇠 합성, 나무토막 질감, OGG, 자체 생성) → `res/raw/earcon_xp.ogg`("톡"), `earcon_levelup.ogg`("톡-톡"), `earcon_achievement.ogg`(높은 "톡"), `USAGE_ASSISTANCE_SONIFICATION`, 요약 박자마다 1회. 주행 중 0. TTS 정책(10분 2회 1문장) 변경 없음 |

---

## 9. 카피 (해요체 1문장, "!"·이모지 없음, 숫자·단위 공백)
| 위치 | 기존 | 변경 |
|---|---|---|
| 글랜스 라벨 | 오늘 안전 주행 (22sp) | **안전 주행** (32sp) |
| 글랜스 | 다음 업적  {title} / 열 수 있는 업적을 모두 열었어요 | (삭제, 메달 아이콘으로 대신) |
| 글랜스 | SimBadge "시뮬레이션" | (삭제) |
| 홈 1박자 | 이번 주 안전 N km | 이번 주 안전 주행 / N km |
| 홈 | 재생 중(필) | 재생 중(KeyStrip 비활성 라벨) |
| 홈 레벨 | 레벨 4 · 시동 | Lv 4 시동 / 다음 Lv 5까지 180 XP |
| 홈 스트릭 | 연속 N일 | N일째예요 / 다시 1일째예요 |
| 홈 게이지 캡션 | (없음) | 다음 업적 안전 100 km까지 58 km |
| 요약 | XP에서 뺐어요 | **1 km 구간 1개 제외** + 급감속 1 |
| 요약 레벨업 | 레벨 5 | Lv 5 · 초보 딱지 뗌 / 꾸미기 5개가 열렸어요 |
| 요약 상한 | (없음) | 오늘 상한 300 XP를 채웠어요 / 연속 기록 보너스 +N XP 포함 / 상한 300 XP + 연속 보너스 N XP |
| 차고 제목 | 미니카 색 | 차고 · 수집 8 / 24 |
| 차고 잠김 | Lv 5에 열려요 / 안전 주행으로 레벨 5이 되면 5색이 열려요 | 칩 안 "Lv 5" / 힌트 "Lv 5 초보 딱지 뗌에서 열려요" |
| 차고 spec | (없음) | 소형 · 하늘 · 스틸 · 화이트 · 루프 없음 |
| 배너 | 업적을 열었어요 / {titles} / 확인 | {title} 업적을 열었어요 (1줄, 탭 해제) |
| 뒤로 | 홈으로 | 홈 |
| 폰 | (없음) | 휴대폰 연결 · 휴대폰 카메라로 QR을 찍어 주세요 · 또는 폰 앱에 코드를 넣어 주세요 · 휴대폰에서 확인했어요 · 연결됨 · {날짜} · 연결을 끊을까요 · 끊기 · 취소 · 연결을 끊었어요 · 아직 연결되지 않았어요. 코드를 다시 보여 드릴게요 · 정차 후 다시 연결할 수 있어요 · 폰 앱은 준비 중이에요 · 공유함: 트립 요약·업적·미니카 꾸미기 · 공유 안 함: 차량 신호 원본·위치 기록 |
| TTS | (변경 없음) | 안전 주행 N km, 오늘 상한을 채웠어요. / {title} 업적을 열었어요. |
- 조사는 하드코딩하지 않고 `ui/text/Josa.kt`의 `josa(word, "이/가")`로(받침 판정). 모든 문자열은 `res/values/strings.xml`로 옮긴다.

---

## 10. 접근성
- 터치 76×76dp, 간격 23dp(KeyStrip·칩·탭 전부). 글자 최소 20sp(정차), 32sp(주행). `font_scale 1.3` 캡처에서 줄바꿈·겹침 0.
- 대비 §1.4(글자 ≥4.5, 비글자 ≥3). 색만으로 상태를 전하지 않음(잠김 = 외곽선 + "Lv N" 글자, 선택 = 2dp 테두리 + 체크 픽토 없음·굵기 차).
- `contentDescription`: 메달("안전 10 km, 열림"), KeyStrip 셀, 칩("체리, Lv 5에 열려요"), 미니카("미니카 미리보기, 소형 하늘"), QR("연결 QR 코드"). 장식(격자·스티치·점)은 `semantics { invisibleToUser() }` 대신 `Canvas`로 그려 트리에서 제외.
- 깜빡임·점멸 0, 자동 스크롤 0, 시간 제한 0(만료 재발급은 무표시).
- 애니메이션 배율 0 존중(§8). 주야 모두 negative polarity(검정에서 쌓기).

---

## 11. 하지 말 것(do-not)
그라데이션·그림자·글로우·비네트(UI. 미니카에 구운 접지 그림자·밤 글로·붉은 후미등은 콘텐츠라 예외, §2.3) · 둥근 아이콘 타일 · 카드 속 카드 · 필 버튼·텍스트 버튼 행 · 파란 버튼 · 빨강/초록/노랑(UI) · 이모지·유니코드 글리프 아이콘 · 지도 위 글자(주행·정차 모두) · 회전 화살표·ETA·제한속도·차로·카메라·교통색 · 주행 중 "데모"·도로명·문장·버튼·트윈 · 주행 중 주야 전환 · 링 게이지·눈금 · 소수점 km · 카운트다운·만료 표시 · 흰 QR(반전) · 모델명·엠블럼·공식 색상명("~풍" 포함) · 그릴·실내·크롬 · 라이더 · 비앵커 레벨 해금 · "?" 숨김 항목 · 스태거·무한 트랜지션 · `fallbackToDestructiveMigration` · Room에 위치/경로 · 런타임 지도 타일·서버 호출 · 번들 글꼴(확인 ④ 전) · 신원 입력란·전체 순위 · "XP에서 뺐어요"류 감점 문구 · 전역 NoticeBanner.

---

## 12. 구현 분담·파일

구현은 아래 세 묶음으로 나눈다. 묶음마다 맡는 파일을 적는다.

### 묶음 1: 3D 미니카 에셋 + 컴포넌트 + 모델 + Room v2
파일: `tools/minicar/**`, `app/src/main/assets/minicar/**`, `cosmetic/**`, `engine/LevelTable.kt`(§3.3 블록 추가만), `data/OdoDatabase.kt`·`data/OdoRepository.kt`(§3.5), `app/schemas/.../2.json`, `ui/vehicle/**`, `ui/profile/MinicarPalette.kt`(래퍼화), 테스트 `LevelTableTest`(추가 2개)·`cosmetic/LoadoutTest`, `NOTICE.md` 미니카 절, `tools/minicar/README.md`.

### 묶음 2: 드라이브 맵 장면 + 데이터 + 위치 배선
파일: `tools/map/**`, `app/src/main/assets/map/corridor.json`, `ui/map/**`(CorridorData, MapCamera, CorridorMap, FrameBudget, 픽토 벡터), `drive/HeadingTracker.kt`(+ 테스트 1개), `drive/DriveController.kt`(§4.4 필드·호출만), `NOTICE.md` 지도 절 줄 추가, `tools/map/README.md`.
화면 묶음이 쓰는 계약: `CorridorMap(...)` 시그니처(§4.2), `MapCamera`, `DriveUi.position/headingDeg/speedKmh/tripPath`.

### 묶음 3: 테마·전 화면·폰 연동·목업·통합
파일: `ui/theme/**`, `ui/components/**`, `ui/text/Josa.kt`, `ui/home/**`, `ui/glance/**`, `ui/summary/**`, `ui/profile/AchievementsScreen.kt`·`GarageScreen.kt`(CustomizeScreen 개명), `ui/phonelink/**`, `platform/PhoneLink.kt`, `platform/fake/FakePhoneLink.kt`, `ui/OdoRoot.kt`, `OdoApp.kt`(PhoneLink 주입), `res/values/strings.xml`, `res/raw/earcon_*.ogg` + `tools/audio/**`, `tools/design/contrast.py`, `tools/phone-mockup/**`, `docs/phone-mockup/**`, `README.md`(글꼴·zxing·ODbL·목업·에셋 재현), 삭제: `RadarRing.kt`, `LaneGauge.kt`, `SimBadge.kt`, `Road` 오브젝트, `Minicar` 호환 오버로드(모든 호출부 교체 후).

---

## 13. 완료 기준 (캡처로 검증, 창 658×552 crop)
| # | 캡처/명령 | 통과 조건 |
|---|---|---|
| 1 | `./gradlew test` 로그 grep | 전부 통과, 실패 0(작성 당시인 2026-11-09에 있던 42개 포함) |
| 2 | `spec_minicar_montage` | 5차종 × hero/navi/top, 그릴·엠블럼·문자 없음, 렌즈 램프 + 붉은 후미등, 스쿠터 라이더 없음, 야/주 두 줄(밤은 글로 포함) |
| 3 | `du -sk app/src/main/assets/minicar` · `check_names.py` · `postprocess.py --check` | ≤4096 KB(실측 1216 KiB) · 금지어 0건 · 파일 165/165 + compose.json, 빠지거나 남는 파일 0 |
| 4 | Blender 실행 시간(README) | ≤10분(실측 208 s, 165장), 두 번 실행 결과 바이트 동일 |
| 5 | `spec_glance_night`(데모 탭 후 60 s) | 패널 글자 = "안전 주행"·숫자·"km" 3개뿐, 지도 글자 0, 게이지 12dp + 메달, 미니카 navi 기준점 (460,356)±8, 점 2개, "데모" 없음, 상단 스크림 |
| 6 | `spec_glance_fontscale13`(`settings put system font_scale 1.3`) | 1줄 유지, 겹침·클리핑 0 |
| 7 | `dumpsys gfxinfo` 파일 | 글랜스 프레임 중앙값 ≤16 ms, 90p ≤33 ms; 미달 시 폴백(틸트 0) 캡처 `spec_glance_flat` 첨부 |
| 8 | `spec_home_night`, `spec_home_day`(데모 칩 길게) | 1박자 "이번 주 안전 주행 42 km", 오늘/어제 2줄, 4dp 게이지+캡션, Lv 4 · 시동(둘째 줄) / 180 XP, KeyStrip 5셀, 지도 위 Top 미니카(띠 가운데), 필 버튼 0 |
| 9 | `spec_summary_b1/b2/b3`(0.3 s / 1.2 s / 2.5 s) | 박자 순서대로, 레벨업 카드 1장, 업적 행 동시, "1 km 구간 1개 제외"·"급감속 1", "뺐어요" 없음 |
| 10 | `spec_achievements` | 2열×6행 12개 전부, 채움 3 / 외곽선 9(시드), 조건 1줄, 스크롤 없음, 배너 없음 |
| 11 | `spec_garage_color_lv4`, `spec_garage_lv5`, `spec_garage_scooter` | 탭 KeyStrip, 칩 76dp·간격 23, 잠긴 칩 "Lv N", 수집 8/24 → 13/24, 2륜 루프 탭 "탑케이스" |
| 12 | `spec_phonelink`, `spec_phonelink_linked` | QR 240 어두운 모듈·밝은 판, 코드 4-4, 공유 범위 2줄, 카운트다운 없음, 입력란 없음 |
| 13 | `spec_notice_home`(≈152 s) | 지도 하단 1줄 "강원도 첫 방문 업적을 열었어요", 업적 화면으로 이동 시 사라짐 |
| 14 | `settings put global animator_duration_scale 0` 후 요약 | 카드 즉시 표시, 크래시 0 |
| 15 | 주행 중 18:30 통과 시험(기기 시각 변경) | 글랜스 색 불변, 정차 후 홈에서 전환 |
| 16 | `tools/design/contrast.py` 출력 | §1.4 표와 ±0.05 일치 |
| 17 | `docs/phone-mockup/*.png` 6장 + manifest | 1170×2532, 글자 겹침 0, 실명·계정 0, 차 토큰과 동일 색 |
| 18 | `grep -rn "fallbackToDestructive\|rememberInfiniteTransition\|Road\." app/src/main` | 0건 |
| 19 | `grep -rn "Color(0xFF" app/src/main/java --include=*.kt \| grep -v OdoTokens` | 0건(색은 토큰 파일에만) |
| 20 | 스크린샷·소스·NOTICE | 실명·이메일·계정명 0, 모델명 0, ODbL 3곳(NOTICE·요약 캡션·README) |

---

## 14. 파일 경로 요약(새로 생기는 것)
```
tools/minicar/{vehicles.json, models.py, models_scooter.py, build_minicar.py, postprocess.py, preview.py, render_meta.json, check_names.py, denylist.txt, README.md}
tools/map/{fetch_overpass.py, build_corridor.py, README.md}
tools/audio/make_earcons.py
tools/design/contrast.py
tools/phone-mockup/{render.py, fetch_fonts.sh, screens.json, README.md, fonts/(gitignore)}
app/src/main/assets/minicar/<vehicle>/<hero|navi|top>/<layer>.webp, compose.json
app/src/main/assets/map/corridor.json
app/schemas/com.mujimujimuji.odo.data.OdoDatabase/2.json
app/src/main/java/com/mujimujimuji/odo/
  cosmetic/{VehicleType.kt, Slot.kt, CosmeticItem.kt, Loadout.kt, CosmeticCatalog.kt}
  drive/HeadingTracker.kt
  platform/PhoneLink.kt, platform/fake/FakePhoneLink.kt
  ui/theme/{OdoTokens.kt, OdoType.kt, OdoTheme.kt, NightMode.kt}
  ui/components/{OdoPanel.kt, KeyStrip.kt, Gauge.kt, Medal.kt, Chip.kt, NoticeLine.kt, DemoChip.kt, Icons.kt}
  ui/vehicle/{OdoVehicle.kt, VehicleAssets.kt, VehicleManifest.kt}
  ui/map/{CorridorData.kt, MapCamera.kt, CorridorMap.kt, FrameBudget.kt}
  ui/phonelink/PhoneLinkScreen.kt, ui/profile/GarageScreen.kt, ui/text/Josa.kt
app/src/main/res/raw/earcon_{xp,levelup,achievement}.ogg
docs/phone-mockup/{phone_01_link, phone_02_home, phone_03_summary, phone_03b_share_card, phone_04_achievements, phone_05_garage}.png, manifest.json, README.md
```

## 15. 확인 항목(구현 전에 답이 필요한 것, 답이 없으면 괄호 기본값으로 진행)
① "캐릭터 의상 1세트" = 투톤 루프 해석(기본: 채택; 문구는 LevelTable에 그대로 둠) ② 글랜스 왼쪽 부착 불투명 패널이 "상자"로 읽히는지 첫 캡처 판단(폴백: 패널 배경 제거, 숫자 밑 2dp `ground` 헤일로만) ③ 앰버 `#F0A83A`가 경고색으로 읽히면 `brass #D8B46A` 1줄 교체 ④ 번들 글꼴은 시스템 글꼴 가짜 볼드가 캡처로 확인될 때만(Wanted Sans OFL) ⑤ Lv 5에 휠 "다이아"·램프 "웜"을 색 5종에 덧붙이는 것(레벨 표의 Lv 5 해금 문구 "미니카 색상 5종" 밖의 추가). 2026-11-11 결정: 덧붙이지 않는다. Lv 5 해금은 색 5종뿐이고, 두 항목은 Lv 10으로 옮겼다(`cosmeticUnlocks` 2줄).
