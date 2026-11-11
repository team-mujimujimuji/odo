# 오도 미니카 에셋 파이프라인 (`tools/minicar/`)

앱 안의 3D 미니카를 Blender로 절차 생성하는 도구. 차종 5 × 뷰 3 × 레이어 14에서 뷰마다 안 쓰는 레이어를 빼면
차종당 33장(hero 13 + navi 10 + top 10), **WebP 165장 + `compose.json` = 166파일**(에셋 계약 v2)이다.
외부 `.blend`·외부 텍스처·HDRI 없이 `vehicles.json`의 치수·단면 파라미터만으로 메시를 만들고, 레이어마다 가림(holdout)을
바꿔 가며 알파 PNG로 렌더한 뒤 WebP로 묶는다. 차 색·램프 색·투톤·데칼 색은 렌더하지 않고 앱(`ui/vehicle/OdoVehicle.kt`)이
흰 레이어에 `Modulate`/`Screen` 틴트를 얹는다(설계서 §2).

## 출처·금지
- 현대차 디자인 언어에서 영감을 받아 **자체 제작한 스타일라이즈드 모델**이다. 특정 차종·엠블럼·명칭을 재현하지 않으며
  로고·글자·그릴 무늬·실내·라이더(2륜)는 모델링하지 않는다. 비례는 일반적인 해치백/SUV/미니밴/패스트백/스쿠터다.
  같은 문구가 `compose.json`의 `provenance`와 `NOTICE.md`에 있다.
- 파일명·레이어명·주석·문서에 실제 모델명(한/영)·공식 색상명·"~풍"을 쓰지 않는다. `check_names.py`가 `denylist.txt`로
  `app/ tools/ docs/ NOTICE.md` 가운데 **공개 저장소에 들어가는 파일**(git이 추적하거나, 아직 추적 안 했지만 `.gitignore`에
  걸리지 않는 파일)을 훑어 **0건**이어야 한다. `.gitignore`로 빠지는 내부 문서는 건너뛰고 그 수를 `skipped`로 알린다
  (전부 보려면 `--include-ignored`).

## 파일
| 파일 | 역할 |
|---|---|
| `vehicles.json` | 차종 5종(compact 소형 / suv 중형 / large 대형 / sedan 세단 / scooter 2륜)의 치수·단면 표(`shape`)·재질, 뷰 3개의 카메라(`views`), 조명(`lighting`). `seed: 7`. |
| `models.py` | 형상·재질(Blender bmesh + numpy). 차 4종은 단면 표로 만든 로프트 케이지 → Subdivision Surface(crease) → 휠 아치를 파고 어두운 휠하우스 라이너를 넣는다. 유리·클래딩·투톤·데칼·램프·번호판은 차체 면을 복제해 살짝 띄운 겉껍질. |
| `models_scooter.py` | 2륜 형상(`models.build_vehicle`이 부른다). 둥근 부품은 초타원 단면 로프트 + Subdivision Surface. |
| `build_minicar.py` | Blender 헤드리스 장면 스크립트. 카메라 3뷰, 조명, 레이어별 가림 패스, 밤 글로 패스(Cycles CPU, OIDN, film transparent) → PNG + `render_meta.json`. |
| `postprocess.py` | PNG → WebP, 그림자 알파 바닥 잡음 제거, 글로 형식 변환, `compose.json` 생성, `--check`(장수·크기·용량·계약 검사). |
| `preview.py` | 앱과 같은 순서·블렌드·밤 규칙으로 레이어를 합성해 보는 도구(contact sheet, 지도 캡처 위 합성). Pillow만 쓴다. |
| `check_names.py`, `denylist.txt` | 금지어 검사(검사 전용 목록; 목록의 단어는 어디에도 쓰이지 않는다). |
| `render_meta.json` | 마지막 렌더의 기준점 기록(뷰별 `origin_px`·`px_per_m`·bbox, `samples`·`seed`·`navi_pitch_deg`). 렌더는 `<out>/`에 쓰므로 저장소로 복사한다(아래). |

## 뷰 3개 (`compose.json` `views`)
| 뷰 | PNG(px) | 앱(dp) | 기준점(px) | 카메라 | 쓰는 곳 | 만들지 않는 레이어 |
|---|---|---|---|---|---|---|
| `hero` | 576×384 | 288×192 | 바닥 원점 (288,272), `groundY` 336 | 원근 70 mm, 앞 3/4(방위 52°, 고도 18°) | 차고, 휴대폰 연결 | `glow_tail` |
| `navi` | 256×320 | 128×160 | 앵커 (128,200) | 원근 200 mm, 뒤에서 내려다봄(고도 45°), 앵커 깊이 56 px/m | 글랜스(heading-up 기울인 지도), 회전 안 함 | `wheel_*` 3장, `lamp` |
| `top` | 256×384 | 128×192 | 앵커 = 회전축 (128,192) | 정사영, 바로 위, 56 px/m | 홈·트립 요약(north-up 지도), `headingDeg`로 회전 | `wheel_*` 3장, `plate_gold` |

- navi·top은 차 앞이 위, hero는 차의 앞과 왼쪽 옆이 보이는 3/4 뷰다. navi·top의 기준점은 차 중심 아래 바닥점이고, 앱은 이 점을 지도 위 현재 위치에 맞춘다.
- navi·top에서는 기본 휠(`wheel_steel`)을 `fixed`에 구워 넣는다(그 크기에서는 휠 디자인이 구분되지 않는다).
- 2륜은 navi·top만 `marker_zoom` 1.6으로 크게 렌더한다(지도에서 알아보게. 기준점 자리는 그대로). 앱의 차종 배율 `naviScale`은 2륜 1.25, 나머지 1.0.
- navi 고도 45°는 38°/45°/52° 후보를 견주어 골랐다(`--navi-pitch`, `build_minicar.py`의 `NAVI_PITCH_DEG` 주석).

## 레이어 14개
`shadow`(접지 그림자) · `body`(흰 디퓨즈, 차 색 Modulate) · `spec`(광택 하이라이트, Screen) ·
`fixed`(유리·타이어·클래딩·허브·라이너·미러·빈 번호판·**붉은 후미등 렌즈**처럼 꾸미기와 무관한 부분) ·
`wheel_steel|dia|aero`(휠 디자인, 1장 선택) · `lamp`(앞 전조등 렌즈·주간등, 램프 색 Modulate, 알파 낮 0.55 / 밤 1.0) ·
`roof_twotone`(투톤 루프/레그실드) · `roof_box`(루프박스/탑케이스) · `decal_stripe`(스트라이프) · `plate_gold`(황금 번호판) ·
`glow_head`(밤 전조등 빛: 렌즈 번짐 + 앞 바닥 빛, 램프 색 틴트 후 Screen) · `glow_tail`(밤 후미등 붉은 번짐 + 뒤 바닥 빛, Screen).

- 그리는 순서(`order`): `shadow, body, spec, roof_twotone, decal_stripe, fixed, wheel, lamp, roof_box, plate_gold, glow_head, glow_tail`.
  순서가 앞인 레이어의 물체는 가림(holdout), 뒤인 레이어의 물체는 숨김으로 렌더해 아래 레이어가 늘 온전한 면이 되게 한다
  (SrcOver 합성에 이음새가 없다). 투톤·스트라이프는 차체 위 2~3 mm 껍질이라 다른 패스에서는 가리지 않고 숨긴다.
- `night` 목록(`glow_head`, `glow_tail`)은 밤에만 그린다. 정지 이미지이고 애니메이션은 없다(주행 중 화면에 연출을 두지 않는 안전 규칙).
- 글로 렌더 원본은 검은 배경 위 RGB이고, `postprocess.py`가 앱 형식(RGB = 빛 색, 알파 = 세기 = max(R,G,B))으로 바꾼다.
- 인코딩: hero 손실(`-q 85 -alpha_q 90 -sharp_yuv`), navi·top 무손실(`-lossless -z 9 -exact`), 글로는 무손실과 손실(-q 90) 가운데 작은 쪽.
- 조명: 노드 그라데이션 world + 뷰별 면 조명(key/fill/rim, 반사용 소프트박스). 유리에는 라이트 링킹으로 유리만 비추는
  하늘 반사 판(`glass_card`)을 둔다. 외부 HDRI는 쓰지 않는다. top 조명은 좌우 대칭이라 지도에서 돌려도 한쪽만 밝아 보이지 않는다.

## 실행
```sh
BLENDER=<Blender 4.5.9 LTS 실행 파일>   # macOS: Blender.app/Contents/MacOS/Blender
PY=<Pillow가 있는 파이썬>
OUT=<저장소 밖 빈 폴더>

# 1) 렌더: PNG 165장 + render_meta.json → $OUT
"$BLENDER" -b -t 6 --python-exit-code 1 -P tools/minicar/build_minicar.py -- \
  --vehicle all --view all --layers all --out "$OUT" --samples 128
cp "$OUT/render_meta.json" tools/minicar/render_meta.json

# 2) WebP 165장 + compose.json → app/src/main/assets/minicar/  (cwebp 1.6.0)
"$PY" -B tools/minicar/postprocess.py --src "$OUT" --cwebp <cwebp 경로>
#    --cwebp 를 빼면 PATH와 흔한 설치 위치에서 찾는다.
#    후보 에셋을 따로 뽑을 때: --dst <폴더>  (기본은 앱 에셋 폴더)

# 3) 검사
python3 -B tools/minicar/postprocess.py --check app/src/main/assets/minicar   # files 165/165 (+compose.json = 166), ≤ 4096 KiB
python3 -B tools/minicar/check_names.py                                     # scanned N files, 0 hits (skipped K git-ignored)
./gradlew :app:testDebugUnitTest --rerun                                    # VehicleManifestTest가 에셋을 계약과 대조한다
du -sk app/src/main/assets/minicar

# 4) 눈으로 보기(앱과 같은 합성 규칙)
"$PY" -B tools/minicar/preview.py --assets app/src/main/assets/minicar --out <폴더>        # contact sheet 9장
"$PY" -B tools/minicar/preview.py --plate <지도 캡처.png> --at 460,356 --view navi --out glance.png
"$PY" -B tools/minicar/preview.py --plate <지도 캡처.png> --at 328,172 --heading 62 --night \
  --vehicle suv --loadout color=cherry,roof=box --out home.png
```
- 렌더 옵션: `--vehicle compact,suv` `--view hero,top` `--layers body,spec`(쉼표 목록) `--samples 32`(시험) `--scale 0.5`(시험 해상도)
  `--navi-pitch 38`(navi 고도 후보) `--stats`(레이어 밝기·테두리 통계) `--spec <json>`(다른 파라미터 파일).
- `postprocess.py`는 렌더 폴더가 계약대로인지 먼저 본다(빠진 PNG, 크기, `render_meta.json`의 바닥 원점이 기준점과 0.5 px 넘게 다르면
  변환 전에 exit 1). `--check`는 표준 라이브러리만 쓰고, 빠진·남는 파일, 크기가 다른 WebP, 용량 초과, 계약과 다른 `compose.json`을 잡는다.
- 에셋만 바꾸면 Gradle이 단위 테스트를 다시 돌리지 않으므로 `--rerun`을 붙인다. 다른 폴더의 후보를 검사하려면 `ODO_MINICAR_ASSETS=<폴더>`.
- 파이썬은 늘 `-B`로 돌린다(또는 `PYTHONDONTWRITEBYTECODE=1`). 다른 스크립트에서 `postprocess`·`models`를 import하면 `__pycache__/*.pyc`가
  생기는데, 그 안에 실행한 기기의 절대 경로(계정명 포함)가 박힌다. 스크립트들도 맨 앞에서 `sys.dont_write_bytecode = True`를 둔다.
  `.gitignore`가 `__pycache__/`·`*.pyc`를 막지만 폴더째 복사할 때 딸려 가지 않게 아예 만들지 않는다.
  이미 생겼으면 `find tools -name __pycache__ -prune -exec rm -r {} +`로 지운다.

## 결정성
- 시드 7, Cycles CPU, OIDN(CPU), PNG 스탬프 메타데이터 끔, `use_persistent_data=False` + 렌더 직전 `view_layer.update()`.
  같은 기기·같은 입력이면 PNG가 바이트까지 같다. 형상 코드는 난수·시간·집합 순회 순서에 기대지 않는다.
- `postprocess.py`는 같은 PNG·같은 cwebp 버전이면 WebP·`compose.json`이 바이트까지 같다.
- 확인(2026-11-11): 같은 코드로 따로 두 번 전체 렌더한 PNG 165장이 `diff -rq`로 전부 같았고(`render_meta.json`도 같음),
  두 번째 렌더를 `postprocess.py --dst`로 다른 폴더에 뽑은 166파일이 앱 에셋 폴더와 바이트까지 같았다.

## 실측(2026-11-11, Apple M4 CPU, Blender 4.5.9 LTS, 128 samples, `-t 6`)
- 렌더 전체 165장: **208.0 s / 210.5 s**(두 번 실행, 벽시계). v1 120장은 46.8 s였다. 뷰·레이어가 늘고, 세분화한 차체와
  유리 반사 판, 밤 글로 패스(선형 EXR + 렌즈 번짐)가 더해졌다. 변환(`postprocess.py`, cwebp 4개 동시) 12 s.
- 에셋: 165장 + `compose.json` = **1216 KiB**(`du -sk`, 한도 4096), 바이트 합 885,867(hero 65장 339,482 · navi 50장 252,760 ·
  top 50장 291,838 · 이 가운데 글로 25장 73,254 · `compose.json` 1,787). 가장 큰 파일 19 KiB(large/hero/shadow).
  v1(728 KiB)보다 커진 이유: top 뷰가 새로 생기고(+50장), navi 판이 192×192 → 256×320 px로 커지고, 밤 글로 25장이 더해졌다.
- 앱 글랜스 프레임 시간(에뮬레이터, SwiftShader) 중앙값 18.3 ms로, v1 에셋 때의 18.5 ms와 같은 수준이다.

## 앞 버전(v1)에서 바뀐 것
- v1은 뷰 2개(hero, 뒤에서 45°로 내려다본 navi 192×192 하나를 기울인 지도와 north-up 지도 양쪽에 씀) × 레이어 12 = 120장이었다.
  north-up 지도에 뒤 45° 그림을 얹으면 원근이 어긋나 지도와 안 어울려 보여서 정사영 `top`을 따로 두었다.
- 차체가 볼록 육면체 조합이라 각진 상자로 보이던 것을 로프트 + Subdivision Surface + 휠 아치로 바꾸었다.
  유리는 반사 판으로 하늘을 비추고, 램프는 앞 렌즈·주간등과 붉은 후미등으로 나눴다(전엔 앞뒤 같은 흰 셀 줄).
- 밤은 틴트를 어둡게 하는 것뿐이라 낮과 구분되지 않아 정지된 글로 레이어 2장(`glow_head`, `glow_tail`)을 더했다.
- 앱은 v1 `compose.json`도 읽는다(`top`이 없으면 Top 요청을 navi 그림으로 대신).
- 검사어 목록에서 일반 영단어와 겹치는 일부 모델명은 영문을 빼고 한글만 둔다(오탐 방지). 브랜드명 자체(현대/기아)는 금지어가 아니다.
