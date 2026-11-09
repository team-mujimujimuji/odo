"""오도 미니카 레이어 렌더러 (Blender 4.5 headless, Cycles CPU).

실행:
  Blender -b -P tools/minicar/build_minicar.py -- --vehicle all --view all --layers all --out <dir> --samples 128

vehicles.json의 치수로 차종 5종을 bmesh로 절차 생성하고(외부 .blend 없음), 뷰 2개 × 레이어 12장을
<out>/<vehicle>/<view>/<layer>.png 로 쓴다. 레이어 합성 규칙(앱 compose.json의 order와 같다):
  그리기 순서가 앞인 레이어의 오브젝트는 is_holdout(가리기만 하고 투명), 뒤인 레이어의 오브젝트는 숨김.
  → 아래 레이어는 항상 완전한 면이고 위 레이어만 잘리므로 SrcOver 합성에 이음새가 없다.
shadow 레이어는 바닥 shadow catcher + 차량 전체 visible_camera=False 로 그림자만 뽑는다.
시드 7, OpenImageDenoise(CPU) → 같은 기기에서 다시 돌리면 바이트 동일.
"""
import argparse
import json
import math
import os
import sys
import time

import bpy
import bmesh
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
LAYERS = ["shadow", "body", "spec", "fixed", "wheel_steel", "wheel_dia", "wheel_aero",
          "lamp", "roof_twotone", "roof_box", "decal_stripe", "plate_gold"]
ORDER = ["shadow", "body", "spec", "fixed", "wheel", "lamp", "roof_twotone", "roof_box", "decal_stripe", "plate_gold"]
GROUPS = ["body", "fixed", "wheel_steel", "wheel_dia", "wheel_aero", "lamp", "roof_twotone", "roof_box",
          "decal_stripe", "plate_gold"]


# ---------- 인자 ----------
def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    p = argparse.ArgumentParser()
    p.add_argument("--vehicle", default="all")
    p.add_argument("--view", default="all")
    p.add_argument("--layers", default="all")
    p.add_argument("--out", required=True)
    p.add_argument("--samples", type=int, default=128)
    p.add_argument("--spec", default=os.path.join(HERE, "vehicles.json"))
    p.add_argument("--scale", type=float, default=1.0, help="해상도 배율(시험용)")
    p.add_argument("--stats", action="store_true", help="body 레이어 밝기 통계 출력(라이팅 보정용)")
    return p.parse_args(argv)


# ---------- 색·재질 ----------
def srgb_to_linear(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def hex_lin(h):
    h = h.lstrip("#")
    return tuple(srgb_to_linear(int(h[i:i + 2], 16) / 255.0) for i in (0, 2, 4))


def make_mat(name, hex_color="#FFFFFF", roughness=0.5, metallic=0.0, specular=0.5, coat=0.0,
             coat_roughness=0.03, emission=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    rgb = hex_lin(hex_color)
    b.inputs["Base Color"].default_value = (*rgb, 1.0)
    b.inputs["Roughness"].default_value = roughness
    b.inputs["Metallic"].default_value = metallic
    b.inputs["Specular IOR Level"].default_value = specular
    b.inputs["Coat Weight"].default_value = coat
    b.inputs["Coat Roughness"].default_value = coat_roughness
    if emission > 0:
        b.inputs["Emission Color"].default_value = (*rgb, 1.0)
        b.inputs["Emission Strength"].default_value = emission
    return m


def build_materials(spec):
    ms = spec["materials"]

    def m(name, **over):
        d = dict(ms[name])
        d.update(over)
        return make_mat(name, hex_color=d.get("hex", "#FFFFFF"), roughness=d.get("roughness", 0.5),
                        metallic=d.get("metallic", 0.0), specular=d.get("specular", 0.5),
                        coat=d.get("coat", 0.0), coat_roughness=d.get("coat_roughness", 0.03),
                        emission=d.get("emission", 0.0))

    M = {k: m(k) for k in ms}
    # body 레이어: 디퓨즈만(스펙큘러 0, 코트 0) → 앱에서 Modulate 틴트. spec 레이어: 검정 베이스 + 글로시/코트만.
    M["body_diffuse"] = make_mat("body_diffuse", "#FFFFFF", roughness=1.0, specular=0.0, coat=0.0)
    if "body_spec" not in M:
        bw = ms["body_white"]
        M["body_spec"] = make_mat("body_spec", "#000000", roughness=bw["roughness"], specular=0.5,
                                  coat=bw["coat"], coat_roughness=bw["coat_roughness"])
    M["floor"] = make_mat("floor", spec["lighting"].get("floor_gray_hex", "#4A4A4A"), roughness=0.9, specular=0.2)
    return M


# ---------- 지오메트리 ----------
def link(ob):
    bpy.context.scene.collection.objects.link(ob)
    return ob


def mark_sharp(bm, deg=30.0):
    """각도 기준으로 날카로운 모서리 표시(에셋 로딩 없는 결정적 auto-smooth 대체)."""
    bm.normal_update()
    th = math.radians(deg)
    for e in bm.edges:
        if len(e.link_faces) == 2:
            e.smooth = e.calc_face_angle(0.0) < th
        else:
            e.smooth = False


def bm_to_object(name, bm, deg=30.0):
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    mark_sharp(bm, deg)
    for f in bm.faces:
        f.smooth = True
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    me.update()
    return link(bpy.data.objects.new(name, me))


def finish(ob, mat, bevel=0.0, segs=3):
    if mat is not None:
        ob.data.materials.append(mat)
    if bevel > 0:
        b = ob.modifiers.new("bevel", "BEVEL")
        b.width = bevel
        b.segments = segs
        b.limit_method = "ANGLE"
        b.angle_limit = math.radians(40)
        b.miter_outer = "MITER_ARC"
        b.harden_normals = True
    return ob


def hull(name, verts, mat, bevel=0.0, segs=3):
    """8점 볼록 육면체: verts[0:4] 아래 사각형(반시계), verts[4:8] 위 사각형(같은 순서)."""
    faces = [(0, 1, 2, 3), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]
    bm = bmesh.new()
    vs = [bm.verts.new(Vector(v)) for v in verts]
    for f in faces:
        bm.faces.new([vs[i] for i in f])
    return finish(bm_to_object(name, bm), mat, bevel, segs)


def rect(x0, x1, y0, y1, z):
    return [(x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z)]


def box(name, x0, x1, y0, y1, z0, z1, mat, bevel=0.0, segs=3):
    return hull(name, rect(x0, x1, y0, y1, z0) + rect(x0, x1, y0, y1, z1), mat, bevel, segs)


def box_slant(name, x0, x1, y0, y1, z0, z1, mat, z1_front=None, bevel=0.0, segs=3):
    """윗면이 앞(+y)과 뒤(-y)에서 높이가 다른 상자(쐐기 후드 등)."""
    zf = z1 if z1_front is None else z1_front
    top = [(x0, y0, z1), (x1, y0, z1), (x1, y1, zf), (x0, y1, zf)]
    return hull(name, rect(x0, x1, y0, y1, z0) + top, mat, bevel, segs)


def cyl(name, center, r, depth, axis, mat, verts=64, bevel=0.0, segs=2):
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=verts, radius1=r, radius2=r, depth=depth)
    ob = bm_to_object(name, bm, deg=45.0)
    ob.location = Vector(center)
    if axis == "X":
        ob.rotation_euler = (0.0, math.pi / 2, 0.0)
    elif axis == "Y":
        ob.rotation_euler = (math.pi / 2, 0.0, 0.0)
    return finish(ob, mat, bevel, segs)


def rod(name, p0, p1, r, mat, verts=32):
    p0, p1 = Vector(p0), Vector(p1)
    d = p1 - p0
    ob = cyl(name, (p0 + p1) / 2, r, d.length, "Z", mat, verts=verts)
    ob.rotation_euler = d.to_track_quat("Z", "Y").to_euler()
    return ob


# ---------- 부품 빌더 ----------
class Parts:
    def __init__(self):
        self.g = {k: [] for k in GROUPS}

    def add(self, group, *obs):
        for o in obs:
            self.g[group].append(o)
        return obs[0] if len(obs) == 1 else obs


def lamp_row(P, M, prefix, n, y_face, z, row_w, cell_w, cell_h, outward):
    """균일 셀 열. outward=+1 앞면(+y), -1 뒷면(-y). 하우징은 fixed, 셀은 lamp."""
    s = outward
    hw = row_w / 2 + 0.06
    y0, y1 = sorted((y_face - 0.03 * s, y_face + 0.012 * s))
    P.add("fixed", box(f"{prefix}_housing", -hw, hw, y0, y1, z - cell_h / 2 - 0.045, z + cell_h / 2 + 0.045,
                       M["lamp_housing"], bevel=0.012))
    pitch = (row_w - cell_w) / (n - 1) if n > 1 else 0
    x = -row_w / 2 + cell_w / 2
    for i in range(n):
        cx = x + i * pitch if n > 1 else 0.0
        ya, yb = sorted((y_face + 0.012 * s, y_face + 0.030 * s))
        P.add("lamp", box(f"{prefix}_cell{i}", cx - cell_w / 2, cx + cell_w / 2, ya, yb, z - cell_h / 2, z + cell_h / 2,
                          M["lamp"], bevel=0.006, segs=1))


def plate(P, M, prefix, y_face, z, outward, half_w=0.21, h=0.11):
    s = outward
    y0, y1 = sorted((y_face - 0.005 * s, y_face + 0.022 * s))
    P.add("plate_gold", box(f"{prefix}_plate", -half_w, half_w, y0, y1, z - h / 2, z + h / 2, M["plate_gold"], bevel=0.008, segs=2))
    yc = y_face + 0.022 * s + 0.006 * s
    for i, dx in enumerate((-0.09 * half_w / 0.21, 0.0, 0.09 * half_w / 0.21)):
        P.add("plate_gold", cyl(f"{prefix}_dot{i}", (dx, yc, z), 0.10 * h, 0.012, "Y", M["plate_gold"], verts=24))


def wheel_set(P, M, name, center, R, tw, side, scale=1.0, tire=True):
    """타이어·허브 베이스(fixed) + 휠 디자인 3종(각 레이어). side=-1 왼쪽(바깥쪽이 -x)."""
    cx, cy, cz = center
    if tire:
        P.add("fixed", cyl(f"{name}_tire", (cx, cy, cz), R, tw, "X", M["tire"], verts=72, bevel=0.035, segs=3))
    face_x = cx + side * (tw / 2)
    P.add("fixed", cyl(f"{name}_hubbase", (face_x + side * 0.006, cy, cz), 0.64 * R, 0.014, "X", M["hub_base"], verts=48))
    fx = face_x + side * 0.02
    # steel: 넓은 접시 + 작은 센터캡
    P.add("wheel_steel", cyl(f"{name}_steel_disc", (fx, cy, cz), 0.56 * R, 0.016, "X", M["hub"], verts=48, bevel=0.01, segs=2))
    P.add("wheel_steel", cyl(f"{name}_steel_cap", (fx + side * 0.012, cy, cz), 0.14 * R, 0.012, "X", M["hub"], verts=32))
    # dia: 5스포크 + 밝은 림
    P.add("wheel_dia", cyl(f"{name}_dia_hub", (fx, cy, cz), 0.20 * R, 0.02, "X", M["hub_bright"], verts=32))
    rim = cyl(f"{name}_dia_rim", (fx, cy, cz), 0.58 * R, 0.012, "X", M["hub_bright"], verts=48)
    P.add("wheel_dia", rim)
    inner = cyl(f"{name}_dia_rimin", (fx + side * 0.004, cy, cz), 0.50 * R, 0.012, "X", M["hub_base"], verts=48)
    P.add("wheel_dia", inner)
    for k in range(5):
        a = k * 2 * math.pi / 5
        sp = box(f"{name}_dia_spoke{k}", -0.012, 0.012, -0.11 * R * scale, 0.11 * R * scale, 0.0, 0.56 * R, M["hub_bright"], bevel=0.004, segs=1)
        sp.location = (fx + side * 0.010, cy, cz)
        sp.rotation_euler = (a, 0.0, 0.0)
        P.add("wheel_dia", sp)
    # aero: 평평한 어두운 디스크 + 6개 슬롯
    P.add("wheel_aero", cyl(f"{name}_aero_disc", (fx, cy, cz), 0.60 * R, 0.016, "X", M["hub_dark"], verts=48, bevel=0.012, segs=2))
    P.add("wheel_aero", cyl(f"{name}_aero_ring", (fx + side * 0.010, cy, cz), 0.22 * R, 0.01, "X", M["hub_bright"], verts=32))
    for k in range(6):
        a = k * 2 * math.pi / 6 + math.pi / 6
        sl = box(f"{name}_aero_slot{k}", -0.006, 0.006, -0.05 * R, 0.05 * R, 0.30 * R, 0.52 * R, M["hub_base"], bevel=0.003, segs=1)
        sl.location = (fx + side * 0.012, cy, cz)
        sl.rotation_euler = (a, 0.0, 0.0)
        P.add("wheel_aero", sl)


def build_car(v, M, P):
    L, W, H = v["dims"]
    hw = W / 2
    R, WB, tw = v["wheel_r"], v["wheelbase"], v["tire_w"]
    gc = v["ground_clearance"]
    belt = v["belt_z"]
    zb_f, zb_r = (belt, belt) if isinstance(belt, (int, float)) else (belt[0], belt[1])
    yF, yR = L / 2, -L / 2
    bev = v["bevel"]
    style = v["style"]

    def zb_at(y):  # 벨트라인 높이(쐐기면 보간)
        t = (y - yR) / (yF - yR)
        return zb_r + (zb_f - zb_r) * t

    # 하부 바디(바닥 살짝 좁힘)
    ins = 0.04
    P.add("body", hull("lower_body",
                       rect(-(hw - ins), hw - ins, yR + ins, yF - ins, gc) +
                       [(-hw, yR, zb_r), (hw, yR, zb_r), (hw, yF, zb_f), (-hw, yF, zb_f)],
                       M["body_white"], bevel=bev, segs=4))

    # 글라스 하우스
    gi, tum = v["glass_inset"], v["tumblehome"]
    yg0, yg1 = v["cabin_rear_y"], v["cabin_front_y"]
    yr0, yr1 = yg0 + v["rear_rake"], yg1 - v["windshield_rake"]
    roof_t = 0.05
    xg, xr = hw - gi, hw - gi - tum
    zr = H - roof_t
    P.add("fixed", hull("glass",
                        [(-xg, yg0, zb_at(yg0) - 0.02), (xg, yg0, zb_at(yg0) - 0.02), (xg, yg1, zb_at(yg1) - 0.02), (-xg, yg1, zb_at(yg1) - 0.02)] +
                        [(-xr, yr0, zr), (xr, yr0, zr), (xr, yr1, zr), (-xr, yr1, zr)],
                        M["glass"], bevel=0.025, segs=3))
    # 루프 슬래브(바디색)
    P.add("body", hull("roof",
                       rect(-(xr + 0.02), xr + 0.02, yr0 - 0.02, yr1 + 0.02, H - roof_t - 0.012) +
                       rect(-(xr - 0.01), xr - 0.01, yr0 + 0.01, yr1 - 0.01, H),
                       M["body_white"], bevel=0.03, segs=3))
    # A/C 필러(바디색, 글라스보다 12 mm 돌출)
    aw, cw = v["a_pillar_w"], v["c_pillar_w"]
    for s in (-1, 1):
        def px(x):  # 좌우 대칭
            return s * x
        P.add("body", hull(f"a_pillar_{'l' if s < 0 else 'r'}",
                           [(px(xg - aw), yg1 - aw - 0.02, zb_at(yg1) - 0.01), (px(xg + 0.012), yg1 - aw - 0.02, zb_at(yg1) - 0.01),
                            (px(xg + 0.012), yg1 + 0.012, zb_at(yg1) - 0.01), (px(xg - aw), yg1 + 0.012, zb_at(yg1) - 0.01)] +
                           [(px(xr - aw), yr1 - aw - 0.02, zr - 0.005), (px(xr + 0.012), yr1 - aw - 0.02, zr - 0.005),
                            (px(xr + 0.012), yr1 + 0.012, zr - 0.005), (px(xr - aw), yr1 + 0.012, zr - 0.005)],
                           M["body_white"], bevel=0.012, segs=2))
        P.add("body", hull(f"c_pillar_{'l' if s < 0 else 'r'}",
                           [(px(xg - cw), yg0 - 0.012, zb_at(yg0) - 0.01), (px(xg + 0.012), yg0 - 0.012, zb_at(yg0) - 0.01),
                            (px(xg + 0.012), yg0 + cw, zb_at(yg0) - 0.01), (px(xg - cw), yg0 + cw, zb_at(yg0) - 0.01)] +
                           [(px(xr - cw), yr0 - 0.012, zr - 0.005), (px(xr + 0.012), yr0 - 0.012, zr - 0.005),
                            (px(xr + 0.012), yr0 + cw, zr - 0.005), (px(xr - cw), yr0 + cw, zr - 0.005)],
                           M["body_white"], bevel=0.012, segs=2))
    # 캐릭터 라인(세단 1개, 어깨)
    if v.get("character_line"):
        zc = zb_r - 0.20
        for s in (-1, 1):
            x0, x1 = sorted((s * (hw - 0.004), s * (hw + 0.014)))
            P.add("body", box_slant(f"charline_{'l' if s < 0 else 'r'}", x0, x1, yR + 0.30, yF - 0.55, zc - 0.03, zc + 0.02,
                                    M["body_white"], z1_front=zc - 0.06, bevel=0.01, segs=2))

    # 하부 클래딩 띠(fixed)
    band = v.get("cladding_band", 0.0)
    if band > 0:
        if style == "mpv":
            for yc, s in ((yF, 1), (yR, -1)):
                y0, y1 = sorted((yc - 0.06 * s, yc + 0.012 * s))
                P.add("fixed", box(f"bumper_{'f' if s > 0 else 'r'}", -(hw - 0.10), hw - 0.10, y0, y1, gc - 0.005, gc + band, M["cladding"], bevel=0.02))
        else:
            P.add("fixed", hull("cladding_band",
                                rect(-(hw - ins + 0.012), hw - ins + 0.012, yR + ins - 0.012, yF - ins + 0.012, gc - 0.006) +
                                rect(-(hw + 0.012), hw + 0.012, yR - 0.012, yF + 0.012, gc + band),
                                M["cladding"], bevel=min(0.03, band / 2), segs=2))

    # 휠아치 패치(fixed) + 바퀴
    arch = v.get("arch_style", "round")
    for wy in (WB / 2, -WB / 2):
        for s in (-1, 1):
            tag = f"{'f' if wy > 0 else 'r'}{'l' if s < 0 else 'r'}"
            if arch != "none":
                hl = R + (0.12 if arch == "square" else 0.08 if arch == "round" else 0.05)
                top = R + (0.16 if arch == "square" else 0.12 if arch == "round" else 0.07)
                proud = 0.03 if arch == "square" else 0.02
                x0, x1 = sorted((s * (hw - 0.02), s * (hw + proud)))
                P.add("fixed", box(f"arch_{tag}", x0, x1, wy - hl, wy + hl, gc - 0.01, top, M["cladding"],
                                   bevel=0.02 if arch == "square" else 0.09 if arch == "round" else 0.05, segs=3))
            cx = s * (hw + 0.025 - tw / 2)
            wheel_set(P, M, f"wheel_{tag}", (cx, wy, R), R, tw, s)

    # 램프 열
    lp = v["lamp"]
    row_w = (W - 0.26) if lp.get("full_width") else lp["row_w"]
    lamp_row(P, M, "lamp_f", lp["front_cells"], yF, zb_f - lp["z_below_belt"], row_w, lp["cell_w"], lp["cell_h"], +1)
    rear_w = (W - 0.30) if lp.get("full_width") else lp["row_w"] * 0.9
    lamp_row(P, M, "lamp_r", lp["rear_cells"], yR, zb_r - lp["z_below_belt"] - 0.02, rear_w, lp["cell_w"], lp["cell_h"], -1)

    # 번호판(황금, 선택)
    plate(P, M, "plate_f", yF, gc + 0.26, +1)
    plate(P, M, "plate_r", yR, gc + 0.28, -1)

    # 루프레일(fixed)
    if v.get("roof_rails"):
        for s in (-1, 1):
            x0, x1 = sorted((s * (xr - 0.14), s * (xr - 0.06)))
            P.add("fixed", box(f"rail_{'l' if s < 0 else 'r'}", x0, x1, yr0 + 0.10, yr1 - 0.10, H - 0.005, H + 0.07, M["cladding"], bevel=0.02, segs=2))

    # 투톤 루프 오버레이
    P.add("roof_twotone", hull("roof_twotone",
                               rect(-(xr - 0.025), xr - 0.025, yr0 + 0.035, yr1 - 0.035, H + 0.002) +
                               rect(-(xr - 0.03), xr - 0.03, yr0 + 0.04, yr1 - 0.04, H + 0.012),
                               M["overlay_white"], bevel=0.004, segs=1))
    # 루프박스
    roof_len = yr1 - yr0
    bl = min(1.5, roof_len - 0.40)
    yc = (yr0 + yr1) / 2
    P.add("roof_box", hull("roof_box",
                           rect(-0.36, 0.36, yc - bl / 2, yc + bl / 2, H + 0.01) +
                           rect(-0.33, 0.33, yc - bl / 2 + 0.04, yc + bl / 2 - 0.06, H + 0.34),
                           M["roof_box"], bevel=0.05, segs=3))
    # 데칼 스트라이프(좌측 오프셋; 후드 → 루프 → 데크, 글라스 위는 건너뜀)
    sx0, sx1 = -(xr - 0.08), -(xr - 0.22)
    sx0, sx1 = min(sx0, sx1), max(sx0, sx1)
    hood_y0, hood_y1 = yg1 + 0.04, yF - 0.08
    P.add("decal_stripe", box_slant("decal_hood", sx0, sx1, hood_y0, hood_y1, zb_at(hood_y0) + 0.0, zb_at(hood_y0) + 0.006,
                                    M["overlay_white"], z1_front=zb_at(hood_y1) + 0.006, bevel=0.002, segs=1))
    P.add("decal_stripe", box("decal_roof", sx0, sx1, yr0 + 0.05, yr1 - 0.05, H + 0.013, H + 0.019, M["overlay_white"], bevel=0.002, segs=1))
    if (yg0 - yR) > 0.30:
        P.add("decal_stripe", box_slant("decal_deck", sx0, sx1, yR + 0.10, yg0 - 0.04, zb_at(yR) + 0.0, zb_at(yR) + 0.006,
                                        M["overlay_white"], z1_front=zb_at(yg0) + 0.006, bevel=0.002, segs=1))


def build_scooter(v, M, P):
    L, W, H = v["dims"]
    R, WB, tw = v["wheel_r"], v["wheelbase"], v["tire_w"]
    yF, yR = L / 2, -L / 2
    # 바퀴 2개(x=0)
    for wy, tag in ((WB / 2, "f"), (-WB / 2, "r")):
        wheel_set(P, M, f"wheel_{tag}", (0.0, wy, R), R, tw, -1, scale=0.9)
        wheel_set(P, M, f"wheelr_{tag}", (0.0, wy, R), R, tw, +1, scale=0.9, tire=False)
    # 발판(fixed, 고무) + 하부 스파인(바디)
    P.add("fixed", box("floor", -0.22, 0.22, -0.20, 0.42, 0.26, 0.31, M["cladding"], bevel=0.02))
    P.add("body", box("spine", -0.15, 0.15, -0.30, 0.50, 0.15, 0.27, M["body_white"], bevel=0.03))
    # 레그실드(바디, 약간 뒤로 기운 판)
    P.add("body", hull("legshield",
                       rect(-0.24, 0.24, 0.42, 0.55, 0.26) + rect(-0.22, 0.22, 0.49, 0.61, 0.88),
                       M["body_white"], bevel=0.04, segs=3))
    # 앞 펜더(바디)
    P.add("body", box("front_fender", -0.13, 0.13, 0.43, yF - 0.02, 0.60, 0.72, M["body_white"], bevel=0.04))
    # 뒷 바디(바디) + 시트(fixed 무광)
    P.add("body", hull("rear_body",
                       rect(-0.20, 0.20, yR + 0.015, -0.20, 0.30) + rect(-0.21, 0.21, yR + 0.06, -0.18, 0.68),
                       M["body_white"], bevel=0.06, segs=3))
    P.add("fixed", hull("seat", rect(-0.20, 0.20, -0.56, -0.12, 0.68) + rect(-0.17, 0.17, -0.52, -0.16, 0.82),
                        M["seat"], bevel=0.05, segs=3))
    # 스템·핸들바·그립·포크(fixed 어두운)
    P.add("fixed", rod("stem", (0.0, 0.57, 0.84), (0.0, 0.52, 1.04), 0.032, M["cladding"]))
    P.add("fixed", cyl("handlebar", (0.0, 0.52, 1.06), 0.028, 0.64, "X", M["cladding"], verts=32))
    for s in (-1, 1):
        P.add("fixed", cyl(f"grip_{'l' if s < 0 else 'r'}", (s * 0.28, 0.52, 1.06), 0.038, 0.14, "X", M["cladding"], verts=32, bevel=0.008))
    P.add("fixed", rod("fork", (0.0, WB / 2, R), (0.0, 0.57, 0.66), 0.03, M["cladding"]))
    # 앞 둥근 램프 1셀(하우징 fixed) + 뒤 3셀
    P.add("fixed", cyl("lamp_f_housing", (0.0, 0.618, 0.72), 0.13, 0.03, "Y", M["lamp_housing"], verts=48, bevel=0.006))
    P.add("lamp", cyl("lamp_f_cell", (0.0, 0.637, 0.72), 0.10, 0.022, "Y", M["lamp"], verts=48))
    lp = v["lamp"]
    lamp_row(P, M, "lamp_r", lp["rear_cells"], yR + 0.015, 0.56, lp["row_w"], lp["cell_w"], lp["cell_h"], -1)
    # 황금 번호판(앞 레그실드 아래 + 뒤)
    plate(P, M, "plate_f", 0.59, 0.42, +1, half_w=0.10, h=0.08)
    plate(P, M, "plate_r", yR + 0.015, 0.44, -1, half_w=0.11, h=0.09)
    # 레그실드 투톤 오버레이(앞뒤 양면을 감싸는 얇은 껍질)
    P.add("roof_twotone", hull("legshield_twotone",
                               rect(-0.245, 0.245, 0.415, 0.555, 0.30) + rect(-0.225, 0.225, 0.485, 0.615, 0.886),
                               M["overlay_white"], bevel=0.04, segs=3))
    # 탑케이스
    P.add("roof_box", hull("top_case", rect(-0.21, 0.21, -0.93, -0.57, 0.70) + rect(-0.19, 0.19, -0.90, -0.60, 1.02),
                           M["roof_box"], bevel=0.05, segs=3))
    # 데칼: 레그실드 앞면 세로 + 앞 펜더 윗면
    P.add("decal_stripe", hull("decal_shield", rect(-0.16, -0.04, 0.561, 0.567, 0.36) + rect(-0.15, -0.03, 0.611, 0.617, 0.80),
                               M["overlay_white"], bevel=0.002, segs=1))
    P.add("decal_stripe", box("decal_fender", -0.10, 0.02, 0.47, yF - 0.06, 0.721, 0.727, M["overlay_white"], bevel=0.002, segs=1))


# ---------- 장면 ----------
def dir_from(az_deg, elev_deg):
    az, el = math.radians(az_deg), math.radians(elev_deg)
    return Vector((-math.cos(el) * math.sin(az), math.cos(el) * math.cos(az), math.sin(el)))


def add_area(name, cfg, energy_scale=1.0):
    d = dir_from(cfg["azimuth_deg"], cfg["elev_deg"])
    data = bpy.data.lights.new(name, "AREA")
    data.shape = "SQUARE"
    data.size = cfg["size_m"]
    data.energy = cfg["energy_w"] * energy_scale
    ob = link(bpy.data.objects.new(name, data))
    ob.location = d * cfg["distance_m"]
    ob.rotation_euler = (-d).to_track_quat("-Z", "Y").to_euler()
    return ob


def setup_scene(spec, samples, scale):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    cy = sc.cycles
    cy.device = "CPU"
    cy.samples = samples
    cy.use_adaptive_sampling = True
    cy.adaptive_threshold = 0.01
    cy.adaptive_min_samples = max(8, samples // 8)
    cy.seed = spec.get("seed", 7)
    cy.use_animated_seed = False
    cy.use_denoising = True
    cy.denoiser = "OPENIMAGEDENOISE"
    cy.denoising_input_passes = "RGB_ALBEDO_NORMAL"
    cy.denoising_prefilter = "ACCURATE"
    try:
        cy.denoising_use_gpu = False
    except Exception:
        pass
    cy.max_bounces = 6
    cy.diffuse_bounces = 3
    cy.glossy_bounces = 3
    cy.transmission_bounces = 2
    cy.caustics_reflective = False
    cy.caustics_refractive = False
    cy.pixel_filter_type = "BLACKMAN_HARRIS"
    cy.filter_width = 1.5
    sc.render.film_transparent = True
    # 영속 데이터(persistent_data)는 레이어 간 가시성·재질 전환이 간헐적으로 반영되지 않아(한 레이어가 미조명으로 렌더) 끈다.
    sc.render.use_persistent_data = False
    sc.render.resolution_percentage = 100
    sc.render.image_settings.file_format = "PNG"
    sc.render.image_settings.color_mode = "RGBA"
    sc.render.image_settings.color_depth = "8"
    sc.render.image_settings.compression = 50
    sc.render.dither_intensity = 0.0
    # PNG 메타데이터(날짜·시간·렌더 시간 등) 비활성화 → 같은 입력이면 바이트 동일 출력
    rd = sc.render
    for flag in ("use_stamp_date", "use_stamp_time", "use_stamp_render_time", "use_stamp_frame",
                 "use_stamp_frame_range", "use_stamp_memory", "use_stamp_hostname", "use_stamp_camera",
                 "use_stamp_lens", "use_stamp_scene", "use_stamp_marker", "use_stamp_filename",
                 "use_stamp_sequencer_strip", "use_stamp_note", "use_stamp"):
        if hasattr(rd, flag):
            setattr(rd, flag, False)
    sc.view_settings.view_transform = "Standard"
    sc.view_settings.look = "None"
    sc.view_settings.exposure = 0.0
    sc.view_settings.gamma = 1.0
    sc.display_settings.display_device = "sRGB"
    # 월드: 균일 그레이
    w = bpy.data.worlds.new("world")
    sc.world = w
    w.use_nodes = True
    bg = w.node_tree.nodes["Background"]
    bg.inputs["Color"].default_value = (1.0, 1.0, 1.0, 1.0)
    bg.inputs["Strength"].default_value = spec["lighting"]["world_gray"]
    # 라이트
    lights = [add_area("key", spec["lighting"]["key"]), add_area("fill", spec["lighting"]["fill"])]
    return sc, lights


def make_floor(M):
    me = bpy.data.meshes.new("floor")
    me.from_pydata([Vector((-40, -40, 0)), Vector((40, -40, 0)), Vector((40, 40, 0)), Vector((-40, 40, 0))], [], [(0, 1, 2, 3)])
    ob = link(bpy.data.objects.new("floor", me))
    ob.data.materials.append(M["floor"])
    return ob


def make_camera(view, zoom):
    sc = bpy.context.scene
    cam_data = bpy.data.cameras.new("cam")
    cam_data.lens = view["focal_mm"]
    cam_data.sensor_width = 36.0
    cam_data.sensor_fit = "HORIZONTAL" if view["px"][0] >= view["px"][1] else "VERTICAL"
    cam_data.clip_end = 200
    cam = link(bpy.data.objects.new("cam", cam_data))
    d = dir_from(view["azimuth_deg"], view["elev_deg"])
    cam.location = d * (view["distance_m"] / zoom)
    cam.rotation_euler = (-d).to_track_quat("-Z", "Y").to_euler()
    sc.camera = cam
    return cam


def aim_origin(cam, view):
    """shift로 바닥 원점(0,0,0)을 origin_px(기준 해상도 px)에 놓는다. 해상도 배율과 무관(정규화 좌표)."""
    from bpy_extras.object_utils import world_to_camera_view as w2c
    sc = bpy.context.scene
    cam.data.shift_x = 0.0
    cam.data.shift_y = 0.0
    bpy.context.view_layer.update()
    u0, v0, _ = w2c(sc, cam, Vector((0, 0, 0)))
    W, Hh = view["px"]
    ut, vt = view["origin_px"][0] / W, 1.0 - view["origin_px"][1] / Hh
    big = max(W, Hh)
    cam.data.shift_x = (u0 - ut) * (W / big)
    cam.data.shift_y = (v0 - vt) * (Hh / big)
    bpy.context.view_layer.update()
    return project(cam, (0, 0, 0), view)


def project(cam, p, view):
    """기준 해상도(view.px) 픽셀 좌표로 투영."""
    from bpy_extras.object_utils import world_to_camera_view as w2c
    u, v, _ = w2c(bpy.context.scene, cam, Vector(p))
    return (round(u * view["px"][0], 1), round((1 - v) * view["px"][1], 1))


def rank(name):
    key = "wheel" if name.startswith("wheel") else ("body" if name == "spec" else name)
    return ORDER.index(key)


def configure(layer, P, floor, M, body_objs):
    """레이어별 가시성. 앞 순서 그룹 = holdout, 뒤 순서 그룹 = 숨김."""
    for g, objs in P.g.items():
        if layer == "shadow":
            show = g in ("body", "fixed", "wheel_steel")
            for o in objs:
                o.hide_render = not show
                o.is_holdout = False
                o.visible_camera = False
            continue
        if g == layer or (layer == "spec" and g == "body"):
            mode = "show"
        elif g.startswith("wheel"):
            if layer.startswith("wheel"):
                mode = "hide"
            else:
                mode = "holdout" if (g == "wheel_steel" and rank("wheel") < rank(layer)) else "hide"
        elif rank(g) < rank(layer):
            mode = "holdout"
        else:
            mode = "hide"
        for o in objs:
            o.visible_camera = True
            o.hide_render = mode == "hide"
            o.is_holdout = mode == "holdout"
    floor.hide_render = False
    floor.is_shadow_catcher = layer == "shadow"
    floor.visible_camera = layer == "shadow"
    mat = M["body_spec"] if layer == "spec" else M["body_white"] if layer == "shadow" else M["body_diffuse"]
    for o in body_objs:
        o.data.materials[0] = mat


def image_stats(path):
    import numpy as np
    img = bpy.data.images.load(path)
    img.colorspace_settings.name = "Non-Color"
    n = img.size[0] * img.size[1]
    buf = np.empty(n * 4, dtype=np.float32)
    img.pixels.foreach_get(buf)
    px = buf.reshape(-1, 4)
    bpy.data.images.remove(img)
    m = px[:, 3] > 0.5
    if not m.any():
        return "empty"
    lum = px[m, :3].max(axis=1)
    return "cover=%.3f p50=%.3f p90=%.3f p99=%.3f max=%.3f" % (m.mean(), *np.percentile(lum, [50, 90, 99]), lum.max())


def main():
    args = parse_args()
    with open(args.spec, "r", encoding="utf-8") as f:
        spec = json.load(f)
    vehicles = list(spec["vehicles"].keys()) if args.vehicle == "all" else args.vehicle.split(",")
    views = list(spec["views"].keys()) if args.view == "all" else args.view.split(",")
    layers = LAYERS if args.layers == "all" else args.layers.split(",")
    for l in layers:
        assert l in LAYERS, l
    os.makedirs(args.out, exist_ok=True)
    meta = {"blender": bpy.app.version_string, "samples": args.samples, "seed": spec.get("seed", 7), "vehicles": {}}
    t_all = time.time()
    for vid in vehicles:
        v = spec["vehicles"][vid]
        sc, lights = setup_scene(spec, args.samples, args.scale)
        M = build_materials(spec)
        floor = make_floor(M)
        P = Parts()
        (build_scooter if v["style"] == "scooter" else build_car)(v, M, P)
        body_objs = list(P.g["body"])
        meta["vehicles"][vid] = {"views": {}}
        for view_name in views:
            view = spec["views"][view_name]
            res = (int(round(view["px"][0] * args.scale)), int(round(view["px"][1] * args.scale)))
            sc = bpy.context.scene
            sc.render.resolution_x, sc.render.resolution_y = res
            zoom = v.get("hero_zoom", 1.0) if view_name == "hero" else 1.0
            cam = make_camera(view, zoom)
            origin = aim_origin(cam, view)
            L, W, H = v["dims"]
            WB, R = v["wheelbase"], v["wheel_r"]
            info = {
                "origin_px": [round(origin[0], 1), round(origin[1], 1)],
                "shift": [round(cam.data.shift_x, 5), round(cam.data.shift_y, 5)],
                "near_wheel_ground_px": project(cam, (-(W / 2 + 0.025), WB / 2, 0.0), view),
                "nose_ground_px": project(cam, (0.0, L / 2, 0.0), view),
                "tail_ground_px": project(cam, (0.0, -L / 2, 0.0), view),
                "roof_px": project(cam, (0.0, 0.0, H), view),
            }
            meta["vehicles"][vid]["views"][view_name] = info
            print(f"[{vid}/{view_name}] {json.dumps(info)}")
            outdir = os.path.join(args.out, vid, view_name)
            os.makedirs(outdir, exist_ok=True)
            for layer in layers:
                configure(layer, P, floor, M, body_objs)
                # shadow_lights == "world": 그림자 패스에서 라이트를 끄고 월드 가림(접촉 그림자)만 남긴다(내비 마커용)
                for lt in lights:
                    lt.hide_render = layer == "shadow" and view.get("shadow_lights", "all") == "world"
                path = os.path.join(outdir, layer + ".png")
                sc.render.filepath = path
                bpy.context.view_layer.update()  # 가시성·홀드아웃·재질 변경을 렌더 전에 확정
                t0 = time.time()
                bpy.ops.render.render(write_still=True)
                dt = time.time() - t0
                extra = ""
                if args.stats and layer in ("body", "spec", "fixed", "lamp", "shadow"):
                    extra = " " + image_stats(path)
                print(f"[{vid}/{view_name}/{layer}] {dt:.1f}s{extra}", flush=True)
            bpy.data.objects.remove(cam, do_unlink=True)
    with open(os.path.join(args.out, "render_meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1, sort_keys=True)
    print(f"total {time.time() - t_all:.1f}s for {len(vehicles)}x{len(views)}x{len(layers)} images")


if __name__ == "__main__":
    main()
