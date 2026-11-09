# tools/map — 코리도 지도 데이터 생성

데모 경로(여주 → 횡성, `tools/scenario/route_yeoju_hoengseong.json`) 양옆 4 km의 도로·수역·숲·시가지·강·휴게소 위치를
OpenStreetMap에서 한 번 받아 `app/src/main/assets/map/corridor.json`으로 단순화한다. 앱은 이 파일만 읽고,
런타임에 지도 서버·타일을 전혀 호출하지 않는다(설계서 §4.1).

## 출처·금지
- 데이터: © OpenStreetMap contributors, Open Database License(ODbL) 1.0 — https://www.openstreetmap.org/copyright
  표기 위치 3곳: `NOTICE.md`, 앱 정차 화면 지도 캡션(`MapAttribution`), 이 README.
- 이름·주소 등 문자열은 결과 파일에 넣지 않는다(`build_corridor.py`가 `"name"` 키가 남으면 실패한다). 지도 위 글자 0 규칙과 개인정보 규칙 때문.
- 받은 원본(`overpass_raw.json`)은 신뢰하지 않는 데이터다: 저장소 밖의 빈 디렉터리에만 두고(스크립트가 저장소 안 경로를 거부한다),
  읽는 파이썬은 `-I`로 실행하며, 스크립트는 다른 디렉터리(여기)에 둔다.

## 파일
| 파일 | 역할 |
| --- | --- |
| `fetch_overpass.py` | Overpass API POST 1회(bbox `37.20,127.54,37.50,128.17`, UA `odo-redesign/1.0 (contest build tool)`) → 원본 JSON. 표준 라이브러리만. |
| `build_corridor.py` | 원본 + 경로 → 경로 ±4 km로 자르고(Douglas-Peucker 10 m, 작은 폴리곤 제거), 정수 로컬 미터로 바꿔 `corridor.json` 생성. 표준 라이브러리만. |
| `app/src/main/assets/map/corridor.json` | 산출물(빌드에 포함). 형식은 아래. |

## 실행 (빌드 전에, 데이터가 바뀔 때만)
지금 데이터를 그대로 다시 만들 때는 받지 말고 아래 "스냅숏"의 보관본을 `--raw`로 쓴다. 새로 받을 때만 이 순서대로 한다.
```sh
RAW=/Volumes/DATA/tmp/osm-data          # 저장소 밖 빈 디렉터리(새로 받는 곳, 보관본과 다른 곳)
mkdir -p "$RAW"
python3 -I tools/map/fetch_overpass.py --out "$RAW/overpass_raw.json"
python3 -I tools/map/build_corridor.py --raw "$RAW/overpass_raw.json" \
  --route tools/scenario/route_yeoju_hoengseong.json \
  --out app/src/main/assets/map/corridor.json
```
옵션: `--buffer 4000`(경로 양옆 m) `--tol 10`(단순화 허용 오차 m) `--min-poly 40`(이보다 작은 폴리곤 제거, m)
`--poi-merge 300`(휴게소 중복 병합 거리 m). `fetch_overpass.py --print-query`는 질의만 출력한다.

## 실측 (2026-11-09, 맥북)
- fetch: 42.0 s, 원본 12.0 MB, 8,554 요소.
- build: 1.9 s → `corridor.json` 399,413 B(0.40 MB, gzip ≈133 KB), 점 25,875개.
  - water 91폴리/2,175점 · forest 401/9,684 · urban 292/2,131 · rivers 46/688
  - motorway 471/1,526 · trunk 385/959 · primary 129/473 · secondary 568/1,523 · tertiary 2,103/5,962
  - route 754점, 휴게소 16곳(여주 way 344480537, 문막 way 223671550, 횡성 node 414680790 포함)
  - 로컬 bbox x −11,206..57,877 m, y −613..34,598 m

## 스냅숏 (재현 기준)
- 지금 `corridor.json`을 만든 원본: Overpass `osm3s.timestamp_osm_base` **2026-11-09T03:48:56Z**, 11,994,929 B,
  sha256 `eb979c50c583183816f62c13ea32cbb1f974d7338084abe53c5a14427cabd853` (Overpass API 0.7.62.11, 8,554 요소).
- 이 원본으로 `build_corridor.py`를 다시 돌리면 `corridor.json`이 바이트까지 같다
  (399,413 B, sha256 `255a55525b9e922808b8abe7ed3fcb10e861c5c2b09a1f9f5aba35cb58f1d887`).
- 보관: 맥미니 외장 SSD `/Volumes/DATA/odo-data/osm-snapshot/overpass_raw_20261109T034856Z.json`(읽기 전용) + 옆의
  `.sha256`. 저장소에는 넣지 않는다(12 MB, 신뢰하지 않는 원본). 새로 받는 `fetch_overpass.py`는 `$RAW`에 쓰므로 보관본을 덮지 않는다.
  2026-11-10 이 보관본으로 다시 만든 결과가 지금 `corridor.json`과 sha256까지 같음을 확인했다.
  ```sh
  SNAP=/Volumes/DATA/odo-data/osm-snapshot
  (cd "$SNAP" && shasum -a 256 -c overpass_raw_20261109T034856Z.json.sha256)   # OK여야 한다
  python3 -I tools/map/build_corridor.py --raw "$SNAP/overpass_raw_20261109T034856Z.json" \
    --route tools/scenario/route_yeoju_hoengseong.json --out app/src/main/assets/map/corridor.json
  ```
- `fetch_overpass.py`는 받는 시점의 최신 OSM을 가져온다. 새로 받으면 OSM이 바뀐 만큼 결과가 달라지므로,
  받은 파일의 `osm3s.timestamp_osm_base`·sha256을 위 값과 비교해 같은 스냅숏인지 먼저 확인한다.

## corridor.json 형식 (모두 정수)
```json
{"version":1,"origin":{"lat":37.2,"lng":127.54},"attribution":"© OpenStreetMap contributors, ODbL 1.0",
 "cell_m":1000,"bbox":[minX,minY,maxX,maxY],
 "polys":{"water":[[x,y,...]],"forest":[...],"urban":[...]},"rivers":[[x,y,...]],
 "roads":[{"cls":"motorway|trunk|primary|secondary|tertiary","pts":[x,y,...]}],
 "route":[x,y,...],"pois":[{"type":"rest","x":0,"y":0,"src":"OSM way 344480537"}]}
```
- 로컬 미터: `x = (lon − 127.54)·111320·cos(37.20°)`, `y = (lat − 37.20)·111320`. 폴리곤 링은 닫는 점을 생략한다.
- 앱 쪽 파서: `ui/map/CorridorData.kt`(순수 Kotlin, org.json) → 1 km 셀 격자 컬링, 경로 누적 거리 질의.

## 결정 사항
- 경로 ±4 km 클립: 글랜스(6 m/dp, 지도 폭 ≈400 dp → 2.4 km)와 정차(20 m/dp) 화면 모두 가장자리가 비지 않는 최소 폭.
- 정수 미터 + 10 m 단순화: 0.4 MB로 25k점. 글랜스 축척(1 dp = 6 m)에서 오차가 2 dp 미만.
- 강(`rivers`)은 명세의 수역 레이어에 더한 선 레이어(water 색 3 dp): 폴리곤이 없는 하천이 많아 선이 없으면 코리도가 비어 보인다.
- 휴게소는 양방향·주차장 중복이 많아 300 m 안은 하나로 합쳤다(way 우선). 20 → 16곳.
- 남한강 수역 릴레이션이 원점 서쪽 11 km까지 이어져 bbox가 서쪽으로 넓다. 렌더는 셀 컬링을 하므로 비용은 없다.
- 폴리곤은 self-intersection 검사를 하지 않는다(단순화 후 드물게 생기지만 채우기 규칙 non-zero에서 눈에 띄지 않음).
