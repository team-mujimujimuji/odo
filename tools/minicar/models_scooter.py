"""2륜(스쿠터) 형상. models.build_vehicle 이 vcfg["style"] == "scooter" 일 때 부른다.

  build_scooter(name, vcfg, M) -> models.Parts

좌표 규칙(C절): +y 가 앞, +x 가 오른쪽, 바닥 z=0, 두 바퀴 사이 가운데가 원점, 단위 m.
body 그룹 객체는 재질 슬롯이 정확히 1개(body_white)다. 패스마다 build_minicar 가 materials[0] 을 바꿔 낀다.

HEAD 의 상자 조립을 다듬은 것이다(C절 "2륜은 기존 방식에서 적당히 다듬는다"):
  - 둥근 부품: 단면(초타원) 링을 이은 로프트 케이지에 SUBSURF 를 적용한다.
  - 앞 전조등 렌즈는 lamp 그룹(marks head), 붉은 후미등 렌즈는 fixed 그룹(marks tail, 발광 재질 tail_lamp).
    둘 다 렌즈만 담은 단독 객체다(글로 패스가 marks 객체의 재질을 통째로 바꿔 끼운다).
  - 휠 3종은 models._wheel 을 좌우 양면에 쓴다(차와 같은 디자인 문법). 타이어만 2륜용 둥근 단면으로 따로 만든다.
  - 꾸미기 층은 HEAD 방식: 투톤 = 레그실드·앞 펜더를 감싸는 얇은 껍질, 루프박스 = 탑케이스,
    데칼 = 몸체 면을 복제해 띄운 줄무늬, 황금 번호판 = 앞(레그실드)·뒤 번호판 위에 얹는 판.
  - 앞: 조향축(캐스터 25°)을 따라 포크 다리 2개가 차축에서 레그실드 안으로 들어가고, 레그실드 앞면·핸들 커버가 같은 축을 따라 눕는다.
    뒤: 왼쪽(hero·옆 뷰가 보는 쪽)은 엔진·변속기 케이스 겸 스윙암(뒷차축까지, 휠 디자인은 위·아래로 보임),
    오른쪽은 가는 스윙암·쇼크·머플러.
로고·엠블럼·글자 없음. 특정 실제 모델을 본뜨지 않은 일반적인 스쿠터 비례다.
"""
import math

import bpy
import bmesh
import numpy as np
from mathutils import Vector
from mathutils.bvhtree import BVHTree


# ---------- 작은 도구 ----------
def _m(M, *keys):
    """재질 dict 에서 처음으로 있는 키(새 재질 키가 없을 때를 대비한 대체)."""
    for k in keys:
        if k in M:
            return M[k]
    raise KeyError(keys)


def _se(t, n):
    """초타원 단위 점(cos, sin 꼴). n=2 원, n 이 클수록 각진 사각형."""
    c, s = math.cos(t), math.sin(t)
    e = 2.0 / n
    return math.copysign(abs(c) ** e, c), math.copysign(abs(s) ** e, s)


def _ring(C, U, V, a, bp, bn=None, n=2.5, N=16, bow=0.0):
    """평면(U, V) 위 초타원 링. a = U 반폭, bp/bn = +V/-V 반높이, bow = 가운데를 +V 로 미는 양(1-u²)."""
    bn = bp if bn is None else bn
    out = []
    for k in range(N):
        u, v = _se(2.0 * math.pi * k / N, n)
        vv = v * (bp if v >= 0 else bn) + bow * (1.0 - u * u)
        out.append(C + U * (a * u) + V * vv)
    return out


def _loft_bm(rings, mat=0):
    """링 목록 → 양 끝을 n각형으로 막은 닫힌 케이지(법선 바깥)."""
    bm = bmesh.new()
    R = [[bm.verts.new(p) for p in r] for r in rings]
    N = len(R[0])
    for A, B in zip(R, R[1:]):
        for k in range(N):
            k2 = (k + 1) % N
            bm.faces.new((A[k], A[k2], B[k2], B[k])).material_index = mat
    bm.faces.new(R[0][::-1]).material_index = mat
    bm.faces.new(R[-1]).material_index = mat
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
    return bm


def _ss_obj(mod, name, bm, mats, levels=2):
    """케이지 → SUBSURF 적용한 매끈한 객체."""
    me = mod._subsurf(bm, levels, name)
    me.name = name
    me.polygons.foreach_set("use_smooth", np.ones(len(me.polygons), bool))
    for m in mats:
        me.materials.append(m)
    me.update()
    return mod._link(bpy.data.objects.new(name, me))


X, Y, Z = Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1))


def _yloft(mod, name, st, mat, n=2.6, N=16, levels=2):
    """y 방향 로프트. st = [(y, zb, zt, a)] (앞→뒤 순서 무관). 단면 가운데 x=0."""
    rings = []
    for (y, zb, zt, a) in st:
        zc = 0.5 * (zb + zt)
        rings.append(_ring(Vector((0.0, y, zc)), X, Z, a, 0.5 * (zt - zb), n=n, N=N))
    return _ss_obj(mod, name, _loft_bm(rings), [mat], levels)


def _tube(mod, name, pts, r, mat, N=10, levels=1, ref=Z):
    """폴리라인을 따라가는 둥근 관(끝은 막힘)."""
    P = [Vector(p) for p in pts]
    rr = r if isinstance(r, (list, tuple)) else [r] * len(P)
    rings = []
    for i, p in enumerate(P):
        t = (P[min(i + 1, len(P) - 1)] - P[max(i - 1, 0)]).normalized()
        u = (ref - t * ref.dot(t)).normalized()
        v = t.cross(u)
        rings.append(_ring(p, u, v, rr[i], rr[i], n=2.0, N=N))
    return _ss_obj(mod, name, _loft_bm(rings), [mat], levels)


def _bvh(ob):
    me = ob.data
    return BVHTree.FromPolygons([v.co.copy() for v in me.vertices], [tuple(p.vertices) for p in me.polygons])


def _frame(Nrm):
    """면 법선 Nrm 에 수직인 (U, V): U 는 x 축(또는 y 축)을 투영, V 는 위쪽을 향하게."""
    Nrm = Nrm.normalized()
    ref = X if abs(Nrm.x) < 0.8 else Y
    U = (ref - Nrm * ref.dot(Nrm)).normalized()
    V = Nrm.cross(U)
    if V.z < 0 or (abs(V.z) < 1e-6 and V.y < 0):
        V = -V
    return U, V


def _proj(mod, name, target, C, Nrm, a, b, mats, n=2.0, dome=0.0, off=0.004, inner=0.0, wall=True, N=32, rings=5,
          mat_wall=None):
    """target 면 위에 붙인 판/렌즈/고리. C 근처를 Nrm 반대 방향 광선으로 투영해 면을 따라 휘게 만든다.
    가운데로 갈수록 dome 만큼 부풀고(렌즈), inner>0 이면 가운데가 빈 고리(베젤). wall 은 가장자리를 면까지 내린 옆벽."""
    bvh = _bvh(target)
    Nrm = Vector(Nrm).normalized()
    U, V = _frame(Nrm)
    C = Vector(C)

    def hit(x, y, o):
        org = C + U * x + V * y + Nrm * 0.25
        loc, no, _i, _d = bvh.ray_cast(org, -Nrm, 1.0)
        assert loc is not None, "투영이 면을 못 맞췄다: %s (%.3f, %.3f)" % (name, x, y)
        if no.dot(Nrm) < 0:
            no = -no
        return loc + no * o

    bm = bmesh.new()
    s_list = [inner + (1.0 - inner) * k / rings for k in range(0 if inner > 0 else 1, rings + 1)]
    uv = [_se(2.0 * math.pi * k / N, n) for k in range(N)]
    R = []
    for s in s_list:
        o = off + dome * (1.0 - s * s)
        R.append([bm.verts.new(hit(a * s * u, b * s * v, o)) for (u, v) in uv])
    faces = []
    if inner <= 0:
        cv = bm.verts.new(hit(0.0, 0.0, off + dome))
        for k in range(N):
            faces.append(bm.faces.new((cv, R[0][k], R[0][(k + 1) % N])))
    for A, B in zip(R, R[1:]):
        for k in range(N):
            k2 = (k + 1) % N
            faces.append(bm.faces.new((A[k], B[k], B[k2], A[k2])))
    walls = []
    if wall:
        edges = [(R[-1], s_list[-1], 1.0)] + ([(R[0], s_list[0], -1.0)] if inner > 0 else [])
        for E, s, side in edges:
            low = [bm.verts.new(hit(a * s * u, b * s * v, 0.0005)) for (u, v) in uv]
            for k in range(N):
                k2 = (k + 1) % N
                walls.append((bm.faces.new((E[k], low[k], low[k2], E[k2])), side))
    # 겉면은 Nrm 쪽, 바깥 옆벽은 판 가운데에서 바깥쪽, 고리 안쪽 옆벽은 가운데(구멍) 쪽을 보게
    mid = sum((v.co for v in bm.verts), Vector()) / len(bm.verts)
    for f in faces:
        f.normal_update()
        if f.normal.dot(Nrm) < 0:
            f.normal_flip()
    for f, side in walls:
        f.normal_update()
        d = f.calc_center_median() - mid
        d -= Nrm * d.dot(Nrm)
        if f.normal.dot(d) * side < 0:
            f.normal_flip()
        if mat_wall is not None:
            f.material_index = 1
    ms = list(mats) + ([mat_wall] if mat_wall is not None else [])
    return mod._obj(name, bm, ms, sharp_deg=50.0, recalc=False)


def _shell(mod, name, src, keep, off, mat, planes=()):
    """src 메시의 면 중 keep(center, normal) 이 참인 것을 꼭짓점 법선 방향으로 off 만큼 띄워 복제하고,
    planes [(점, 법선)] 마다 법선 쪽을 잘라 낸다(가장자리가 곧게 잘린다)."""
    me = src.data
    co = [v.co.copy() for v in me.vertices]
    no = [v.normal.copy() for v in me.vertices]
    bm = bmesh.new()
    vmap = {}
    for p in me.polygons:
        if not keep(Vector(p.center), Vector(p.normal)):
            continue
        vs = []
        for vi in p.vertices:
            bv = vmap.get(vi)
            if bv is None:
                bv = vmap[vi] = bm.verts.new(co[vi] + no[vi] * off)
            vs.append(bv)
        bm.faces.new(vs)
    for (pco, pno) in planes:
        bmesh.ops.bisect_plane(bm, geom=bm.verts[:] + bm.edges[:] + bm.faces[:], dist=1e-6,
                               plane_co=Vector(pco), plane_no=Vector(pno), clear_outer=True)
    for v in [v for v in bm.verts if not v.link_faces]:
        bm.verts.remove(v)
    assert len(bm.faces) > 0, "껍질이 비었다: " + name
    return mod._obj(name, bm, [mat], sharp_deg=None, recalc=False)


def _box(x0, x1, y0, y1, z0, z1):
    return [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]


# ---------- 바퀴 ----------
HUB_SCALE = 1.0    # 휠 디자인 반지름 배율(models._wheel 에 넘기는 R 의 비)


def _tire(mod, P, M, tag, c, R, tw, Rh):
    """2륜 타이어: 둥근 크라운(단면 반원 꼴) + 볼록한 옆벽. 비드 반지름은 휠 디자인(models._wheel, R=Rh)에 맞춘다."""
    xf = tw / 2
    rb = 0.63 * Rh
    h = R - rb
    bul = 0.07 * tw
    rc = rb + 0.48 * h              # 크라운 원호 중심 반지름
    prof = [(-0.80 * xf, rb), (-xf - 0.2 * bul, rb + 0.14 * h), (-xf - 0.8 * bul, rb + 0.32 * h)]
    for i in range(0, 13):
        ph = math.radians(-90 + 180 * i / 12)
        prof.append(((xf + bul) * math.sin(ph), rc + (R - rc) * math.cos(ph)))
    prof += [(xf + 0.8 * bul, rb + 0.32 * h), (xf + 0.2 * bul, rb + 0.14 * h), (0.80 * xf, rb)]
    bm = bmesh.new()
    mod._lathe(bm, prof, 72, -1, c, closed=True)
    P.add("fixed", mod._obj(tag + "_tire", bm, [M["tire"]], sharp_deg=60.0, recalc=False))


# ---------- 본체 ----------
def build_scooter(name, vcfg, M):
    import models as mod
    from models import Parts

    P = Parts()
    R, WB, tw = vcfg["wheel_r"], vcfg["wheelbase"], vcfg["tire_w"]
    yf, yr = WB / 2, -WB / 2
    body = _m(M, "body_white")
    dark = _m(M, "cladding")
    seat_m = _m(M, "seat")
    metal = _m(M, "hub_dark")
    Rh = HUB_SCALE * R

    # 바퀴 2개(x=0): 타이어 1개 + 휠 디자인 좌우 양면(왼쪽 = hero 쪽)
    for wy, tag in ((yf, "f"), (yr, "r")):
        c = (0.0, wy, R)
        _tire(mod, P, M, "wheel_" + tag, c, R, tw, Rh)
        mod._wheel(P, M, "wheel_%s_l" % tag, c, Rh, tw, -1, tire=False)
        mod._wheel(P, M, "wheel_%s_r" % tag, c, Rh, tw, +1, tire=False)

    # 조향축: 앞 차축을 지나 위로 갈수록 뒤로 눕는다(캐스터 25°). 포크·레그실드 앞면·핸들 커버가 이 축을 따른다.
    rake = math.radians(25.0)
    ax_d = Vector((0.0, -math.sin(rake), math.cos(rake)))

    def on_axis(x, t):
        return Vector((x, yf, R)) + ax_d * t

    # 레그실드(앞 방패): z 로프트. 앞면은 앞바퀴 뒤에서 무릎 높이까지 나왔다가 위로 갈수록 축과 나란히 뒤로 눕는다.
    # (z, 앞면 y, 뒷면 y, 반폭, bow). 앞/뒷면은 x=0 에서의 값(bow 포함), 평면에서 가운데가 앞으로 나온 C 꼴.
    ap = [(0.17, 0.300, 0.160, 0.170, 0.000), (0.30, 0.310, 0.160, 0.215, 0.030), (0.42, 0.330, 0.170, 0.240, 0.045),
          (0.52, 0.370, 0.190, 0.250, 0.050), (0.60, 0.440, 0.210, 0.255, 0.055), (0.68, 0.520, 0.240, 0.255, 0.060),
          (0.78, 0.490, 0.270, 0.240, 0.055), (0.88, 0.455, 0.300, 0.195, 0.040), (0.95, 0.430, 0.310, 0.100, 0.020)]
    apron = P.add("body", _ss_obj(mod, "apron", _loft_bm([_ring(Vector((0.0, 0.5 * (yF + yB) - bw, z)), X, Y, a, 0.5 * (yF - yB),
                                                                n=2.8, N=20, bow=bw)
                                                          for (z, yF, yB, a, bw) in ap]), [body], 3))
    # 발판 몸통
    floor = P.add("body", _yloft(mod, "floor", [(0.24, 0.19, 0.36, 0.16), (0.18, 0.15, 0.37, 0.19), (-0.10, 0.15, 0.37, 0.19),
                                                (-0.24, 0.16, 0.37, 0.185), (-0.30, 0.20, 0.35, 0.15)], body, n=4.0))
    # 뒷 몸통: 시트 아래 기둥 → 뒷바퀴 위로 올라가는 옆 패널 → 둥근 꼬리
    # (뒷바퀴 위쪽은 타이어에서 2 cm 넘게 띄운다: 바퀴가 몸통에 박혀 보이지 않게)
    rear = P.add("body", _yloft(mod, "rear_body", [
        (-0.14, 0.24, 0.72, 0.13), (-0.20, 0.18, 0.78, 0.16), (-0.30, 0.17, 0.81, 0.18), (-0.38, 0.36, 0.82, 0.19),
        (-0.45, 0.55, 0.82, 0.19), (-0.55, 0.655, 0.82, 0.19), (-0.70, 0.685, 0.82, 0.185), (-0.84, 0.665, 0.80, 0.165),
        (-0.92, 0.66, 0.78, 0.15), (-0.965, 0.67, 0.755, 0.12)], body, n=2.6, N=20, levels=3))  # 꼬리 끝을 뭉툭하게(큰 후미등 자리)
    # 앞 펜더: 앞바퀴를 감싸는 원호 로프트(단면은 위로 볼록한 얕은 U). 뒤끝은 레그실드 앞면 안으로 들어간다.
    fr, fa, fb = R + 0.050, 0.085, 0.024
    rings = []
    for i in range(12):
        th = math.radians(-38 + (70 + 38) * i / 11)
        rd = Vector((0.0, math.sin(th), math.cos(th)))
        rings.append(_ring(Vector((0.0, yf, R)) + rd * fr, X, rd, fa, fb, n=3.0, N=16, bow=0.016))
    fender = P.add("body", _ss_obj(mod, "front_fender", _loft_bm(rings), [body], 2))
    # 핸들 커버(둥근 머리): 앞 코에 전조등
    hc = [(0.28, 0.080, 0.035, 0.035), (0.31, 0.160, 0.060, 0.060), (0.38, 0.190, 0.070, 0.070), (0.46, 0.160, 0.070, 0.075),
          (0.52, 0.110, 0.065, 0.070), (0.55, 0.070, 0.045, 0.050)]
    zc = 0.965
    cover = P.add("body", _ss_obj(mod, "handle_cover", _loft_bm([_ring(Vector((0.0, y, zc)), X, Z, a, bu, bd, n=2.4, N=20)
                                                                for (y, a, bu, bd) in hc]), [body], 3))

    # 시트(무광 검정): 앞 코 → 운전석 → 동승석 단
    P.add("fixed", _yloft(mod, "seat", [(-0.06, 0.78, 0.86, 0.11), (-0.12, 0.78, 0.90, 0.16), (-0.30, 0.78, 0.91, 0.18),
                                        (-0.48, 0.78, 0.905, 0.18), (-0.56, 0.78, 0.945, 0.17), (-0.72, 0.78, 0.95, 0.155),
                                        (-0.78, 0.78, 0.925, 0.12)], seat_m, n=3.2, N=20, levels=3))
    # 발판 매트(어두운 고무)
    P.add("fixed", _proj(mod, "floor_mat", floor, (0.0, -0.03, 0.37), Z, 0.15, 0.23, [dark], n=6.0, off=0.004))

    # 앞 포크(양쪽): 차축에서 조향축을 따라 올라가 레그실드 앞면 안으로 들어간다. 아래는 굵은 슬라이더, 위는 가는 관.
    # x=±.118 이면 타이어 옆벽(.068)·펜더(.085)·휠 디자인 면(.058) 밖이다. 차축은 두 다리를 잇는다.
    xk = 0.118
    for s, sn in ((-1, "l"), (1, "r")):
        P.add("fixed", _tube(mod, "fork_" + sn, [on_axis(s * xk, t) for t in (-0.035, 0.0, 0.17, 0.21, 0.36, 0.50)],
                             [0.024, 0.029, 0.028, 0.021, 0.020, 0.020], metal, N=12, levels=2, ref=X))
    P.add("fixed", _tube(mod, "front_axle", [(-0.140, yf, R), (0.0, yf, R), (0.140, yf, R)], 0.013, _m(M, "hub_bright"), N=10, ref=Z))

    # 뒤: 왼쪽(hero 쪽) = 엔진·변속기 케이스가 그대로 스윙암인 유닛(뒷차축까지), 오른쪽 = 가는 스윙암·쇼크 1개·머플러.
    # 케이스는 뒷몸통 기둥 안에서 나와 뒷차축 보스에서 끝난다(바퀴 디자인은 위·아래·뒤로 보인다).
    xc = -0.104
    cs = [(-0.26, 0.300, 0.028, 0.060, 0.070), (-0.36, 0.305, 0.028, 0.075, 0.075), (-0.48, 0.315, 0.026, 0.068, 0.070),
          (-0.60, 0.320, 0.025, 0.058, 0.060), (-0.68, 0.320, 0.024, 0.048, 0.050), (-0.72, 0.320, 0.020, 0.034, 0.036)]
    P.add("fixed", _ss_obj(mod, "drive_case", _loft_bm([_ring(Vector((xc, y, zc_)), X, Z, a, bu, bd, n=2.6, N=16)
                                                        for (y, zc_, a, bu, bd) in cs]), [metal], 2))
    P.add("fixed", _tube(mod, "rear_axle", [(-0.142, yr, R), (0.0, yr, R), (0.142, yr, R)], 0.013, _m(M, "hub_bright"), N=10, ref=Z))
    P.add("fixed", _tube(mod, "swing_arm_r", [(0.10, -0.30, 0.29), (0.10, -0.48, 0.31), (0.10, yr, R), (0.10, yr - 0.04, R)],
                         [0.022, 0.024, 0.026, 0.020], metal, N=12, levels=2, ref=Z))
    P.add("fixed", _tube(mod, "shock_rod", [(0.112, -0.62, 0.34), (0.117, -0.555, 0.50), (0.122, -0.49, 0.66)], 0.012, metal,
                         N=10, ref=X))
    P.add("fixed", _tube(mod, "shock_spring", [(0.113, -0.605, 0.38), (0.116, -0.565, 0.475), (0.119, -0.525, 0.57)], 0.024,
                         _m(M, "hub_bright"), N=12, ref=X))
    # 머플러: 몸통 폭 안쪽으로 붙이고 밝은 금속(뒤 45° 에서 어두운 원판 = 두 번째 바퀴로 읽히지 않게)
    P.add("fixed", _tube(mod, "muffler", [(0.150, -0.33, 0.22), (0.170, -0.42, 0.27), (0.176, -0.56, 0.30), (0.176, -0.74, 0.33),
                                          (0.172, -0.79, 0.335)], [0.020, 0.034, 0.038, 0.038, 0.030], _m(M, "hub"), N=14, levels=2,
                         ref=X))
    P.add("fixed", _tube(mod, "muffler_tip", [(0.172, -0.782, 0.336), (0.171, -0.812, 0.338)], 0.017, metal, N=10, ref=X))

    # 핸들바·그립·레버·미러(핸들 커버와 함께 조향축 위로 옮김)
    yb, zb = 0.37, 0.975
    P.add("fixed", _tube(mod, "handlebar", [(-0.24, yb, zb), (0.0, yb, zb), (0.24, yb, zb)], 0.014, metal, ref=Z))
    for s, sn in ((-1, "l"), (1, "r")):
        P.add("fixed", _tube(mod, "grip_" + sn, [(s * 0.205, yb, zb), (s * 0.27, yb, zb), (s * 0.325, yb, zb)], [0.021, 0.024, 0.022],
                             seat_m, ref=Z))
        # 레버: 그립 앞, 그립과 같은 높이(옆에서 그립 위 눈썹처럼 보이지 않게)
        P.add("fixed", _tube(mod, "lever_" + sn, [(s * 0.19, yb + 0.035, zb - 0.002), (s * 0.25, yb + 0.050, zb - 0.006),
                                                   (s * 0.30, yb + 0.045, zb - 0.010)], 0.0065, metal, N=8, ref=Z))
        P.add("fixed", _tube(mod, "mirror_stem_" + sn, [(s * 0.15, 0.39, 1.01), (s * 0.20, 0.395, 1.05), (s * 0.235, 0.40, 1.075)],
                             0.008, metal, N=8, ref=Y))
        mx, my, mz = s * 0.255, 0.40, 1.085
        head = P.add("fixed", mod._pebble("mirror_" + sn, _box(mx - 0.056, mx + 0.056, my - 0.018, my + 0.018, mz - 0.034, mz + 0.034),
                                          0.016, [dark]))
        P.add("fixed", _proj(mod, "mirror_glass_" + sn, head, (mx, my - 0.018, mz), -Y, 0.044, 0.024, [_m(M, "mirror")],
                             n=3.0, off=0.0015, wall=False))

    # 계기판(핸들 커버 위 뒤쪽)
    P.add("fixed", _proj(mod, "cluster", cover, (0.0, 0.345, zc + 0.07), Z, 0.065, 0.032, [_m(M, "glass")], n=3.0, off=0.003))

    # 전조등: 핸들 커버 코의 둥근 렌즈(lamp, marks head) + 어두운 베젤(fixed)
    lz = zc - 0.004
    P.add("fixed", _proj(mod, "head_bezel", cover, (0.0, 0.54, lz), Y, 0.060, 0.056, [_m(M, "lamp_housing")], inner=0.80,
                         off=0.007, rings=2))
    lens = _proj(mod, "head_lens", cover, (0.0, 0.54, lz), Y, 0.049, 0.046, [_m(M, "lamp_lens", "lamp")], dome=0.012, off=0.004)
    P.add("lamp", lens)
    P.marks["head"].append(lens)

    # 후미등: 꼬리 끝 모서리(뒷면 → 윗면)를 감싸는 큰 붉은 렌즈(fixed, 발광, marks tail) + 하우징.
    # 렌즈를 뒤·위 55° 방향에서 투영한다: navi(뒤 45°)·top(위) 둘 다 거의 정면이라 표시 크기(35 px/m)에서 4 px 이상.
    tn = Vector((0.0, -math.cos(math.radians(55.0)), math.sin(math.radians(55.0))))
    tc = (0.0, -0.90, 0.77)   # 아래 끝이 꼬리 맨 끝 모서리, 위 끝이 윗면(y 약 -.83), 시트 끝(y -.78)보다 뒤
    P.add("fixed", _proj(mod, "tail_housing", rear, tc, tn, 0.114, 0.082, [_m(M, "tail_housing", "lamp_housing")],
                         n=3.2, off=0.004, rings=3))
    tl = _proj(mod, "tail_lens", rear, tc, tn, 0.100, 0.074, [_m(M, "tail_lamp", "lamp")], n=3.2, dome=0.008, off=0.008, rings=4)
    P.add("fixed", tl)
    P.marks["tail"].append(tl)

    # 뒤 번호판: 꼬리 밑에서 내려온 어두운 받침(hugger) 끝에 위로 40° 젖혀 단다(navi 에서 거의 정면 → 높이 4 px 이상).
    # 크기 0.22 x 0.12 m(이륜차 번호판 비율). 받침 윗부분은 꼬리 아랫면 안으로 들어간다.
    pt = math.radians(40.0)
    pn = Vector((0.0, -math.cos(pt), math.sin(pt)))   # 번호판 법선(뒤·위)
    pv = Vector((0.0, math.sin(pt), math.cos(pt)))    # 번호판 위쪽(앞·위)
    pc = Vector((0.0, -0.99, 0.585))                  # 번호판 면 가운데
    slab = [tuple(pc + X * x + pn * n + pv * v) for v in (-0.085, 0.13) for (x, n) in
            ((-0.125, -0.045), (0.125, -0.045), (0.125, 0.0), (-0.125, 0.0))]
    hug = P.add("fixed", mod._pebble("plate_bracket", slab, 0.018, [dark]))
    P.add("fixed", _proj(mod, "plate_rear", hug, pc, pn, 0.11, 0.06, [_m(M, "plate_blank", "overlay_white")],
                         n=7.0, off=0.003, rings=2))
    P.add("plate_gold", _proj(mod, "plate_gold_rear", hug, pc, pn, 0.11, 0.06, [M["plate_gold"]], n=7.0,
                              off=0.007, rings=2))
    # 앞 황금 번호판: 레그실드 앞면 가운데(앞에는 빈 번호판이 없다 → 꾸미기를 켰을 때만 보인다)
    P.add("plate_gold", _proj(mod, "plate_gold_front", apron, (0.0, 0.45, 0.80), Y, 0.072, 0.040, [M["plate_gold"]], n=7.0,
                              off=0.010, rings=3))

    # 투톤(HEAD 방식): 레그실드·앞 펜더를 통째로 감싸는 얇은 껍질
    white = M["overlay_white"]
    P.add("roof_twotone", _shell(mod, "twotone_apron", apron, lambda c, n: True, 0.003, white))
    P.add("roof_twotone", _shell(mod, "twotone_fender", fender, lambda c, n: True, 0.003, white))

    # 데칼 줄무늬(띄운 복제면, 곧게 잘린 가장자리)
    P.add("decal_stripe", _shell(mod, "decal_apron", apron, lambda c, n: n.y > 0.25, 0.006, white,
                                 planes=[((-0.158, 0, 0), X), ((-0.200, 0, 0), -X), ((0, 0, 0.84), Z), ((0, 0, 0.32), -Z)]))
    axle = Vector((0.0, yf, R))
    P.add("decal_stripe", _shell(mod, "decal_fender", fender, lambda c, n: n.dot((c - axle).normalized()) > 0.5, 0.006, white,
                                 planes=[((0.022, 0, 0), X), ((-0.022, 0, 0), -X)]))
    P.add("decal_stripe", _shell(mod, "decal_cover", cover, lambda c, n: n.z > 0.3, 0.004, white,
                                 planes=[((0.018, 0, 0), X), ((-0.018, 0, 0), -X), ((0, 0.515, 0), Y), ((0, 0.415, 0), -Y)]))
    P.add("decal_stripe", _shell(mod, "decal_side", rear, lambda c, n: abs(n.x) > 0.35, 0.004, white,
                                 planes=[((0, 0, 0.74), Z), ((0, 0, 0.70), -Z), ((0, -0.30, 0), Y), ((0, -0.86, 0), -Y)]))

    # 탑케이스(roof_box): 받침 + 상자 + 뚜껑 이음 띠
    rb_m = M["roof_box"]
    P.add("roof_box", mod._pebble("box_rack", _box(-0.14, 0.14, -0.98, -0.82, 0.79, 0.88), 0.02, [dark]))
    case = P.add("roof_box", mod._pebble("top_case", _box(-0.19, 0.19, -1.03, -0.80, 0.87, 1.085), 0.065, [rb_m], levels=3))
    P.add("roof_box", _shell(mod, "top_case_seam", case, lambda c, n: abs(n.z) < 0.7, 0.002, metal,
                             planes=[((0, 0, 0.989), Z), ((0, 0, 0.975), -Z)]))
    return P
