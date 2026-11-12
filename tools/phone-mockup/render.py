#!/usr/bin/env python3
"""오도 폰 동반 화면 목업 생성기(설계서 §7.3).

차 앱과 같은 색 토큰(OdoTokens.kt의 OdoNight), 꾸미기 카탈로그·해금 표, 레벨 표·일일 상한,
차 화면 문구(strings.xml), 업적 목록, 미니카 hero 플레이트(app/src/main/assets/minicar)를
앱 소스에서 그대로 읽어 그린다. 의존은 Pillow 하나이고, 시각을 읽지 않고 카메라 잡음만
고정 시드 난수를 써서 몇 번을 돌려도 같은 PNG가 나온다.

    sh tools/phone-mockup/fetch_fonts.sh
    python3 -I tools/phone-mockup/render.py          # → docs/phone-mockup/*.png + manifest.json
"""
import argparse
import base64
import hashlib
import html
import json
import math
import os
import random
import re
import sys

import PIL
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont, features

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, '..', '..'))
MAIN = os.path.join(REPO, 'app', 'src', 'main')
SRC = os.path.join(MAIN, 'java', 'com', 'mujimujimuji', 'odo')
FONT_DIR = os.path.join(HERE, 'fonts')

S = 3                                        # px / dp (390×844 dp → 1170×2532 px)
SS = 4                                       # 도형 안티에일리어싱 배율
FRAME = (390, 844)                           # 기기 전체(dp)
BEZEL = 12
SCREEN = (FRAME[0] - 2 * BEZEL, FRAME[1] - 2 * BEZEL)   # 366×820 dp
M = 16                                       # 좌우 여백
CW = SCREEN[0] - 2 * M                       # 내용 폭 334 dp
HI, MID, DIS = 0.96, 0.72, 0.38              # ink 알파 단계(설계서 §1.1). HI = 차 본문·숫자 글자색 OdoColors.ink96
LOCKED = 0.56                                # 잠긴 항목 제목(차 KeyStrip DISABLED_ALPHA와 같음)

C = {}       # 색 토큰
APP = None   # 앱 소스에서 읽은 값
CFG = {}     # screens.json
CP = {}      # 문구
CAR = None   # 미니카 합성기
QR = None    # QR 모듈 행렬


def read(p):
    with open(p, encoding='utf-8') as f:
        return f.read()


def rel(p):
    return os.path.relpath(p, REPO).replace(os.sep, '/')


def hex_rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def sha256(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


# ── 앱 소스 읽기 ────────────────────────────────────────────────────────────

class AppSource:
    """목업 숫자를 손으로 옮기지 않도록 앱 소스를 직접 읽는다."""

    def __init__(self):
        p_tok = os.path.join(SRC, 'ui', 'theme', 'OdoTokens.kt')
        p_veh = os.path.join(SRC, 'cosmetic', 'VehicleType.kt')
        p_cat = os.path.join(SRC, 'cosmetic', 'CosmeticCatalog.kt')
        p_lv = os.path.join(SRC, 'engine', 'LevelTable.kt')
        p_thr = os.path.join(SRC, 'engine', 'Thresholds.kt')
        p_str = os.path.join(MAIN, 'res', 'values', 'strings.xml')
        p_ach = os.path.join(MAIN, 'assets', 'achievements.json')
        p_comp = os.path.join(MAIN, 'assets', 'minicar', 'compose.json')
        self.paths = [p_tok, p_veh, p_cat, p_lv, p_thr, p_str, p_ach, p_comp]

        # 차 화면과 같은 말은 같은 글자로: strings.xml을 그대로 읽는다(%1$d·%1$s는 res()가 채움).
        self.strings = {n: html.unescape(v).replace("\\'", "'").replace('\\"', '"').replace('\\n', '\n')
                        for n, v in re.findall(r'<string name="(\w+)"[^>]*>(.*?)</string>', read(p_str), re.S)}
        self.daily_cap = int(re.search(r'DAILY_XP_CAP = (\d+)', read(p_thr)).group(1))

        block = re.search(r'val OdoNight = OdoColors\((.*?)\n\)', read(p_tok), re.S).group(1)
        self.colors = {k: hex_rgb(v) for k, v in re.findall(r'(\w+) = Color\(0xFF([0-9A-Fa-f]{6})\)', block)}

        self.items = [dict(slot='vehicle', id=i, label=l, rgb=None)
                      for i, l in re.findall(r'^\s*[A-Z]+\("(\w+)",\s*"([^"]+)"\)', read(p_veh), re.M)]
        for i, none, slot, label, hx in re.findall(
                r'CosmeticItem\((?:"(\w+)"|(NONE)), Slot\.(\w+), "([^"]+)"(?:, 0xFF([0-9A-Fa-f]{6})L)?\)', read(p_cat)):
            self.items.append(dict(slot=slot.lower(), id=i or 'none', label=label, rgb=hex_rgb(hx) if hx else None))
        if sum(1 for it in self.items if it['id'] != 'none') != 24 or not self.slot('roof'):
            sys.exit('CosmeticCatalog.kt 형식이 바뀐 것 같아요(수집 대상 24개를 못 읽음).')

        lv = read(p_lv)
        self.anchors = [(int(a), t, int(x.replace('_', '')))
                        for a, t, x in re.findall(r'Anchor\((\d+), "([^"]+)", ([\d_]+),', lv)]
        levels = [a for a, _, _ in self.anchors]
        block = re.search(r'val cosmeticUnlocks.*?= run \{(.*?)\n    \}', lv, re.S).group(1)
        var = {n: levels[int(i)] for n, i in re.findall(r'val (\w+) = anchors\[(\d+)\]\.level', block)}
        unlock = {key: var[v] for key, v in re.findall(r'"(\w+:\w+)" to (\w+)', block)}
        if len(unlock) != 16:
            sys.exit('LevelTable.kt cosmeticUnlocks 형식이 바뀐 것 같아요(해금 16개를 못 읽음).')
        for it in self.items:
            it['level'] = unlock.get(it['slot'] + ':' + it['id'], 1)

        self.ach = []
        for a in json.loads(read(p_ach))['achievements']:
            r = a['rule']
            tier = 0
            if r['type'] in ('safeDistance', 'nightSafeDistance', 'rainSafeDistance'):   # Medal.kt medalTierOf
                tier = 3 if r['km'] >= 1000 else 2 if r['km'] >= 100 else 1
            self.ach.append(dict(id=a['id'], title=a['title'], description=a['description'], tier=tier, rule=r,
                                condition=a.get('condition', '')))
        self.ach_by_id = {a['id']: a for a in self.ach}
        self.compose = json.loads(read(p_comp))

    def slot(self, slot):
        return [it for it in self.items if it['slot'] == slot]

    def item(self, slot, id_):
        return next(it for it in self.items if it['slot'] == slot and it['id'] == id_)

    def collected(self, level):
        return sum(1 for it in self.items if it['id'] != 'none' and it['level'] <= level)

    def total(self):
        return sum(1 for it in self.items if it['id'] != 'none')

    def unlocked_at(self, level):
        return [it for it in self.items if it['id'] != 'none' and it['level'] == level]

    def xp_for_level(self, level):
        """LevelTable.xpForLevel을 옮김(앵커 사이 선형 보간, Math.round = 0.5 올림)."""
        if level >= self.anchors[-1][0]:
            return self.anchors[-1][2]
        upper = next(a for a in self.anchors if a[0] >= level)
        if upper[0] == level:
            return upper[2]
        lower = [a for a in self.anchors if a[0] < level][-1]
        step = (upper[2] - lower[2]) / (upper[0] - lower[0])
        return lower[2] + math.floor(step * (level - lower[0]) + 0.5)

    def level_for_xp(self, xp):
        level = 1
        while level < self.anchors[-1][0] and xp >= self.xp_for_level(level + 1):
            level += 1
        return level

    def title_for_level(self, level):
        return [t for a, t, _ in self.anchors if a <= level][-1]


def res(name, *args):
    """차 앱 strings.xml 문구를 꺼내 %1$d·%1$s 자리를 채운다."""
    def fill(m):
        return '%' if m.group(0) == '%%' else str(args[int(m.group(1)) - 1])
    return re.sub(r'%%|%(\d+)\$[ds]', fill, APP.strings[name])


# ── 글자 ────────────────────────────────────────────────────────────────────

FONT_FILES = {
    ('latin', 500): 'Roboto-Medium.ttf', ('latin', 700): 'Roboto-Bold.ttf',
    ('cjk', 500): 'NotoSansCJKkr-Medium.otf', ('cjk', 700): 'NotoSansCJKkr-Bold.otf',
}
RAQM = features.check('raqm')
LAYOUT = ImageFont.Layout.RAQM if RAQM else ImageFont.Layout.BASIC
FEAT = ['tnum'] if RAQM else None            # 변하는 숫자는 표 숫자
LANG = 'ko' if RAQM else None                # 언어를 주지 않으면 harfbuzz가 실행 로캘을 쓰고, 한글 뒤 ':' 자리가 로캘마다 달라진다
ROLES = {
    # 폰 척도(설계서 §7.3): (크기 sp, 줄 높이 sp, 굵기, 자간 sp)
    'caption': (13, 18, 500, 0.0),
    'body': (16, 22, 500, 0.0),
    'title': (20, 26, 700, 0.0),
    'headline': (28, 34, 700, 0.0),
    'num44': (44, 48, 700, -0.5),
    'num64': (64, 68, 700, -1.0),
    # 카메라에 비친 차 패널(차 척도, OdoType.kt)
    'car_caption': (20, 26, 500, 0.0),
    'car_body': (24, 32, 500, 0.0),
    'car_title': (28, 36, 700, 0.0),
    'car_headline': (36, 44, 700, 0.0),
}
STICKY = set('!,-.:;?')
_fonts, _cover, _notdef = {}, {}, []


def font(script, weight, px):
    key = (script, weight, px)
    if key not in _fonts:
        path = os.path.join(FONT_DIR, FONT_FILES[(script, weight)])
        if not os.path.exists(path):
            sys.exit('글꼴이 없어요. 먼저 sh tools/phone-mockup/fetch_fonts.sh 를 실행해 주세요.')
        _fonts[key] = ImageFont.truetype(path, px, layout_engine=LAYOUT)
    return _fonts[key]


def _shot(ch):
    im = Image.new('L', (120, 100), 0)
    ImageDraw.Draw(im).text((20, 70), ch, font=font('latin', 500, 48), fill=255, anchor='ls')
    return im.tobytes()


def latin_has(ch):
    if ch not in _cover:
        if not _notdef:
            _notdef.append(_shot(''))
        _cover[ch] = ch.isspace() or _shot(ch) != _notdef[0]
    return _cover[ch]


def is_cjk(ch):
    o = ord(ch)
    return (0x1100 <= o <= 0x11FF or 0x3000 <= o <= 0x303F or 0x3130 <= o <= 0x318F
            or 0x4E00 <= o <= 0x9FFF or 0xAC00 <= o <= 0xD7A3 or 0xFF00 <= o <= 0xFFEF)


def runs(s):
    """안드로이드 글꼴 대체 순서: 라틴·숫자는 Roboto, 한글과 Roboto에 없는 글자는 Noto Sans CJK."""
    out = []
    for ch in s:
        sc = 'cjk' if is_cjk(ch) or not latin_has(ch) else 'latin'
        if ch in STICKY and out and out[-1][0] == 'cjk':
            sc = 'cjk'
        if out and out[-1][0] == sc:
            out[-1][1] += ch
        else:
            out.append([sc, ch])
    return out


def tw(s, role, k=S):
    """글 폭(dp)."""
    size, _, wt, track = ROLES[role]
    px = round(size * k)
    w = 0.0
    for sc, run in runs(s):
        f = font(sc, wt, px)
        w += (sum(f.getlength(ch, features=FEAT, language=LANG) for ch in run) if track
              else f.getlength(run, features=FEAT, language=LANG))
    if track:
        w += track * k * (len(s) - 1)
    return w / k


def baseline(top, role):
    """줄 상자 위쪽(dp) → 기준선(dp). 한글·숫자의 눈 중심이 줄 가운데에 오게 한다."""
    size, lh, _, _ = ROLES[role]
    return top + lh / 2 + 0.36 * size


def text_bl(img, x, base, s, role, rgb, alpha=HI, align='left', k=S):
    size, _, wt, track = ROLES[role]
    px = round(size * k)
    w = tw(s, role, k)
    if align == 'center':
        x -= w / 2
    elif align == 'right':
        x -= w
    X, B = x * k, base * k
    x0, y0 = math.floor(X) - 4, math.floor(B - 1.15 * px) - 4
    x1, y1 = math.ceil(X + w * k) + 8, math.ceil(B + 0.4 * px) + 4
    m = Image.new('L', (x1 - x0, y1 - y0), 0)
    d = ImageDraw.Draw(m)
    cx = X - x0
    for sc, run in runs(s):
        f = font(sc, wt, px)
        if track:
            for ch in run:
                d.text((cx, B - y0), ch, font=f, fill=255, anchor='ls', features=FEAT, language=LANG)
                cx += f.getlength(ch, features=FEAT, language=LANG) + track * k
        else:
            d.text((cx, B - y0), run, font=f, fill=255, anchor='ls', features=FEAT, language=LANG)
            cx += f.getlength(run, features=FEAT, language=LANG)
    paint(img, m, x0, y0, rgb, alpha)
    return w


def text(img, x, top, s, role, rgb, alpha=HI, align='left', k=S):
    return text_bl(img, x, baseline(top, role), s, role, rgb, alpha, align, k)


def wrap(s, role, max_w, k=S):
    """strings.xml의 줄바꿈(\\n)은 차 Text처럼 강제 줄바꿈으로 지키고, 문단마다 공백 기준으로 max_w에 맞춰 접는다."""
    if not s:
        return []
    lines = []
    for para in s.split('\n'):
        cur = ''
        for word in para.split(' '):
            t = word if not cur else cur + ' ' + word
            if not cur or tw(t, role, k) <= max_w:
                cur = t
            else:
                lines.append(cur)
                cur = word
        lines.append(cur)
    return lines


# ── 도형 ────────────────────────────────────────────────────────────────────

def paint(img, m, x0, y0, rgb, alpha=1.0):
    if alpha < 1.0:
        m = m.point([int(v * alpha + 0.5) for v in range(256)])
    col = tuple(rgb) + ((255,) if img.mode == 'RGBA' and len(rgb) == 3 else ())
    img.paste(col, (x0, y0, x0 + m.width, y0 + m.height), m)


def shape(img, box, fn, rgb, alpha=1.0, k=S):
    """box(dp) 둘레를 SS배로 그려 줄인 덮개로 칠한다. fn(draw, P, q): P는 dp→덮개 좌표, q는 덮개 px/dp."""
    x, y, w, h = box
    x0, y0 = math.floor(x * k) - 2, math.floor(y * k) - 2
    x1, y1 = math.ceil((x + w) * k) + 2, math.ceil((y + h) * k) + 2
    m = Image.new('L', ((x1 - x0) * SS, (y1 - y0) * SS), 0)

    def P(px_, py_):
        return ((px_ * k - x0) * SS, (py_ * k - y0) * SS)
    fn(ImageDraw.Draw(m), P, k * SS)
    paint(img, m.resize((x1 - x0, y1 - y0), Image.BOX), x0, y0, rgb, alpha)


def rrect(img, x, y, w, h, r, rgb, alpha=1.0, k=S):
    shape(img, (x, y, w, h),
          lambda d, P, q: d.rounded_rectangle([P(x, y), P(x + w, y + h)], radius=r * q, fill=255), rgb, alpha, k)


def rrect_line(img, x, y, w, h, r, sw, rgb, alpha=1.0, k=S):
    def fn(d, P, q):
        d.rounded_rectangle([P(x, y), P(x + w, y + h)], radius=r * q, fill=255)
        d.rounded_rectangle([P(x + sw, y + sw), P(x + w - sw, y + h - sw)], radius=max(r - sw, 0) * q, fill=0)
    shape(img, (x, y, w, h), fn, rgb, alpha, k)


def circle(img, cx, cy, r, rgb, alpha=1.0, k=S):
    shape(img, (cx - r, cy - r, 2 * r, 2 * r),
          lambda d, P, q: d.ellipse([P(cx - r, cy - r), P(cx + r, cy + r)], fill=255), rgb, alpha, k)


def ring(img, cx, cy, r, sw, rgb, alpha=1.0, k=S):
    """반지름 r을 가운데로 걸치는 sw 굵기 원(Compose Stroke와 같음)."""
    ro, ri = r + sw / 2, r - sw / 2

    def fn(d, P, q):
        d.ellipse([P(cx - ro, cy - ro), P(cx + ro, cy + ro)], fill=255)
        d.ellipse([P(cx - ri, cy - ri), P(cx + ri, cy + ri)], fill=0)
    shape(img, (cx - ro, cy - ro, 2 * ro, 2 * ro), fn, rgb, alpha, k)


def stroke(img, pts, sw, rgb, alpha=1.0, k=S, closed=False):
    """둥근 끝·둥근 이음 선(Icons.kt의 2dp 스트로크)."""
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    box = (min(xs) - sw, min(ys) - sw, max(xs) - min(xs) + 2 * sw, max(ys) - min(ys) + 2 * sw)

    def fn(d, P, q):
        pp = [P(*p) for p in pts] + ([P(*pts[0])] if closed else [])
        d.line(pp, fill=255, width=max(1, round(sw * q)), joint='curve')
        rr = sw * q / 2
        for px_, py_ in pp:
            d.ellipse([px_ - rr, py_ - rr, px_ + rr, py_ + rr], fill=255)
    shape(img, box, fn, rgb, alpha, k)


def rect(img, x, y, w, h, rgb, alpha=1.0, k=S):
    x0, y0, x1, y1 = round(x * k), round(y * k), round((x + w) * k), round((y + h) * k)
    paint(img, Image.new('L', (max(1, x1 - x0), max(1, y1 - y0)), 255), x0, y0, rgb, alpha)


def rounded_mask(w, h, r):
    m = Image.new('L', (w * SS, h * SS), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w * SS - 1, h * SS - 1], radius=r * SS, fill=255)
    return m.resize((w, h), Image.BOX)


def paste_rounded(img, im, x, y, r, k=S):
    img.paste(im, (round(x * k), round(y * k)), rounded_mask(im.width, im.height, r * k))


def paste_circle(img, im, cx, cy, k=S):
    n = im.width
    m = Image.new('L', (n * SS, n * SS), 0)
    ImageDraw.Draw(m).ellipse([0, 0, n * SS - 1, n * SS - 1], fill=255)
    img.paste(im, (round(cx * k - n / 2), round(cy * k - n / 2)), m.resize((n, n), Image.BOX))


# ── 아이콘·메달·게이지(차 앱 컴포넌트를 옮김) ─────────────────────────────

SILHOUETTE = {   # Icons.kt drawVehicleSilhouette, 48 단위 격자
    'compact': ([(6, 30), (8, 22), (16, 20), (21, 13), (31, 13), (36, 20), (42, 23), (43, 30)], [(15, 31, 4), (34, 31, 4)]),
    'suv': ([(5, 31), (6, 20), (14, 19), (18, 11), (33, 11), (38, 19), (43, 21), (43, 31)], [(14, 32, 4.5), (35, 32, 4.5)]),
    'large': ([(4, 31), (4, 13), (14, 10), (44, 10), (44, 31)], [(13, 32, 4.5), (36, 32, 4.5)]),
    'sedan': ([(4, 30), (6, 23), (14, 22), (19, 14), (30, 14), (36, 22), (44, 24), (44, 30)], [(13, 31, 4), (36, 31, 4)]),
}
SCOOTER_LINES = ([(12, 30), (26, 30), (33, 13)], [(29, 13), (38, 13)], [(14, 22), (26, 20)], [(14, 22), (12, 30)])


def icon(img, name, x, y, rgb, alpha=HI, size=24, k=S):
    """Icons.kt와 같은 24 단위 격자, 2dp 둥근 스트로크. trip·back은 폰에만 있는 그림(같은 굵기)."""
    u, sw = size / 24.0, 2.0

    def T(pts):
        return [(x + a * u, y + b * u) for a, b in pts]
    if name == 'home':
        stroke(img, T([(4, 11), (12, 4), (20, 11), (20, 20), (14, 20), (14, 14), (10, 14), (10, 20), (4, 20)]),
               sw, rgb, alpha, k, closed=True)
    elif name == 'medal':
        ring(img, x + 12 * u, y + 9 * u, 5.5 * u, sw, rgb, alpha, k)
        stroke(img, T([(9.5, 14), (7.5, 21)]), sw, rgb, alpha, k)
        stroke(img, T([(14.5, 14), (16.5, 21)]), sw, rgb, alpha, k)
    elif name == 'garage':
        stroke(img, T([(3, 20), (3, 9), (12, 4), (21, 9), (21, 20)]), sw, rgb, alpha, k)
        stroke(img, T([(7, 20), (7, 13), (17, 13), (17, 20)]), sw, rgb, alpha, k)
        stroke(img, T([(7, 16.5), (17, 16.5)]), sw, rgb, alpha, k)
    elif name == 'back':
        stroke(img, T([(15, 5), (8, 12), (15, 19)]), sw, rgb, alpha, k)
    elif name == 'trip':
        ring(img, x + 6 * u, y + 18.5 * u, 2.5 * u, sw, rgb, alpha, k)
        ring(img, x + 18 * u, y + 5.5 * u, 2.5 * u, sw, rgb, alpha, k)
        p0, p1, p2, p3 = (6, 15), (6, 8.5), (18, 15.5), (18, 9)
        pts = []
        for i in range(25):
            t = i / 24
            a, b, c, d = (1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t * t, t ** 3
            pts.append((a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]))
        stroke(img, T(pts), sw, rgb, alpha, k)


def silhouette(img, vid, x, y, size, rgb, alpha=HI, k=S):
    u = size / 48.0
    if vid == 'scooter':
        for seg in SCOOTER_LINES:
            stroke(img, [(x + a * u, y + b * u) for a, b in seg], 2.0, rgb, alpha, k)
        wheels = [(11, 33, 5), (37, 33, 5)]
    else:
        poly, wheels = SILHOUETTE[vid]
        stroke(img, [(x + a * u, y + b * u) for a, b in poly], 2.0, rgb, alpha, k, closed=True)
    for wx, wy, r in wheels:
        ring(img, x + wx * u, y + wy * u, r * u, 2.0, rgb, alpha, k)


def line_butt(img, p0, p1, sw, rgb, alpha=1.0, k=S):
    """끝을 자른 직선(Compose StrokeCap.Butt)."""
    box = (min(p0[0], p1[0]) - sw, min(p0[1], p1[1]) - sw, abs(p1[0] - p0[0]) + 2 * sw, abs(p1[1] - p0[1]) + 2 * sw)
    shape(img, box, lambda d, P, q: d.line([P(*p0), P(*p1)], fill=255, width=max(1, round(sw * q))), rgb, alpha, k)


MEDAL_RIBBON = ((12, 17), (24, 19))   # Medal.kt RIBBON: (위 x, 아래 x), 36 단위


def medal(img, x, y, d, unlocked, tier=0, k=S):
    """Medal.kt: 36 단위 격자에 V자 리본 두 가닥(y 0→11) + 원판(중심 18,20 · 반지름 11).
    열림 = 노랑 원판 + onAmber 1dp 안쪽 고리 + inkDim 4dp 리본, 잠김 = surface 원판 + inkDim 1dp 고리·1dp 리본.
    거리 업적은 아래 점 1~3개. 선 굵기·점 크기는 dp 고정(앱과 같음)."""
    u = d / 36
    cx, cy, r = x + 18 * u, y + 20 * u, 11 * u
    rw = 4 if unlocked else 1
    for top, bottom in MEDAL_RIBBON:
        line_butt(img, (x + top * u, y + rw / 2), (x + bottom * u, y + 11 * u), rw, C['inkDim'], 1, k)
    if unlocked:
        circle(img, cx, cy, r, C['amber'], 1, k)
        ring(img, cx, cy, r - 3, 1, C['onAmber'], 1, k)
    else:
        circle(img, cx, cy, r, C['surface'], 1, k)
        ring(img, cx, cy, r - 0.5, 1, C['inkDim'], 1, k)
    if tier:
        n = min(tier, 3)
        x0 = x + d / 2 - (n - 1) * 8 / 2
        for i in range(n):
            circle(img, x0 + i * 8, y + d - 2, 2, C['amber'] if unlocked else C['inkDim'], 1, k)


def gauge(img, x, y, w, h, p, k=S):
    """Gauge.kt: track 바탕 + 노랑 채움, 끝이 둥글고 채움 폭은 최소 h."""
    rrect(img, x, y, w, h, h / 2, C['track'], 1, k)
    rrect(img, x, y, max(w * max(0.0, min(1.0, p)), h), h, h / 2, C['amber'], 1, k)


def lamp_swatch(img, cx, cy, r, rgb, alpha=1.0, k=S):
    """램프 색: 페인트 견본(꽉 찬 원)과 헷갈리지 않게 고리 + 가운데 점."""
    ring(img, cx, cy, r - 1, 2, rgb, alpha, k)
    circle(img, cx, cy, r * 0.42, rgb, alpha, k)


# ── 미니카(OdoVehicle.kt 합성 규칙을 옮김) ─────────────────────────────────

WHEEL_PAD = 3   # 휠 썸네일: 가까운 앞바퀴 알파 bbox 둘레 여백(hero 플레이트 px)
ROOF_ON_BRIGHT = (0x6B, 0x72, 0x7D)   # 밝은 차 위 투톤 지붕색(밝은 진회색). OdoVehicle.kt와 같은 값


class Minicar:
    """hero 뷰만 그린다(폰 화면에는 지도 위 미니카가 없다). 에셋 계약 v2(compose.json version 2: 뷰별 skip,
    밤 전용 글로 레이어)와 v1 을 둘 다 읽는다. 규칙은 <plates>/compose.json, 없으면(렌더 PNG 폴더) 앱 에셋의 것."""

    def __init__(self, plates):
        self.dir, self.cache = plates, {}
        p = os.path.join(plates, 'compose.json')
        self.compose = json.loads(read(p)) if os.path.exists(p) else APP.compose
        hero = self.compose['views']['hero']
        self.vw, self.vh = hero['px']
        self.skip = set(hero.get('skip', []))               # v2: 이 뷰에 파일이 없는 레이어
        self.night_only = set(self.compose.get('night', []))   # v2: 밤에만 그리는 레이어(glow_head, glow_tail)

    def layer(self, vehicle, name):
        key = (vehicle, name)
        if key not in self.cache:
            self.cache[key] = None
            for ext in ('.png', '.webp'):
                p = os.path.join(self.dir, vehicle, 'hero', name + ext)
                if os.path.exists(p):
                    self.cache[key] = Image.open(p).convert('RGBA')
                    break
        return self.cache[key]

    def layers_for(self, lo, night=False):
        out = []
        for name in self.compose['order']:
            f = name
            if name == 'wheel':
                f = 'wheel_' + lo['wheel']
            elif name in ('roof_twotone', 'roof_box'):
                if lo['roof'] != name[5:]:
                    continue
            elif name == 'decal_stripe':
                if lo['decal'] != 'stripe':
                    continue
            elif name == 'plate_gold':
                if lo['plate'] != 'gold':
                    continue
            if f in self.skip or (name in self.night_only and not night):
                continue
            out.append((name, f))
        return out

    def draw(self, dst, box, lo, k=S, night=False):
        """box(dp) 가운데에 놓고 min(W/576, H/384)배. 그림자 0.9, body·lamp·투톤·데칼은 곱하기, spec·글로는 스크린
        (glow_head 는 램프 색을 곱한 뒤 스크린). 글로는 밤에만.
        투톤 지붕색은 밝은 차(휘도 0.35 초과) 위 ROOF_ON_BRIGHT, 어두운 차 위 pearl. 밤에는 차 색·보조색·지붕색을 0.85배."""
        x, y, w, h = box
        base = self.layer(lo['vehicle'], 'body')
        iw, ih = base.size
        sc = min(w * k / iw, h * k / ih)
        cw, ch = round(iw * sc), round(ih * sc)
        ox, oy = round((x + w / 2) * k - cw / 2), round((y + h / 2) * k - ch / 2)
        # 바닥 타원(턴테이블)은 그리지 않는다: 차 앱 미니카(OdoVehicle)에 없는 장식이라 목업만 달라 보였다.
        paint_rgb = APP.item('color', lo['color'])['rgb']
        lamp_rgb = APP.item('lamp', lo['lamp'])['rgb']
        luma = (0.2126 * paint_rgb[0] + 0.7152 * paint_rgb[1] + 0.0722 * paint_rgb[2]) / 255
        accent = APP.item('color', 'graphite' if luma > 0.35 else 'pearl')['rgb']
        roof = ROOF_ON_BRIGHT if luma > 0.35 else APP.item('color', 'pearl')['rgb']
        if night:
            paint_rgb, accent, roof = (tuple(round(v * 0.85) for v in c) for c in (paint_rgb, accent, roof))
        blend = self.compose['blend']
        region = dst.crop((ox, oy, ox + cw, oy + ch))
        for base_name, name in self.layers_for(lo, night):
            im = self.layer(lo['vehicle'], name)
            if im is None:
                continue
            r, g, b, a = im.resize((cw, ch), Image.BICUBIC).split()
            mode = blend.get(base_name)
            if base_name == 'shadow':
                a = a.point([round(v * 0.9) for v in range(256)])
            if mode == 'screen':
                if base_name == 'glow_head':
                    r, g, b = (c.point([round(v * t / 255) for v in range(256)]) for c, t in zip((r, g, b), lamp_rgb))
                region = ImageChops.screen(region, Image.merge('RGB', [ImageChops.multiply(c, a) for c in (r, g, b)]))
                continue
            if mode == 'modulate':
                tint = {'body': paint_rgb, 'lamp': lamp_rgb, 'roof_twotone': roof}.get(base_name, accent)
                rgb = Image.merge('RGB', [c.point([round(v * t / 255) for v in range(256)]) for c, t in zip((r, g, b), tint)])
                if base_name == 'lamp' and not night:
                    a = a.point([round(v * 0.55) for v in range(256)])
            else:
                rgb = Image.merge('RGB', (r, g, b))
            region.paste(rgb, (0, 0), a)
        dst.paste(region, (ox, oy))

    def wheel_thumb(self, lo, wheel_id, d, bg, k=S):
        """휠 레이어만 바탕 위에 얹고 가까운 앞바퀴(플레이트 왼쪽 절반의 알파 bbox)만 잘라 d(dp) 정사각형으로.
        차체는 합성하지 않는다(차체 색 조각이 남지 않게). 휠 레이어는 그 자체로 바퀴 전체가 그려져 있다."""
        n = round(d * k)
        im = self.layer(lo['vehicle'], 'wheel_' + wheel_id)
        W, H = im.size
        x0, y0, x1, y1 = im.split()[3].crop((0, 0, W // 2, H)).getbbox()
        half = max(x1 - x0, y1 - y0) / 2 + WHEEL_PAD
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        box = (round(cx - half), round(cy - half), round(cx + half), round(cy + half))
        tile = Image.new('RGBA', (box[2] - box[0], box[3] - box[1]), tuple(bg) + (255,))
        tile.alpha_composite(im.crop(box))
        return tile.convert('RGB').resize((n, n), Image.LANCZOS)


# ── QR(바이트 모드, 버전 1~6) ────
# Project Nayuki QR Code generator 파이썬 판(qrcodegen.py, https://github.com/nayuki/QR-Code-generator)의
# 알고리즘을 바이트 모드·버전 1~6만 남기고 줄여 옮겼다. 원본: Copyright (c) Project Nayuki. (MIT License)
# 허가 문구 전문은 NOTICE.md 'QR 코드 라이브러리' 절에 있다. 목업 PNG를 그릴 때만 쓰고 앱에는 들어가지 않는다.

def qr_matrix(data, ecl='M'):
    ecc_t = {'L': (7, 10, 15, 20, 26, 18), 'M': (10, 16, 26, 18, 24, 16),
             'Q': (13, 22, 18, 26, 18, 24), 'H': (17, 28, 22, 16, 22, 28)}
    blk_t = {'L': (1, 1, 1, 1, 1, 2), 'M': (1, 1, 1, 2, 2, 4), 'Q': (1, 1, 2, 2, 4, 4), 'H': (1, 1, 2, 4, 4, 4)}
    fmt_t = {'L': 1, 'M': 0, 'Q': 3, 'H': 2}

    def raw_modules(v):
        r = (16 * v + 128) * v + 64
        if v >= 2:
            na = v // 7 + 2
            r -= (25 * na - 10) * na - 55
        return r

    for ver in range(1, 7):
        ecc_len, nblk = ecc_t[ecl][ver - 1], blk_t[ecl][ver - 1]
        cap = raw_modules(ver) // 8 - ecc_len * nblk
        if 4 + 8 + 8 * len(data) <= cap * 8:
            break
    else:
        raise ValueError('QR 데이터가 너무 길어요')

    bits = [0, 1, 0, 0] + [(len(data) >> i) & 1 for i in range(7, -1, -1)]
    for byte in data:
        bits += [(byte >> i) & 1 for i in range(7, -1, -1)]
    bits += [0] * min(4, cap * 8 - len(bits))
    bits += [0] * (-len(bits) % 8)
    pad = 0xEC
    while len(bits) < cap * 8:
        bits += [(pad >> i) & 1 for i in range(7, -1, -1)]
        pad ^= 0xEC ^ 0x11
    cws = [int(''.join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]

    def gmul(x, y):
        z = 0
        for i in range(7, -1, -1):
            z = (z << 1) ^ ((z >> 7) * 0x11D)
            z ^= ((y >> i) & 1) * x
        return z

    div = [0] * (ecc_len - 1) + [1]
    root = 1
    for _ in range(ecc_len):
        for j in range(ecc_len):
            div[j] = gmul(div[j], root)
            if j + 1 < ecc_len:
                div[j] ^= div[j + 1]
        root = gmul(root, 2)

    def remainder(block):
        res = [0] * ecc_len
        for b in block:
            f = b ^ res.pop(0)
            res.append(0)
            for i, c in enumerate(div):
                res[i] ^= gmul(c, f)
        return res

    raw_cw = raw_modules(ver) // 8
    n_short, short_len = nblk - raw_cw % nblk, raw_cw // nblk
    blocks, pos = [], 0
    for i in range(nblk):
        dl = short_len - ecc_len + (0 if i < n_short else 1)
        dat = cws[pos:pos + dl]
        pos += dl
        ecc = remainder(dat)
        blocks.append(dat + ([0] if i < n_short else []) + ecc)
    allcw = [blk[i] for i in range(len(blocks[0])) for j, blk in enumerate(blocks)
             if i != short_len - ecc_len or j >= n_short]

    size = ver * 4 + 17
    mod = [[False] * size for _ in range(size)]
    isfn = [[False] * size for _ in range(size)]

    def setf(x, y, dark):
        mod[y][x] = dark
        isfn[y][x] = True

    for i in range(size):
        setf(6, i, i % 2 == 0)
        setf(i, 6, i % 2 == 0)
    for cx, cy in ((3, 3), (size - 4, 3), (3, size - 4)):
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                if 0 <= cx + dx < size and 0 <= cy + dy < size:
                    setf(cx + dx, cy + dy, max(abs(dx), abs(dy)) not in (2, 4))
    if ver >= 2:
        a = size - 7
        for dy in range(-2, 3):
            for dx in range(-2, 3):
                setf(a + dx, a + dy, max(abs(dx), abs(dy)) != 1)

    def draw_format(mask):
        dat = fmt_t[ecl] << 3 | mask
        r = dat
        for _ in range(10):
            r = (r << 1) ^ ((r >> 9) * 0x537)
        fb = (dat << 10 | r) ^ 0x5412

        def bit(i):
            return ((fb >> i) & 1) == 1
        for i in range(6):
            setf(8, i, bit(i))
        setf(8, 7, bit(6))
        setf(8, 8, bit(7))
        setf(7, 8, bit(8))
        for i in range(9, 15):
            setf(14 - i, 8, bit(i))
        for i in range(8):
            setf(size - 1 - i, 8, bit(i))
        for i in range(8, 15):
            setf(8, size - 15 + i, bit(i))
        setf(8, size - 8, True)

    draw_format(0)
    i, total = 0, len(allcw) * 8
    right = size - 1
    while right >= 1:
        if right == 6:
            right = 5
        for vert in range(size):
            for j in range(2):
                x = right - j
                y = size - 1 - vert if ((right + 1) & 2) == 0 else vert
                if not isfn[y][x] and i < total:
                    mod[y][x] = ((allcw[i >> 3] >> (7 - (i & 7))) & 1) == 1
                    i += 1
        right -= 2

    masks = (lambda x, y: (x + y) % 2 == 0, lambda x, y: y % 2 == 0, lambda x, y: x % 3 == 0,
             lambda x, y: (x + y) % 3 == 0, lambda x, y: (x // 3 + y // 2) % 2 == 0,
             lambda x, y: x * y % 2 + x * y % 3 == 0, lambda x, y: (x * y % 2 + x * y % 3) % 2 == 0,
             lambda x, y: ((x + y) % 2 + x * y % 3) % 2 == 0)

    def apply(mk):
        f = masks[mk]
        for y in range(size):
            for x in range(size):
                if not isfn[y][x] and f(x, y):
                    mod[y][x] = not mod[y][x]

    def finder_count(h):
        n = h[1]
        core = n > 0 and h[2] == h[4] == h[5] == n and h[3] == n * 3
        return (int(core and h[0] >= n * 4 and h[6] >= n)) + (int(core and h[6] >= n * 4 and h[0] >= n))

    def add_hist(length, h):
        if h[0] == 0:
            length += size
        h.insert(0, length)
        h.pop()

    def line_penalty(line):
        p, color, run, h = 0, False, 0, [0] * 7
        for c in line:
            if c == color:
                run += 1
                p += 3 if run == 5 else 1 if run > 5 else 0
            else:
                add_hist(run, h)
                if not color:
                    p += finder_count(h) * 40
                color, run = c, 1
        if color:
            add_hist(run, h)
            run = 0
        add_hist(run + size, h)
        return p + finder_count(h) * 40

    def penalty():
        p = sum(line_penalty(mod[y]) for y in range(size))
        p += sum(line_penalty([mod[y][x] for y in range(size)]) for x in range(size))
        for y in range(size - 1):
            for x in range(size - 1):
                if mod[y][x] == mod[y][x + 1] == mod[y + 1][x] == mod[y + 1][x + 1]:
                    p += 3
        dark, n2 = sum(sum(row) for row in mod), size * size
        return p + ((abs(dark * 20 - n2 * 10) + n2 - 1) // n2 - 1) * 10

    best, best_p = 0, None
    for mk in range(8):
        apply(mk)
        draw_format(mk)
        p = penalty()
        if best_p is None or p < best_p:
            best, best_p = mk, p
        apply(mk)
    apply(best)
    draw_format(best)
    return mod, ver


def link_payload():
    """FakePhoneLink와 같은 형식: odo-link:v1: + 16바이트 base32(RFC 4648, 패딩 없음). 목업은 고정 시드의 데모 토큰."""
    token = hashlib.sha256(CFG['link']['tokenSeed'].encode()).digest()[:16]
    return 'odo-link:v1:' + base64.b32encode(token).decode().rstrip('=')


# ── 공통 화면 조각 ──────────────────────────────────────────────────────────

def new_screen():
    return Image.new('RGB', (SCREEN[0] * S, SCREEN[1] * S), C['ground'])


def status_bar(img, time):
    base = 26
    text_bl(img, 24, base, time, 'caption', C['ink'], HI)
    circle(img, SCREEN[0] / 2, 20, 6, (0, 0, 0))                     # 펀치홀
    ring(img, SCREEN[0] / 2, 20, 6.5, 1, (22, 26, 31))
    x = SCREEN[0] - 24
    rrect_line(img, x - 21, 14.5, 19, 11, 3, 1.2, C['ink'], HI)        # 배터리
    rrect(img, x - 18.6, 16.9, 10.5, 6.2, 1.2, C['ink'], HI)
    rrect(img, x - 1.6, 17.5, 1.8, 5, 0.9, C['ink'], HI)
    wx = x - 33                                                         # 와이파이(부채꼴)
    shape(img, (wx - 9, 11, 18, 16),
          lambda d, P, q: d.pieslice([P(wx - 9, 12), P(wx + 9, 30)], 225, 315, fill=255), C['ink'], HI)
    sx = x - 58                                                         # 신호(직각삼각형)
    shape(img, (sx, 13, 13, 13),
          lambda d, P, q: d.polygon([P(sx, 25.5), P(sx + 12, 25.5), P(sx + 12, 13.5)], fill=255), C['ink'], HI)


def home_indicator(img):
    rrect(img, SCREEN[0] / 2 - 54, SCREEN[1] - 14, 108, 4, 2, C['ink'], 0.56)


APPBAR_BASE = 40 + 28 + 0.36 * 20


def app_bar(img, title, back=False, sub=None, right=None):
    x = M
    if back:
        icon(img, 'back', M, 56, C['ink'], HI)
        x = 56
    w = text_bl(img, x, APPBAR_BASE, title, 'title', C['ink'], HI)
    if sub:
        text_bl(img, x + w + 8, APPBAR_BASE, sub, 'body', C['inkDim'], 1)
    if right:
        text_bl(img, SCREEN[0] - M, APPBAR_BASE, right, 'caption', C['inkDim'], 1, align='right')


def primary(img, label, y, h=56):
    """주 행동 1개: 앰버 2dp 테두리 + ink 글자. 채움은 없다(설계서 §11 필 버튼 금지)."""
    rrect_line(img, M, y, CW, h, 12, 2, C['amber'])
    text(img, SCREEN[0] / 2, y + (h - 22) / 2, label, 'body', C['ink'], HI, align='center')


def hairline(img, y, x=M, w=CW):
    rect(img, x, y, w, 1, C['line'])


def share_scope(img, y):
    """공유 범위 두 줄: 차 '휴대폰 연결' 패널과 같은 문장(strings.xml)."""
    text(img, M, y, res('phone_share_yes'), 'caption', C['inkDim'], 1)
    text(img, M, y + 18, res('phone_share_no'), 'caption', C['inkDim'], 1)


def key_strip(img, y, cells, h=88):
    """KeyStrip 문법: 1dp 세로 선으로 나눈 칸, 아이콘 위 라벨, 아래 보조 글."""
    hairline(img, y, 0, SCREEN[0])
    cw = SCREEN[0] / len(cells)
    for i, (ic, label, sub) in enumerate(cells):
        x = i * cw
        if i:
            rect(img, x, y, 1, h, C['line'])
        icon(img, ic, x + cw / 2 - 12, y + 12, C['ink'], HI)
        text(img, x + cw / 2, y + 40, label, 'body', C['ink'], HI, align='center')
        text(img, x + cw / 2, y + 62, sub, 'caption', C['inkDim'], 1, align='center')


def num_unit(img, x, top, num, unit, role='num64'):
    b = baseline(top, role)
    w = text_bl(img, x, b, num, role, C['ink'], HI)
    return w + 6 + text_bl(img, x + w + 6, b, unit, 'title', C['inkDim'], 1)


def framed(screen):
    """제네릭 기기 프레임: 모서리 r 48dp, 베젤 12dp, 펀치홀 1개(화면 쪽에 그림), 로고·버튼 글자 없음."""
    out = Image.new('RGBA', (FRAME[0] * S, FRAME[1] * S), (0, 0, 0, 0))
    rrect(out, 0, 0, FRAME[0], FRAME[1], 48, (8, 10, 13))
    rrect_line(out, 0, 0, FRAME[0], FRAME[1], 48, 1, C['line'])
    rrect_line(out, BEZEL - 1, BEZEL - 1, SCREEN[0] + 2, SCREEN[1] + 2, 37, 1, (3, 4, 6))
    out.paste(screen, (BEZEL * S, BEZEL * S), rounded_mask(screen.width, screen.height, 36 * S))
    return out


# ── 01 연결 ─────────────────────────────────────────────────────────────────

def car_link_panel():
    """카메라에 비친 차 화면의 '휴대폰 연결' 패널(차 척도로 다시 그림). 돌려주는 값: 그림, QR 가운데(px)."""
    k = 2.0
    img = Image.new('RGB', (round(760 * k), round(600 * k)), C['ground'])
    for pts in ([(0, 520), (120, 470), (230, 500), (400, 430), (760, 455)],
                [(610, 0), (650, 160), (700, 260), (760, 300)],
                [(0, 90), (40, 140), (30, 260), (60, 600)]):            # 지도 느낌의 흐린 길(실제 지도 아님)
        stroke(img, pts, 7, C['roadCasing'], 1, k)
        stroke(img, pts, 4, C['roadCore'], 0.5, k)
    px, py, pw, ph = 70, 50, 592, 484                                   # PhoneLinkScreen PANEL_W·PANEL_H
    rrect(img, px, py, pw, ph, 16, C['surface'], 1, k)
    rrect_line(img, px, py, pw, ph, 16, 1, C['line'], 1, k)
    text(img, px + 24, py + 20, res('phone_title'), 'car_title', C['ink'], HI, k=k)
    rect(img, px + pw - 120, py, 1, 76, C['line'], 1, k)
    icon(img, 'home', px + pw - 76, py + 8, C['ink'], HI, 32, k)
    text(img, px + pw - 60, py + 40, res('key_home'), 'car_body', C['ink'], HI, 'center', k)
    rect(img, px, py + 76, pw, 1, C['line'], 1, k)
    qx, qy = px + 24, py + 77 + 24                                      # 머리 76 + 선 1 + 안쪽 여백 xl
    rrect(img, qx, qy, 272, 272, 16, C['qrPlate'], 1, k)
    n = len(QR)
    ms = 240 / n
    for yy in range(n):
        for xx in range(n):
            if QR[yy][xx]:
                rect(img, qx + 16 + xx * ms, qy + 16 + yy * ms, ms, ms, C['qrModule'], 1, k)
    # 오른쪽 열(PresentingColumn): 코드(numeralM, 넘치면 열 폭에 맞게 줄임) → 16 → 찍기 안내 → 12 →
    # 준비 중 안내(phone_app_pending, caption·ink96, 두 줄)를 위에서부터 차례로 쌓는다.
    # 공유 범위 두 줄은 판 아래 전체 폭(ShareScope).
    tx = qx + 272 + 24
    colw = px + pw - 24 - tx
    size = 48.0
    for _ in range(3):                                                  # CodeLine.fitWidth(FIT_PASSES 3, FIT_MARGIN 0.99)
        ROLES['car_code'] = (size, 52, 700, -0.5)
        cw = tw(CFG['link']['code'], 'car_code', k)
        if cw <= colw:
            break
        size *= colw / cw * 0.99
    text(img, tx, qy, CFG['link']['code'], 'car_code', C['ink'], HI, k=k)
    y = qy + 52 + 16
    for ln in wrap(res('phone_scan'), 'car_body', colw, k):
        text(img, tx, y, ln, 'car_body', C['ink'], HI, k=k)
        y += 32
    y += 12
    for ln in wrap(res('phone_app_pending'), 'car_caption', colw, k):  # \n은 강제 줄바꿈, 넘치면 열 폭에서 접음
        text(img, tx, y, ln, 'car_caption', C['ink'], HI, k=k)
        y += 26
    bottom = py + ph - 24
    text(img, qx, bottom - 26 - 4 - 26, res('phone_share_yes'), 'car_caption', C['inkDim'], 1, k=k)
    text(img, qx, bottom - 26, res('phone_share_no'), 'car_caption', C['inkDim'], 1, k=k)
    return img, ((qx + 136) * k, (qy + 136) * k)


def _mmul(a, b):
    return [[sum(a[i][t] * b[t][j] for t in range(3)) for j in range(3)] for i in range(3)]


def _minv(m):
    (a, b, c), (d, e, f), (g, h, i) = m
    det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    return [[(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det],
            [(f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det],
            [(d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det]]


def camera_view(wd, hd, vf):
    """폰 카메라 미리보기: 차 패널을 살짝 기울여 원근 변환하고, 흐림·주변 감광·입자를 더한 뒤 조준 틀을 올린다."""
    W, H = round(wd * S), round(hd * S)
    car, qc = car_link_panel()
    vc = (W / 2, H / 2)
    m, th, g, hh = 0.97, math.radians(-4), 0.00017, -0.00004
    tq = [[1, 0, -qc[0]], [0, 1, -qc[1]], [0, 0, 1]]
    sc = [[m, 0, 0], [0, m, 0], [0, 0, 1]]
    rot = [[math.cos(th), -math.sin(th), 0], [math.sin(th), math.cos(th), 0], [0, 0, 1]]
    per = [[1, 0, 0], [0, 1, 0], [g, hh, 1]]
    tv = [[1, 0, vc[0]], [0, 1, vc[1]], [0, 0, 1]]
    hi = _minv(_mmul(tv, _mmul(per, _mmul(rot, _mmul(sc, tq)))))
    coeffs = [hi[0][0], hi[0][1], hi[0][2], hi[1][0], hi[1][1], hi[1][2], hi[2][0], hi[2][1]]
    coeffs = [v / hi[2][2] for v in coeffs]
    cam = car.transform((W, H), Image.PERSPECTIVE, coeffs, Image.BICUBIC, fillcolor=(4, 5, 7))
    cam = cam.filter(ImageFilter.GaussianBlur(0.8))       # 비네트는 넣지 않는다(설계서 §11)
    rng = random.Random(20261109)
    noise = Image.frombytes('L', (W // 2, H // 2), rng.randbytes((W // 2) * (H // 2))).resize((W, H), Image.BILINEAR)
    cam = Image.blend(cam, Image.merge('RGB', (noise, noise, noise)), 0.035)
    # 조준 틀: 바깥은 ground 50% 덮개, 모서리 꺾쇠는 ink
    side = round(vf * S)
    x0, y0 = round(vc[0] - side / 2), round(vc[1] - side / 2)
    scrim = Image.new('L', (W, H), 128)
    scrim.paste(0, (x0, y0, x0 + side, y0 + side), rounded_mask(side, side, 16 * S))
    cam.paste(C['ground'], (0, 0, W, H), scrim)
    fx, fy, L, r = x0 / S, y0 / S, 30, 16
    arc = [(r - r * math.cos(math.radians(a)), r - r * math.sin(math.radians(a))) for a in range(0, 91, 10)]
    for sx, sy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
        ox = fx if sx > 0 else fx + vf
        oy = fy if sy > 0 else fy + vf
        pts = [(ox, oy + sy * L)] + [(ox + sx * ax, oy + sy * ay) for ax, ay in arc] + [(ox + sx * L, oy)]
        stroke(cam, pts, 3, C['ink'], HI)
    return cam


def screen_link():
    img = new_screen()
    status_bar(img, CFG['time']['link'])
    app_bar(img, CP['linkTitle'], back=True)
    vy, vh = 104, 368
    paste_rounded(img, camera_view(CW, vh, 216), M, vy, 16)
    rrect_line(img, M, vy, CW, vh, 16, 1, C['line'])                  # 미리보기 경계(그림자 없이 선만)
    text(img, SCREEN[0] / 2, vy + vh + 12, CP['linkAim'], 'body', C['ink'], HI, align='center')
    y = vy + vh + 58
    text(img, M, y, CP['linkOr'], 'body', C['inkDim'], 1)
    bw = (CW - 12) / 2
    for i, part in enumerate(CFG['link']['code'].split('-')):
        bx = M + i * (bw + 12)
        rrect(img, bx, y + 30, bw, 56, 12, C['surface'])
        rrect_line(img, bx, y + 30, bw, 56, 12, 1, C['line'])
        text(img, bx + bw / 2, y + 41, part, 'headline', C['ink'], HI, align='center')
    share_scope(img, y + 110)
    primary(img, CP['linkCta'], 724)
    home_indicator(img)
    return img


# ── 02 연결됨 홈 ────────────────────────────────────────────────────────────

def screen_home():
    img = new_screen()
    status_bar(img, CFG['time']['home'])
    app_bar(img, res('app_name'), right=res('phone_linked_since', CFG['date']))
    lo, lv, wk = CFG['loadout'], CFG['level'], CFG['week']
    km = res('unit_km')
    CAR.draw(img, (M, 100, CW, 212), lo)
    y = 320
    b = baseline(y, 'title')
    w = text_bl(img, M, b, res('level_short', lv['lv']), 'title', C['ink'], HI)
    text_bl(img, M + w + 8, b, APP.title_for_level(lv['lv']), 'body', C['inkDim'], 1)
    gauge(img, M, y + 38, CW, 4, lv['progress'])
    text(img, M, y + 50, res('home_next_level', lv['next'], lv['xpToNext']), 'caption', C['inkDim'], 1)
    hairline(img, y + 84)
    y += 100
    text(img, M, y, res('home_week_title'), 'body', C['inkDim'], 1)
    num_unit(img, M, y + 24, str(wk['km']), km)
    y2 = y + 24 + 68 + 4
    text(img, M, y2, f"{res('home_today')} {wk['todayKm']} {km} · {res('home_yesterday')} {wk['yesterdayKm']} {km}",
         'body', C['ink'], HI)
    text_bl(img, SCREEN[0] - M, baseline(y2, 'body'), res('home_streak_days', wk['streakDays']),
            'caption', C['inkDim'], 1, align='right')
    hairline(img, y2 + 38)
    y3 = y2 + 54
    nxt = APP.ach_by_id[CFG['nextAchievement']]
    medal(img, M, y3, 36, False, nxt['tier'])
    text(img, M + 48, y3 + 7, res('home_next_goal', nxt['title'], nxt['rule']['km'] - CFG['safeTotalKm']),
         'body', C['ink'], HI)
    share_scope(img, y3 + 56)
    lvl = lv['lv']
    key_strip(img, 708, [('trip', CP['keyTrip'], CFG['date']),
                         ('medal', res('home_key_achievements'), res('count_of_total', len(CFG['unlocked']), len(APP.ach))),
                         ('garage', res('home_key_garage'), res('count_of_total', APP.collected(lvl), APP.total()))])
    home_indicator(img)
    return img


# ── 03 트립 요약 ────────────────────────────────────────────────────────────

def item_name(it):
    """해금 목록용 이름: 색은 이름만, 휠·램프는 슬롯 이름을 붙여 무엇인지 알 수 있게."""
    return it['label'] + (' ' + res('garage_tab_' + it['slot']) if it['slot'] in ('wheel', 'lamp') else '')


def grid_height(n, cols=4, d=32):
    rows = (n + cols - 1) // cols
    return rows * (d + 4 + 18) + max(rows - 1, 0) * 12


def unlock_grid(img, x, y, w, items, lo, cols=4, d=32):
    """레벨업으로 열린 꾸미기를 견본 + 이름으로(4열, 줄 사이 12dp)."""
    cw, row_h = w / cols, d + 4 + 18
    for i, it in enumerate(items):
        r, c = divmod(i, cols)
        cx, cy = x + cw * c + cw / 2, y + (row_h + 12) * r + d / 2
        if it['slot'] == 'color':
            circle(img, cx, cy, d / 2, it['rgb'])
        elif it['slot'] == 'wheel':
            paste_circle(img, CAR.wheel_thumb(lo, it['id'], d, C['surface']), cx, cy)
            ring(img, cx, cy, d / 2 - 0.5, 1, C['line'])
        elif it['slot'] == 'lamp':
            lamp_swatch(img, cx, cy, d / 2, it['rgb'])
        else:
            ring(img, cx, cy, d / 2 - 0.5, 1, C['inkDim'])
        text(img, cx, cy + d / 2 + 4, item_name(it), 'caption', C['inkDim'], 1, align='center')


def trip_details():
    """차 요약과 같은 조각을 한 줄로: 주행 n km · 1 km 구간 n개 제외 · 급감속 n."""
    tr = CFG['trip']
    parts = [res('summary_distance', tr['distanceKm'])]
    if tr['excluded']:
        parts.append(res('summary_excluded', tr['excluded']))
    parts += [f"{res(name)} {n}" for name, n in tr['risks'].items() if n]
    return ' · '.join(parts)


def screen_summary():
    img = new_screen()
    status_bar(img, CFG['time']['summary'])
    tr = CFG['trip']
    app_bar(img, tr['title'], back=True)
    y = 104
    text(img, M, y, res('summary_safe'), 'body', C['inkDim'], 1)
    num_unit(img, M, y + 24, str(tr['safeKm']), res('unit_km'))
    yy = y + 24 + 68 + 2
    for ln in wrap(trip_details(), 'caption', CW):
        text(img, M, yy, ln, 'caption', C['inkDim'], 1)
        yy += 18
    y = yy + 22
    col2 = M + CW / 2
    text(img, M, y, res('summary_xp'), 'body', C['inkDim'], 1)
    text(img, M, y + 24, res('summary_xp_value', tr['xp']), 'title', C['ink'], HI)
    text(img, col2, y, res('summary_level'), 'body', C['inkDim'], 1)
    text(img, col2, y + 24, res('summary_level_change', tr['levelFrom'], tr['levelTo']), 'title', C['ink'], HI)
    if tr['xp'] >= APP.daily_cap:
        # 차는 josa("300 XP", "을/를")로 붙인다. XP는 '피'(모음)로 끝나 '를'.
        text(img, M, y + 58, res('summary_cap_reached', f'{APP.daily_cap} XP를'), 'caption', C['inkDim'], 1)
    # 레벨 업 카드(차 요약의 LevelUpCard와 같은 1dp 노랑 테두리. 앱이 허용한 유일한 연출 모멘트)
    items = APP.unlocked_at(tr['levelTo'])
    cy = y + 96
    ch = 16 + 26 + 4 + 22 + 16 + grid_height(len(items)) + 16
    rrect(img, M, cy, CW, ch, 16, C['surface'])
    rrect_line(img, M, cy, CW, ch, 16, 1, C['amber'])
    b = baseline(cy + 16, 'title')
    w = text_bl(img, M + 16, b, res('level_short', tr['levelTo']), 'title', C['ink'], HI)
    text_bl(img, M + 16 + w + 8, b, APP.title_for_level(tr['levelTo']), 'title', C['ink'], HI)
    text(img, M + 16, cy + 46, res('summary_levelup_unlocks', len(items)), 'body', C['inkDim'], 1)
    unlock_grid(img, M + 16, cy + 84, CW - 32, items, CFG['loadout'])
    ay = cy + ch + 24
    text(img, M, ay, CP['summaryAch'], 'body', C['inkDim'], 1)
    ay += 32
    for aid in tr['achievements']:
        a = APP.ach_by_id[aid]
        medal(img, M, ay, 36, True, a['tier'])
        text(img, M + 48, ay - 2, a['title'], 'body', C['ink'], HI)
        yy = ay + 20
        for ln in wrap(a['description'], 'caption', CW - 48):
            text(img, M + 48, yy, ln, 'caption', C['inkDim'], 1)
            yy += 18
        ay = max(ay + 48, yy + 12)
    primary(img, CP['summaryCta'], 724)
    home_indicator(img)
    return img


# ── 03b 공유 카드(1080×1080, 숫자·메달·미니카만) ───────────────────────────

def share_card():
    tr = CFG['trip']
    img = Image.new('RGB', (1080, 1080), C['ground'])
    W = 360
    text_bl(img, 24, 44, res('app_name'), 'title', C['ink'], HI)
    text_bl(img, W - 24, 44, tr['title'], 'caption', C['inkDim'], 1, align='right')
    CAR.draw(img, (24, 56, 312, 168), CFG['loadout'])
    y = 228
    text(img, 24, y, res('summary_safe'), 'body', C['inkDim'], 1)
    num_unit(img, 24, y + 22, str(tr['safeKm']), res('unit_km'))
    rx = W - 24
    text(img, rx, y + 6, f"{res('summary_xp_value', tr['xp'])} {res('summary_xp')}", 'title', C['ink'], HI, align='right')
    text(img, rx, y + 38, res('summary_level_change', tr['levelFrom'], tr['levelTo']), 'title', C['ink'], HI,
         align='right')
    n = len(tr['achievements'])
    lbl = CP['cardNewAch'].format(n=n)
    lw = tw(lbl, 'caption')
    text(img, rx, y + 76, lbl, 'caption', C['inkDim'], 1, align='right')
    for i in range(n):
        medal(img, rx - lw - 8 - 24 * (i + 1) - 4 * i, y + 72, 24, True)
    return img


# ── 04 업적 컬렉션 ──────────────────────────────────────────────────────────

def achievement_progress(a):
    """잠긴 업적 중 누적으로 셀 수 있는 것만 (비율, 진행 문구). 문구는 차 업적 화면 lockedLine과 같은 strings.xml 형식."""
    r, t = a['rule'], a['rule']['type']
    if t in ('safeDistance', 'nightSafeDistance', 'rainSafeDistance'):
        have = {'safeDistance': CFG['safeTotalKm'], 'nightSafeDistance': CFG['nightSafeKm'],
                'rainSafeDistance': CFG['rainSafeKm']}[t]
        have, need = min(have, r['km']), r['km']
        return have / need, res('achievement_progress_km', have, need)
    if t == 'streakDays':
        have, need = min(CFG['week']['streakDays'], r['days']), r['days']
        return have / need, res('achievement_progress_days', have, need)
    if t == 'level':                               # 막대도 글("Lv 5 / 10")과 같은 비율
        lv, need = min(CFG['level']['lv'], r['level']), r['level']
        return lv / need, res('achievement_progress_level', lv, need)
    if t == 'tripCount' and r['count'] > 1:
        have, need = min(CFG.get('tripCount', 0), r['count']), r['count']
        return have / need, res('achievement_progress_count', have, need)
    return None


def locked_line(a):
    """차 AchievementsScreen.lockedLine: 조건 문구 + 진행(있을 때). 둘 다 없으면 설명."""
    prog = achievement_progress(a)
    return ' '.join(x for x in (a['condition'], prog[1] if prog else '') if x) or a['description']


def screen_achievements():
    """위계: 연 것(크게, 날짜·설명) → 진행 중(크게, 막대) → 잠긴 것(작게, 조건 한 줄). 칸 격자는 쓰지 않는다."""
    img = new_screen()
    status_bar(img, CFG['time']['achievements'])
    app_bar(img, res('achievements_title'), back=True)
    unlocked, n = CFG['unlocked'], len(APP.ach)
    y = 92
    b = baseline(y, 'num44')
    w = text_bl(img, M, b, str(len(unlocked)), 'num44', C['ink'], HI)
    text_bl(img, M + w + 8, b, f"/ {n}", 'title', C['inkDim'], 1)
    gauge(img, M, y + 56, CW, 4, len(unlocked) / n)
    done = [a for a in APP.ach if a['id'] in unlocked]
    going = [a for a in APP.ach if a['id'] not in unlocked and (achievement_progress(a) or (0,))[0] > 0]
    rest = [a for a in APP.ach if a['id'] not in unlocked and a not in going]
    tx, rx = M + 36 + 12, SCREEN[0] - M            # 글 시작(메달 36 + 12), 오른쪽 끝
    y += 56 + 4 + 16

    def header(y, label):
        text(img, M, y, label, 'caption', C['inkDim'], 1)
        return y + 18 + 6

    y = header(y, CP['achOpen'])
    for a in done:                                 # 46dp: 메달 36 · 제목 body · 날짜 · 설명 caption
        medal(img, M, y + 5, 36, True, a['tier'])
        text(img, tx, y + 3, a['title'], 'body', C['ink'], HI)
        text(img, tx, y + 25, f"{unlocked[a['id']]} · {a['description']}", 'caption', C['inkDim'], 1)
        y += 46
    y = header(y + 16, CP['achGoing'])
    for a in going:                                # 46dp: 메달 36(잠김) · 제목 + 오른쪽 진행 숫자 · 막대
        ratio, label = achievement_progress(a)
        medal(img, M, y + 5, 36, False, a['tier'])
        b = baseline(y + 3, 'body')
        text_bl(img, tx, b, a['title'], 'body', C['ink'], HI)
        text_bl(img, rx, b, label, 'caption', C['inkDim'], 1, align='right')
        gauge(img, tx, y + 33, rx - tx, 4, ratio)
        y += 46
    y = header(y + 16, CP['achLocked'])
    for a in rest:                                 # 28dp: 메달 24 · 제목 caption(흐림) · 오른쪽 조건
        medal(img, M + 6, y + 2, 24, False)
        text(img, tx, y + 5, a['title'], 'caption', C['ink'], LOCKED)
        text(img, rx, y + 5, locked_line(a), 'caption', C['inkDim'], 1, align='right')
        y += 28
    if y > SCREEN[1] - 34:
        sys.exit(f'업적 화면이 넘쳐요(y={y:.0f}dp).')
    home_indicator(img)
    return img


# ── 05 차고 미리보기(읽기 전용) ─────────────────────────────────────────────

def screen_garage():
    img = new_screen()
    status_bar(img, CFG['time']['garage'])
    lv, lo = CFG['level']['lv'], CFG['loadout']
    rows = {s: res('garage_tab_' + s) for s in ('vehicle', 'color', 'wheel', 'lamp')}
    app_bar(img, res('garage_title'), back=True, sub=res('garage_collected', APP.collected(lv), APP.total()))
    CAR.draw(img, (M, 96, CW, 184), lo)
    roof = (res('garage_none_of', res('garage_tab_roof')) if lo['roof'] == 'none'
            else APP.item('roof', lo['roof'])['label'])
    spec = ' · '.join([APP.item('vehicle', lo['vehicle'])['label'], APP.item('color', lo['color'])['label'],
                       APP.item('wheel', lo['wheel'])['label'], APP.item('lamp', lo['lamp'])['label'], roof])
    text(img, SCREEN[0] / 2, 284, spec, 'body', C['inkDim'], 1, align='center')
    ix, iw = M + 56, CW - 56

    def label(y, h, s):
        text(img, M, y + (h - 22) / 2, s, 'body', C['inkDim'], 1)

    def cap(cx, top, s, sel, locked):
        text(img, cx, top, s, 'caption', C['ink'] if sel else C['inkDim'], HI if sel else 1, align='center')

    y = 322
    hairline(img, y - 8)
    vs = APP.slot('vehicle')
    cw = iw / len(vs)
    label(y, 72, rows['vehicle'])
    for i, it in enumerate(vs):
        cx, sel = ix + cw * i + cw / 2, it['id'] == lo['vehicle']
        if sel:
            rrect_line(img, cx - cw / 2 + 1, y, cw - 2, 72, 12, 2, C['amber'])
        silhouette(img, it['id'], cx - 20, y + 6, 40, C['ink'], HI)
        cap(cx, y + 46, it['label'], sel, False)
    y += 80
    hairline(img, y)
    y += 8
    cw = iw / 4
    label(y, 64, rows['color'])
    for i, it in enumerate(APP.slot('color')):
        r, c = divmod(i, 4)
        cx, cy = ix + cw * c + cw / 2, y + 64 * r + 22
        locked, sel = it['level'] > lv, it['id'] == lo['color']
        circle(img, cx, cy, 14, it['rgb'], DIS if locked else 1)
        ring(img, cx, cy, 19, 2 if sel else 1, C['amber'] if sel else C['line'])
        cap(cx, cy + 22, f"Lv {it['level']}" if locked else it['label'], sel, locked)
    y += 128
    hairline(img, y)
    y += 8
    label(y, 72, rows['wheel'])
    for i, it in enumerate(APP.slot('wheel')):
        cx, cy = ix + cw * i + cw / 2, y + 26
        locked, sel = it['level'] > lv, it['id'] == lo['wheel']
        th = CAR.wheel_thumb(lo, it['id'], 40, C['ground'])
        if locked:
            th = Image.blend(Image.new('RGB', th.size, C['ground']), th, DIS)
        paste_circle(img, th, cx, cy)
        ring(img, cx, cy, 23, 2 if sel else 1, C['amber'] if sel else C['line'])
        cap(cx, cy + 28, f"Lv {it['level']}" if locked else it['label'], sel, locked)
    y += 80
    hairline(img, y)
    y += 8
    label(y, 64, rows['lamp'])
    for i, it in enumerate(APP.slot('lamp')):
        cx, cy = ix + cw * i + cw / 2, y + 22
        locked, sel = it['level'] > lv, it['id'] == lo['lamp']
        lamp_swatch(img, cx, cy, 14, it['rgb'], DIS if locked else 1)
        ring(img, cx, cy, 19, 2 if sel else 1, C['amber'] if sel else C['line'])
        cap(cx, cy + 22, f"Lv {it['level']}" if locked else it['label'], sel, locked)
    y += 72
    text(img, SCREEN[0] / 2, y + 8, CP['garageNote'], 'caption', C['inkDim'], 1, align='center')
    home_indicator(img)
    return img


# ── 실행 ────────────────────────────────────────────────────────────────────

JOBS = [
    ('phone_01_link.png', '연결: QR 비추기·코드 입력', lambda: framed(screen_link())),
    ('phone_02_home.png', '연결됨 홈: 내 미니카·레벨·이번 주 안전 거리', lambda: framed(screen_home())),
    ('phone_03_summary.png', '트립 요약 카드', lambda: framed(screen_summary())),
    ('phone_03b_share_card.png', '공유 카드(숫자·메달·미니카만)', share_card),
    ('phone_04_achievements.png', '업적: 연 것·진행 중(크게)·잠긴 것(작게)', lambda: framed(screen_achievements())),
    ('phone_05_garage.png', '차고 미리보기(읽기 전용)', lambda: framed(screen_garage())),
]


def derive():
    """screens.json의 원값(누적 XP, 트립 XP)에서 레벨 숫자를 앱 규칙(LevelTable)으로 계산한다."""
    xp, tr = CFG['xp'], CFG['trip']
    lv = APP.level_for_xp(xp)
    lo, hi = APP.xp_for_level(lv), APP.xp_for_level(lv + 1)
    CFG['level'] = dict(lv=lv, next=lv + 1, xpToNext=hi - xp, progress=(xp - lo) / (hi - lo))
    tr['levelFrom'], tr['levelTo'] = APP.level_for_xp(xp - tr['xp']), lv
    missing = [a['id'] for a in APP.ach
               if a['id'] not in CFG['unlocked'] and achievement_progress(a) is None and not a['condition']]
    if missing:   # 조건도 진행도 없으면 설명(지난 시제)이 조건 자리에 나온다
        sys.exit('achievements.json에 잠긴 업적 조건(condition)이 빠졌어요: ' + ', '.join(missing))


def main():
    global APP, C, CFG, CP, CAR, QR
    ap = argparse.ArgumentParser(description='오도 폰 동반 화면 목업 PNG를 만든다.')
    ap.add_argument('--out', default=os.path.join(REPO, 'docs', 'phone-mockup'), help='출력 폴더')
    ap.add_argument('--plates', default=os.path.join(MAIN, 'assets', 'minicar'),
                    help='미니카 플레이트 폴더(<차종>/hero/<층>.webp|png)')
    ap.add_argument('--only', nargs='*', help='일부 파일만(이때는 manifest를 쓰지 않음)')
    args = ap.parse_args()

    APP = AppSource()
    C = APP.colors
    cfg_path = os.path.join(HERE, 'screens.json')
    CFG = json.loads(read(cfg_path))
    CP = CFG['copy']
    derive()
    CAR = Minicar(args.plates)
    payload = link_payload()
    QR, qr_ver = qr_matrix(payload.encode('ascii'))
    os.makedirs(args.out, exist_ok=True)

    done = []
    for name, desc, fn in JOBS:
        if args.only and name not in args.only:
            continue
        im = fn()
        path = os.path.join(args.out, name)
        im.save(path)
        done.append((name, desc, im.size, path))
        print(f'{name} {im.size[0]}x{im.size[1]}')
    if args.only:
        return

    manifest = {
        'generator': 'tools/phone-mockup/render.py',
        'command': 'sh tools/phone-mockup/fetch_fonts.sh && python3 -I tools/phone-mockup/render.py',
        'pillow': PIL.__version__,
        'raqm': RAQM,
        'canvas': {'phone': {'dp': list(FRAME), 'density': S, 'px': [FRAME[0] * S, FRAME[1] * S],
                             'frame': 'generic, corner r 48dp, bezel 12dp, one punch hole, no logo'},
                   'shareCard': {'px': [1080, 1080]}},
        'palette': 'OdoNight',
        'inputs': [rel(p) for p in APP.paths] + [rel(cfg_path)],
        'plates': rel(args.plates),
        'fonts': {n: sha256(os.path.join(FONT_DIR, n)) for n in sorted(set(FONT_FILES.values()))},
        'qr': {'format': 'odo-link:v1:<base32 16 bytes>', 'version': qr_ver, 'ecl': 'M',
               'token': 'demo token = sha256(tokenSeed)[:16], not a live pairing token'},
        'files': [{'file': n, 'screen': d, 'px': list(sz), 'sha256': sha256(p)} for n, d, sz, p in done],
    }
    with open(os.path.join(args.out, 'manifest.json'), 'w', encoding='utf-8') as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print('manifest.json')


if __name__ == '__main__':
    main()
