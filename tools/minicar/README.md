# 오도 미니카 에셋 파이프라인 (`tools/minicar/`)

앱 안의 3D 미니카(차종 5 × 뷰 2 × 레이어 12 = 120장 WebP)를 Blender로 절차 생성하는 도구. 외부 `.blend`·외부 텍스처 없이
`vehicles.json`의 치수 파라미터만으로 bmesh 모델을 만들고, 레이어마다 홀드아웃을 바꿔 가며 알파 PNG로 렌더한 뒤 WebP로 묶는다.
색·램프·투톤·데칼은 렌더하지 않고 앱(`ui/vehicle/OdoVehicle.kt`)이 흰 레이어에 `Modulate`/`Screen` 틴트를 얹는다(설계서 §2).

## 출처·금지
- 현대차 디자인 언어에서 영감을 받아 **자체 제작한 스타일라이즈드 모델**이다. 특정 차종·엠블럼·명칭을 재현하지 않으며
  그릴·실내·크롬·라이더(2륜)는 모델링하지 않는다. 같은 문구가 `compose.json`의 `provenance`와 `NOTICE.md`에 있다.
- 파일명·레이어명·주석·문서에 실제 모델명(한/영)·공식 색상명·"~풍"을 쓰지 않는다. `check_names.py`가 `denylist.txt`로
  `app/ tools/ docs/ NOTICE.md`를 훑어 **0건**이어야 한다(아래).

## 파일
| 파일 | 역할 |
|---|---|
| `vehicles.json` | 차종 5종(compact 소형 / suv 중형 / large 대형 / sedan 세단 / scooter 2륜)의 치수·램프 셀·재질·카메라·조명 파라미터. `seed: 7`. |
| `build_minicar.py` | Blender 헤드리스 스크립트. 모델 생성 → 레이어별 홀드아웃 렌더(Cycles CPU, 적응 샘플링, OIDN, film transparent) → PNG + `render_meta.json`. |
| `postprocess.py` | PNG → WebP(hero lossy q85/alpha_q90/sharp_yuv, navi lossless), 그림자 알파 리매핑, `compose.json` 생성, 총량·장수 검사. |
| `check_names.py`, `denylist.txt` | 금지어 검사(검사 전용 목록; 목록의 단어는 어디에도 쓰이지 않는다). |
| `render_meta.json` | 마지막 렌더의 기준점(원점·바퀴·코·루프 px) 기록. 결정적 출력이라 재실행해도 같다. |

## 실행(맥미니, 설치는 `/Volumes/DATA`)
```sh
# 1) 렌더: 120장 PNG (+ render_meta.json)
/Volumes/DATA/tools/blender-4.5.9/Blender.app/Contents/MacOS/Blender -b -P tools/minicar/build_minicar.py -- \
  --vehicle all --view all --layers all --out /Volumes/DATA/tmp/minicar_out --samples 128

# 2) WebP + compose.json → app/src/main/assets/minicar/  (cwebp 1.6.0, /opt/homebrew/bin/cwebp)
/Volumes/DATA/venv/minicar/bin/python -B tools/minicar/postprocess.py --src /Volumes/DATA/tmp/minicar_out --cwebp /opt/homebrew/bin/cwebp

# 3) 검사
python3 -B tools/minicar/postprocess.py --check app/src/main/assets/minicar   # 120장·≤4096 KiB
python3 -B tools/minicar/check_names.py                                     # scanned N files, 0 hits
du -sk app/src/main/assets/minicar
```
파이썬은 늘 `-B`로 돌린다(또는 `PYTHONDONTWRITEBYTECODE=1`). 다른 스크립트나 셸에서 `postprocess`를 import하면 `__pycache__/*.pyc`가 생기는데,
그 안에 실행한 기기의 절대 경로(계정명 포함)가 그대로 박힌다. `.gitignore`가 `__pycache__/`·`*.pyc`를 막지만 폴더째 복사·동기화할 때 딸려 가지 않게
아예 만들지 않는다. 이미 생겼으면 `find tools -name __pycache__ -prune -exec rm -r {} +`로 지운다.

옵션: `--vehicle compact,suv` `--view hero` `--layers body,spec` `--scale 0.5`(시험 해상도) `--stats`(body 밝기 통계).

## 실측(2026-11-09, 맥미니 M4 CPU, Blender 4.5.9 LTS, 128 samples)
- 렌더 전체 120장: **46.8 s / 46.6 s**(두 번 실행). 설계서 상한 10분.
- 결정성: 두 번 실행한 PNG 120장의 SHA-256이 **전부 동일**(`IDENTICAL`). 이를 위해 PNG 스탬프 메타데이터(`use_stamp_*`)를 끄고,
  `use_persistent_data=False` + 렌더 직전 `view_layer.update()`로 홀드아웃·가시성 변경이 확실히 반영되게 했다
  (영속 데이터가 켜져 있으면 가끔 이전 레이어 상태가 남아 한 장이 어둡게 나왔다).
- WebP 결과: 120장 + `compose.json` = **696 KiB**(`du -sk`), 바이트 합 415,330. 가장 큰 파일 22 KiB(large/hero/shadow).
- navi 카메라·세단 휠을 고친 뒤 다시 렌더: 120장 + `compose.json` = **728 KiB**, 바이트 합 447,524, 가장 큰 파일 22 KiB(large/hero/shadow).

## 레이어(설계서 §2.3)
`shadow`(접지 그림자) · `body`(흰 디퓨즈, Modulate 틴트) · `spec`(글로시 직접광, Screen) · `fixed`(글라스·타이어·클래딩·허브 공통·시트) ·
`wheel_steel|dia|aero`(휠 디자인, 선택 1장) · `lamp`(램프 셀, Modulate + 야간 α1.0 / 주간 α0.55) · `roof_twotone`(루프/레그실드 흰 영역) ·
`roof_box`(루프박스/탑케이스, 무광) · `decal_stripe`(오프셋 스트라이프 흰 영역) · `plate_gold`(황금 번호판 전·후).
hero는 576×384 px(288×192 dp @2×), navi는 192×192 px(96×96 dp @2×, 접지 기준점 (96,104), 차 머리가 위). navi의 `wheel_*`는 시점상 거의 비어 있다(정상).
기준점은 `vehicles.json` `views.navi.origin_px` 한 곳에만 적고, `postprocess.py`가 그 값을 `compose.json` `views.navi.anchor`로 옮긴다.

## 결정 사항(요약)
- navi 카메라는 고도 45°·거리 10 m·초점 85 mm(설계서 §2.2의 62°·14 m에서 바꿈). 62°에서는 거의 위에서 내려다봐 지붕 사각형만 보여
  차가 상자처럼 읽혔다. 지도 기울기 58°(카메라 고도 약 32°)와 62° 사이에서 뒷유리·트렁크·테일램프 띠가 보이는 45°를 골랐고,
  5종 모두 192 px 틀 안에 들어가게 기준점을 (96,112)→(96,104)로 올렸다(대형 꼬리 접지 187.5 px, 지붕 앞 35.8 px).
  비교 시안 5개(62°/14, 50°/10.5, 45°/10, 40°/9.5, 45°/9)는 `--spec`으로 32 samples 렌더해 골랐다.
- 세단 바퀴 반지름 0.42→0.39 m, 벨트라인 높이 [앞 0.72, 뒤 0.84]→[0.86, 0.9] m. 앞바퀴 꼭대기(2r = 0.84)가 그 자리 벨트 높이(약 0.74)보다
  높아 hero에서 바퀴가 펜더·보닛 위로 뚫고 나왔다. 지금은 바퀴 꼭대기 0.78, 벨트 0.86~0.9로 여유 0.08 m 이상.
- 세단 휠베이스 2.95 m(비율상 길게), 조명 key 290 W / fill 97 W(body 레이어 평균 밝기를 틴트 기준에 맞춤), `body_spec`은 검은 글로시 재질로 하이라이트만 뽑음.
- 그림자 알파는 `a' = max(0, a-6)·255/249`로 바닥 노이즈 제거. navi 그림자는 조명 없이 월드 AO만.
- 원점은 hero (288,272) → `compose.json`의 `groundY: 336`은 가장 가까운 바퀴 접지선.
- 검사어 목록에서 일반 영단어와 겹치는 모델명(Accent, Venue, Soul, Ray 등)은 영문을 빼고 한글만 둔다(오탐 방지). 브랜드명 자체(현대/기아)는 금지어가 아니다.
