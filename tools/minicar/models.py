"""오도 미니카 형상·재질 (Blender 4.5 headless, bmesh + numpy). 외부 .blend·이미지·에셋 없음.

build_minicar.py(장면·카메라·조명·레이어 패스)가 import 해서 쓴다:

    M = build_materials(spec)                           # spec = vehicles.json 전체
    P = build_vehicle(name, spec["vehicles"][name], M)  # Parts
    P.g[group]      -> [bpy 객체]   (group 은 GROUPS 의 이름)
    P.marks["head"] -> [전조등 렌즈 객체]  (차는 좌·우 2개, 2륜은 1개. 전부 lamp 그룹에도 들어 있다)
    P.marks["tail"] -> [붉은 후미등 렌즈 객체]  (전부 fixed 그룹에도 들어 있다. 발광 재질 tail_lamp)
    P.info          -> 치수·면 수·생성 시간(참고용)

규칙
  - +y 가 차 앞, +x 가 차 오른쪽, 바닥 z=0, 차체 바운딩 박스의 x·y 중앙이 원점, 치수 m.
  - body 그룹 객체는 재질 슬롯이 정확히 1개다(패스마다 materials[0] 을 바꿔 끼운다).
  - 같은 입력이면 같은 정점을 같은 순서로 만든다. 난수·시간·집합 순회 순서에 기대지 않는다.
  - 로고·엠블럼·글자·그릴 무늬 없음. 일반적인 해치백/SUV/미니밴/패스트백/스쿠터 비례이고 특정 차를 본뜨지 않았다.

만드는 법 (차 4종)
  1. 스테이션 표(vehicles.json 의 차종별 "shape")로 링 15점짜리 로프트 케이지를 만든다.
  2. Subdivision Surface(crease 포함)로 세분화하고 dims(L, W, H)에 맞춘 뒤 펜더 플레어를 변위로 준다.
  3. 유리·클래딩·투톤 루프·데칼·램프·번호판은 세분화된 차체 면을 복제해 법선 방향으로 살짝 띄운 겉껍질이다.
     영역은 케이지 면 태그(구역×띠)와 평면 절단으로 정한다. 차체는 그 아래에 온전히 남는다.
  4. 휠 아치는 차체 겉면을 아치 다각형 기둥으로 잘라 내고, 구멍 경계를 안쪽으로 밀어 어두운 휠하우스
     라이너를 만든다. 타이어는 아치 안에 들어간다.
"""
import sys

sys.dont_write_bytecode = True

import math
import time

import bpy
import bmesh
import numpy as np
from mathutils import Vector

GROUPS = ["body", "fixed", "wheel_steel", "wheel_dia", "wheel_aero", "lamp", "roof_twotone", "roof_box",
          "decal_stripe", "plate_gold"]


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


# vehicles.json 에 없을 때 쓰는 기본값(새로 더한 재질). JSON 값이 있으면 그쪽이 이긴다.
EXTRA_MATERIALS = {
    "liner": {"hex": "#0E1013", "roughness": 0.95, "specular": 0.1},
    "tail_lamp": {"hex": "#FF2B1C", "roughness": 0.25, "emission": 1.6},
    "tail_housing": {"hex": "#6A1218", "roughness": 0.12, "specular": 0.6, "coat": 0.6, "coat_roughness": 0.05},
    "lamp_lens": {"hex": "#FFFFFF", "roughness": 0.15, "emission": 0.55},
    "lamp_proj": {"hex": "#8E98A4", "metallic": 0.8, "roughness": 0.16},  # 투사 렌즈(은색 반사경)
    "plate_blank": {"hex": "#E6E3DB", "roughness": 0.45},
    "mirror": {"hex": "#B4BCC6", "metallic": 1.0, "roughness": 0.06},
}


def build_materials(spec):
    """재질 dict. 키 = vehicles.json materials 의 키 + EXTRA_MATERIALS + body_diffuse, body_spec, floor."""
    ms = dict(EXTRA_MATERIALS)
    ms.update(spec["materials"])

    def m(name):
        d = ms[name]
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
    M["floor"] = make_mat("floor", spec.get("lighting", {}).get("floor_gray_hex", "#4A4A4A"), roughness=0.9, specular=0.2)
    return M


# ---------- 부품 묶음 ----------
class Parts:
    def __init__(self):
        self.g = {k: [] for k in GROUPS}
        self.marks = {"head": [], "tail": []}
        self.info = {}

    def add(self, group, *obs):
        for o in obs:
            self.g[group].append(o)
        return obs[0] if len(obs) == 1 else obs

    def objects(self):
        return [o for k in GROUPS for o in self.g[k]]

    def mark_centers(self, kind):
        """marks[kind] 객체들의 바운딩 박스 중심(월드 좌표) 목록."""
        out = []
        for o in self.marks[kind]:
            c = sum((o.matrix_world @ Vector(b) for b in o.bound_box), Vector()) / 8.0
            out.append((c.x, c.y, c.z))
        return out


# ---------- 기본 도구 ----------
def _link(ob):
    bpy.context.scene.collection.objects.link(ob)
    return ob


def _obj(name, bm, mats, sharp_deg=35.0, recalc=True):
    """bmesh → 객체. 면은 전부 smooth, 모서리는 각도 기준으로 sharp(sharp_deg=None 이면 경계만)."""
    if recalc:
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
    bm.normal_update()
    th = None if sharp_deg is None else math.radians(sharp_deg)
    for e in bm.edges:
        if len(e.link_faces) == 2:
            e.smooth = True if th is None else e.calc_face_angle(0.0) < th
        else:
            e.smooth = False
    for f in bm.faces:
        f.smooth = True
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    for m in mats:
        me.materials.append(m)
    me.update()
    return _link(bpy.data.objects.new(name, me))


def _subsurf(bm, levels, name, slots=0):
    """케이지 bmesh → SUBSURF 를 적용한 새 Mesh 데이터(임시 객체는 지운다). bm 은 free 된다."""
    me = bpy.data.meshes.new(name + "_cage")
    bm.to_mesh(me)
    bm.free()
    for _ in range(slots):
        me.materials.append(None)
    ob = _link(bpy.data.objects.new(name + "_cage", me))
    mod = ob.modifiers.new("ss", "SUBSURF")
    mod.subdivision_type = "CATMULL_CLARK"
    mod.levels = levels
    mod.render_levels = levels
    dense = bpy.data.meshes.new_from_object(ob.evaluated_get(bpy.context.evaluated_depsgraph_get()))
    bpy.data.objects.remove(ob, do_unlink=True)
    bpy.data.meshes.remove(me)
    return dense


def _mesh_arrays(me):
    nv, nf = len(me.vertices), len(me.polygons)
    tot = np.empty(nf, np.int32)
    me.polygons.foreach_get("loop_total", tot)
    assert (tot == 4).all(), "세분화 결과가 전부 사각형이 아니다"
    co = np.empty(nv * 3, np.float32)
    me.vertices.foreach_get("co", co)
    fv = np.empty(nf * 4, np.int32)
    me.loops.foreach_get("vertex_index", fv)
    tag = np.empty(nf, np.int32)
    me.polygons.foreach_get("material_index", tag)
    return co.reshape(-1, 3).astype(np.float64), fv.reshape(-1, 4).copy(), tag


def _smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def _planes(poly3, d):
    """볼록 다각형(3D 점 목록)을 d 방향으로 밀어 만든 기둥의 옆면 평면들. (co, no), no 는 바깥쪽."""
    pts = np.asarray(poly3, float)
    d = np.asarray(d, float)
    c = pts.mean(0)
    co, no = [], []
    n = len(pts)
    for k in range(n):
        e = pts[(k + 1) % n] - pts[k]
        if np.linalg.norm(e) < 1e-9:
            continue
        nn = np.cross(e, d)
        ln = np.linalg.norm(nn)
        if ln < 1e-12:
            continue
        nn /= ln
        if np.dot(nn, pts[k] - c) < 0:
            nn = -nn
        co.append(pts[k])
        no.append(nn)
    return np.array(co), np.array(no)


def _plane(co, no):
    no = np.asarray(no, float)
    return np.array([co], float), np.array([no / np.linalg.norm(no)])


def _inside(cen, planes, tol=0.0):
    """cen(N,3)이 planes(볼록 영역) 안에 있는지."""
    co, no = planes
    d = np.einsum("nkj,kj->nk", cen[:, None, :] - co[None, :, :], no)
    return (d < tol).all(1)


def _geom(faces):
    """면 목록 + 그 모서리·정점(순서 보존, 중복 없음)."""
    vs, es = {}, {}
    for f in faces:
        for v in f.verts:
            vs[v] = None
        for e in f.edges:
            es[e] = None
    return list(vs) + list(es) + list(faces)


def _cut(bm, faces, planes, eps=1e-6):
    """faces 를 planes 로 자르기만 한다(지우지 않는다). 자른 뒤의 면 목록."""
    co, no = planes
    geom = _geom(faces)
    for k in range(len(co)):
        r = bmesh.ops.bisect_plane(bm, geom=geom, dist=eps, plane_co=Vector(co[k]), plane_no=Vector(no[k]),
                                   clear_outer=False, clear_inner=False)
        geom = r["geom"]
    return [g for g in geom if isinstance(g, bmesh.types.BMFace)]


def _carve(bm, faces, planes, eps=1e-6):
    """faces 를 볼록 영역 planes 로 자른다. 평면마다 안쪽(d<0) 조각만 다음 평면으로 넘긴다
    (줄어드는 작업 집합: 바깥 조각을 다시 자르지 않아 난도질이 없다). 영역 안쪽 면 목록을 돌려준다."""
    co, no = planes
    work = list(faces)
    for k in range(len(co)):
        if not work:
            break
        r = bmesh.ops.bisect_plane(bm, geom=_geom(work), dist=eps, plane_co=Vector(co[k]), plane_no=Vector(no[k]),
                                   clear_outer=False, clear_inner=False)
        fs = [g for g in r["geom"] if isinstance(g, bmesh.types.BMFace)]
        if not fs:
            return []
        d = (_centers(fs) - co[k]) @ no[k]
        work = [f for f, dd in zip(fs, d) if dd < 0.0]
    return work


def _clip(bm, planes, eps=1e-6):
    """planes(볼록 영역) 바깥을 잘라 버린다."""
    co, no = planes
    for k in range(len(co)):
        if not bm.faces:
            return
        bmesh.ops.bisect_plane(bm, geom=bm.verts[:] + bm.edges[:] + bm.faces[:], dist=eps, plane_co=Vector(co[k]),
                               plane_no=Vector(no[k]), clear_outer=True, clear_inner=False)


def _centers(faces):
    return np.array([f.calc_center_median() for f in faces], float).reshape(-1, 3)


def _cleanup(bm):
    bmesh.ops.dissolve_degenerate(bm, dist=1e-5, edges=bm.edges[:])  # 평면 절단이 남긴 0.01 mm 미만 조각
    le = [e for e in bm.edges if not e.link_faces]
    if le:
        bmesh.ops.delete(bm, geom=le, context="EDGES")
    lv = [v for v in bm.verts if not v.link_faces]
    if lv:
        bmesh.ops.delete(bm, geom=lv, context="VERTS")


# ---------- 차체 케이지 ----------
# 링: 오른쪽 U(바닥 바깥) R(로커) M(옆면 최대 폭) S(어깨) G(유리 아래) E(루프 레일 바깥) T(레일 안쪽), 가운데 C, 왼쪽은 거울상.
U_, R_, M_, S_, G_, E_, T_, C_ = range(8)
ZONES = {"hood": 0, "ws": 1, "roof": 2, "bl": 3, "deck": 4, "capf": 5, "capr": 6}
ZONE_AFTER = {"nose": "hood", "hood": "hood", "cowl": "ws", "roof_f": "roof", "roof": "roof", "roof_r": "bl",
              "bl": "bl", "base": "deck", "deck": "deck"}
STRIP_UNDER = 7
SHAPE_DEFAULTS = {"subdiv": 3, "shelf": 0.04, "tuck": 0.09, "rocker": 0.10, "mid": 0.55, "side_in": 0.012,
                  "lift": 0.02, "rail": 0.08, "crown": 0.02, "hood_crown": 0.012, "hood_rail": 0.09,
                  "nose_lean": [0.06, 0.03], "tail_lean": [0.02, 0.03]}


def _tag(zone, strip):
    return ZONES[zone] * 8 + strip


def _half_profile(st, sh):
    """스테이션 한 줄 → 오른쪽 반 프로파일 (x, z) 7점 + 가운데 높이."""
    hw, zb, zs, zt = st["hw"], st["zb"], st["zs"], st["zt"]
    tw, ze = st.get("tw"), st.get("ze")
    pts = [(hw - sh["tuck"], zb),
           (hw - 0.010, zb + sh["rocker"]),
           (hw, zb + sh["mid"] * (zs - zb)),
           (hw - sh["side_in"], zs),
           (hw - sh["shelf"], zs + sh["lift"])]
    if tw is None:  # 후드·데크: 위쪽 점들이 어깨 가까이에 모인다
        xe = hw - sh["shelf"] - 0.045
        pts.append((xe, zs + sh["lift"] + 0.4 * (zt - zs - sh["lift"])))
        pts.append((xe - sh["hood_rail"], zt - sh["hood_crown"]))
    else:           # 캐빈: 레일(E)·루프(T)
        pts.append((tw, ze))
        pts.append((tw - sh["rail"], zt - sh["crown"]))
    return pts, zt


def _ring(st, sh):
    """스테이션 → 링 15점 (x, y, z)."""
    half, zt = _half_profile(st, sh)
    y, hw, zb = st["y"], st["hw"], st["zb"]
    bow = st.get("bow") or 0.0
    role = st["role"]
    zm = half[M_][1]
    lean = sh["nose_lean"] if role == "nose" else sh["tail_lean"] if role == "tail" else None
    sgn = -1.0 if role == "nose" else 1.0

    def yy(x, z, top):
        dy = 0.0
        if top and bow:
            dy += bow * (1.0 - (x / hw) ** 2)
        if lean is not None:
            if z >= zm:
                dy += sgn * lean[0] * (z - zm) / max(1e-6, zt - zm)
            else:
                dy += sgn * lean[1] * (zm - z) / max(1e-6, zm - zb)
        return y + dy

    right = [(x, yy(x, z, i >= E_), z) for i, (x, z) in enumerate(half)]
    left = [(-x, yv, z) for (x, yv, z) in reversed(right)]
    return np.array(right + [(0.0, yy(0.0, zt, True), zt)] + left, float)


def _stations(sh):
    rows = [dict(zip(sh["cols"], r)) for r in sh["stations"]]
    # "lerp" 스테이션: 위쪽 점(E, T, C)은 앞뒤 명시 스테이션 사이를 y 로 보간한다(뒷유리 중간 등).
    rings = [None] * len(rows)
    for i, st in enumerate(rows):
        if st.get("tw") != "lerp":
            rings[i] = _ring(st, sh)
    for i, st in enumerate(rows):
        if st.get("tw") != "lerp":
            continue
        a = max(j for j in range(i) if rings[j] is not None)
        b = min(j for j in range(i + 1, len(rows)) if rows[j].get("tw") != "lerp")
        t = (rows[a]["y"] - st["y"]) / (rows[a]["y"] - rows[b]["y"])
        tmp = dict(st)
        tmp["tw"], tmp["ze"] = None, None
        ring = _ring(tmp, sh)
        mix = rings[a] * (1 - t) + rings[b] * t
        for k in (E_, T_, C_, 14 - T_, 14 - E_):
            ring[k] = mix[k]
            ring[k][1] = st["y"] + (mix[k][1] - (rows[a]["y"] * (1 - t) + rows[b]["y"] * t))
        rings[i] = ring
    return rows, rings


def _cage(sh):
    """(bmesh 케이지, 기준 스테이션 링 {role: [ring, ...]})."""
    rows, base = _stations(sh)
    seq = []  # (ring, 뒤쪽 구간의 zone, role 또는 None)
    for i, st in enumerate(rows):
        h = st.get("hold") or 0.0
        ha, hb = (h, h) if isinstance(h, (int, float)) else h
        zone = ZONE_AFTER.get(st["role"])
        if ha and i > 0:
            t = min(0.45, ha / (rows[i - 1]["y"] - st["y"]))
            seq.append((base[i] * (1 - t) + base[i - 1] * t, ZONE_AFTER[rows[i - 1]["role"]], None))
        seq.append((base[i], zone, st["role"]))
        if hb and i < len(rows) - 1:
            t = min(0.45, hb / (st["y"] - rows[i + 1]["y"]))
            seq.append((base[i] * (1 - t) + base[i + 1] * t, zone, None))
    n = len(seq)
    for i in range(n - 1):
        assert (seq[i][0][:, 1] > seq[i + 1][0][:, 1] + 1e-4).all(), "링이 겹친다(y 순서): %d" % i
    bm = bmesh.new()
    cl = bm.edges.layers.float.new("crease_edge")
    V = [[bm.verts.new(p) for p in ring] for (ring, _, _) in seq]

    def face(vs, tag):
        bm.faces.new(vs).material_index = tag

    for i in range(n - 1):
        z = seq[i][1]
        for k in range(14):
            face((V[i][k], V[i][k + 1], V[i + 1][k + 1], V[i + 1][k]), _tag(z, k if k < 7 else 13 - k))
        face((V[i][14], V[i][0], V[i + 1][0], V[i + 1][14]), _tag(z, STRIP_UNDER))
    for i, zone in ((0, "capf"), (n - 1, "capr")):
        r = V[i]
        for j in range(5):
            face((r[j], r[j + 1], r[13 - j], r[14 - j]), _tag(zone, j))
        face((r[5], r[6], r[7], r[8], r[9]), _tag(zone, 5))
    cr = sh.get("crease", {})
    line = {"R": (R_, 14 - R_), "M": (M_, 14 - M_), "S": (S_, 14 - S_), "G": (G_, 14 - G_), "E": (E_, 14 - E_),
            "T": (T_, 14 - T_), "C": (C_,)}
    for nm, ks in line.items():
        c = cr.get(nm, 0.0)
        if not c:
            continue
        for i in range(n - 1):
            for k in ks:
                bm.edges.get((V[i][k], V[i + 1][k]))[cl] = c
    for i, (_, _, role) in enumerate(seq):
        c = cr.get(role, 0.0) if role else 0.0
        if not c:
            continue
        ks = range(14) if role in ("nose", "tail") else range(G_, 14 - G_)
        for k in ks:
            bm.edges.get((V[i][k], V[i][k + 1]))[cl] = c
        if role in ("nose", "tail"):
            bm.edges.get((V[i][14], V[i][0]))[cl] = c
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
    feat = {}
    for st, ring in zip(rows, base):
        feat.setdefault(st["role"], []).append(ring.copy())
    return bm, feat


class Skin:
    """세분화된 차체 겉면(전부 사각형). co(V,3), faces(F,4), tag(F,) = zone*8+strip."""

    def __init__(self, co, faces, tag):
        self.co, self.faces, self.tag = co, faces, tag
        self.zone, self.strip = tag // 8, tag % 8
        self.update()

    def update(self):
        c = self.co[self.faces]
        self.cen = c.mean(1)
        n = np.cross(c[:, 2] - c[:, 0], c[:, 3] - c[:, 1])
        self.fno = n / np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
        vn = np.zeros_like(self.co)
        for j in range(4):
            np.add.at(vn, self.faces[:, j], n)
        self.vno = vn / np.maximum(np.linalg.norm(vn, axis=1, keepdims=True), 1e-12)

    def sel(self, zones, strips):
        return np.isin(self.zone, [ZONES[z] for z in zones]) & np.isin(self.strip, list(strips))

    def surf_point(self, end, x, z):
        """앞(end='f')·뒤('r') 면에서 정면 투영 (x, z)에 해당하는 표면 점."""
        s = 1.0 if end == "f" else -1.0
        face_ok = self.fno[:, 1] * s > 0.1
        near = face_ok & (np.abs(self.cen[:, 0] - x) < 0.06) & (np.abs(self.cen[:, 2] - z) < 0.06)
        if near.any():
            idx = np.nonzero(near)[0]
            i = idx[np.argmax(self.cen[idx, 1] * s)]
        else:
            idx = np.nonzero(face_ok)[0]
            d = (self.cen[idx, 0] - x) ** 2 + (self.cen[idx, 2] - z) ** 2
            i = idx[np.argmin(d)]
        return np.array([x, self.cen[i, 1], z])

    def top_z(self, x, y):
        """위를 보는 면 가운데 (x, y)에 가장 가까운 면의 높이."""
        idx = np.nonzero(self.fno[:, 2] > 0.5)[0]
        d = (self.cen[idx, 0] - x) ** 2 + (self.cen[idx, 1] - y) ** 2
        return float(self.cen[idx[np.argmin(d)], 2])


def _arch_metric(y, z, yc, zc, a, b, n):
    return (np.abs((y - yc) / a) ** n + np.abs((z - zc) / b) ** n) ** (1.0 / n)


def _arch_poly(sx, x, yc, zc, a, b, n, seg=64):
    """아치 다각형(초타원)의 3D 점 목록. x 는 놓일 평면."""
    pts = []
    for k in range(seg):
        t = 2 * math.pi * k / seg
        c, s = math.cos(t), math.sin(t)
        pts.append((sx * x, yc + a * math.copysign(abs(c) ** (2.0 / n), c), zc + b * math.copysign(abs(s) ** (2.0 / n), s)))
    return pts


def _axles(sh):
    """앞(+1)·뒤(-1) 축별 아치 설정: shape.arch 에 arch.front / arch.rear 를 덮어쓴다(없으면 같은 값).
    축별로 읽는 키: flare·falloff·flare_top·flare_lo([dz0, dz1], 축 높이 기준 그 아래는 플레어 없음)·tire_inset
    (없으면 shape.tire_inset, 기본 .05). 아치 구멍 크기(gap·wide·n)는 두 축 공통."""
    ar = sh["arch"]
    out = {}
    for s, key in ((1, "front"), (-1, "rear")):
        d = {k: v for k, v in ar.items() if k not in ("front", "rear")}
        d.update(ar.get(key, {}))
        d.setdefault("tire_inset", sh.get("tire_inset", 0.05))
        out[s] = d
    return out


def _tire_out(v, sh, s):
    """축 s 의 타이어 바깥면 |x|. 플레어가 가장 큰 축의 펜더 끝이 W/2 이고, 다른 축은 자기 플레어만큼 안쪽에서 inset 을 뺀다."""
    ax = _axles(sh)
    fmax = max(ax[1].get("flare", 0.0), ax[-1].get("flare", 0.0))
    return v["dims"][1] / 2 - (fmax - ax[s].get("flare", 0.0)) - ax[s]["tire_inset"]


def _body_skin(v):
    """케이지 → 세분화 → dims 에 맞춤 → 펜더 플레어. (Skin, feat, 아치 목록)."""
    L, W, H = v["dims"]
    R, WB = v["wheel_r"], v["wheelbase"]
    sh = dict(SHAPE_DEFAULTS)
    sh.update(v["shape"])
    bm, feat = _cage(sh)
    dense = _subsurf(bm, sh["subdiv"], "body", slots=64)
    co, faces, tag = _mesh_arrays(dense)
    bpy.data.meshes.remove(dense)
    # 바깥을 향하게(부피가 양수)
    c = co[faces]
    vol = np.einsum("ij,ij->i", c[:, 0], np.cross(c[:, 1], c[:, 2])).sum() + np.einsum("ij,ij->i", c[:, 0], np.cross(c[:, 2], c[:, 3])).sum()
    if vol < 0:
        faces = faces[:, ::-1].copy()
    ar = sh["arch"]
    axc = _axles(sh)
    flare = max(axc[1].get("flare", 0.0), axc[-1].get("flare", 0.0))
    y0, y1 = co[:, 1].min(), co[:, 1].max()
    sy, oy = L / (y1 - y0), (y1 + y0) / 2
    sz = H / co[:, 2].max()
    sx0 = (W / 2 - flare) / np.abs(co[:, 0]).max()

    def xf(p, sxx):
        q = np.array(p, float)
        q[..., 0] *= sxx
        q[..., 1] = (q[..., 1] - oy) * sy
        q[..., 2] *= sz
        return q

    co = xf(co, sx0)
    a = R + ar["gap"] + ar.get("wide", 0.0)
    b = R + ar["gap"]
    n = ar.get("n", 2.0)
    arches = [(sxx, syy * WB / 2, R, a, b, n) for syy in (1, -1) for sxx in (1, -1)]
    if flare > 0:
        hw = np.abs(co[:, 0]).max()
        # 어깨선(S) 높이를 축 위치에서 읽는다: 플레어는 그 바로 아래에서 사라진다.
        sp = sorted(((r[S_][1] - oy) * sy, r[S_][2] * sz) for rs in feat.values() for r in rs)
        sy_, sz_ = np.array([p[0] for p in sp]), np.array([p[1] for p in sp])
        for (sxx, yc, zc, aa, bb, nn) in arches:
            ac = axc[1 if yc > 0 else -1]
            fl = ac.get("flare", 0.0)
            if fl <= 0:
                continue
            zsa = float(np.interp(yc, sy_, sz_))
            rho = _arch_metric(co[:, 1], co[:, 2], yc, zc, aa, bb, nn)
            w = 1.0 - _smoothstep(1.0, 1.0 + ac.get("falloff", 0.2) / aa, rho)
            w *= 1.0 - _smoothstep(zsa - ac.get("flare_top", 0.12), zsa - 0.01, co[:, 2])
            lo = ac.get("flare_lo")   # [dz0, dz1]: 축 높이 기준 아래쪽에서 플레어를 줄인다(뒤에서 타이어 아래 어깨가 보이게)
            if lo:
                w *= _smoothstep(zc + lo[0], zc + lo[1], co[:, 2])
            w *= _smoothstep(0.55 * hw, 0.80 * hw, sxx * co[:, 0])
            co[:, 0] += sxx * fl * w
    sx1 = (W / 2) / np.abs(co[:, 0]).max()
    co[:, 0] *= sx1
    feat = {k: [xf(r, sx0 * sx1) for r in rs] for k, rs in feat.items()}
    return Skin(co, faces, tag), feat, arches, sh


def _patch_bm(skin, mask, off):
    """mask 로 고른 면을 법선 방향으로 off 만큼 띄워 복제한 bmesh."""
    bm = bmesh.new()
    vmap = {}
    co, vno = skin.co, skin.vno
    for fi in np.nonzero(mask)[0]:
        vs = []
        for vi in skin.faces[fi]:
            vi = int(vi)
            bv = vmap.get(vi)
            if bv is None:
                p = co[vi] + vno[vi] * off
                bv = bm.verts.new((p[0], p[1], p[2]))
                vmap[vi] = bv
            vs.append(bv)
        bm.faces.new(vs)
    return bm


def _patch_obj(name, bm, mat):
    _cleanup(bm)
    if not bm.faces:
        bm.free()
        return None
    return _obj(name, bm, [mat], sharp_deg=None, recalc=False)


# ---------- 차체 + 휠 아치 + 라이너 ----------
def _body_and_liners(skin, arches, x_in, M, P):
    bm = _patch_bm(skin, np.ones(len(skin.faces), bool), 0.0)
    bm.faces.ensure_lookup_table()
    fc = skin.co[skin.faces]  # (F, 4, 3). 후보는 면 중심이 아니라 꼭짓점 범위로 고른다
    xin = x_in if isinstance(x_in, dict) else {1: x_in, -1: x_in}   # 축(앞 +1·뒤 -1)별 라이너 안쪽 평면
    cands = []
    for (sx, yc, zc, a, b, n) in arches:
        m = ((fc[:, :, 0] * sx).max(1) > xin[1 if yc > 0 else -1] - 0.01) & (fc[:, :, 1].max(1) > yc - a - 0.01) & \
            (fc[:, :, 1].min(1) < yc + a + 0.01) & (fc[:, :, 2].min(1) < zc + b + 0.01)
        cands.append([bm.faces[int(i)] for i in np.nonzero(m)[0]])
    for wi, (sx, yc, zc, a, b, n) in enumerate(arches):
        x_in = xin[1 if yc > 0 else -1]
        poly = _planes(_arch_poly(sx, x_in, yc, zc, a, b, n), (1.0, 0.0, 0.0))
        inner = _plane((sx * x_in, 0, 0), (-sx, 0, 0))
        dead = _carve(bm, cands[wi], (np.concatenate([inner[0], poly[0]]), np.concatenate([inner[1], poly[1]])))
        assert dead, "아치가 차체를 자르지 못했다"
        rim = {}
        for f in dead:
            for e in f.edges:
                rim[e] = None
        bmesh.ops.delete(bm, geom=dead, context="FACES")
        # 구멍 경계 고리
        edges = [e for e in rim if e.is_valid and len(e.link_faces) == 1]
        assert edges, "아치 경계를 찾지 못했다"
        nbr = {}
        for e in edges:
            for vtx in e.verts:
                nbr.setdefault(vtx, []).append(e)
        loop = [edges[0].verts[0]]
        e = edges[0]
        cur = e.other_vert(loop[0])
        while cur is not loop[0]:
            loop.append(cur)
            nxt = [x for x in nbr[cur] if x is not e]
            assert len(nxt) == 1, "아치 경계가 한 고리가 아니다"
            e = nxt[0]
            cur = e.other_vert(cur)
        if len(loop) != len(edges):
            rest = [e for e in edges if e.verts[0] not in set(loop) and e.verts[1] not in set(loop)]
            ps = np.array([v.co for e in rest for v in e.verts])
            raise AssertionError("아치 경계가 여러 고리다: 고리 %d/%d 모서리, 나머지 범위 min %s max %s"
                                 % (len(loop), len(edges), np.round(ps.min(0), 3), np.round(ps.max(0), 3)))
        # 라이너: 경계를 x = sx*x_in 평면까지 민 통 + 안쪽 마개
        lb = bmesh.new()
        outer, inner_v = [], []
        last_q = None
        for vtx in loop:
            p = vtx.co
            on_plane = abs(abs(p.x) - x_in) < 1e-5
            ov = lb.verts.new(p)
            outer.append(ov)
            q = (sx * x_in, p.y, p.z)
            if on_plane:
                inner_v.append(ov)
            elif last_q is not None and (Vector(q) - last_q.co).length < 1e-6:
                inner_v.append(last_q)
            else:
                last_q = lb.verts.new(q)
                inner_v.append(last_q)
        nl = len(loop)
        for i in range(nl):
            j = (i + 1) % nl
            vs = []
            for vtx in (outer[i], outer[j], inner_v[j], inner_v[i]):
                if vtx not in vs:
                    vs.append(vtx)
            if len(vs) >= 3:
                try:
                    f = lb.faces.new(vs)
                except ValueError:
                    continue
                if f.calc_area() < 1e-9:
                    lb.faces.remove(f)
        cap = []
        for vtx in inner_v:
            if vtx.is_valid and (not cap or vtx is not cap[-1]) and vtx not in cap:
                cap.append(vtx)
        assert len(cap) >= 3, "라이너 마개가 비었다"
        capf = lb.faces.new(cap)
        # 통과 마개는 같은 고리 순서로 만들어 감는 방향이 이미 맞다. 마개가 바깥(+sx, 보는 쪽)을 보게 전체를 뒤집는다.
        lb.normal_update()
        if capf.normal.x * sx < 0:
            bmesh.ops.reverse_faces(lb, faces=lb.faces[:])
        _cleanup(lb)
        tagn = "%s%s" % ("f" if yc > 0 else "r", "l" if sx < 0 else "r")
        P.add("fixed", _obj("liner_" + tagn, lb, [M["liner"]], sharp_deg=40.0, recalc=False))
    _cleanup(bm)
    return _obj("body_shell", bm, [M["body_white"]], sharp_deg=None, recalc=False)


# ---------- 겉껍질 패치: 유리·투톤·데칼·클래딩 ----------
def _glass(skin, feat, sh, M, P):
    g = sh.get("glass", {})
    off = 0.0045
    for nm, zones, strips in (("glass_ws", ("ws",), (6,)), ("glass_bl", ("bl",), (6,))):
        ob = _patch_obj(nm, _patch_bm(skin, skin.sel(zones, strips), off), M["glass"])
        if ob:
            P.add("fixed", ob)
    # 옆 유리: G–E 띠. 뒤쪽은 C 필러 평면으로 자르고, B 필러 틈을 낸다.
    bm = _patch_bm(skin, skin.sel(("ws", "roof", "bl"), (4,)), off)
    zs = feat["roof_r"][0][S_][2]
    if "a_y" in g:   # 앞 끝(A 필러 밑동): 평면 y = a_y - tan(a_lean)(z - zs) 앞쪽을 버린다
        a = math.radians(g.get("a_lean", 0.0))
        _clip(bm, _plane((0, g["a_y"], zs), (0, math.cos(a), math.sin(a))))
    if "c_y" in g:
        a = math.radians(g.get("c_lean", 0.0))
        _clip(bm, _plane((0, g["c_y"], zs), (0, -math.cos(a), math.sin(a))))
    bys = g.get("b_y")
    bys = [] if bys is None else bys if isinstance(bys, list) else [bys]
    for by in bys:  # 필러 틈(B, 미니밴은 C 도): 두께 b_w, 위로 갈수록 b_lean 만큼 뒤로
        if not bm.faces:
            break
        w = g.get("b_w", 0.05) / 2
        a = math.radians(g.get("b_lean", 0.0))
        nn = np.array((0, math.cos(a), -math.sin(a)))
        slab = (np.array([(0, by + w, zs), (0, by - w, zs)], float), np.array([nn, -nn]))
        faces = _cut(bm, bm.faces[:], slab)
        kill = _inside(_centers(faces), slab)
        bmesh.ops.delete(bm, geom=[f for f, k in zip(faces, kill) if k], context="FACES")
    ob = _patch_obj("glass_side", bm, M["glass"])
    if ob:
        P.add("fixed", ob)


def _twotone(skin, M, P):
    ob = _patch_obj("roof_twotone", _patch_bm(skin, skin.sel(("ws", "roof", "bl"), (5, 6)), 0.002), M["overlay_white"])
    if ob:
        P.add("roof_twotone", ob)


def _stripe(skin, sh, M, P):
    st = sh.get("stripe")
    if not st:
        return
    # 뒷유리(bl 띠 6)·앞유리(ws)는 건너뛴다. 위를 보거나 앞·뒤를 보는 면만(옆면으로 흘러내리지 않게).
    mask = skin.sel(("hood", "roof", "deck", "capf", "capr"), (3, 4, 5, 6)) & (skin.fno[:, 2] > -0.2)
    for i, (x0, x1) in enumerate(st):
        bm = _patch_bm(skin, mask, 0.003)
        _clip(bm, (np.array([(x0, 0, 0), (x1, 0, 0)], float), np.array([(-1.0, 0, 0), (1.0, 0, 0)])))
        ob = _patch_obj("decal_stripe%d" % i, bm, M["overlay_white"])
        if ob:
            P.add("decal_stripe", ob)


def _cladding(skin, arches, sh, v, M, P):
    cl = sh.get("clad")
    if not cl:
        return
    z_band = v["ground_clearance"] + cl["band"]
    fl = cl.get("flare", 0.0)
    off = 0.005
    side = np.isin(skin.strip, (0, 1, 2)) & (skin.zone != ZONES["capf"]) & (skin.zone != ZONES["capr"])
    caps = ((skin.zone == ZONES["capf"]) | (skin.zone == ZONES["capr"])) & (skin.strip <= 2)
    mask = (side | caps) & (skin.cen[:, 2] < z_band + 0.08)
    if fl > 0:
        for (sx, yc, zc, a, b, n) in arches:
            near = side & (skin.cen[:, 0] * sx > 0) & (_arch_metric(skin.cen[:, 1], skin.cen[:, 2], yc, zc, a + fl, b + fl, n) < 1.15)
            mask |= near
    bm = _patch_bm(skin, mask, off)
    band = _plane((0, 0, z_band), (0, 0, 1))
    _cut(bm, bm.faces[:], band)
    regs = []
    for (sx, yc, zc, a, b, n) in arches:
        inner = _planes(_arch_poly(sx, 0.0, yc, zc, a, b, n), (1.0, 0.0, 0.0))
        outer = _planes(_arch_poly(sx, 0.0, yc, zc, a + fl, b + fl, n), (1.0, 0.0, 0.0)) if fl > 0 else None
        near = [f for f in bm.faces if f.calc_center_median().x * sx > 0 and abs(f.calc_center_median().y - yc) < a + fl + 0.1]
        near = _cut(bm, near, inner)
        if outer is not None:
            _cut(bm, near, outer)
        regs.append((sx, inner, outer))
    faces = bm.faces[:]
    cen = _centers(faces)
    keep = _inside(cen, band)
    for (sx, inner, outer) in regs:
        sidem = cen[:, 0] * sx > 0
        if outer is not None:
            keep |= sidem & _inside(cen, outer)
    for (sx, inner, outer) in regs:
        keep &= ~((cen[:, 0] * sx > 0) & _inside(cen, inner))
    bmesh.ops.delete(bm, geom=[f for f, k in zip(faces, keep) if not k], context="FACES")
    ob = _patch_obj("cladding", bm, M["cladding"])
    if ob:
        P.add("fixed", ob)


# ---------- 투영 패치: 램프·흡기구·번호판 ----------
def _shape2d(spec, rot=0.0):
    kind = spec[0]
    if kind == "ellipse":
        w, h = spec[1], spec[2]
        pts = [(0.5 * w * math.cos(2 * math.pi * k / 40), 0.5 * h * math.sin(2 * math.pi * k / 40)) for k in range(40)]
    elif kind == "rrect":
        w, h, r = spec[1], spec[2], spec[3]
        shear = spec[4] if len(spec) > 4 else 0.0
        r = min(r, w / 2 - 1e-4, h / 2 - 1e-4)
        pts = []
        for (cx, cy, a0) in ((w / 2 - r, h / 2 - r, 0), (-(w / 2 - r), h / 2 - r, 90), (-(w / 2 - r), -(h / 2 - r), 180), (w / 2 - r, -(h / 2 - r), 270)):
            for k in range(7):
                t = math.radians(a0 + 90.0 * k / 6)
                pts.append((cx + r * math.cos(t), cy + r * math.sin(t)))
        pts = [(a + shear * b, b) for (a, b) in pts]
    else:
        pts = [tuple(p) for p in spec[1]]
    if rot:
        c, s = math.cos(math.radians(rot)), math.sin(math.radians(rot))
        pts = [(a * c - b * s, a * s + b * c) for (a, b) in pts]
    return pts


def _frame(d, sx):
    d = np.asarray(d, float)
    d = d / np.linalg.norm(d)
    X = np.array([1.0 if sx >= 0 else -1.0, 0.0, 0.0])
    u = X - np.dot(X, d) * d
    u /= np.linalg.norm(u)
    w = np.cross(d, u)
    if w[2] < 0:
        w = -w
    return d, u, w


def _decor(skin, items, M, P):
    """shape.decor 목록: 앞·뒤 면에 투영한 패치(램프 하우징·렌즈, 흡기구, 번호판 등).
    "hole": 도형(볼록) 을 주면 그 안쪽을 뚫어 고리 모양 패치가 된다(가운데는 "at" 기준 같은 자리, "hole_at" 으로 옮김)."""
    for it in items:
        s = 1.0 if it["end"] == "f" else -1.0
        x = it.get("x", 0.0)
        wrap, pitch = math.radians(it.get("wrap", 0.0)), math.radians(it.get("pitch", 0.0))
        for sx in ((1, -1) if x > 1e-6 else (0,)):
            d, u, w = _frame((sx * math.sin(wrap) * math.cos(pitch), s * math.cos(wrap) * math.cos(pitch), math.sin(pitch)), sx)
            c = skin.surf_point(it["end"], sx * x, it["z"])
            a0, b0 = it.get("at", (0.0, 0.0))
            shp = _shape2d(it["shape"], it.get("rot", 0.0))
            rmax = max(math.hypot(a + a0, b + b0) for (a, b) in shp) + 0.12
            rel = skin.cen - c
            along = rel @ d
            lat = np.linalg.norm(rel - np.outer(along, d), axis=1)
            mask = (skin.fno @ d > it.get("face", 0.12)) & (np.abs(along) < it.get("depth", 0.45)) & (lat < rmax)
            bm = _patch_bm(skin, mask, it.get("off", 0.004))
            _clip(bm, _planes([c + u * (a + a0) + w * (b + b0) for (a, b) in shp], d))
            if it.get("hole"):
                h0, h1 = it.get("hole_at", (a0, b0))
                hole = _planes([c + u * (a + h0) + w * (b + h1) for (a, b) in _shape2d(it["hole"], it.get("rot", 0.0))], d)
                inner = _carve(bm, bm.faces[:], hole)
                bmesh.ops.delete(bm, geom=inner, context="FACES")
            nm = it["n"] + ("" if sx == 0 else "_l" if sx < 0 else "_r")
            ob = _patch_obj(nm, bm, M[it["mat"]])
            assert ob is not None, "투영 패치가 비었다: " + nm
            P.add(it["grp"], ob)
            if it.get("mark"):
                P.marks[it["mark"]].append(ob)


# ---------- 작은 덩어리(둥근 상자) ----------
def _rbox_bm(verts8, r, mat=0):
    """8점 육면체(아래 4점 반시계 + 위 4점)의 모서리를 r 만큼 깎은 케이지(세분화용)."""
    faces = [(0, 1, 2, 3), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]
    bm = bmesh.new()
    vs = [bm.verts.new(p) for p in verts8]
    for f in faces:
        bm.faces.new([vs[i] for i in f]).material_index = mat
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
    if r > 0:
        bmesh.ops.bevel(bm, geom=bm.edges[:], offset=r, offset_type="OFFSET", segments=1, profile=0.5,
                        affect="EDGES", clamp_overlap=True)
    return bm


def _pebble(name, verts8, r, mats, levels=2):
    """둥근 덩어리 객체(깎은 육면체 + SUBSURF)."""
    me = _subsurf(_rbox_bm(verts8, r), levels, name)
    me.name = name
    me.polygons.foreach_set("use_smooth", np.ones(len(me.polygons), bool))
    for m in mats:
        me.materials.append(m)
    me.update()
    return _link(bpy.data.objects.new(name, me))


def _rect(x0, x1, y0, y1, z):
    return [(x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z)]


def _box8(x0, x1, y0, y1, z0, z1):
    return _rect(x0, x1, y0, y1, z0) + _rect(x0, x1, y0, y1, z1)


def _box(name, x0, x1, y0, y1, z0, z1, mat, bevel=0.0):
    bm = _rbox_bm(_box8(x0, x1, y0, y1, z0, z1), bevel)
    return _obj(name, bm, [mat], sharp_deg=30.0)


# ---------- 바퀴 ----------
def _lathe(bm, prof, seg, sx, c, mat=0, closed=False, phase=0.0):
    """prof [(x_local, r)] 를 X 축 둘레로 돌린다. x_local 은 바깥쪽(+).
    면 법선은 프로파일 접선 (dx, dr) 의 (-dr, dx) 쪽이다: r 이 줄어드는 프로파일이면 바깥(+x_local)을 본다.
    (sx 에 따라 감는 방향을 바꿔 좌우 바퀴가 같은 규칙을 따른다. 법선을 다시 계산하지 않는다.)"""
    cx, cy, cz = c
    rings = []
    for (xl, r) in prof:
        x = cx + sx * xl
        if r < 1e-6:
            rings.append([bm.verts.new((x, cy, cz))])
        else:
            rings.append([bm.verts.new((x, cy + r * math.cos(phase + 2 * math.pi * k / seg), cz + r * math.sin(phase + 2 * math.pi * k / seg)))
                          for k in range(seg)])
    n = len(rings)
    pairs = [(i, i + 1) for i in range(n - 1)] + ([(n - 1, 0)] if closed else [])
    rev = sx > 0
    for i, j in pairs:
        A, B = rings[i], rings[j]
        if len(A) == 1 and len(B) == 1:
            continue
        for k in range(seg):
            k2 = (k + 1) % seg
            if len(A) == 1:
                vs = (A[0], B[k], B[k2])
            elif len(B) == 1:
                vs = (A[k], B[0], A[k2])
            else:
                vs = (A[k], B[k], B[k2], A[k2])
            f = bm.faces.new(vs[::-1] if rev else vs)
            f.material_index = mat


def _prof_x(prof, r):
    """프로파일 [(x, r)](r 내림차순)에서 반지름 r 의 x(선형 보간)."""
    for (x0, r0), (x1, r1) in zip(prof, prof[1:]):
        if r1 <= r <= r0:
            t = (r0 - r) / max(1e-9, r0 - r1)
            return x0 + (x1 - x0) * t
    return prof[-1][0]


def _wheel_pt(sx, c, xl, t, r, ang):
    """바퀴 로컬 (바깥 xl, 접선 t, 반지름 r)을 ang 만큼 돌려 월드 좌표로."""
    ca, sa = math.cos(ang), math.sin(ang)
    return (c[0] + sx * xl, c[1] + t * ca - r * sa, c[2] + t * sa + r * ca)


def _flat_poly(bm, sx, c, pts_tr, xfun, ang, mat):
    """바퀴 면 위의 납작한 다각형(접선 t, 반지름 r 목록). x 는 xfun(r)."""
    vs = [bm.verts.new(_wheel_pt(sx, c, xfun(math.hypot(t, r)), t, r, ang)) for (t, r) in pts_tr]
    f = bm.faces.new(vs)
    f.material_index = mat
    f.normal_update()
    if f.normal.x * sx < 0:  # 바퀴 바깥(보는 쪽)을 보게
        f.normal_flip()


def _wheel(P, M, tag, c, R, tw, sx, tire=True):
    """타이어·허브 바닥(fixed) + 휠 디자인 3종(각 레이어). sx=-1 이면 바깥쪽이 -x."""
    xf = tw / 2
    rb = 0.63 * R          # 비드(림 바깥) 반지름
    rl = 0.565 * R         # 림 립 안쪽 = 디자인 면의 반지름
    k = R / 0.40           # 깊이 치수 배율
    if tire:
        # 안쪽 비드 → 안쪽 옆벽(살짝 불룩) → 어깨(둥근) → 트레드(홈 2줄, 평평한 구간 안에만) → 바깥 어깨 → 옆벽 → 비드.
        h = R - rb
        rho = min(0.28 * tw, 0.40 * h)          # 어깨 반지름
        bul = 0.06 * tw                          # 옆벽 불룩
        prof = [(-0.80 * xf, rb), (-xf + 0.2 * bul, rb + 0.12 * h), (-xf - bul, rb + 0.45 * h), (-xf - 0.4 * bul, R - rho - 0.05 * h)]
        for i in range(1, 6):
            t = math.radians(180 - 90 * i / 5)
            prof.append((-xf + rho + rho * math.cos(t), R - rho + rho * math.sin(t)))
        f = xf - rho                             # 평평한 트레드 반폭
        gd = min(0.010 * k, 0.12 * h)
        if f > 0.03:
            for (u0, u1) in ((-0.55, -0.30), (0.30, 0.55)):  # 홈 2줄
                a_, b_ = u0 * f, u1 * f
                e_ = 0.06 * f
                prof += [(a_, R), (a_ + e_, R - gd), (b_ - e_, R - gd), (b_, R)]
        for i in range(0, 5):
            t = math.radians(90 - 90 * i / 5)
            prof.append((xf - rho + rho * math.cos(t), R - rho + rho * math.sin(t)))
        prof += [(xf + 0.4 * bul, R - rho - 0.05 * h), (xf + bul, rb + 0.45 * h), (xf - 0.2 * bul, rb + 0.12 * h), (0.80 * xf, rb)]
        xs = [p[0] for p in prof[3:-3]]
        assert all(b > a for a, b in zip(xs, xs[1:])), "타이어 트레드 프로파일이 x 로 단조롭지 않다"
        bm = bmesh.new()
        _lathe(bm, prof, 72, sx, c, closed=True)
        P.add("fixed", _obj(tag + "_tire", bm, [M["tire"]], sharp_deg=50.0, recalc=False))
    bm = bmesh.new()
    _lathe(bm, [(xf - 0.010 * k, rb - 0.002), (xf - 0.052 * k, rb - 0.018 * k), (xf - 0.052 * k, 0.0)], 48, sx, c)
    P.add("fixed", _obj(tag + "_hubbase", bm, [M["hub_base"]], sharp_deg=40.0, recalc=False))
    lip = [(xf - 0.020 * k, rb + 0.004), (xf - 0.005 * k, rb - 0.002), (xf - 0.002 * k, rb - 0.016 * k),
           (xf - 0.010 * k, rl + 0.004), (xf - 0.046 * k, rl - 0.006)]
    # steel: 접시 + 캡 + 통풍 구멍 8개
    bm = bmesh.new()
    _lathe(bm, lip, 48, sx, c, mat=0)
    dish = [(xf - 0.046 * k, rl - 0.006), (xf - 0.036 * k, 0.84 * rl), (xf - 0.020 * k, 0.56 * rl), (xf - 0.018 * k, 0.36 * rl),
            (xf - 0.008 * k, 0.27 * rl), (xf - 0.004 * k, 0.0)]
    _lathe(bm, dish, 48, sx, c, mat=0)
    for i in range(8):
        ang = 2 * math.pi * (i + 0.5) / 8
        oval = [(0.085 * rl * math.cos(2 * math.pi * j / 12), 0.70 * rl + 0.045 * rl * math.sin(2 * math.pi * j / 12)) for j in range(12)]
        _flat_poly(bm, sx, c, oval, lambda r: _prof_x(dish, r) + 0.0015, ang, 1)
    P.add("wheel_steel", _obj(tag + "_steel", bm, [M["hub"], M["hub_base"]], sharp_deg=30.0, recalc=False))
    # dia: 5스포크 + 밝은 림 립
    bm = bmesh.new()
    _lathe(bm, lip, 48, sx, c, mat=0)
    xb = xf - 0.050 * k
    for i in range(5):
        ang = 2 * math.pi * i / 5
        base = [(-0.11 * rl, 0.15 * rl), (0.11 * rl, 0.15 * rl), (0.17 * rl, 1.0 * rl), (-0.17 * rl, 1.0 * rl)]
        top = [(-0.065 * rl, 0.15 * rl), (0.065 * rl, 0.15 * rl), (0.11 * rl, 0.99 * rl), (-0.11 * rl, 0.99 * rl)]
        xt = [xf - 0.012 * k, xf - 0.012 * k, xf - 0.022 * k, xf - 0.022 * k]
        vb = [bm.verts.new(_wheel_pt(sx, c, xb, t, r, ang)) for (t, r) in base]
        vt = [bm.verts.new(_wheel_pt(sx, c, xt[j], t, r, ang)) for j, (t, r) in enumerate(top)]
        fs = [bm.faces.new((vb[j], vb[(j + 1) % 4], vt[(j + 1) % 4], vt[j])) for j in range(4)] + [bm.faces.new(vt)]
        sc_ = sum((v.co for v in vb + vt), Vector()) / 8.0
        for f_ in fs:  # 스포크(볼록 덩어리) 바깥을 보게
            f_.normal_update()
            if f_.normal.dot(f_.calc_center_median() - sc_) < 0:
                f_.normal_flip()
    _lathe(bm, [(xb, 0.27 * rl), (xf - 0.012 * k, 0.21 * rl), (xf - 0.007 * k, 0.11 * rl), (xf - 0.006 * k, 0.0)], 32, sx, c, mat=0)
    _lathe(bm, [(xf - 0.0045 * k, 0.085 * rl), (xf - 0.0035 * k, 0.0)], 24, sx, c, mat=1)
    P.add("wheel_dia", _obj(tag + "_dia", bm, [M["hub_bright"], M["hub_base"]], sharp_deg=30.0, recalc=False))
    # aero: 평평한 덮개 + 슬롯 6개 + 밝은 고리
    bm = bmesh.new()
    _lathe(bm, lip, 48, sx, c, mat=2)
    cover = [(xf - 0.046 * k, rl - 0.006), (xf - 0.016 * k, 0.93 * rl), (xf - 0.011 * k, 0.42 * rl), (xf - 0.007 * k, 0.22 * rl),
             (xf - 0.006 * k, 0.0)]
    _lathe(bm, cover, 48, sx, c, mat=0)
    for i in range(6):
        ang = 2 * math.pi * (i + 0.5) / 6
        slot = [(-0.035 * rl, 0.50 * rl), (0.035 * rl, 0.50 * rl), (0.075 * rl, 0.80 * rl), (0.05 * rl, 0.87 * rl),
                (-0.05 * rl, 0.87 * rl), (-0.075 * rl, 0.80 * rl)]
        _flat_poly(bm, sx, c, slot, lambda r: _prof_x(cover, r) + 0.0015, ang, 1)
    _lathe(bm, [(_prof_x(cover, 0.34 * rl) + 0.002, 0.34 * rl), (_prof_x(cover, 0.25 * rl) + 0.002, 0.25 * rl)], 48, sx, c, mat=2)
    P.add("wheel_aero", _obj(tag + "_aero", bm, [M["hub_dark"], M["hub_base"], M["hub_bright"]], sharp_deg=30.0, recalc=False))


# ---------- 차 ----------
def _mirrors(feat, sh, M, P):
    mr = sh.get("mirror", {})
    t = mr.get("t", 0.30)
    for sx in (1, -1):
        g0, g1 = feat["cowl"][0][G_], feat["roof_f"][0][G_]
        p = g0 * (1 - t) + g1 * t
        x0 = sx * (p[0] - 0.01)
        y, z = p[1] + mr.get("dy", 0.0), p[2] + 0.02
        sw, sd, shh = mr.get("w", 0.16), mr.get("d", 0.085), mr.get("h", 0.10)
        xa, xb = sorted((x0 + sx * 0.035, x0 + sx * (0.035 + sw)))
        tagn = "l" if sx < 0 else "r"
        # 몸통(차 색): 앞이 조금 좁은 둥근 덩어리
        v8 = [(xa + 0.012, y - sd / 2, z), (xb - 0.012, y - sd / 2, z), (xb - 0.02, y + sd / 2, z + 0.01), (xa + 0.02, y + sd / 2, z + 0.01),
              (xa, y - sd / 2, z + shh), (xb, y - sd / 2, z + shh), (xb - 0.02, y + sd / 2, z + shh - 0.012), (xa + 0.02, y + sd / 2, z + shh - 0.012)]
        P.add("body", _pebble("mirror_" + tagn, v8, 0.022, [M["body_white"]]))
        # 받침(어두운) + 거울면
        sa, sb = sorted((x0 - sx * 0.02, x0 + sx * 0.06))
        P.add("fixed", _box("mirror_stalk_" + tagn, sa, sb, y - 0.03, y + 0.03, z - 0.012, z + 0.03, M["cladding"], bevel=0.008))
        P.add("fixed", _box("mirror_glass_" + tagn, xa + 0.02, xb - 0.02, y - sd / 2 - 0.004, y - sd / 2 + 0.004, z + 0.02, z + shh - 0.018,
                            M["mirror"], bevel=0.0))


def _roof_parts(skin, feat, sh, v, M, P):
    L, W, H = v["dims"]
    rf, rr = feat["roof_f"][0], feat["roof_r"][0]
    y1, y0 = rf[T_][1], rr[T_][1]
    xr = min(rf[T_][0], rr[T_][0])
    yc = (y0 + y1) / 2
    rb = sh.get("roof_box", {})
    # 루프레일(fixed)
    if sh.get("roof_rails"):
        for sx in (1, -1):
            xm = sx * (xr + 0.01)
            n = 12
            ys = [y1 - 0.06 - (y1 - y0 - 0.12) * i / (n - 1) for i in range(n)]
            bm = bmesh.new()
            rings = []
            for i, yv in enumerate(ys):
                zr = skin.top_z(xm, yv)
                end = i in (0, n - 1)
                lift = 0.0 if end else 0.045
                rings.append([bm.verts.new((xm + dx, yv, zr + dz)) for (dx, dz) in
                              ((-0.02, lift), (0.02, lift), (0.016, lift + (0.004 if end else 0.03)), (-0.016, lift + (0.004 if end else 0.03)))])
            for i in range(n - 1):
                for j in range(4):
                    j2 = (j + 1) % 4
                    bm.faces.new((rings[i][j], rings[i][j2], rings[i + 1][j2], rings[i + 1][j]))
            bm.faces.new(rings[0])
            bm.faces.new(rings[-1])
            P.add("fixed", _obj("rail_" + ("l" if sx < 0 else "r"), bm, [M["cladding"]], sharp_deg=50.0))
    # 지붕 안테나(fixed)
    if sh.get("antenna", True):
        ya = y0 + 0.16
        za = skin.top_z(0.0, ya) - 0.004
        v8 = [(-0.024, ya - 0.09, za), (0.024, ya - 0.09, za), (0.016, ya + 0.10, za), (-0.016, ya + 0.10, za),
              (-0.010, ya - 0.085, za + 0.065), (0.010, ya - 0.085, za + 0.065), (0.006, ya - 0.02, za + 0.03), (-0.006, ya - 0.02, za + 0.03)]
        P.add("fixed", _pebble("antenna", v8, 0.008, [M["cladding"]]))
    # 루프박스 + 크로스바(roof_box)
    bl = min(rb.get("len", 1.45), (y1 - y0) - 0.30)
    bw = min(rb.get("w", 0.74), 2 * xr - 0.20)
    bh = rb.get("h", 0.30)
    by = yc + rb.get("dy", -0.02)
    bars = (by + 0.30 * bl, by - 0.30 * bl)
    zbar = max(skin.top_z(0.0, yb) for yb in bars) + 0.035
    for i, yb in enumerate(bars):
        xe = xr + 0.015
        P.add("roof_box", _box("crossbar%d" % i, -xe, xe, yb - 0.03, yb + 0.03, zbar, zbar + 0.03, M["roof_box"], bevel=0.008))
        for sx in (1, -1):
            xa, xb2 = sorted((sx * (xe - 0.07), sx * (xe + 0.005)))
            zf = skin.top_z(sx * (xe - 0.03), yb) - 0.01
            P.add("roof_box", _box("barfoot%d%s" % (i, "l" if sx < 0 else "r"), xa, xb2, yb - 0.04, yb + 0.04, zf, zbar + 0.012, M["roof_box"], bevel=0.008))
    z0 = zbar + 0.028
    yf, yb_ = by + bl / 2, by - bl / 2
    v8 = [(-bw / 2 + 0.03, yb_ + 0.04, z0), (bw / 2 - 0.03, yb_ + 0.04, z0), (bw / 2 - 0.06, yf - 0.10, z0), (-bw / 2 + 0.06, yf - 0.10, z0),
          (-bw / 2, yb_, z0 + bh), (bw / 2, yb_, z0 + bh), (bw / 2 - 0.10, yf - 0.02, z0 + 0.55 * bh), (-bw / 2 + 0.10, yf - 0.02, z0 + 0.55 * bh)]
    P.add("roof_box", _pebble("roof_box", v8, 0.07, [M["roof_box"]], levels=3))


def _build_car(v, M, P):
    L, W, H = v["dims"]
    R, WB, tw = v["wheel_r"], v["wheelbase"], v["tire_w"]
    skin, feat, arches, sh = _body_skin(v)
    # 축별 타이어 바깥면 |x|: 플레어가 가장 큰 축의 펜더 끝 = W/2 에서 (그 축과의 플레어 차 + 축의 tire_inset) 만큼 안쪽
    t_out = {s: _tire_out(v, sh, s) for s in (1, -1)}
    x_in = {s: t_out[s] - tw - 0.06 for s in (1, -1)}
    _glass(skin, feat, sh, M, P)
    _cladding(skin, arches, sh, v, M, P)
    _decor(skin, sh.get("decor", []), M, P)
    _twotone(skin, M, P)
    _stripe(skin, sh, M, P)
    P.g["body"].insert(0, _body_and_liners(skin, arches, x_in, M, P))
    for (sx, yc, zc, a, b, n) in arches:
        tagn = "wheel_%s%s" % ("f" if yc > 0 else "r", "l" if sx < 0 else "r")
        _wheel(P, M, tagn, (sx * (t_out[1 if yc > 0 else -1] - tw / 2), yc, R), R, tw, sx)
    _mirrors(feat, sh, M, P)
    _roof_parts(skin, feat, sh, v, M, P)


def build_vehicle(name, vcfg, M):
    """차종 하나를 현재 장면에 만든다. Parts 를 돌려준다."""
    t0 = time.time()
    P = Parts()
    if vcfg.get("style") == "scooter":
        from models_scooter import build_scooter  # 2륜은 models_scooter.py(M2) 몫: build_scooter(name, vcfg, M) -> Parts
        P = build_scooter(name, vcfg, M)
        assert isinstance(P, Parts), "build_scooter 는 models.Parts 를 돌려줘야 한다"
    else:
        _build_car(vcfg, M, P)
    for o in P.g["body"]:
        assert len(o.data.materials) == 1, "body 객체의 재질 슬롯은 1개여야 한다: " + o.name
    P.info = {"name": name, "dims": list(vcfg["dims"]), "objects": len(P.objects()),
              "polygons": int(sum(len(o.data.polygons) for o in P.objects())), "build_s": round(time.time() - t0, 3)}
    return P

