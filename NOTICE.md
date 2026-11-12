# NOTICE

## 지도 데이터
데모 시나리오 경로(`tools/scenario/route_yeoju_hoengseong.json`, `app/src/main/assets/scenarios/gangneung_trip.json`)와
업적 좌표(`app/src/main/assets/achievements.json`)는 OpenStreetMap 데이터에서 가져왔다.

© OpenStreetMap contributors. 데이터는 Open Database License(ODbL) 1.0을 따른다. https://www.openstreetmap.org/copyright

- 휴게소 좌표: Nominatim 검색 결과(OSM way 344480538, node 414680791, way 223671550)
- 경로: OSRM 공개 데모 서버(router.project-osrm.org)가 OSM 데이터로 계산한 경로
- 경로 주변 지도 `app/src/main/assets/map/corridor.json`(여주 → 횡성 경로 ±4 km의 도로·수역·숲·시가지·강·휴게소 위치를
  단순화한 오프라인 지도): OSM Overpass API 추출물을 `tools/map/fetch_overpass.py` → `tools/map/build_corridor.py`로
  변환했다(빌드 전에 한 번 실행, 앱은 런타임에 지도 서버·타일을 호출하지 않는다). 이름·주소 등 문자열은 담지 않았다.
  원본 스냅숏은 Overpass `timestamp_osm_base` 2026-11-09T03:48:56Z, sha256 `eb979c50c583183816f62c13ea32cbb1f974d7338084abe53c5a14427cabd853`
  (자세한 값과 재현 방법은 `tools/map/README.md`).
  © OpenStreetMap contributors, ODbL 1.0. 지도가 보이는 앱 안 정차 화면(홈·트립 요약·업적·차고·휴대폰 연결)마다 같은 캡션을 둔다.

## 휴대폰 연결 공유 범위
휴대폰 연결 화면(연결 전·연결 후)과 README에 같은 두 줄을 둔다. 1차 빌드는 실제 폰 앱·서버가 없어 연결돼도 기기 밖으로 나가는 데이터가 없다.

- 공유함: 트립 요약·업적·미니카 꾸미기
- 공유 안 함: 차량 신호 원본·위치 기록

## 미니카 3D 에셋
`app/src/main/assets/minicar/**`(WebP 165장 + `compose.json`)는 이 저장소의 `tools/minicar/build_minicar.py`가
Blender 4.5.9 LTS(GPL)로 절차 생성한 자체 제작물이다. 외부 모델·텍스처·글꼴을 쓰지 않았다.
특정 차종을 본뜨지 않고 차급 비례를 일반화해 자체 제작한 스타일라이즈드 모델이며, 엠블럼·명칭을 재현하지 않는다.

재생성: Apple M4 CPU 기준 165장 렌더에 약 3.7분(222 s, 6스레드)이 걸리고, 두 번 실행해도 바이트가 같다.
명령은 README '에셋 재현'과 같고, postprocess.py는 Pillow가 필요하다.

```sh
blender -b -t 6 --python-exit-code 1 -P tools/minicar/build_minicar.py -- --vehicle all --view all --layers all --out <렌더 폴더> --samples 128
python3 -B tools/minicar/postprocess.py --src <렌더 폴더> --cwebp <cwebp 경로>
```

## QR 코드 라이브러리
휴대폰 연결 화면의 QR 코드는 ZXing core 3.5.3(Apache License 2.0, https://github.com/zxing/zxing)으로 기기 안에서만 그린다.
네트워크 호출·외부 이미지 없이 문자열 → 모듈 행렬만 계산하고, 그리기는 Compose Canvas가 한다.

폰 동반 화면 목업 생성기(`tools/phone-mockup/render.py`의 `qr_matrix`)의 QR 인코더는 Project Nayuki QR Code generator
(https://github.com/nayuki/QR-Code-generator, 파이썬 판 `python/qrcodegen.py`)의 알고리즘을 바이트 모드·버전 1~6만 남기고 줄여 옮긴 것이다.
MIT License이며, 목업 PNG를 그릴 때만 쓰고 앱(APK)에는 들어가지 않는다. 원본의 저작권·허가 문구는 다음과 같다.

```
Copyright (c) Project Nayuki. (MIT License)
https://www.nayuki.io/page/qr-code-generator-library

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:
- The above copyright notice and this permission notice shall be included in
  all copies or substantial portions of the Software.
- The Software is provided "as is", without warranty of any kind, express or
  implied, including but not limited to the warranties of merchantability,
  fitness for a particular purpose and noninfringement. In no event shall the
  authors or copyright holders be liable for any claim, damages or other
  liability, whether in an action of contract, tort or otherwise, arising from,
  out of or in connection with the Software or the use or other dealings in the
  Software.
```

## 이어콘·아이콘
트립 요약의 효과음 3개(`app/src/main/res/raw/earcon_xp.ogg`, `earcon_levelup.ogg`, `earcon_achievement.ogg`)는
`tools/audio/make_earcons.py`가 사인파로 합성한 자체 제작물이다. 외부 음원·샘플을 쓰지 않았다.
앱 안 아이콘(홈·업적·차고·휴대폰·재생·초기화·확인·끊기)과 차종 실루엣은 Compose 벡터로 직접 그렸다. 번들 글꼴은 없다.
런처 아이콘(`design/app_icon_512.png`, `app/src/main/res/mipmap-*/ic_launcher.png`)은 기획서의 앱 아이콘 시안 이미지를 쓴다. 이 그림을 만드는 스크립트는 저장소에 없다.
