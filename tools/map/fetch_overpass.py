#!/usr/bin/env python3
"""OpenStreetMap Overpass API를 **빌드 전에 1회** 조회해 원본 JSON을 저장한다(설계서 §4.1).

- 앱은 런타임에 지도 서버를 부르지 않는다. 이 스크립트의 결과를 build_corridor.py가 가공해
  app/src/main/assets/map/corridor.json을 만들고, 앱은 그 파일만 읽는다.
- 원본 JSON(10~20 MB)은 저장소에 넣지 않는다. --out 은 저장소 밖(예: scratchpad/osm-data/)이어야 하며,
  저장소 안 경로를 주면 거부한다.
- 출처: © OpenStreetMap contributors, ODbL 1.0 — NOTICE.md·tools/map/README.md·앱 내 표기(`CorridorData.attribution`).

사용:
  python3 -I tools/map/fetch_overpass.py --out <scratch>/osm-data/overpass_raw.json
옵션:
  --endpoint URL   기본 https://overpass-api.de/api/interpreter (대체: https://overpass.kumi.systems/api/interpreter)
  --timeout SEC    HTTP 대기(기본 360)
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BBOX = "37.20,127.54,37.50,128.17"  # 남위,서경,북위,동경 — 여주~횡성 시나리오 복도를 덮는 상자
USER_AGENT = "odo-redesign/1.0 (contest build tool)"  # 없으면 Overpass가 406을 돌려준다
DEFAULT_ENDPOINT = "https://overpass-api.de/api/interpreter"

# 도로 5등급(+_link), 물(면·강 중심선), 숲, 시가지, 휴게소(노드·면). 이름 등 문자열 태그는 쓰지 않는다(지도 위 글자 0).
QUERY = f"""[out:json][timeout:300][bbox:{BBOX}];
(
  way[highway~"^(motorway|trunk|primary|secondary|tertiary)(_link)?$"];
  way[natural=water];
  relation[natural=water];
  way[waterway=river];
  way[landuse=forest];
  way[natural=wood];
  way[landuse~"^(residential|commercial|industrial|retail)$"];
  node[highway~"^(services|rest_area)$"];
  way[highway~"^(services|rest_area)$"];
);
out geom;
"""


def repo_root() -> str:
    return os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", help="원본 JSON 저장 경로(저장소 밖). --print-query만 줄 때는 없어도 된다")
    ap.add_argument("--endpoint", default=DEFAULT_ENDPOINT)
    ap.add_argument("--timeout", type=int, default=360)
    ap.add_argument("--print-query", action="store_true", help="쿼리만 출력하고 끝낸다")
    args = ap.parse_args()

    if args.print_query:
        print(QUERY)
        return 0
    if not args.out:
        ap.error("--out이 필요하다(원본 JSON 저장 경로, 저장소 밖)")

    out = os.path.abspath(args.out)
    root = repo_root()
    if out == root or out.startswith(root + os.sep):
        print(f"refuse: --out {out} 은 저장소({root}) 안이다. scratchpad 등 밖의 경로를 써라.", file=sys.stderr)
        return 2
    os.makedirs(os.path.dirname(out), exist_ok=True)

    body = urllib.parse.urlencode({"data": QUERY}).encode("utf-8")
    req = urllib.request.Request(
        args.endpoint, data=body, method="POST",
        headers={"User-Agent": USER_AGENT, "Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"},
    )
    t0 = time.time()
    print(f"POST {args.endpoint} bbox={BBOX} ...", flush=True)
    try:
        with urllib.request.urlopen(req, timeout=args.timeout) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code}: {e.read()[:400]!r}", file=sys.stderr)
        return 1
    except (urllib.error.URLError, TimeoutError) as e:
        print(f"network error: {e}", file=sys.stderr)
        return 1
    dt = time.time() - t0

    # 받은 바이트를 그대로 저장하기 전에 JSON으로 읽히는지, 요소 수가 있는지만 확인한다(내용은 신뢰하지 않는 입력).
    try:
        doc = json.loads(raw)
    except ValueError as e:
        print(f"not JSON ({e}); first bytes: {raw[:200]!r}", file=sys.stderr)
        return 1
    elements = doc.get("elements") or []
    if not elements:
        print(f"empty result: {json.dumps(doc)[:400]}", file=sys.stderr)
        return 1
    with open(out, "wb") as f:
        f.write(raw)
    kinds = {}
    for e in elements:
        kinds[e.get("type", "?")] = kinds.get(e.get("type", "?"), 0) + 1
    print(f"saved {out}: {len(raw)/1e6:.1f} MB, {len(elements)} elements {kinds}, {dt:.1f} s")
    print("attribution: © OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
