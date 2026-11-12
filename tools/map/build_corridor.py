#!/usr/bin/env python3
"""Overpass 원본 JSON → `app/src/main/assets/map/corridor.json` (설계서 §4.1).

가공 순서
 1. 시나리오 경로(`tools/scenario/route_yeoju_hoengseong.json`, OSRM 결과)를 로컬 미터로 바꾸고 2 km 격자에 색인한다.
 2. 요소마다 경로와의 거리를 재서 ±BUFFER(4 km) 복도 밖이면 버린다. 선(도로·강)은 복도 + 여유 밖으로 나간 구간을 잘라 낸다.
 3. Douglas-Peucker(기본 10 m)로 점을 줄이고, 정수 미터로 반올림한 뒤 연속 중복점을 지운다.
 4. 작은 면(40 m × 40 m 미만)·2점 미만 선은 버린다. 휴게소는 면 중심 우선 1점씩, 300 m 안의 다른 요소는 같은 휴게소로 합친다.
 5. 검증 출력: 레이어별 요소·점 수, 총 점 수, 바이트, 로컬/위경도 bbox, 휴게소 목록.

좌표: x = (lon − 127.54)·111320·cos(37.20°), y = (lat − 37.20)·111320 (정수 m, 원점 = 조회 bbox 남서(37.20, 127.54) = origin).
출력의 "bbox"는 이 원점 기준 로컬 m 범위다(조회 bbox와 다름).
형식(값은 모두 정수):
 {"version":1,"origin":{"lat":37.2,"lng":127.54},"attribution":"© OpenStreetMap contributors, ODbL 1.0","cell_m":1000,
  "bbox":[minx,miny,maxx,maxy],
  "polys":{"water":[[x,y,...]],"forest":[...],"urban":[...]},   # 고리(마지막 점은 첫 점과 같지 않음, 그리기 때 close)
  "rivers":[[x,y,...]],                                          # waterway=river 중심선(선)
  "roads":[{"cls":"motorway","pts":[x,y,...]}, ...],             # cls ∈ motorway trunk primary secondary tertiary
  "route":[x,y,...],
  "pois":[{"type":"rest","x":..,"y":..,"src":"OSM way 344480538"}]}

사용:
  python3 -I tools/map/build_corridor.py --raw /path/to/osm-data/overpass_raw.json \
      --route tools/scenario/route_yeoju_hoengseong.json --out app/src/main/assets/map/corridor.json
옵션: --buffer 4000  --tol 10  --min-poly 40
"""
import argparse
import json
import math
import os
import sys
import time

ORIGIN_LAT, ORIGIN_LNG = 37.20, 127.54
K = 111_320.0
COS0 = math.cos(math.radians(ORIGIN_LAT))
ROAD_CLASSES = ("motorway", "trunk", "primary", "secondary", "tertiary")
POLY_KINDS = ("water", "forest", "urban")
ATTRIBUTION = "© OpenStreetMap contributors, ODbL 1.0"


def to_m(lon, lat):
    return ((lon - ORIGIN_LNG) * K * COS0, (lat - ORIGIN_LAT) * K)


def to_ll(x, y):
    return (y / K + ORIGIN_LAT, x / (K * COS0) + ORIGIN_LNG)


def road_class(tags):
    hw = tags.get("highway") or ""
    base = hw[:-5] if hw.endswith("_link") else hw
    return base if base in ROAD_CLASSES else None


def poly_kind(tags):
    if tags.get("natural") == "water":
        return "water"
    if tags.get("landuse") == "forest" or tags.get("natural") == "wood":
        return "forest"
    if tags.get("landuse") in ("residential", "commercial", "industrial", "retail"):
        return "urban"
    return None


def is_rest(tags):
    return tags.get("highway") in ("services", "rest_area")


# ---------- 기하 ----------
def seg_dist(p, a, b):
    px, py = p
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


class RouteIndex:
    """경로 선분을 2 km 격자에 넣어 점→경로 거리를 빠르게 잰다(격자 밖은 '멀다')."""

    CELL = 2000.0

    def __init__(self, pts, reach):
        self.grid = {}
        pad = int(reach // self.CELL) + 1
        for i in range(len(pts) - 1):
            a, b = pts[i], pts[i + 1]
            for cx in range(int(min(a[0], b[0]) // self.CELL) - pad, int(max(a[0], b[0]) // self.CELL) + pad + 1):
                for cy in range(int(min(a[1], b[1]) // self.CELL) - pad, int(max(a[1], b[1]) // self.CELL) + pad + 1):
                    self.grid.setdefault((cx, cy), []).append((a, b))

    def dist(self, p):
        segs = self.grid.get((int(p[0] // self.CELL), int(p[1] // self.CELL)))
        if not segs:
            return 1e9
        return min(seg_dist(p, a, b) for a, b in segs)


def douglas_peucker(pts, tol):
    """반복(스택) 구현이라 재귀 깊이 제한에 걸리지 않는다."""
    n = len(pts)
    if n < 3:
        return list(pts)
    keep = [False] * n
    keep[0] = keep[-1] = True
    stack = [(0, n - 1)]
    while stack:
        i0, i1 = stack.pop()
        if i1 - i0 < 2:
            continue
        a, b = pts[i0], pts[i1]
        imax, dmax = i0, -1.0
        for i in range(i0 + 1, i1):
            d = seg_dist(pts[i], a, b)
            if d > dmax:
                imax, dmax = i, d
        if dmax > tol:
            keep[imax] = True
            stack.append((i0, imax))
            stack.append((imax, i1))
    return [p for p, k in zip(pts, keep) if k]


def integerize(pts):
    out = []
    for x, y in pts:
        q = (int(round(x)), int(round(y)))
        if not out or out[-1] != q:
            out.append(q)
    return out


def flat(pts):
    return [c for p in pts for c in p]


def clip_line(pts, idx, reach):
    """복도(+여유) 밖으로 나간 꼭짓점 구간을 잘라 여러 조각으로 돌려준다. 나가는 첫 점 하나는 남겨 선이 화면 밖으로 이어지게 한다."""
    inside = [idx.dist(p) <= reach for p in pts]
    pieces, cur = [], []
    for i, p in enumerate(pts):
        if inside[i] or (i > 0 and inside[i - 1]):
            cur.append(p)
        elif cur:
            pieces.append(cur)
            cur = []
        if not inside[i] and cur and (i + 1 < len(pts)) and not inside[i + 1]:
            pieces.append(cur)
            cur = []
    if cur:
        pieces.append(cur)
    return [pc for pc in pieces if len(pc) >= 2]


def assemble_rings(ways):
    """관계의 outer 멤버(조각 선)를 끝점이 같은 것끼리 이어 닫힌 고리로 만든다."""
    pieces = [list(w) for w in ways if len(w) >= 2]
    rings = []
    while pieces:
        ring = pieces.pop()
        changed = True
        while changed and ring[0] != ring[-1]:
            changed = False
            for j, pc in enumerate(pieces):
                if pc[0] == ring[-1]:
                    ring += pc[1:]
                elif pc[-1] == ring[-1]:
                    ring += pc[-2::-1]
                elif pc[-1] == ring[0]:
                    ring = pc[:-1] + ring
                elif pc[0] == ring[0]:
                    ring = pc[::-1][:-1] + ring
                else:
                    continue
                pieces.pop(j)
                changed = True
                break
        rings.append(ring)
    return rings


def ring_bbox(r):
    xs = [p[0] for p in r]
    ys = [p[1] for p in r]
    return min(xs), min(ys), max(xs), max(ys)


def centroid(r):
    return (sum(p[0] for p in r) / len(r), sum(p[1] for p in r) / len(r))


# ---------- 메인 ----------
def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--raw", required=True)
    ap.add_argument("--route", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--buffer", type=float, default=4000.0, help="경로 양쪽 복도 폭(m)")
    ap.add_argument("--tol", type=float, default=10.0, help="Douglas-Peucker 허용 오차(m)")
    ap.add_argument("--min-poly", type=float, default=40.0, help="이보다 작은 면(가로·세로 모두)은 버림(m)")
    ap.add_argument("--poi-merge", type=float, default=300.0, help="이 거리(m) 안의 휴게소 요소는 하나로 합침")
    args = ap.parse_args()
    t0 = time.time()

    with open(args.raw, encoding="utf-8") as f:
        doc = json.load(f)
    with open(args.route, encoding="utf-8") as f:
        route_ll = json.load(f)["routes"][0]["geometry"]["coordinates"]  # [lon, lat]
    route_m = [to_m(lon, lat) for lon, lat in route_ll]
    reach_line = args.buffer + 500.0  # 선은 복도보다 조금 더 밖까지 남겨 화면 밖으로 이어지게
    idx = RouteIndex(route_m, reach_line)

    polys = {k: [] for k in POLY_KINDS}  # kind -> [ring(list of (x,y) float)]
    rivers, roads = [], []  # roads: (cls, pts)
    rest_ways, rest_nodes = [], []  # (id, (x,y))
    counts = {"elements": 0, "dropped_far": 0}

    for e in doc.get("elements", []):
        counts["elements"] += 1
        tags = e.get("tags") or {}
        typ = e.get("type")
        if typ == "node":
            if is_rest(tags):
                p = to_m(e["lon"], e["lat"])
                if idx.dist(p) <= args.buffer:
                    rest_nodes.append((e["id"], p))
            continue
        if typ == "way":
            g = [to_m(n["lon"], n["lat"]) for n in e.get("geometry") or [] if "lon" in n]
            if len(g) < 2:
                continue
            if is_rest(tags):
                if any(idx.dist(p) <= args.buffer for p in g):
                    rest_ways.append((e["id"], centroid(g[:-1] if g[0] == g[-1] else g)))
                continue
            cls = road_class(tags)
            if cls:
                pieces = clip_line(g, idx, reach_line)
                if not pieces:
                    counts["dropped_far"] += 1
                for pc in pieces:
                    roads.append((cls, pc))
                continue
            if tags.get("waterway") == "river":
                for pc in clip_line(g, idx, reach_line):
                    rivers.append(pc)
                continue
            kind = poly_kind(tags)
            if kind and g[0] == g[-1] and len(g) >= 4:
                if any(idx.dist(p) <= args.buffer for p in g):
                    polys[kind].append(g[:-1])
                else:
                    counts["dropped_far"] += 1
            continue
        if typ == "relation":
            kind = poly_kind(tags)
            if not kind:
                continue
            outers = []
            for m in e.get("members") or []:
                if m.get("role") == "outer" and m.get("type") == "way":
                    outers.append([to_m(n["lon"], n["lat"]) for n in m.get("geometry") or [] if "lon" in n])
            for ring in assemble_rings(outers):
                if len(ring) < 4:
                    continue
                if ring[0] == ring[-1]:
                    ring = ring[:-1]
                if any(idx.dist(p) <= args.buffer for p in ring[:: max(1, len(ring) // 200)]) or any(idx.dist(p) <= args.buffer for p in ring):
                    polys[kind].append(ring)
                else:
                    counts["dropped_far"] += 1

    # 단순화·정수화
    out_polys = {}
    stats = {}
    total_pts = 0
    for kind in POLY_KINDS:
        rings_out = []
        for r in polys[kind]:
            s = douglas_peucker(r + [r[0]], args.tol)
            s = integerize(s)
            if len(s) >= 2 and s[0] == s[-1]:
                s = s[:-1]
            if len(s) < 3:
                continue
            x0, y0, x1, y1 = ring_bbox(s)
            if (x1 - x0) < args.min_poly and (y1 - y0) < args.min_poly:
                continue
            rings_out.append(flat(s))
            total_pts += len(s)
        out_polys[kind] = rings_out
        stats[kind] = (len(rings_out), sum(len(r) // 2 for r in rings_out))

    out_rivers = []
    for pc in rivers:
        s = integerize(douglas_peucker(pc, args.tol))
        if len(s) >= 2:
            out_rivers.append(flat(s))
            total_pts += len(s)
    stats["river"] = (len(out_rivers), sum(len(r) // 2 for r in out_rivers))

    out_roads = []
    for cls in ROAD_CLASSES:
        n_pts = 0
        n_ways = 0
        for c, pc in roads:
            if c != cls:
                continue
            s = integerize(douglas_peucker(pc, args.tol))
            if len(s) >= 2:
                out_roads.append({"cls": cls, "pts": flat(s)})
                n_pts += len(s)
                n_ways += 1
        stats[cls] = (n_ways, n_pts)
        total_pts += n_pts

    route_i = integerize(route_m)
    total_pts += len(route_i)

    # 휴게소: 면 중심 우선(면끼리는 Overpass 응답 순서, 면적으로 고르지 않음), 300 m 안의 다른 면·노드는 같은 휴게소로 보고
    # 하나만 남긴다(픽토 1종, 겹침 방지). 순서를 바꾸면 고르는 면이 달라져 corridor.json과 README의 sha256도 바뀐다.
    pois = []
    candidates = [("way", wid, p) for wid, p in rest_ways] + [("node", nid, p) for nid, p in rest_nodes]
    for typ, oid, p in candidates:
        if all(math.hypot(p[0] - q["x"], p[1] - q["y"]) > args.poi_merge for q in pois):
            pois.append({"type": "rest", "x": int(round(p[0])), "y": int(round(p[1])), "src": f"OSM {typ} {oid}"})
    pois.sort(key=lambda q: (q["x"], q["y"]))

    # bbox(모든 정수 좌표)
    xs, ys = [], []
    for rings in out_polys.values():
        for r in rings:
            xs += r[0::2]
            ys += r[1::2]
    for r in out_rivers:
        xs += r[0::2]
        ys += r[1::2]
    for rd in out_roads:
        xs += rd["pts"][0::2]
        ys += rd["pts"][1::2]
    xs += [p[0] for p in route_i]
    ys += [p[1] for p in route_i]
    bbox = [min(xs), min(ys), max(xs), max(ys)]

    result = {
        "version": 1,
        "origin": {"lat": ORIGIN_LAT, "lng": ORIGIN_LNG},
        "attribution": ATTRIBUTION,
        "cell_m": 1000,
        "bbox": bbox,
        "polys": out_polys,
        "rivers": out_rivers,
        "roads": out_roads,
        "route": flat(route_i),
        "pois": pois,
    }
    text = json.dumps(result, ensure_ascii=False, separators=(",", ":"))
    # 검증: 이름·문자열 태그가 섞이지 않았는지(지도 위 글자 0), 좌표가 모두 정수인지
    for kind, rings in out_polys.items():
        assert all(isinstance(v, int) for r in rings for v in r), kind
    assert all(isinstance(v, int) for rd in out_roads for v in rd["pts"])
    assert all(isinstance(v, int) for v in result["route"])
    assert "name" not in text

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(text)

    sw, ne = to_ll(bbox[0], bbox[1]), to_ll(bbox[2], bbox[3])
    print(f"elements={counts['elements']} dropped_far={counts['dropped_far']} buffer={args.buffer:.0f} m tol={args.tol:.0f} m")
    for k, (n, p) in stats.items():
        print(f"  {k:10s} features={n:5d} pts={p:6d}")
    print(f"  route      pts={len(route_i)}  pois={len(pois)}")
    for q in pois:
        lat, lng = to_ll(q["x"], q["y"])
        print(f"    rest ({q['x']},{q['y']}) = ({lat:.5f},{lng:.5f}) {q['src']}")
    print(f"total points={total_pts}  bytes={len(text.encode('utf-8'))} ({len(text.encode('utf-8'))/1e6:.2f} MB)")
    print(f"bbox local m: x {bbox[0]}..{bbox[2]} ({(bbox[2]-bbox[0])/1000:.1f} km)  y {bbox[1]}..{bbox[3]} ({(bbox[3]-bbox[1])/1000:.1f} km)")
    print(f"bbox lat/lng: SW ({sw[0]:.4f},{sw[1]:.4f}) NE ({ne[0]:.4f},{ne[1]:.4f})")
    print(f"wrote {args.out} in {time.time()-t0:.1f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
