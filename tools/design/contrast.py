#!/usr/bin/env python3
"""오도 색 토큰 대비표(WCAG 2.x 상대 휘도 기준) — 설계서 §1.4 표를 다시 계산한다.

사용: python3 tools/design/contrast.py
표준 라이브러리만 쓴다. 토큰 값은 app/src/main/java/com/mujimujimuji/odo/ui/theme/OdoTokens.kt와 같아야 하며,
토큰을 바꾸면 이 파일의 값도 함께 고치고 다시 돌려 설계서 §1.4 표를 갱신한다.
기준: 글자 ≥ 4.5, 큰 글자·컨트롤·아이콘·지도 그래픽 ≥ 3. 알파가 섞인 색은 바탕과 먼저 합성한 뒤 잰다.
"""


def _lin(c: int) -> float:
    c /= 255
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def luminance(hex_color: str) -> float:
    h = hex_color.lstrip("#")
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    return 0.2126 * _lin(r) + 0.7152 * _lin(g) + 0.0722 * _lin(b)


def ratio(a: str, b: str) -> float:
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def blend(fg: str, alpha: float, bg: str) -> str:
    f, g = fg.lstrip("#"), bg.lstrip("#")
    return "#" + "".join(
        "%02X" % round(alpha * int(f[i : i + 2], 16) + (1 - alpha) * int(g[i : i + 2], 16)) for i in (0, 2, 4)
    )


# OdoTokens.kt — OdoNight / OdoDay
NIGHT = dict(
    ground="#0B0F14", surface="#121821", line="#2E3845", ink="#F4F6F8", inkDim="#8D9AAB", track="#6E7A8B",
    amber="#F0A83A", onAmber="#231B00", route="#3D86FF", routeOutline="#0E2E6B", routePassed="#4A5566",
    mapWater="#0E1826", mapForest="#0E1711", mapUrban="#141920", roadCore="#4A5563", qrPlate="#D0D4DA", qrModule="#0B0F14",
)
DAY = dict(
    NIGHT,
    ground="#171C23", surface="#1F262F", line="#3E4857", ink="#FFFFFF", inkDim="#A3AEBC", track="#7E8A9C",
    routePassed="#5C6778", mapWater="#1B2838", mapForest="#1A241C", mapUrban="#20262E", roadCore="#5A6573",
)
PAINTS = [
    ("sky", "#5AA9E6"), ("cherry", "#E5484D"), ("mint", "#3CC7A6"), ("lavender", "#9B8CFF"),
    ("tangerine", "#FF9F43"), ("pearl", "#F1F3F6"), ("graphite", "#3A3F47"), ("sand", "#D8C8A4"),
]


def rows(t: dict) -> list:
    ink_on = lambda a, bg: ratio(blend(t["ink"], a, t[bg]), t[bg])  # noqa: E731
    return [
        ("ink 96% on surface / on ground", f"{ink_on(0.96, 'surface'):.2f} / {ink_on(0.96, 'ground'):.2f}"),
        ("ink 88% on surface", f"{ink_on(0.88, 'surface'):.2f}"),
        ("ink 72% on surface", f"{ink_on(0.72, 'surface'):.2f}"),
        ("ink 56% on surface(비활성 글자)", f"{ink_on(0.56, 'surface'):.2f}"),
        ("ink 38% on surface(장식선)", f"{ink_on(0.38, 'surface'):.2f}"),
        ("inkDim(캡션) on surface / ground", f"{ratio(t['inkDim'], t['surface']):.2f} / {ratio(t['inkDim'], t['ground']):.2f}"),
        ("track(게이지 트랙) on surface / ground", f"{ratio(t['track'], t['surface']):.2f} / {ratio(t['track'], t['ground']):.2f}"),
        ("line(헤어라인) on surface", f"{ratio(t['line'], t['surface']):.2f}"),
        ("amber on surface / on ground", f"{ratio(t['amber'], t['surface']):.2f} / {ratio(t['amber'], t['ground']):.2f}"),
        ("onAmber on amber", f"{ratio(t['onAmber'], t['amber']):.2f}"),
        ("amber(채움) vs track(미채움)", f"{ratio(t['amber'], t['track']):.2f}"),
        (
            "route on ground / water / forest / urban",
            " / ".join(f"{ratio(t['route'], t[k]):.2f}" for k in ("ground", "mapWater", "mapForest", "mapUrban")),
        ),
        ("route on roadCore", f"{ratio(t['route'], t['roadCore']):.2f}"),
        ("routePassed on ground", f"{ratio(t['routePassed'], t['ground']):.2f}"),
        ("roadCore on ground / urban", f"{ratio(t['roadCore'], t['ground']):.2f} / {ratio(t['roadCore'], t['mapUrban']):.2f}"),
        # 요약 지도 출처 캡션: ground 60 % 스크림 아래 가장 밝은 지도 색(roadCore) 기준(impeccable §3.3)
        ("출처 캡션 inkDim on ground 60 % 스크림 over roadCore", f"{ratio(t['inkDim'], blend(t['ground'], 0.6, t['roadCore'])):.2f}"),
        ("ink on route(흰 글자 on 파란 버튼 — 폐기)", f"{ratio(t['ink'], t['route']):.2f}"),
        # 설계서 초판의 주간 값 17.12는 ink 100%로 잰 것. 96% 합성(야간 값과 같은 방식)이 맞다 → 15.84.
        ("ground 글자 on ink96 채움", f"{ratio(t['ground'], blend(t['ink'], 0.96, t['ground'])):.2f}"),
        ("qrModule on qrPlate", f"{ratio(t['qrModule'], t['qrPlate']):.2f}"),
        ("페인트 스와치 on surface(" + "/".join(n for n, _ in PAINTS) + ")", "/".join(f"{ratio(c, t['surface']):.1f}" for _, c in PAINTS)),
        # 견본 링(§1.1): 스와치 원 안쪽 가장자리 1dp ink 38%(Chip·차고 색 칸 공통). 원 안에 그리므로 링 색 = ink 38% over 페인트.
        # 잠긴 칸은 페인트를 38%로 surface에 합성한 뒤 링을 얹는다(GarageScreen LOCKED_PAINT_ALPHA).
        ("graphite 견본 링 on surface / 링 vs graphite / 잠김 링 on surface", _graphite_ring(t)),
    ]


def _graphite_ring(t: dict) -> str:
    graphite = dict(PAINTS)["graphite"]
    ring = blend(t["ink"], 0.38, graphite)
    locked_ring = blend(t["ink"], 0.38, blend(graphite, 0.38, t["surface"]))
    return f"{ratio(ring, t['surface']):.2f} / {ratio(ring, graphite):.2f} / {ratio(locked_ring, t['surface']):.2f}"


def main() -> None:
    night, day = rows(NIGHT), rows(DAY)
    print("| 조합 | 야간 | 주간 |")
    print("|---|---|---|")
    for (name, n), (_, d) in zip(night, day):
        print(f"| {name} | {n} | {d} |")
    # 글랜스 패널 알파(§0.3): 패널 밑에 route가 지나갈 때 ink96 글자 대비
    vals = []
    for a in (1.0, 0.96, 0.92):
        bg = blend(NIGHT["surface"], a, NIGHT["route"])
        vals.append(f"{ratio(blend(NIGHT['ink'], 0.96, bg), bg):.2f}")
    print(f"| 글랜스 패널 alpha 1.0 / 0.96 / 0.92, 밑에 route가 있을 때 ink96 | {' / '.join(vals)} | — |")


if __name__ == "__main__":
    main()
