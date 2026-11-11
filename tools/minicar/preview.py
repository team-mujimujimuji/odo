#!/usr/bin/env python3
"""미니카 미리보기: 앱(ui/vehicle/OdoVehicle.kt)과 같은 순서·블렌드·밤 규칙으로 에셋 레이어를 합성한다.

에뮬레이터에 올리기 전에 후보 에셋을 눈으로 보려는 도구다. 앱 코드를 대신하지 않는다(규칙을 바꾸면 양쪽을 같이 고친다).

  # 1) contact sheet: 차종 × 뷰 × 낮/밤 × 꾸미기 조합
  python -B tools/minicar/preview.py --assets <에셋 폴더> --out <폴더>
      → sheet_<hero|navi|top>_<day|night>.png  (열 = 차종 5, 행 = 꾸미기 조합 4)
        sheet_appsize.png                      (navi·top 을 앱 크기(1 dp = 1 px)로, 낮/밤)
        sheet_top_headings_<day|night>.png     (top 을 45° 간격으로 돌린 것)

  # 2) 지도 플레이트(미니카 없는 화면 캡처) 위에 navi/top 을 얹은 그림
  python -B tools/minicar/preview.py --assets <에셋 폴더> --plate map.png --at 460,356 --view navi --out glance.png
  python -B tools/minicar/preview.py --assets <에셋 폴더> --plate map.png --at 328,172 --heading 62 --scale 1.0 \\
        --vehicle suv --night --loadout color=cherry,roof=box --out home.png

  --at x,y      미니카 앵커(차 중심 아래 바닥점)가 놓일 플레이트 좌표(px)
  --heading 도  top 뷰만. 북 0°, 시계 방향(앱의 headingDeg). navi 는 회전하지 않는다. --view 를 안 주면 top 이 된다
  --scale 배    앱의 markerScale. 그려지는 크기 = 에셋 px × 0.5(dp) × --density × markerScale × naviScale(차종)
  --density     플레이트의 px/dp(에뮬레이터 캡처는 1.0)
  --crop W,H / --zoom N   앵커 둘레만 잘라 N배로 키운 그림도 저장(<out>_zoom.png)

합성 규칙(OdoVehicle 과 같음): compose.json 의 order 순서, wheel→wheel_<휠>, 루프·데칼·번호판은 꾸미기에 따라,
뷰의 skip 은 건너뜀, night 목록(glow_head, glow_tail)은 밤에만. body=차 색 곱하기, spec=Screen,
lamp=램프 색 곱하기(알파 낮 0.55/밤 1.0), 투톤·데칼=보조색 곱하기, glow_head=램프 색 곱한 뒤 Screen, glow_tail=Screen,
그림자 알파 0.9, 밤에는 차 색·보조색을 0.85배. 의존은 Pillow 하나.
"""
import argparse
import json
import math
import os
import re
import sys

sys.dont_write_bytecode = True
from PIL import Image, ImageChops, ImageDraw, ImageFont  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
CATALOG = os.path.join(REPO, "app", "src", "main", "java", "com", "mujimujimuji", "odo", "cosmetic", "CosmeticCatalog.kt")

# ── OdoVehicle.kt 와 같은 값 ───────────────────────────────────────────────────
SHADOW_ALPHA = 0.9
LAMP_ALPHA = {False: 0.55, True: 1.0}       # 낮 / 밤
NIGHT_TINT = 0.85                            # 밤: 차 색·보조색을 이만큼 어둡게
ACCENT_LUMA = 0.35                           # 차 색이 이보다 밝으면 보조색 graphite, 아니면 pearl
PX_TO_DP = 0.5                               # 에셋 px → dp

# CosmeticCatalog.kt 를 못 읽을 때만 쓰는 값(72ec74d 기준)
FALLBACK = {
    "color": {"sky": "5AA9E6", "cherry": "E5484D", "mint": "3CC7A6", "lavender": "9B8CFF", "tangerine": "FF9F43",
              "pearl": "F1F3F6", "graphite": "3A3F47", "sand": "D8C8A4"},
    "lamp": {"white": "FFFFFF", "warm": "FFD6A5", "ice": "CFE6FF"},
}
# 미리보기 바탕(지도 색): ui/theme/OdoTokens.kt 의 OdoDay / OdoNight (72ec74d 기준)
MAP = {False: {"ground": "171C23", "casing": "0F1317", "road": "5A6573"},
       True: {"ground": "0B0F14", "casing": "06080B", "road": "4A5563"}}
ROAD_DP = 14                                 # 바탕에 그리는 길 띠 폭(dp, 어림값)

DEFAULT_LOADOUT = {"color": "sky", "wheel": "steel", "lamp": "white", "roof": "none", "decal": "none", "plate": "none"}
SHEET_LOADOUTS = [
    ("base", {}),
    ("cherry dia warm twotone stripe", {"color": "cherry", "wheel": "dia", "lamp": "warm", "roof": "twotone", "decal": "stripe"}),
    ("pearl aero ice box gold", {"color": "pearl", "wheel": "aero", "lamp": "ice", "roof": "box", "plate": "gold"}),
    ("graphite dia twotone stripe gold", {"color": "graphite", "wheel": "dia", "roof": "twotone", "decal": "stripe", "plate": "gold"}),
]
SHEET_SCALE = {"hero": 0.5, "navi": 1.0, "top": 1.0}     # sheet 에서 에셋 px 에 곱하는 배율
HEADINGS = [0, 45, 90, 135, 180, 225, 270, 315]
LABEL_W, LABEL_H, GAP = 132, 22, 2
PAPER, INK = (30, 34, 40), (220, 224, 230)


def hex_rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def load_palette():
    """꾸미기 색을 앱 소스에서 읽는다(실패하면 FALLBACK)."""
    pal = {slot: {k: hex_rgb(v) for k, v in items.items()} for slot, items in FALLBACK.items()}
    try:
        with open(CATALOG, encoding="utf-8") as f:
            src = f.read()
        for item_id, slot, hx in re.findall(r'CosmeticItem\("(\w+)", Slot\.(COLOR|LAMP), "[^"]+", 0xFF([0-9A-Fa-f]{6})L\)', src):
            pal[slot.lower()][item_id] = hex_rgb(hx)
    except OSError:
        print("WARN CosmeticCatalog.kt 를 읽지 못해 내장 색을 쓴다")
    return pal


def scale_lut(f):
    return [min(255, round(v * f)) for v in range(256)]


class Minicar:
    def __init__(self, root):
        self.root = os.path.abspath(root)
        with open(os.path.join(self.root, "compose.json"), encoding="utf-8") as f:
            self.m = json.load(f)
        self.pal = load_palette()
        self.cache = {}
        self.missing = set()

    def layer(self, vehicle, view, name):
        key = (vehicle, view, name)
        if key not in self.cache:
            self.cache[key] = None
            for ext in (".webp", ".png"):
                p = os.path.join(self.root, vehicle, view, name + ext)
                if os.path.exists(p):
                    self.cache[key] = Image.open(p).convert("RGBA")
                    break
            else:
                self.missing.add("/".join(key))
        return self.cache[key]

    def layers_for(self, view, lo, night):
        """[(order 이름, 파일 이름)]. 앱 VehicleManifest.layersFor 와 같은 규칙."""
        skip = set(self.m["views"][view].get("skip", []))
        night_only = set(self.m.get("night", []))
        out = []
        for name in self.m["order"]:
            f = name
            if name == "wheel":
                f = "wheel_" + lo["wheel"]
            elif name in ("roof_twotone", "roof_box"):
                if lo["roof"] != name[5:]:
                    continue
            elif name == "decal_stripe":
                if lo["decal"] != "stripe":
                    continue
            elif name == "plate_gold":
                if lo["plate"] != "gold":
                    continue
            if f in skip or (name in night_only and not night):
                continue
            out.append((name, f))
        return out

    def ref(self, view):
        """뷰의 기준점(에셋 px): navi·top 은 anchor, hero 는 그림 가운데."""
        spec = self.m["views"][view]
        return tuple(spec["anchor"]) if "anchor" in spec else (spec["px"][0] / 2, spec["px"][1] / 2)

    def marker_scale(self, vehicle, density=1.0, marker=1.0):
        """navi·top 이 앱에서 그려지는 배율(에셋 px → 화면 px)."""
        return PX_TO_DP * density * marker * self.m["vehicles"][vehicle].get("naviScale", 1.0)

    def draw(self, dst, vehicle, view, lo, night, pos, scale=1.0, heading=0.0):
        """dst(RGB) 에 그린다. 뷰 기준점(ref)이 pos(px)에 오고, 에셋 px × scale 크기, heading(도, 시계 방향)만큼 돈다."""
        pw, ph = self.m["views"][view]["px"]
        rx, ry = self.ref(view)
        th = math.radians(heading)
        c, s = math.cos(th), math.sin(th)
        xs, ys = [], []
        for u, v in ((0, 0), (pw, 0), (0, ph), (pw, ph)):       # 돌린 플레이트가 덮는 상자
            du, dv = (u - rx) * scale, (v - ry) * scale
            xs.append(pos[0] + c * du - s * dv)
            ys.append(pos[1] + s * du + c * dv)
        box = (max(0, math.floor(min(xs))), max(0, math.floor(min(ys))),
               min(dst.size[0], math.ceil(max(xs))), min(dst.size[1], math.ceil(max(ys))))
        if box[2] <= box[0] or box[3] <= box[1]:
            return
        paint, lamp = self.pal["color"][lo["color"]], self.pal["lamp"][lo["lamp"]]
        luma = (0.2126 * paint[0] + 0.7152 * paint[1] + 0.0722 * paint[2]) / 255
        accent = self.pal["color"]["graphite" if luma > ACCENT_LUMA else "pearl"]
        if night:
            paint, accent = (tuple(round(v * NIGHT_TINT) for v in col) for col in (paint, accent))
        blend = self.m["blend"]
        region = dst.crop(box).convert("RGB")
        for name, fname in self.layers_for(view, lo, night):
            im = self.layer(vehicle, view, fname)
            if im is None:
                continue
            r, g, b, a = self.place(im, (rx, ry), pos, scale, (c, s), box).split()   # 미리 곱한 알파
            mode = blend.get(name)
            tint = None
            if mode == "modulate":
                tint = {"body": paint, "lamp": lamp}.get(name, accent)
            elif name == "glow_head":
                tint = lamp
            if tint is not None:
                r, g, b = (ch.point(scale_lut(t / 255)) for ch, t in zip((r, g, b), tint))
            f = SHADOW_ALPHA if name == "shadow" else LAMP_ALPHA[night] if name == "lamp" else 1.0
            if f != 1.0:
                r, g, b, a = (ch.point(scale_lut(f)) for ch in (r, g, b, a))
            src = Image.merge("RGB", (r, g, b))
            if mode == "screen":
                region = ImageChops.screen(region, src)
            else:                                                                    # SrcOver
                inv = ImageChops.invert(a)
                region = ImageChops.add(ImageChops.multiply(region, Image.merge("RGB", (inv, inv, inv))), src)
        dst.paste(region, box[:2])

    @staticmethod
    def place(im, ref, pos, scale, cs, box):
        """레이어(RGBA)를 box 크기의 미리 곱한 RGBa 로 옮긴다. 축소는 resize(계단 방지), 회전은 affine."""
        c, s = cs
        w, h = im.size
        sx = sy = 1.0
        if scale != 1.0:
            nw, nh = max(1, round(w * scale)), max(1, round(h * scale))
            im = im.resize((nw, nh), Image.LANCZOS)
            sx, sy = nw / w, nh / h
        rx, ry = ref[0] * sx, ref[1] * sy
        size = (box[2] - box[0], box[3] - box[1])
        if abs(s) < 1e-9 and c > 0:
            out = Image.new("RGBA", size, (0, 0, 0, 0))
            out.paste(im, (round(pos[0] - rx) - box[0], round(pos[1] - ry) - box[1]))
        else:   # dst → src: src = R(-θ)·(dst − pos) + ref
            dx, dy = box[0] - pos[0], box[1] - pos[1]
            out = im.transform(size, Image.AFFINE,
                               (c, s, rx + c * dx + s * dy, -s, c, ry - s * dx + c * dy), resample=Image.BICUBIC)
        return out.convert("RGBa")


# ── 바탕·글자 ──────────────────────────────────────────────────────────────────

def font(size=13):
    try:
        return ImageFont.load_default(size)
    except TypeError:                         # 옛 Pillow
        return ImageFont.load_default()


def backdrop(size, night, road_x=None, road_px=0):
    """지도 바탕색 + (road_x 가 있으면) 세로 길 띠."""
    col = MAP[night]
    im = Image.new("RGB", size, hex_rgb(col["ground"]))
    if road_x is not None and road_px > 0:
        d = ImageDraw.Draw(im)
        d.rectangle((road_x - road_px / 2 - 2, 0, road_x + road_px / 2 + 2, size[1]), fill=hex_rgb(col["casing"]))
        d.rectangle((road_x - road_px / 2, 0, road_x + road_px / 2, size[1]), fill=hex_rgb(col["road"]))
    return im


def grid(cols, rows, cell, col_labels, row_labels):
    cw, ch = cell
    W = LABEL_W + cols * (cw + GAP) + GAP
    H = LABEL_H + rows * (ch + GAP) + GAP
    im = Image.new("RGB", (W, H), PAPER)
    d = ImageDraw.Draw(im)
    f = font()
    for i, t in enumerate(col_labels):
        d.text((LABEL_W + GAP + i * (cw + GAP) + 4, 4), t, fill=INK, font=f)
    for j, t in enumerate(row_labels):
        y = LABEL_H + GAP + j * (ch + GAP) + 4
        for k, line in enumerate(wrap_words(t, 17)):
            d.text((4, y + k * 15), line, fill=INK, font=f)
    return im


def wrap_words(text, width):
    lines, cur = [], ""
    for word in text.split():
        if cur and len(cur) + 1 + len(word) > width:
            lines.append(cur)
            cur = word
        else:
            cur = (cur + " " + word).strip()
    return lines + ([cur] if cur else [])


def cell_xy(i, j, cell):
    return LABEL_W + GAP + i * (cell[0] + GAP), LABEL_H + GAP + j * (cell[1] + GAP)


def loadout(over):
    lo = dict(DEFAULT_LOADOUT)
    lo.update(over)
    return lo


# ── contact sheet ─────────────────────────────────────────────────────────────

def sheet_view(car, view, night, path):
    vehicles = list(car.m["vehicles"])
    pw, ph = car.m["views"][view]["px"]
    k = SHEET_SCALE[view]
    cell = (round(pw * k), round(ph * k))
    rx, ry = car.ref(view)
    im = grid(len(vehicles), len(SHEET_LOADOUTS), cell, vehicles, [n for n, _ in SHEET_LOADOUTS])
    for j, (_, over) in enumerate(SHEET_LOADOUTS):
        for i, v in enumerate(vehicles):
            road = (rx * k, ROAD_DP / PX_TO_DP * k) if view != "hero" else (None, 0)
            tile = backdrop(cell, night, *road)
            car.draw(tile, v, view, loadout(over), night, (rx * k, ry * k), k)
            im.paste(tile, cell_xy(i, j, cell))
    im.save(path)
    return path


def sheet_appsize(car, path):
    """navi·top 을 앱에서 보이는 크기(1 dp = 1 px, markerScale 1)로. 행 = 뷰 × 낮/밤."""
    vehicles = list(car.m["vehicles"])
    cell = (150, 190)
    rows = [(view, night) for view in ("navi", "top") if view in car.m["views"] for night in (False, True)]
    im = grid(len(vehicles), len(rows), cell, vehicles, [f"{view} {'night' if night else 'day'} (app size)" for view, night in rows])
    for j, (view, night) in enumerate(rows):
        for i, v in enumerate(vehicles):
            tile = backdrop(cell, night, cell[0] / 2, ROAD_DP)
            pos = (cell[0] / 2, cell[1] * (0.62 if view == "navi" else 0.5))
            car.draw(tile, v, view, loadout({}), night, pos, car.marker_scale(v))
            im.paste(tile, cell_xy(i, j, cell))
    im.save(path)
    return path


def sheet_headings(car, night, path):
    vehicles = list(car.m["vehicles"])
    cell = (200, 200)
    im = grid(len(HEADINGS), len(vehicles), cell, [f"{h} deg" for h in HEADINGS], vehicles)
    for j, v in enumerate(vehicles):
        for i, hd in enumerate(HEADINGS):
            tile = backdrop(cell, night)
            car.draw(tile, v, "top", loadout({}), night, (cell[0] / 2, cell[1] / 2), car.marker_scale(v) * 1.5, hd)
            im.paste(tile, cell_xy(i, j, cell))
    im.save(path)
    return path


def make_sheets(car, out):
    os.makedirs(out, exist_ok=True)
    done = []
    for view in car.m["views"]:
        for night in (False, True):
            done.append(sheet_view(car, view, night, os.path.join(out, f"sheet_{view}_{'night' if night else 'day'}.png")))
    done.append(sheet_appsize(car, os.path.join(out, "sheet_appsize.png")))
    if "top" in car.m["views"]:
        for night in (False, True):
            done.append(sheet_headings(car, night, os.path.join(out, f"sheet_top_headings_{'night' if night else 'day'}.png")))
    return done


# ── 지도 플레이트 ──────────────────────────────────────────────────────────────

def pair(text, kind=float):
    a, b = re.split(r"[,x]", text)
    return kind(a), kind(b)


def parse_loadout(text):
    over = {}
    for part in filter(None, (text or "").split(",")):
        k, _, v = part.partition("=")
        if k not in DEFAULT_LOADOUT or not v:
            sys.exit(f"--loadout 항목이 틀림: {part} (쓸 수 있는 키: {', '.join(DEFAULT_LOADOUT)})")
        over[k] = v
    return loadout(over)


def on_plate(car, a):
    view = a.view or ("top" if a.heading is not None else "navi")
    if view not in ("navi", "top") or view not in car.m["views"]:
        sys.exit(f"플레이트에는 navi/top 만 얹는다(이 에셋의 뷰: {', '.join(car.m['views'])})")
    heading = (a.heading or 0.0) if view == "top" else 0.0
    if view == "navi" and a.heading:
        print("NOTE navi 는 회전하지 않는다(--heading 무시)")
    lo = parse_loadout(a.loadout)
    for slot in ("color", "lamp"):
        if lo[slot] not in car.pal[slot]:
            sys.exit(f"모르는 {slot}: {lo[slot]} ({', '.join(car.pal[slot])})")
    plate = Image.open(a.plate).convert("RGB")
    pos = pair(a.at)
    scale = car.marker_scale(a.vehicle, a.density, a.scale)
    car.draw(plate, a.vehicle, view, lo, a.night, pos, scale, heading)
    os.makedirs(os.path.dirname(os.path.abspath(a.out)) or ".", exist_ok=True)
    plate.save(a.out)
    done = [a.out]
    if a.crop:
        cw, ch = pair(a.crop, int)
        x0 = int(max(0, min(plate.size[0] - cw, pos[0] - cw / 2)))
        y0 = int(max(0, min(plate.size[1] - ch, pos[1] - ch / 2)))
        z = plate.crop((x0, y0, x0 + cw, y0 + ch))
        z = z.resize((z.size[0] * a.zoom, z.size[1] * a.zoom), Image.LANCZOS)
        zp = os.path.splitext(a.out)[0] + "_zoom.png"
        z.save(zp)
        done.append(zp)
    print(f"{view} {a.vehicle} at {pos[0]:g},{pos[1]:g} heading {heading:g} scale {scale:g} px/px "
          f"({'night' if a.night else 'day'})")
    return done


def main():
    ap = argparse.ArgumentParser(description="미니카 에셋 미리보기(contact sheet / 지도 플레이트 합성)")
    ap.add_argument("--assets", default=os.path.join(REPO, "app", "src", "main", "assets", "minicar"),
                    help="에셋 폴더(compose.json + <차종>/<뷰>/<레이어>.webp). 기본: 앱 에셋")
    ap.add_argument("--out", required=True, help="contact sheet 는 폴더, 플레이트 합성은 PNG 파일")
    ap.add_argument("--plate", help="지도 플레이트 PNG(미니카 없는 화면 캡처)")
    ap.add_argument("--at", help="x,y: 앵커가 놓일 플레이트 좌표(px)")
    ap.add_argument("--view", choices=["navi", "top"])
    ap.add_argument("--vehicle", default="compact")
    ap.add_argument("--heading", type=float, help="top 뷰 방향(도, 북 0°, 시계 방향)")
    ap.add_argument("--scale", type=float, default=1.0, help="markerScale(기본 1.0)")
    ap.add_argument("--density", type=float, default=1.0, help="플레이트 px/dp(기본 1.0)")
    ap.add_argument("--night", action="store_true")
    ap.add_argument("--loadout", help="color=cherry,wheel=dia,lamp=warm,roof=twotone|box,decal=stripe,plate=gold")
    ap.add_argument("--crop", help="W,H: 앵커 둘레를 잘라 <out>_zoom.png 로도 저장")
    ap.add_argument("--zoom", type=int, default=3)
    a = ap.parse_args()
    car = Minicar(a.assets)
    if a.vehicle not in car.m["vehicles"]:
        sys.exit(f"모르는 차종: {a.vehicle} ({', '.join(car.m['vehicles'])})")
    if a.plate:
        if not a.at:
            ap.error("--plate 에는 --at x,y 가 필요")
        done = on_plate(car, a)
    else:
        done = make_sheets(car, a.out)
    for p in done:
        with Image.open(p) as im:
            print(f"{p} {im.size[0]}x{im.size[1]}")
    if car.missing:
        print(f"WARN 없는 레이어 {len(car.missing)}개(앱은 조용히 건너뛴다): {', '.join(sorted(car.missing)[:8])}")


if __name__ == "__main__":
    main()
