#!/usr/bin/env python3
"""렌더 PNG → 앱 에셋(WebP) + compose.json (에셋 계약 v2).

usage:
  python3 -B postprocess.py --src <render_dir> [--dst <에셋 폴더>] [--cwebp /opt/homebrew/bin/cwebp]
  python3 -B postprocess.py --check <에셋 폴더> [--limit-kib 4096]

--dst 를 생략하면 app/src/main/assets/minicar 에 쓴다. 후보를 따로 뽑을 때는 --dst 로 다른 폴더를 준다.

계약 v2 (compose.json "version": 2):
  뷰 3개   hero 576×384(차고·휴대폰 연결) / navi 256×320(글랜스, 뒤에서 내려다봄) / top 256×384(north-up 지도, 회전)
  레이어 14개, 뷰마다 skip 레이어는 만들지 않는다 → 차종당 13+10+10장, 5차종 165장 + compose.json = 166파일
  hero          → 손실 WebP  (cwebp -q 85 -alpha_q 90 -sharp_yuv -m 6 -alpha_filter best)
  navi·top      → 무손실 WebP(cwebp -lossless -z 9 -exact)
  shadow        → 알파 바닥 잡음을 선형 리매핑으로 제거(a' = max(0, a-6)·255/249), RGB 는 검정
  glow_head/tail→ 렌더 원본은 검은 배경 위 RGB(알파 무시). A = max(R,G,B)(세기), RGB = 빛 색(최대 채널이 255가 되게
                  정규화)으로 바꾼다. 앱은 이것을 Screen 으로 얹는다(RGB×A = 원본). 세기 바닥 2/255 를 선형 리매핑으로
                  걷어 내고 플레이트 가장자리에서 0으로 떨어지게 한다. 무손실과 손실(-q 90, 알파는 무손실) 가운데 작은 쪽.
  총 용량 ≤ 4096 KiB(du -sk 기준: 4 KiB 블록 합)

--check 가 실패하는 경우: 빠진 파일, 남는 파일(숨김 파일 제외), WebP 가 아니거나 크기가 뷰 px 와 다른 파일,
용량 초과, compose.json 이 없거나 계약과 다름. --check 는 표준 라이브러리만 쓴다(Pillow 없이 돈다).
변환에는 Pillow 와 cwebp 가 필요하다. 같은 입력·같은 cwebp 버전이면 바이트 동일.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor

sys.dont_write_bytecode = True

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DST = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "minicar")
VEHICLES = ["compact", "suv", "large", "sedan", "scooter"]
LAYERS = ["shadow", "body", "spec", "fixed", "wheel_steel", "wheel_dia", "wheel_aero", "lamp",
          "roof_twotone", "roof_box", "decal_stripe", "plate_gold", "glow_head", "glow_tail"]
GLOW = ["glow_head", "glow_tail"]
WHEELS = ["wheel_steel", "wheel_dia", "wheel_aero"]
VIEWS = {
    "hero": {"px": [576, 384], "dp": [288, 192], "groundY": 336, "skip": ["glow_tail"]},
    "navi": {"px": [256, 320], "dp": [128, 160], "anchor": [128, 200], "skip": WHEELS + ["lamp"]},
    "top": {"px": [256, 384], "dp": [128, 192], "anchor": [128, 192], "skip": WHEELS + ["plate_gold"]},
}
# 렌더가 바닥 원점(차 중심 아래)을 놓아야 하는 자리(px). render_meta.json 의 origin_px 와 맞는지 본다.
ORIGIN_PX = {"hero": [288, 272], "navi": VIEWS["navi"]["anchor"], "top": VIEWS["top"]["anchor"]}
LOSSY_VIEWS = {"hero"}
LIMIT_KIB = 4096
SHADOW_FLOOR = 6
GLOW_FLOOR = 2          # 세기 바닥(렌더 잡음·빛 꼬리)
GLOW_EDGE = 0.05        # 가장자리 페더 폭 = 짧은 변 × 이 값
GLOW_COLOR_BLUR = 2.0   # 빛 색을 구할 때 쓰는 흐림 반경(px). 세기(알파)는 흐리지 않는다
GLOW_COLOR_REG = 4      # 어두운 곳의 색을 전체 평균 색으로 끌어당기는 정도(나눗셈 잡음 방지)
GLOW_EDGE_WARN = 8      # 페더 전 가장자리 세기가 이보다 크면 빛이 플레이트에서 잘린 것
JOBS = 4                # cwebp 동시 실행 수


def manifest(samples=128):
    """compose.json v2. 키 이름은 track-a-3d.md '에셋 계약 v2' 그대로. order 는 build_minicar.ORDER 와 같아야 한다
    (2026-11-11 W2-fix: 덧칠 roof_twotone·decal_stripe 를 spec 바로 뒤로, roof_box 를 lamp 뒤로 옮김 — 깊이 순서)."""
    return {
        "version": 2,
        "views": {k: dict(v) for k, v in VIEWS.items()},
        "order": ["shadow", "body", "spec", "roof_twotone", "decal_stripe", "fixed", "wheel", "lamp", "roof_box",
                  "plate_gold", "glow_head", "glow_tail"],
        "blend": {"body": "modulate", "spec": "screen", "lamp": "modulate", "roof_twotone": "modulate",
                  "decal_stripe": "modulate", "glow_head": "screen", "glow_tail": "screen"},
        "night": list(GLOW),
        "vehicles": {"compact": {"label": "소형", "naviScale": 1.0}, "suv": {"label": "중형", "naviScale": 1.0},
                     "large": {"label": "대형", "naviScale": 1.0}, "sedan": {"label": "세단", "naviScale": 1.0},
                     "scooter": {"label": "2륜", "naviScale": 1.25}},
        "layers": list(LAYERS),
        "provenance": "현대차 디자인 언어에서 영감을 받아 자체 제작한 스타일라이즈드 모델. 특정 차종·엠블럼·명칭을 재현하지 않음.",
        "generator": "tools/minicar/build_minicar.py",
        "blender": "4.5.9",
        "samples": samples,
    }


def expected():
    """[(차종, 뷰, 레이어)] — 뷰의 skip 은 뺀다. 165개."""
    return [(v, view, layer) for v in VEHICLES for view in VIEWS for layer in LAYERS
            if layer not in VIEWS[view]["skip"]]


def rel_path(item, ext=".webp"):
    return os.path.join(item[0], item[1], item[2] + ext)


def find_cwebp(explicit):
    for c in [explicit, shutil.which("cwebp"), "/opt/homebrew/bin/cwebp", "/usr/local/bin/cwebp"]:
        if c and os.path.exists(c):
            return c
    sys.exit("cwebp를 찾을 수 없습니다(brew install webp).")


# ── 변환(Pillow) ───────────────────────────────────────────────────────────────

def remap_shadow(src_png, dst_png):
    from PIL import Image
    im = Image.open(src_png).convert("RGBA")
    a = im.getchannel("A").point(lambda v: max(0, v - SHADOW_FLOOR) * 255 // (255 - SHADOW_FLOOR))
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))  # 그림자는 순수 검정
    out.putalpha(a)
    out.save(dst_png, optimize=False)


def edge_mask(size, width):
    """가장자리에서 0, width px 안쪽부터 255 인 마스크(smoothstep)."""
    from PIL import Image, ImageChops

    def ramp(n):
        vals = []
        for i in range(n):
            t = min(1.0, min(i, n - 1 - i) / float(width)) if width > 0 else 1.0
            vals.append(round(255 * t * t * (3 - 2 * t)))
        return bytes(vals)

    w, h = size
    mx = Image.frombytes("L", (w, 1), ramp(w)).resize((w, h), Image.NEAREST)
    my = Image.frombytes("L", (1, h), ramp(h)).resize((w, h), Image.NEAREST)
    return ImageChops.multiply(mx, my)


def convert_glow(src_png, dst_png):
    """검은 배경 위 RGB → RGB=빛 색, A=세기. (가장자리 세기 최대, 색 복원 오차 최대, 원본에 알파가 있었는지)를 돌려준다."""
    from PIL import Image, ImageChops, ImageFilter, ImageMath, ImageStat
    raw = Image.open(src_png)
    had_alpha = "A" in raw.getbands() and raw.getchannel("A").getextrema()[0] < 255
    im = raw.convert("RGB")                                   # 알파는 무시(계약: 검은 배경 위 RGB)
    w, h = im.size
    r, g, b = im.split()
    a = ImageChops.lighter(ImageChops.lighter(r, g), b)        # 세기 = max(R,G,B)
    border = max(a.crop(box).getextrema()[1] for box in ((0, 0, w, 1), (0, h - 1, w, h), (0, 0, 1, h), (w - 1, 0, w, h)))

    # 빛 색: 살짝 흐린 원본을 그 최대 채널로 나눈다. 어두운 곳은 전체 평균 색으로 끌어당긴다(0 나눗셈·잡음 방지).
    sm = im.filter(ImageFilter.GaussianBlur(GLOW_COLOR_BLUR)) if GLOW_COLOR_BLUR else im
    sr, sg, sb = sm.split()
    sa = ImageChops.lighter(ImageChops.lighter(sr, sg), sb)
    sums = ImageStat.Stat(im).sum
    top = max(sums)
    mean = [round(255 * s / top) for s in sums] if top > 0 else [255, 255, 255]
    k = GLOW_COLOR_REG

    def norm(c, m):
        return ImageMath.lambda_eval(
            lambda args: args["convert"](args["min"]((args["c"] * 255 + m * k + (args["a"] + k) / 2) / (args["a"] + k), 255), "L"),
            c=c, a=sa)

    color = [norm(c, m) for c, m in zip((sr, sg, sb), mean)]
    err = max(ImageChops.difference(ImageChops.multiply(c, a), o).getextrema()[1] for c, o in zip(color, (r, g, b)))

    a = a.point(lambda v: max(0, v - GLOW_FLOOR) * 255 // (255 - GLOW_FLOOR))
    a = ImageChops.multiply(a, edge_mask((w, h), round(min(w, h) * GLOW_EDGE)))
    Image.merge("RGBA", color + [a]).save(dst_png, optimize=False)
    return border, err, had_alpha


def encode(cwebp, src, dst, lossless, glow=False):
    args = [cwebp, "-quiet", "-mt"]
    if lossless:
        args += ["-lossless", "-z", "9"] + ([] if glow else ["-exact"])
    elif glow:                                                 # 색만 손실, 세기(알파)는 무손실. -m 6 은 알파 무손실에서 10배 느려 -m 5
        args += ["-q", "90", "-alpha_q", "100", "-sharp_yuv", "-m", "5", "-alpha_filter", "best"]
    else:
        args += ["-q", "85", "-alpha_q", "90", "-sharp_yuv", "-m", "6", "-alpha_filter", "best"]
    args += [src, "-o", dst]
    subprocess.run(args, check=True)


def encode_glow(cwebp, src, dst):
    """무손실·손실을 둘 다 만들어 작은 쪽을 남긴다(같으면 무손실). 고른 방식을 돌려준다."""
    a, b = dst + ".ll", dst + ".ly"
    encode(cwebp, src, a, lossless=True, glow=True)
    encode(cwebp, src, b, lossless=False, glow=True)
    keep, drop, how = (a, b, "lossless") if os.path.getsize(a) <= os.path.getsize(b) else (b, a, "lossy")
    os.replace(keep, dst)
    os.remove(drop)
    return how


# ── 검사(표준 라이브러리만) ─────────────────────────────────────────────────────

def du_kib(paths):
    """du -sk 근사: 파일마다 4 KiB 블록 올림 합."""
    return sum(((os.path.getsize(p) + 4095) // 4096) * 4 for p in paths)


def webp_size(path):
    """WebP 헤더에서 (w, h)를 읽는다. WebP 가 아니면 None."""
    with open(path, "rb") as f:
        h = f.read(30)
    if len(h) < 30 or h[:4] != b"RIFF" or h[8:12] != b"WEBP":
        return None
    kind = h[12:16]
    if kind == b"VP8X":
        return 1 + int.from_bytes(h[24:27], "little"), 1 + int.from_bytes(h[27:30], "little")
    if kind == b"VP8L" and h[20] == 0x2F:
        bits = int.from_bytes(h[21:25], "little")
        return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
    if kind == b"VP8 " and h[23:26] == b"\x9d\x01\x2a":
        return int.from_bytes(h[26:28], "little") & 0x3FFF, int.from_bytes(h[28:30], "little") & 0x3FFF
    return None


def check(dst, limit_kib=LIMIT_KIB):
    dst = os.path.abspath(dst)
    want = {rel_path(it): it for it in expected()}
    have, missing, bad = [], [], []
    for rp, (_, view, _) in want.items():
        p = os.path.join(dst, rp)
        if not os.path.isfile(p):
            missing.append(rp)
            continue
        have.append(p)
        size = webp_size(p)
        if size is None:
            bad.append(f"{rp}: WebP 가 아님")
        elif list(size) != VIEWS[view]["px"]:
            bad.append(f"{rp}: {size[0]}x{size[1]} (계약 {VIEWS[view]['px'][0]}x{VIEWS[view]['px'][1]})")
    extra = []
    for dp, dns, fns in os.walk(dst):
        dns[:] = sorted(d for d in dns if not d.startswith("."))
        for fn in sorted(fns):
            rp = os.path.relpath(os.path.join(dp, fn), dst)
            if not fn.startswith(".") and rp not in want and rp != "compose.json":
                extra.append(rp)

    man_path = os.path.join(dst, "compose.json")
    man_err, n_layers, version = [], 0, None
    if not os.path.isfile(man_path):
        man_err.append("compose.json 없음")
    else:
        try:
            with open(man_path, encoding="utf-8") as f:
                m = json.load(f)
        except ValueError as e:
            m = None
            man_err.append(f"compose.json 을 읽을 수 없음: {e}")
        if isinstance(m, dict):
            version = m.get("version")
            ref = manifest(m.get("samples", 128))
            if version != 2:
                man_err.append(f"compose.json version {version!r} (v2 에셋이 아님)")
            for key in sorted(set(ref) | set(m)):
                if m.get(key) != ref.get(key):
                    man_err.append(f"compose.json '{key}' 가 계약과 다름: {json.dumps(m.get(key), ensure_ascii=False)[:160]}")
            try:
                n_layers = sum(len(m["layers"]) - len(set(v.get("skip", [])) & set(m["layers"]))
                               for v in m["views"].values()) * len(m["vehicles"])
            except (KeyError, TypeError, AttributeError):
                n_layers = 0
            if m.get("samples") != 128:
                print(f"WARN compose.json samples {m.get('samples')!r} (제출용은 128)")
        elif m is not None:
            man_err.append("compose.json 최상위가 객체가 아님")

    total = sum(os.path.getsize(p) for p in have)
    kib = du_kib(have + ([man_path] if os.path.isfile(man_path) else []))
    n_want = len(want)
    print(f"files {len(have)}/{n_want} (+compose.json = {n_want + 1}), missing {len(missing)}, extra {len(extra)}, "
          f"bad {len(bad)}, bytes {total}, du≈{kib} KiB (limit {limit_kib}), manifest v{version} layers {n_layers}")
    for rp in missing:
        print("MISSING", rp)
    for rp in extra:
        print("EXTRA", rp)
    for msg in bad:
        print("BAD", msg)
    for msg in man_err:
        print("MANIFEST", msg)
    if kib > limit_kib:
        print(f"OVER du≈{kib} KiB > {limit_kib} KiB")
    ok = not missing and not extra and not bad and not man_err and kib <= limit_kib and n_layers == n_want
    print("CHECK OK" if ok else "CHECK FAILED")
    return ok


# ── 실행 ───────────────────────────────────────────────────────────────────────

def validate_src(src):
    """렌더 폴더가 계약대로인지 본다(빠진 PNG, 크기, 바닥 원점). 문제를 전부 모아 한 번에 알린다."""
    from PIL import Image
    problems = []
    for it in expected():
        p = os.path.join(src, rel_path(it, ".png"))
        if not os.path.isfile(p):
            problems.append(f"렌더 누락: {p}")
            continue
        with Image.open(p) as im:
            if list(im.size) != VIEWS[it[1]]["px"]:
                problems.append(f"크기 틀림: {p} {im.size[0]}x{im.size[1]} (계약 {VIEWS[it[1]]['px']})")
    samples = 128
    meta_path = os.path.join(src, "render_meta.json")
    if os.path.isfile(meta_path):
        with open(meta_path, encoding="utf-8") as f:
            meta = json.load(f)
        samples = meta.get("samples", 128)
        for v in VEHICLES:
            for view, want in ORIGIN_PX.items():
                got = meta.get("vehicles", {}).get(v, {}).get("views", {}).get(view, {}).get("origin_px")
                if got is None:
                    problems.append(f"render_meta.json 에 {v}/{view} origin_px 없음")
                elif abs(got[0] - want[0]) > 0.5 or abs(got[1] - want[1]) > 0.5:
                    problems.append(f"바닥 원점 틀림: {v}/{view} {got} (계약 {want})")
    else:
        print("WARN render_meta.json 없음: 바닥 원점(anchor) 확인을 건너뜀")
    if problems:
        for msg in problems:
            print(msg)
        sys.exit(f"렌더 폴더가 에셋 계약 v2 와 다릅니다({len(problems)}건).")
    return samples


def prune_skipped(dst):
    """그 뷰에서 이제 안 쓰는(skip) 레이어의 옛 .webp 를 지운다. 다른 남는 파일은 건드리지 않는다(--check 가 알린다)."""
    removed = []
    for v in VEHICLES:
        for view, spec in VIEWS.items():
            for layer in spec["skip"]:
                p = os.path.join(dst, v, view, layer + ".webp")
                if os.path.isfile(p):
                    os.remove(p)
                    removed.append(os.path.join(v, view, layer + ".webp"))
    return removed


def main():
    ap = argparse.ArgumentParser(description="미니카 렌더 PNG → 앱 에셋(WebP) + compose.json v2")
    ap.add_argument("--src", help="렌더 폴더(<차종>/<뷰>/<레이어>.png + render_meta.json)")
    ap.add_argument("--dst", default=DEFAULT_DST, help="에셋 폴더(기본: app/src/main/assets/minicar)")
    ap.add_argument("--cwebp")
    ap.add_argument("--check", metavar="DIR", help="에셋 폴더 검사만")
    ap.add_argument("--limit-kib", type=int, default=LIMIT_KIB, help="용량 한도(KiB, 기본 4096)")
    a = ap.parse_args()
    if a.check:
        sys.exit(0 if check(a.check, a.limit_kib) else 1)
    if not a.src:
        ap.error("--src 필요")
    cwebp = find_cwebp(a.cwebp)
    src, dst = os.path.abspath(a.src), os.path.abspath(a.dst)
    samples = validate_src(src)
    tmp = tempfile.mkdtemp(prefix="minicar_pp_")
    sizes, glow_how, notes = {}, {}, []
    worst_err = 0

    def job(png, out, key, view, glow):
        if glow:
            glow_how[key] = encode_glow(cwebp, png, out)
        else:
            encode(cwebp, png, out, lossless=view not in LOSSY_VIEWS)
        sizes[key] = os.path.getsize(out)

    try:
        jobs = []
        for v, view, layer in expected():                      # 알파 손질(Pillow)은 차례로, 인코딩(cwebp)은 4개씩 동시에
            os.makedirs(os.path.join(dst, v, view), exist_ok=True)
            png = os.path.join(src, v, view, layer + ".png")
            key = f"{v}/{view}/{layer}"
            if layer in GLOW:
                fixed = os.path.join(tmp, f"{v}_{view}_{layer}.png")
                border, err, had_alpha = convert_glow(png, fixed)
                worst_err = max(worst_err, err)
                if border > GLOW_EDGE_WARN:
                    notes.append(f"WARN {key}: 가장자리 세기 {border}/255 — 빛이 플레이트 밖으로 잘린다(페더로 0까지 낮췄다)")
                if had_alpha:
                    notes.append(f"NOTE {key}: 원본에 알파가 있다(계약은 검은 배경 위 RGB). 알파를 무시했다")
                png = fixed
            elif layer == "shadow":
                fixed = os.path.join(tmp, f"{v}_{view}_shadow.png")
                remap_shadow(png, fixed)
                png = fixed
            jobs.append((png, os.path.join(dst, v, view, layer + ".webp"), key, view, layer in GLOW))
        with ThreadPoolExecutor(max_workers=JOBS) as pool:
            for f in [pool.submit(job, *j) for j in jobs]:
                f.result()
        sizes = {j[2]: sizes[j[2]] for j in jobs}              # 출력 순서를 고정
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    removed = prune_skipped(dst)
    with open(os.path.join(dst, "compose.json"), "w", encoding="utf-8") as f:
        json.dump(manifest(samples), f, ensure_ascii=False, indent=1)
        f.write("\n")

    big = sorted(sizes.items(), key=lambda kv: -kv[1])[:8]
    print("largest:", ", ".join(f"{k} {s // 1024}K" for k, s in big))
    for view in VIEWS:
        part = [s for k, s in sizes.items() if k.split("/")[1] == view]
        print(f"view {view}: {len(part)} files, {sum(part) // 1024} KiB")
    glow_bytes = sum(sizes[k] for k in glow_how)
    n_ll = sum(1 for h in glow_how.values() if h == "lossless")
    print(f"glow: {len(glow_how)} files, {glow_bytes // 1024} KiB, lossless {n_ll} / lossy {len(glow_how) - n_ll}, "
          f"색 복원 오차 최대 {worst_err}/255")
    for msg in notes:
        print(msg)
    if removed:
        print(f"removed stale ({len(removed)}):", ", ".join(removed))
    sys.exit(0 if check(dst, a.limit_kib) else 1)


if __name__ == "__main__":
    main()
