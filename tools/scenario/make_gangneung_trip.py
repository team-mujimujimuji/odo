#!/usr/bin/env python3
"""데모 시나리오 gangneung_trip.json 생성기.

경로: route_yeoju_hoengseong.json (OSRM 공개 서버, OSM 데이터 © OpenStreetMap contributors, ODbL)
      여주휴게소(강릉방향, OSM way 344480538) → 횡성휴게소(강릉방향, OSM node 414680791)

대본에 맞춘 구성:
  - 여주휴게소 정차(P) 5초 → D, 초당 3 km/h로 100 km/h까지 가속
  - 45 km 지점(일일 상한 30 km 이후) 급감속 1회: 1초에 100→90 km/h (기준 7.5 km/h/s)
  - 횡성휴게소 진입 전 초당 3 km/h로 감속해 정차 → P, 11분 체류(장소 업적 10분 조건)
  - 운전자 혼자, 벨트 체결, 지시등 OFF, 와이퍼 OFF
실행: python3 make_gangneung_trip.py > ../../app/src/main/assets/scenarios/gangneung_trip.json
"""
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CRUISE = 100.0
ACCEL = 3.0          # km/h per s
DECEL = 3.0
HARSH_AT_KM = 45.0
HARSH_DROP = 10.0    # 1초에 10 km/h 감소
DWELL_SEC = 660


def haversine(a, b):
    r = 6371000.0
    la1, lo1, la2, lo2 = map(math.radians, (a[1], a[0], b[1], b[0]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def main():
    route = json.load(open(os.path.join(HERE, "route_yeoju_hoengseong.json")))
    coords = route["routes"][0]["geometry"]["coordinates"]  # [lng, lat]
    cum = [0.0]
    for i in range(1, len(coords)):
        cum.append(cum[-1] + haversine(coords[i - 1], coords[i]))
    total = cum[-1]

    def point_at(d):
        d = max(0.0, min(total, d))
        lo, hi = 0, len(cum) - 1
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if cum[mid] <= d:
                lo = mid
            else:
                hi = mid
        seg = cum[hi] - cum[lo]
        f = 0.0 if seg == 0 else (d - cum[lo]) / seg
        a, b = coords[lo], coords[hi]
        return a[1] + (b[1] - a[1]) * f, a[0] + (b[0] - a[0]) * f

    samples = []
    t = 0

    def add(gear, v, d):
        lat, lng = point_at(d)
        samples.append({
            "t": t, "gear": gear, "speedKmh": round(v, 1),
            "lat": round(lat, 6), "lng": round(lng, 6),
            "seatbelt": True, "turnSignal": "OFF",
        })

    for _ in range(5):
        add("P", 0.0, 0.0)
        t += 1

    d = 0.0
    v = 0.0
    harsh_done = False
    while True:
        remaining = total - d
        stop_dist = (v / 3.6) * (v / DECEL) / 2.0  # 등감속 정지 거리(m)
        if remaining <= stop_dist + v / 3.6:
            nv = max(0.0, v - DECEL)
        elif not harsh_done and d >= HARSH_AT_KM * 1000:
            nv = v - HARSH_DROP
            harsh_done = True
        elif v < CRUISE:
            nv = min(CRUISE, v + (2.0 if harsh_done and v >= CRUISE - HARSH_DROP else ACCEL))
        else:
            nv = v
        d += (v + nv) / 2.0 / 3.6
        v = nv
        add("D", v, d)
        t += 1
        if v == 0.0:
            break

    end_d = d
    add("P", 0.0, end_d)
    t += DWELL_SEC
    add("P", 0.0, end_d)

    out = {
        "name": "여주휴게소 → 횡성휴게소 (영동고속도로)",
        "source": "경로: OSRM(router.project-osrm.org), OSM 데이터 © OpenStreetMap contributors (ODbL). tools/scenario/make_gangneung_trip.py로 생성",
        "tickMs": 1000,
        "speedup": 20,
        "samples": samples,
    }
    json.dump(out, sys.stdout, ensure_ascii=False, separators=(",", ":"))
    print(f"route={total:.0f}m driven={end_d:.0f}m samples={len(samples)} end_t={t}s", file=sys.stderr)
    for km in (3.0,):
        print(f"gate@{km}km lat,lng={point_at(km * 1000)}", file=sys.stderr)


if __name__ == "__main__":
    main()
