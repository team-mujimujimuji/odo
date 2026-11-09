#!/usr/bin/env python3
"""렌더 PNG → 앱 에셋(WebP) + compose.json.

usage:
  python3 postprocess.py --src <render_dir> --dst app/src/main/assets/minicar [--cwebp /opt/homebrew/bin/cwebp]
  python3 postprocess.py --check app/src/main/assets/minicar      # 파일 수·용량만 검사

규칙(설계서 §2.3·§2.4):
  hero 576×384  → 손실 WebP  (cwebp -q 85 -alpha_q 90 -sharp_yuv -m 6 -alpha_filter best)
  navi 192×192  → 무손실 WebP(cwebp -lossless -z 9)
  shadow 레이어는 알파 바닥 노이즈를 선형 리매핑으로 제거(a' = max(0, a-6)·255/249, 단차 없음)
  총 용량 ≤ 4096 KiB(du -sk 기준: 4 KiB 블록 합)이 아니면 실패.
Pillow(알파 리매핑)와 cwebp가 필요하다. 같은 입력·같은 cwebp 버전이면 바이트 동일.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
VEHICLES = ["compact", "suv", "large", "sedan", "scooter"]
VIEWS = ["hero", "navi"]
LAYERS = ["shadow", "body", "spec", "fixed", "wheel_steel", "wheel_dia", "wheel_aero",
          "lamp", "roof_twotone", "roof_box", "decal_stripe", "plate_gold"]
LIMIT_KIB = 4096
SHADOW_FLOOR = 6



def navi_anchor():
    """navi 기준점(px)은 vehicles.json views.navi.origin_px를 그대로 쓴다(렌더 카메라와 앱 매니페스트가 어긋나지 않게)."""
    with open(os.path.join(HERE, "vehicles.json"), encoding="utf-8") as f:
        return json.load(f)["views"]["navi"]["origin_px"]


MANIFEST = {
    "version": 1,
    "views": {"hero": {"px": [576, 384], "dp": [288, 192], "groundY": 336},
              "navi": {"px": [192, 192], "dp": [96, 96], "anchor": navi_anchor()}},
    "order": ["shadow", "body", "spec", "fixed", "wheel", "lamp", "roof_twotone", "roof_box", "decal_stripe", "plate_gold"],
    "blend": {"body": "modulate", "spec": "screen", "lamp": "modulate", "roof_twotone": "modulate", "decal_stripe": "modulate"},
    "vehicles": {"compact": {"label": "소형", "naviScale": 1.0}, "suv": {"label": "중형", "naviScale": 1.0},
                 "large": {"label": "대형", "naviScale": 1.0}, "sedan": {"label": "세단", "naviScale": 1.0},
                 "scooter": {"label": "2륜", "naviScale": 1.25}},
    "layers": LAYERS,
    "provenance": "현대차 디자인 언어에서 영감을 받아 자체 제작한 스타일라이즈드 모델. 특정 차종·엠블럼·명칭을 재현하지 않음.",
    "generator": "tools/minicar/build_minicar.py",
    "blender": "4.5.9",
    "samples": 128,
}


def find_cwebp(explicit):
    for c in [explicit, shutil.which("cwebp"), "/opt/homebrew/bin/cwebp", "/usr/local/bin/cwebp"]:
        if c and os.path.exists(c):
            return c
    sys.exit("cwebp를 찾을 수 없습니다(brew install webp).")


def remap_shadow(src_png, dst_png):
    from PIL import Image
    im = Image.open(src_png).convert("RGBA")
    a = im.getchannel("A").point(lambda v: max(0, v - SHADOW_FLOOR) * 255 // (255 - SHADOW_FLOOR))
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))  # 그림자는 순수 검정
    out.putalpha(a)
    out.save(dst_png, optimize=False)


def encode(cwebp, src, dst, lossless):
    args = [cwebp, "-quiet", "-mt"]
    if lossless:
        args += ["-lossless", "-z", "9", "-exact"]
    else:
        args += ["-q", "85", "-alpha_q", "90", "-sharp_yuv", "-m", "6", "-alpha_filter", "best"]
    args += [src, "-o", dst]
    subprocess.run(args, check=True)


def du_kib(paths):
    """du -sk 근사: 파일마다 4 KiB 블록 올림 합."""
    return sum(((os.path.getsize(p) + 4095) // 4096) * 4 for p in paths)


def check(dst):
    files = []
    missing = []
    for v in VEHICLES:
        for view in VIEWS:
            for layer in LAYERS:
                p = os.path.join(dst, v, view, layer + ".webp")
                (files if os.path.exists(p) else missing).append(p)
    total = sum(os.path.getsize(p) for p in files)
    kib = du_kib(files)
    man = os.path.join(dst, "compose.json")
    ok_manifest = os.path.exists(man)
    if ok_manifest:
        with open(man, encoding="utf-8") as f:
            m = json.load(f)
        n_layers = len(m["vehicles"]) * len(m["views"]) * len(m["layers"])
    else:
        n_layers = 0
    print(f"files {len(files)}/120, missing {len(missing)}, bytes {total}, du≈{kib} KiB (limit {LIMIT_KIB}), manifest layers {n_layers}")
    for p in missing:
        print("MISSING", p)
    ok = not missing and kib <= LIMIT_KIB and ok_manifest and n_layers == 120
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src")
    ap.add_argument("--dst", default=os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "minicar"))
    ap.add_argument("--cwebp")
    ap.add_argument("--check", metavar="DIR")
    a = ap.parse_args()
    if a.check:
        sys.exit(0 if check(a.check) else 1)
    if not a.src:
        ap.error("--src 필요")
    cwebp = find_cwebp(a.cwebp)
    dst = os.path.abspath(a.dst)
    tmp = tempfile.mkdtemp(prefix="minicar_pp_")
    sizes = {}
    for v in VEHICLES:
        for view in VIEWS:
            os.makedirs(os.path.join(dst, v, view), exist_ok=True)
            for layer in LAYERS:
                src = os.path.join(a.src, v, view, layer + ".png")
                if not os.path.exists(src):
                    sys.exit(f"렌더 누락: {src}")
                if layer == "shadow":
                    fixed = os.path.join(tmp, f"{v}_{view}_shadow.png")
                    remap_shadow(src, fixed)
                    src = fixed
                out = os.path.join(dst, v, view, layer + ".webp")
                encode(cwebp, src, out, lossless=(view == "navi"))
                sizes[f"{v}/{view}/{layer}"] = os.path.getsize(out)
    shutil.rmtree(tmp, ignore_errors=True)
    with open(os.path.join(dst, "compose.json"), "w", encoding="utf-8") as f:
        json.dump(MANIFEST, f, ensure_ascii=False, indent=1)
        f.write("\n")
    big = sorted(sizes.items(), key=lambda kv: -kv[1])[:8]
    print("largest:", ", ".join(f"{k} {s // 1024}K" for k, s in big))
    sys.exit(0 if check(dst) else 1)


if __name__ == "__main__":
    main()
