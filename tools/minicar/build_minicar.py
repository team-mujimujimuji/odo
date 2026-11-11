"""오도 미니카 레이어 렌더러 v2: 장면·카메라·조명·레이어 패스 (Blender 4.5 headless, Cycles CPU).

실행:
  Blender -b -t 6 -P tools/minicar/build_minicar.py -- --vehicle all --view all --layers all --out <dir> --samples 128
  선택: --navi-pitch 38|45|52   navi 뷰 내려다보는 각(기본 45, 아래 NAVI_PITCH_DEG)
        --stats                레이어 밝기·테두리 통계(조명 보정, 잘림 확인)

형상·재질은 models.py(GROUPS, Parts, build_materials, build_vehicle; 2륜은 models_scooter.py)가 만들고 이 파일은 장면만 맡는다.
출력: <out>/<차종>/<뷰>/<레이어>.png + <out>/render_meta.json. 뷰 3개(hero·navi·top) × 레이어 14개에서 뷰별 skip 을 뺀다.

레이어 합성 규칙(앱 compose.json 의 order 와 같다):
  그리기 순서가 앞인 레이어의 객체는 is_holdout(가리기만 하고 투명), 뒤인 레이어의 객체는 숨김.
  → 아래 레이어는 항상 완전한 면이고 위 레이어만 잘리므로 SrcOver 합성에 이음새가 없다.
  얇은 덧칠(OVERLAY_GROUPS: 투톤·스트라이프, 차체 위 2~3 mm 껍질)은 차체 바로 뒤에 그리고 다른 패스에서는
  holdout 대신 숨긴다(가림은 그 아래 차체 holdout 이 똑같이 한다). 덧칠이 레일·유리·박스 밑에 온전히 깔리고
  앞 물체가 나중에 그려지므로 경계에 차체 색이 새지 않고, 고르지 않은 꾸미기가 다른 꾸미기에 구멍·그림자를 내지 않는다.
  뷰가 skip 하는 레이어의 그룹은 그 뷰의 모든 패스에서 숨긴다(앱이 그리지 않으므로 가리지도 않는다).
차종별 조정(vehicles.json 차종 항목): hero_zoom(hero 카메라 당김), marker_zoom(navi·top 배율. 2륜을 지도에서
  알아보게 키운다. 앵커 자리는 그대로), view_overrides.<뷰>(그 뷰 설정 위에 깊게 덮어씀, 예: 2륜 글로 웅덩이 크기.
  값이 null 이면 그 키를 지운다).
뷰 glow: head/tail = 바닥 스폿(없으면 렌즈 번짐만), bloom_gain = 번짐 세기, bloom_scale = 번짐 반경 배수.
  hero 는 바닥 스폿을 두지 않는다(18° 카메라가 앞 오버행 밑 바닥까지 보는데 차 밖 스폿은 거기를 못 비춰
  빛 웅덩이가 범퍼에서 떨어져 보인다 → 렌즈 번짐만).
shadow: 바닥 shadow catcher + 차량 visible_camera=False. 뷰의 shadow_lights 가 "world" 면 리그를 끄고
  world 가림(접지 그림자)만 남긴다. 뷰의 shadow_gain 은 그림자 알파 배수(번짐은 world 의 shadow 램프가 정한다).
조명 receivers: "glass" 인 리그 조명은 유리 재질(M["glass"]) 객체만 비춘다(Cycles 라이트 링킹). 유리(반사율 약 3%)에
  하늘 반사를 넣을 만큼 밝은 조명이 도장(5~7%, 클리어코트)을 하얗게 날리지 않게 하려는 것.
조명 kind "glass_card": 유리 한 면(front|rear|side, 카메라를 향한 유리 면을 법선 방향으로 가름)의 거울 방향에 세로
  그라디언트 발광 판을 놓는다(add_glass_card). 면 조명은 반사에서 고른 밝기라 평평한 유리를 한 색으로만 칠하므로,
  유리 아래 끝 → 위 끝이 판의 ramp 0 → 1 에 맞게 판 크기·자리를 형상에서 잰다(형상이 바뀌어도 그대로 맞는다).
glow_head / glow_tail(밤 전용): world 와 리그를 끄고 차는 holdout, 바닥은 보이게 두고 렌즈(Parts.marks)만
  발광시키고 렌즈 위치에 스폿을 둔다. 선형 EXR 로 받아 렌즈 번짐을 더한 뒤 **검은 배경 위 RGB**(알파 255) PNG 로 쓴다.
  postprocess.py 가 a=max(r,g,b), rgb=rgb/a 로 바꿔 앱 형식(RGB=빛 색, 알파=세기)으로 저장한다.
시드 7, OpenImageDenoise(CPU) → 같은 기기에서 다시 돌리면 바이트 동일.
"""
import argparse
import json
import math
import os
import struct
import sys
import time
import zlib

sys.dont_write_bytecode = True  # 저장소에 __pycache__ 를 남기지 않는다(.pyc 에 계정 경로가 들어간다)

import bpy
import numpy as np
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Euler, Vector

HERE = os.path.dirname(os.path.abspath(__file__))
LAYERS = ["shadow", "body", "spec", "fixed", "wheel_steel", "wheel_dia", "wheel_aero", "lamp",
          "roof_twotone", "roof_box", "decal_stripe", "plate_gold", "glow_head", "glow_tail"]
ORDER = ["shadow", "body", "spec", "roof_twotone", "decal_stripe", "fixed", "wheel", "lamp", "roof_box",
         "plate_gold", "glow_head", "glow_tail"]
OVERLAY_GROUPS = ("roof_twotone", "decal_stripe")  # 차체에 붙은 얇은 덧칠: 다른 패스에서 holdout 하지 않는다
GLOW = {"glow_head": "head", "glow_tail": "tail"}  # 레이어 → Parts.marks 키
SHADOW_GROUPS = ("body", "fixed", "wheel_steel")  # 그림자를 드리우는 그룹
GLOW_GROUPS = ("body", "fixed", "wheel_steel", "lamp")  # 글로 패스에서 빛을 가리는 그룹(선택 레이어는 뺀다)
SENSOR_MM = 36.0
# navi(글랜스) 뷰 내려다보는 각. 2026-11-11 앱 캡처에서 38·45·52 를 견주어 45 로 정했다:
# 글랜스 지도 기울기 실측 약 43~46°와 맞고 지붕·뒷유리·후미등이 고르게 보인다. 38 은 지붕 앞이 정반사로 하얗게 뜨고
# 차가 지면에서 일어선 듯 보이며, 52 는 후면이 가는 띠로 줄고 밤 후미등 번짐이 작아진다.
NAVI_PITCH_DEG = 45.0


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
    p.add_argument("--stats", action="store_true", help="레이어 밝기·테두리 통계 출력(조명 보정, 잘림 확인)")
    p.add_argument("--navi-pitch", type=float, default=NAVI_PITCH_DEG,
                   help="navi 뷰 내려다보는 각(도). 기본 %(default)s(후보 비교로 고름, NAVI_PITCH_DEG 주석)")
    return p.parse_args(argv)


# ---------- 색·장면 재질 ----------
def srgb_to_linear(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def hex_lin(h):
    h = h.lstrip("#")
    return tuple(srgb_to_linear(int(h[i:i + 2], 16) / 255.0) for i in (0, 2, 4))


def diffuse_mat(name, rgb, roughness=1.0, specular=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*rgb, 1.0)
    b.inputs["Roughness"].default_value = roughness
    b.inputs["Specular IOR Level"].default_value = specular
    return m


def emission_mat(name, rgb, strength):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    em = nt.nodes.new("ShaderNodeEmission")
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    em.inputs["Color"].default_value = (*rgb, 1.0)
    em.inputs["Strength"].default_value = strength
    nt.links.new(em.outputs["Emission"], out.inputs["Surface"])
    return m


def scene_materials(spec):
    """장면이 가진 재질: 바닥, 글로 패스용 바닥·렌즈 발광."""
    lighting = spec["lighting"]
    glow = lighting["glow"]
    g = glow["floor_gray"]
    S = {
        "floor": diffuse_mat("scene_floor", hex_lin(lighting.get("floor_gray_hex", "#4A4A4A")), roughness=0.9, specular=0.2),
        "glow_floor": diffuse_mat("glow_floor", (g, g, g)),
    }
    for kind in GLOW.values():
        S["glow_" + kind] = emission_mat("glow_emit_" + kind, hex_lin(glow[kind]["hex"]), glow[kind]["lens_emission"])
    return S


def link(ob):
    bpy.context.scene.collection.objects.link(ob)
    return ob


# ---------- 장면 ----------
def dir_from(az_deg, elev_deg):
    """방위(0 = 차 앞 +y, 양수 = 왼쪽 -x 로 돈다)와 고도에서 원점→바깥 단위 벡터."""
    az, el = math.radians(az_deg), math.radians(elev_deg)
    return Vector((-math.cos(el) * math.sin(az), math.cos(el) * math.cos(az), math.sin(el)))


def aim_rotation(direction):
    """-Z 축이 direction 을 보게 하는 회전. 수직이면 롤이 정해지지 않으므로 고정값을 쓴다."""
    if abs(direction.x) < 1e-6 and abs(direction.y) < 1e-6:
        return (0.0, 0.0, 0.0) if direction.z < 0 else (math.pi, 0.0, 0.0)
    return direction.to_track_quat("-Z", "Y").to_euler()


def setup_scene(spec, samples):
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
    set_png_output(sc)
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
    return sc


def set_png_output(sc):
    ims = sc.render.image_settings
    ims.file_format = "PNG"
    ims.color_mode = "RGBA"
    ims.color_depth = "8"
    ims.compression = 50


def set_exr_output(sc):
    ims = sc.render.image_settings
    ims.file_format = "OPEN_EXR"
    ims.color_mode = "RGBA"
    ims.color_depth = "32"
    ims.exr_codec = "ZIP"


class World:
    """노드로 만든 그라데이션 환경(외부 HDRI 없음). 고도(z)에 따른 밝기 램프 2개를 광선 종류로 나눠 쓴다.
    디퓨즈·카메라 광선: 천정이 밝은 돔 → 차체 디퓨즈의 부드러운 위쪽 빛, 접지 그림자가 차 가까이에만 생긴다.
    글로시 광선: 지평선 띠가 밝은 스튜디오 → 도장·유리에 비치는 반사."""

    def __init__(self, cfg):
        self.cfg = cfg
        w = bpy.data.worlds.new("world")
        bpy.context.scene.world = w
        w.use_nodes = True
        nodes, links = w.node_tree.nodes, w.node_tree.links
        bg = nodes["Background"]
        tc = nodes.new("ShaderNodeTexCoord")
        sep = nodes.new("ShaderNodeSeparateXYZ")
        links.new(tc.outputs["Generated"], sep.inputs["Vector"])  # world 에서는 바라보는 방향 벡터
        mr = nodes.new("ShaderNodeMapRange")
        mr.inputs["From Min"].default_value = -1.0
        mr.inputs["From Max"].default_value = 1.0
        mr.inputs["To Min"].default_value = 0.0
        mr.inputs["To Max"].default_value = 1.0
        links.new(sep.outputs["Z"], mr.inputs["Value"])

        def ramp(stops):
            n = nodes.new("ShaderNodeValToRGB")
            cr = n.color_ramp
            cr.interpolation = "LINEAR"
            els = cr.elements
            while len(els) > 1:
                els.remove(els[-1])
            stops = sorted(stops)
            els[0].position = (stops[0][0] + 1.0) / 2.0
            els[0].color = (stops[0][1],) * 3 + (1.0,)
            for z, g in stops[1:]:
                e = els.new((z + 1.0) / 2.0)
                e.color = (g, g, g, 1.0)
            links.new(mr.outputs["Result"], n.inputs["Fac"])
            return n

        rd, rg = ramp(cfg["diffuse"]["stops"]), ramp(cfg["glossy"]["stops"])
        # 그림자 패스 전용 디퓨즈 램프(천정에 몰린 빛): 차가 하늘을 가리는 범위가 차 가까이로 줄어 접지 그림자가 짧게 끊긴다.
        rs = ramp(cfg.get("shadow", cfg["diffuse"])["stops"])
        dsel = nodes.new("ShaderNodeMix")
        dsel.data_type = "RGBA"
        dsel.blend_type = "MIX"
        dsel.inputs[0].default_value = 0.0
        links.new(rd.outputs["Color"], dsel.inputs[6])
        links.new(rs.outputs["Color"], dsel.inputs[7])
        self.shadow_sel = dsel
        lp = nodes.new("ShaderNodeLightPath")
        tint = nodes.new("ShaderNodeMix")  # 글로시 램프 × 색조
        tint.data_type = "RGBA"
        tint.blend_type = "MULTIPLY"
        tint.inputs[0].default_value = 1.0
        links.new(rg.outputs["Color"], tint.inputs[6])
        tint.inputs[7].default_value = (*hex_lin(cfg["glossy"].get("tint_hex", "#FFFFFF")), 1.0)
        pick = nodes.new("ShaderNodeMix")  # 글로시 광선이면 글로시 쪽
        pick.data_type = "RGBA"
        pick.blend_type = "MIX"
        links.new(lp.outputs["Is Glossy Ray"], pick.inputs[0])
        links.new(dsel.outputs[2], pick.inputs[6])  # 디퓨즈 쪽 = 평소 램프 또는 그림자 램프
        links.new(tint.outputs[2], pick.inputs[7])
        links.new(pick.outputs[2], bg.inputs["Color"])
        self.strength = nodes.new("ShaderNodeMath")  # 세기 = 디퓨즈 + is_glossy × (글로시 - 디퓨즈)
        self.strength.operation = "MULTIPLY_ADD"
        links.new(lp.outputs["Is Glossy Ray"], self.strength.inputs[0])
        links.new(self.strength.outputs["Value"], bg.inputs["Strength"])
        self.set()

    def set(self, diffuse=None, glossy=None, shadow=False):
        """shadow=True 면 디퓨즈 광선이 그림자 램프(lighting.world.shadow)를 본다(세기는 shadow.strength)."""
        if shadow:
            d = self.cfg.get("shadow", self.cfg["diffuse"])["strength"] if diffuse is None else diffuse
        else:
            d = self.cfg["diffuse"]["strength"] if diffuse is None else diffuse
        g = self.cfg["glossy"]["strength"] if glossy is None else glossy
        self.shadow_sel.inputs[0].default_value = 1.0 if shadow else 0.0
        self.strength.inputs[1].default_value = g - d
        self.strength.inputs[2].default_value = d


def receiver_sets(P, M):
    """라이트 링킹 받는 쪽 묶음. glass = 유리 재질 슬롯이 있는 객체(앞·뒤·옆 유리, 거울 유리)."""
    coll = bpy.data.collections.new("lk_glass")
    for objs in P.g.values():
        for o in objs:
            if o.type == "MESH" and any(m is M["glass"] for m in o.data.materials):
                coll.objects.link(o)
    if not len(coll.objects):  # 빈 묶음이면 receivers glass 조명은 아무것도 비추지 않는다(glass_card 는 건너뜀)
        print("  WARN 유리 재질 객체가 없다: receivers glass 조명·glass_card 는 효과 없음", flush=True)
    return {"glass": coll}


def add_area(cfg, receivers=None):
    """면 조명. size_m 이 [가로, 세로]면 직사각형(가로 = 수평 방향). diffuse/glossy 로 어느 광선에 보일지 고른다.
    receivers 이름이 있으면 그 묶음의 객체만 비춘다(receiver_sets)."""
    data = bpy.data.lights.new(cfg["name"], "AREA")
    size = cfg["size_m"]
    if isinstance(size, (int, float)):
        data.shape = "SQUARE"
        data.size = size
    else:
        data.shape = "RECTANGLE"
        data.size, data.size_y = size
    data.energy = cfg["energy_w"]
    data.color = hex_lin(cfg.get("hex", "#FFFFFF"))
    ob = link(bpy.data.objects.new(cfg["name"], data))
    d = dir_from(cfg["azimuth_deg"], cfg["elev_deg"])
    ob.location = Vector(cfg.get("target", (0.0, 0.0, 0.0))) + d * cfg["distance_m"]
    ob.rotation_euler = aim_rotation(-d)
    ob.visible_camera = False
    ob.visible_diffuse = cfg.get("diffuse", True)
    ob.visible_glossy = cfg.get("glossy", True)
    if "receivers" in cfg:
        ob.light_linking.receiver_collection = receivers[cfg["receivers"]]
    return ob


def glass_faces(coll, M, side, cam):
    """coll 의 유리 재질 면 중 카메라를 향하고 법선이 side(front: +y, rear: -y, side: ±x 가 우세)인 면.
    작은 조각(거울 유리 등, 그 쪽 넓이가 가장 큰 객체의 20% 미만)은 뺀다. → [(넓이, 중심, 법선, 꼭짓점들)]"""
    dg = bpy.context.evaluated_depsgraph_get()
    cam_dir = cam.matrix_world.to_3x3() @ Vector((0.0, 0.0, -1.0))
    per_obj = []
    for o in coll.objects:
        oe = o.evaluated_get(dg)
        me = oe.to_mesh()
        mw = o.matrix_world
        nm = mw.to_3x3().inverted().transposed()
        slots = [i for i, m in enumerate(o.data.materials) if m is M["glass"]]
        faces = []
        for poly in me.polygons:
            if poly.material_index not in slots:
                continue
            c = mw @ poly.center
            n = (nm @ poly.normal).normalized()
            v = cam_dir if cam.data.type == "ORTHO" else (c - cam.matrix_world.translation).normalized()
            if n.dot(v) >= 0.0:
                continue
            cls = "front" if n.y > abs(n.x) else "rear" if -n.y > abs(n.x) else "side"
            if cls == side:
                faces.append((poly.area * mw.median_scale ** 2, c, n, [mw @ me.vertices[i].co for i in poly.vertices]))
        oe.to_mesh_clear()
        if faces:
            per_obj.append(faces)
    if not per_obj:
        return []
    top = max(sum(f[0] for f in fs) for fs in per_obj)
    return [f for fs in per_obj if sum(x[0] for x in fs) >= 0.2 * top for f in fs]


def card_material(cfg, g0, g1):
    """판 재질: 생성 좌표 y(판 아래 0 → 위 1)의 ramp 세기 × hex 발광 + 투명(링킹 밖 광선은 뚫고 지나간다).
    ramp 위치(유리 아래 끝 0 → 위 끝 1)를 판 좌표 g0..g1 로 옮긴다."""
    mat = bpy.data.materials.new(cfg["name"])
    mat.use_nodes = True
    nt = mat.node_tree
    nt.nodes.clear()
    tc = nt.nodes.new("ShaderNodeTexCoord")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    cr = nt.nodes.new("ShaderNodeValToRGB")
    mul = nt.nodes.new("ShaderNodeMath")
    mul.operation = "MULTIPLY"
    em = nt.nodes.new("ShaderNodeEmission")
    tr = nt.nodes.new("ShaderNodeBsdfTransparent")
    add = nt.nodes.new("ShaderNodeAddShader")
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    stops = cfg["ramp"]
    vmax = max(s[1] for s in stops)
    els = cr.color_ramp.elements
    while len(els) < len(stops):
        els.new(0.5)
    for el, (pos, val) in zip(els, stops):
        el.position = g0 + (g1 - g0) * pos
        el.color = (val / vmax, val / vmax, val / vmax, 1.0)
    mul.inputs[1].default_value = vmax
    em.inputs["Color"].default_value = (*hex_lin(cfg.get("hex", "#FFFFFF")), 1.0)
    nt.links.new(tc.outputs["Generated"], sep.inputs[0])
    nt.links.new(sep.outputs["Y"], cr.inputs["Fac"])
    nt.links.new(cr.outputs["Color"], mul.inputs[0])
    nt.links.new(mul.outputs[0], em.inputs["Strength"])
    nt.links.new(em.outputs[0], add.inputs[0])
    nt.links.new(tr.outputs[0], add.inputs[1])
    nt.links.new(add.outputs[0], out.inputs["Surface"])
    return mat


def add_glass_card(cfg, receivers, cam, M):
    """kind glass_card: 유리 cfg["glass"] 쪽의 거울 방향(카메라→유리 중심 광선을 넓이 가중 평균 법선으로 반사)으로
    distance_m 떨어진 곳에, 그 방향을 마주 보는 발광 판을 놓는다. 유리 꼭짓점을 반사 방향으로 판에 옮겨 잰 범위에
    margin_m 를 더한 크기로 만들고, ramp 0 = 유리 아래(낮은) 끝, 1 = 위 끝. 유리만 비춘다(receivers glass).
    판은 투명+발광이라 링킹 밖 광선(도장의 반사 등)은 그대로 뚫고 지나가고, 카메라·확산·그림자 광선엔 아예 안 보인다."""
    faces = glass_faces(receivers["glass"], M, cfg["glass"], cam)
    if not faces:  # 그 쪽 유리가 없는 형상(2륜 등)·뷰면 판 없이 넘어간다
        print(f"  WARN glass_card {cfg['name']}: 카메라를 향한 {cfg['glass']} 유리 면이 없어 건너뜀", flush=True)
        return None
    area = sum(f[0] for f in faces)
    c = sum((f[1] * f[0] for f in faces), Vector()) / area
    n = sum((f[2] * f[0] for f in faces), Vector()).normalized()
    v = (cam.matrix_world.to_3x3() @ Vector((0.0, 0.0, -1.0)) if cam.data.type == "ORTHO"
         else (c - cam.matrix_world.translation)).normalized()
    r = (v - 2.0 * v.dot(n) * n).normalized()
    rot = Euler(aim_rotation(-r))
    rm = rot.to_matrix()
    ax, ay = rm.col[0].normalized(), rm.col[1].normalized()
    cc = c + r * cfg["distance_m"]
    xs, ys, zs = [], [], []
    for f in faces:
        for p in f[3]:
            q = p + r * (cc - p).dot(r)
            xs.append((q - cc).dot(ax))
            ys.append((q - cc).dot(ay))
            zs.append(p.z)
    m = cfg.get("margin_m", 0.3)
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    w, h = x1 - x0 + 2 * m, y1 - y0 + 2 * m
    # 반사가 천정 가까이로 가면 판 세로축이 유리 위아래와 거꾸로 설 수 있다 → 유리 꼭짓점 높이와 판 세로 좌표가
    # 반대로 움직이면 ramp 를 뒤집어 늘 유리 아래 끝 = ramp 0 이 되게 한다.
    zm, ym = sum(zs) / len(zs), sum(ys) / len(ys)
    flip = sum((z - zm) * (y - ym) for z, y in zip(zs, ys)) < 0.0
    ccfg = dict(cfg, ramp=[[1.0 - p, val] for p, val in reversed(cfg["ramp"])]) if flip else cfg
    # 반사가 가파르게 아래를 보면(위에서 본 거의 선 뒷유리) 판이 바닥 밑으로 들어가 바닥(글로시 광선엔 보임)에
    # 가려진다. 그때 유리는 바닥을 비추는 게 맞아서 그대로 둔다(판을 끌어올리면 뒤끝에 흰 줄이 생긴다).
    me = bpy.data.meshes.new(cfg["name"])
    me.from_pydata([(-w / 2, -h / 2, 0.0), (w / 2, -h / 2, 0.0), (w / 2, h / 2, 0.0), (-w / 2, h / 2, 0.0)], [],
                   [(0, 1, 2, 3)])
    me.materials.append(card_material(ccfg, m / h, 1.0 - m / h))
    ob = link(bpy.data.objects.new(cfg["name"], me))
    ob.location = cc + ax * (x0 + x1) / 2 + ay * (y0 + y1) / 2
    ob.rotation_euler = rot
    ob.visible_camera = False
    ob.visible_diffuse = False
    ob.visible_shadow = False
    ob.visible_transmission = False
    ob.visible_volume_scatter = False
    ob.visible_glossy = True
    ob.light_linking.receiver_collection = receivers["glass"]
    el = math.degrees(math.asin(max(-1.0, min(1.0, r.z))))
    az = math.degrees(math.atan2(-r.x, r.y))
    print(f"  glass_card {cfg['name']}: {cfg['glass']} 넓이 {area:.2f} m² 면 {len(faces)} 반사 방위 {az:.0f} 고도 {el:.0f}"
          f" 판 {w:.2f}x{h:.2f} m 아래끝 z {min(ob.location.z + (ax * sx * w + ay * sy * h).z / 2 for sx in (-1, 1) for sy in (-1, 1)):.2f}"
          f"{' ramp 뒤집음' if flip else ''}", flush=True)
    return ob


def add_light(cfg, receivers, cam, M):
    if cfg.get("kind") == "glass_card":
        return add_glass_card(cfg, receivers, cam, M)
    return add_area(cfg, receivers)


def remove_lights(obs):
    for ob in obs:
        data = ob.data
        bpy.data.objects.remove(ob, do_unlink=True)
        if isinstance(data, bpy.types.Light):
            bpy.data.lights.remove(data)
        else:
            mats = [m for m in data.materials if m]
            bpy.data.meshes.remove(data)
            for m in mats:
                bpy.data.materials.remove(m)


def make_floor(mat):
    me = bpy.data.meshes.new("scene_floor")
    me.from_pydata([Vector((-40, -40, 0)), Vector((40, -40, 0)), Vector((40, 40, 0)), Vector((-40, 40, 0))], [], [(0, 1, 2, 3)])
    ob = link(bpy.data.objects.new("scene_floor", me))
    ob.data.materials.append(mat)
    return ob


# ---------- 카메라 ----------
def make_camera(view, zoom, pitch):
    """persp: 원점을 보는 원근 카메라. px_per_m 이 있으면 원점 깊이에서 px_per_m × zoom 배율이 되도록 거리를 정한다
    (없으면 distance_m / zoom). ortho: 바로 위에서 내려다보는 정사영(차 앞 +y 가 화면 위), px_per_m × zoom.
    센서 맞춤은 항상 가로."""
    cam_data = bpy.data.cameras.new("cam")
    cam_data.sensor_fit = "HORIZONTAL"
    cam_data.sensor_width = SENSOR_MM
    cam_data.clip_start = 0.1
    cam_data.clip_end = 200
    cam = link(bpy.data.objects.new("cam", cam_data))
    w_px = view["px"][0]
    if view.get("camera", "persp") == "ortho":
        cam_data.type = "ORTHO"
        cam_data.ortho_scale = w_px / (view["px_per_m"] * zoom)
        d, dist = Vector((0.0, 0.0, 1.0)), 30.0
    else:
        cam_data.lens = view["focal_mm"]
        d = dir_from(view["azimuth_deg"], view["elev_deg"] if pitch is None else pitch)
        if "px_per_m" in view:
            dist = cam_data.lens / SENSOR_MM * w_px / (view["px_per_m"] * zoom)
        else:
            dist = view["distance_m"] / zoom
    cam.location = d * dist
    cam.rotation_euler = aim_rotation(-d)
    bpy.context.scene.camera = cam
    return cam


def project(cam, p, view):
    """기준 해상도(view.px) 픽셀 좌표로 투영(반올림 없음)."""
    u, v, _ = world_to_camera_view(bpy.context.scene, cam, Vector(p))
    return (u * view["px"][0], (1 - v) * view["px"][1])


def r1(xy):
    return [round(xy[0], 1), round(xy[1], 1)]


def aim_origin(cam, view):
    """shift 로 바닥 원점(0,0,0)을 origin_px(기준 해상도 px)에 놓는다. 해상도 배율과 무관(정규화 좌표).
    shift 단위는 센서 맞춤 변(가로) 기준이다."""
    cam.data.shift_x = 0.0
    cam.data.shift_y = 0.0
    bpy.context.view_layer.update()
    W, Hh = view["px"]
    x0, y0 = project(cam, (0, 0, 0), view)
    cam.data.shift_x = (x0 - view["origin_px"][0]) / W
    cam.data.shift_y = (view["origin_px"][1] - y0) / W
    bpy.context.view_layer.update()
    x1, y1 = project(cam, (0, 0, 0), view)
    err = math.hypot(x1 - view["origin_px"][0], y1 - view["origin_px"][1])
    assert err < 0.02, f"앵커가 origin_px 에 놓이지 않았다: ({x1:.3f}, {y1:.3f}) 오차 {err:.3f}px"
    return (x1, y1)


def px_scale(cam, view, axis):
    """원점에서 axis 방향 1 m 가 화면에서 몇 px 인지(±0.5 m 두 점의 거리)."""
    a = Vector(axis) * 0.5
    p0, p1 = project(cam, -a, view), project(cam, a, view)
    return math.hypot(p1[0] - p0[0], p1[1] - p0[1])


def bbox_world(objs):
    pts = [o.matrix_world @ Vector(c) for o in objs for c in o.bound_box]
    lo = Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts)))
    hi = Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
    return lo, hi, pts


def view_info(cam, view, v, P, origin):
    """render_meta.json 에 남기는 기준점. HEAD 의 키(origin_px, shift, near_wheel/nose/tail/roof)는 같은 식으로 두고
    배율과 실제 차체 범위(body+fixed 경계 상자)를 더한다."""
    right = cam.matrix_world.to_3x3() @ Vector((1.0, 0.0, 0.0))
    lo, hi, pts = bbox_world([o for g in ("body", "fixed") for o in P.g[g]])
    proj = [project(cam, p, view) for p in pts]
    L, W, H = v["dims"]
    return {
        "origin_px": r1(origin),
        "shift": [round(cam.data.shift_x, 5), round(cam.data.shift_y, 5)],
        "near_wheel_ground_px": r1(project(cam, (-(W / 2 + 0.025), v["wheelbase"] / 2, 0.0), view)),
        "nose_ground_px": r1(project(cam, (0.0, L / 2, 0.0), view)),
        "tail_ground_px": r1(project(cam, (0.0, -L / 2, 0.0), view)),
        "roof_px": r1(project(cam, (0.0, 0.0, H), view)),
        "px_per_m": round(px_scale(cam, view, right), 2),  # 원점 깊이, 화면 가로 방향
        "px_per_m_fwd": round(px_scale(cam, view, (0, 1, 0)), 2),  # 바닥에서 차 앞 방향
        "px_per_m_up": round(px_scale(cam, view, (0, 0, 1)), 2),
        "bbox_m": [[round(c, 3) for c in lo], [round(c, 3) for c in hi]],  # body+fixed 의 실제 범위
        "bbox_px": r1((min(p[0] for p in proj), min(p[1] for p in proj))) + r1((max(p[0] for p in proj), max(p[1] for p in proj))),
        "skip": list(view.get("skip", [])),
    }


# ---------- 레이어 패스 ----------
def rank(name):
    key = "wheel" if name.startswith("wheel") else ("body" if name == "spec" else name)
    return ORDER.index(key)


def group_mode(g, layer, baked):
    """레이어 패스에서 그룹 g 를 어떻게 둘지: show / holdout / hide / cast(카메라에 안 보이고 그림자만).
    baked = 이 뷰에서 다른 레이어에 함께 굽는 그룹({그룹: 레이어})."""
    if layer == "shadow":
        return "cast" if g in SHADOW_GROUPS else "hide"
    if layer in GLOW:
        return "holdout" if g in GLOW_GROUPS else "hide"
    g = baked.get(g, g)
    if g == layer or (layer == "spec" and g == "body"):
        return "show"
    if g in OVERLAY_GROUPS:  # 덧칠은 차체 holdout 이 대신 가린다. holdout 으로 두면 안 고른 덧칠이 구멍·그림자를 낸다
        return "hide"
    if g.startswith("wheel"):  # 휠 디자인 3종은 같은 자리. 뒤 레이어에서는 steel 이 대신 가린다.
        return "holdout" if (g == "wheel_steel" and not layer.startswith("wheel") and rank("wheel") < rank(layer)) else "hide"
    return "holdout" if rank(g) < rank(layer) else "hide"


def configure(layer, P, hidden, baked, floor, M, S, rig, rig_cfg, world, view):
    """레이어별 가시성·재질·조명. hidden = 이 뷰가 건너뛰는 그룹(모든 패스에서 숨김)."""
    glow = layer in GLOW
    for g, objs in P.g.items():
        mode = "hide" if g in hidden else group_mode(g, layer, baked)
        for o in objs:
            o.hide_render = mode == "hide"
            o.is_holdout = mode == "holdout"
            o.visible_camera = mode != "cast"
            # 글로 패스: 차는 바닥에 빛을 내거나(뒤 램프 발광·렌즈) 되비추지 않는다 → 바닥 빛은 스폿만 만든다.
            # 그림자 광선은 그대로(스폿 빛이 차를 뚫고 지나가지 않게).
            o.visible_diffuse = not glow
            o.visible_glossy = not glow
            o.visible_transmission = not glow
            o.visible_shadow = True
    floor.hide_render = False
    floor.is_shadow_catcher = layer == "shadow"
    floor.visible_camera = layer == "shadow" or glow
    floor.data.materials[0] = S["glow_floor"] if glow else S["floor"]
    mat = M["body_spec"] if layer == "spec" else M["body_white"] if layer == "shadow" else M["body_diffuse"]
    for o in P.g["body"]:
        o.data.materials[0] = mat
    world_only = layer == "shadow" and view.get("shadow_lights", "all") == "world"
    for lt, cfg in rig:
        lt.hide_render = glow or world_only or (layer == "shadow" and not cfg.get("shadow_pass", True))
    if glow:
        world.set(0.0, 0.0)
    elif layer == "shadow":
        world.set(rig_cfg.get("world_shadow"), 0.0, shadow=True)
    else:
        world.set(rig_cfg.get("world_diffuse"), rig_cfg.get("world_glossy"))


def render(sc, path):
    sc.render.filepath = path
    bpy.context.view_layer.update()  # 가시성·홀드아웃·재질 변경을 렌더 전에 확정
    bpy.ops.render.render(write_still=True)


# ---------- 글로 패스 ----------
def glow_spots(kind, marks, gcfg, vcfg, tip):
    """바닥을 비추는 스폿. center=true 면 렌즈 전체의 가운데에 1개(빛 웅덩이 하나), 아니면 렌즈 묶음(왼쪽·오른쪽)마다 1개.
    axis_m 이 있으면 원뿔 축이 범퍼 밑에서 axis_m 떨어진 바닥에 닿고(0 이면 바로 아래) 먼 가장자리가 reach_m 에 닿는다
    → 가장 밝은 곳이 범퍼에 붙고 바깥으로 옅어진다(축보다 안쪽 절반은 차 밑이라 보이지 않는다).
    axis_m 이 없으면 바닥에 닿는 범위가 near_m ~ reach_m 이 되도록 기울기와 원뿔 각을 정한다.
    tip = 차 형상의 맨 앞(head)·맨 뒤(tail) y. 스폿은 렌즈와 tip 중 더 바깥에서 offset_m 떨어진 곳에 둔다
    (2륜처럼 렌즈가 앞바퀴·펜더보다 안쪽에 있으면 차 자신이 스폿을 가려 빛이 바닥에 닿지 않는다)."""
    sgn = 1.0 if kind == "head" else -1.0
    sides = {}
    for o in marks:
        pts = [o.matrix_world @ Vector(c) for c in o.bound_box]
        cx = sum(p.x for p in pts) / len(pts)
        side = 0 if vcfg.get("center") else (-1 if cx < -0.05 else 1 if cx > 0.05 else 0)
        sides.setdefault(side, []).extend(pts)
    if len(sides) > 1:
        sides.pop(0, None)  # 좌우가 있으면 가운데 렌즈는 좌우 스폿이 대신한다(좌우 대칭 유지)
    spots = []
    for side in sorted(sides):
        pts = sides[side]
        x = 0.0 if vcfg.get("center") else (min(p.x for p in pts) + max(p.x for p in pts)) / 2
        z = (min(p.z for p in pts) + max(p.z for p in pts)) / 2
        y = sgn * max(sgn * p.y for p in pts + [Vector((0.0, tip, 0.0))]) + sgn * gcfg["offset_m"]
        a_far = math.atan2(z, vcfg["reach_m"])
        if "axis_m" in vcfg:
            pitch = math.atan2(z, vcfg["axis_m"])
            cone = 2.0 * (pitch - a_far)
        else:
            a_near = math.atan2(z, vcfg["near_m"])
            pitch, cone = (a_far + a_near) / 2, a_near - a_far
        slant = z / math.sin(pitch)  # 렌즈에서 스폿 축이 바닥에 닿는 점까지
        data = bpy.data.lights.new(f"glow_{kind}_{side}", "SPOT")
        # 렌즈 높이·기울기와 무관하게 빛 웅덩이 밝기가 같도록 맞춘다(바닥 조도 ∝ 세기·sin(pitch)/slant²).
        data.energy = vcfg["level_w"] * slant * slant / math.sin(pitch)
        data.color = hex_lin(gcfg["hex"])
        data.spot_size = cone
        data.spot_blend = gcfg["blend"]
        data.shadow_soft_size = 0.03
        ob = link(bpy.data.objects.new(data.name, data))
        ob.location = (x, y, z)
        ob.rotation_euler = aim_rotation(Vector((0.0, sgn * math.cos(pitch), -math.sin(pitch))))
        ob.scale = (vcfg.get("spread", 1.0), 1.0, 1.0)  # 좌우로 넓힌 타원 원뿔
        ob.visible_camera = False
        spots.append(ob)
    return spots


def load_pixels(path):
    """이미지를 (H, W, C) float32 로 읽는다(위쪽 행이 먼저, 색 변환 없음)."""
    img = bpy.data.images.load(path, check_existing=False)
    img.colorspace_settings.name = "Non-Color"
    w, h, c = img.size[0], img.size[1], img.channels
    buf = np.empty(w * h * c, dtype=np.float32)
    img.pixels.foreach_get(buf)
    bpy.data.images.remove(img)
    return buf.reshape(h, w, c)[::-1].copy()


def gauss_blur(a, sigma):
    r = min(int(math.ceil(3 * sigma)), (min(a.shape[0], a.shape[1]) - 1) // 2)
    if r < 1:
        return a
    x = np.arange(-r, r + 1, dtype=np.float64)
    k = np.exp(-0.5 * (x / sigma) ** 2)
    k /= k.sum()
    out = np.apply_along_axis(lambda m: np.convolve(m, k, mode="same"), 1, a)
    return np.apply_along_axis(lambda m: np.convolve(m, k, mode="same"), 0, out)


def edge_ramp(h, w, width):
    """테두리에서 0, width px 안쪽부터 1 인 (H, W) 마스크(smoothstep)."""
    yy, xx = np.mgrid[0:h, 0:w]
    t = np.clip(np.minimum(np.minimum(xx, w - 1 - xx), np.minimum(yy, h - 1 - yy)) / max(1.0, width), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def feather_png(path, width, gain=1.0):
    """렌더한 RGBA PNG 의 알파만 테두리에서 0 으로 낮춘다(그림자 레이어: 잡음·코너 잔광이 지도 위에 선으로 남지 않게).
    gain = 알파 배수(1 에서 자름. 그림자를 짙게 할 때. 선형이라 바닥 잡음은 postprocess 의 바닥 자르기가 그대로 지운다).
    RGB 는 그대로 둔다. 페더 전 테두리 알파 최대(8bit)를 돌려준다."""
    px = load_pixels(path)
    img8 = np.rint(px * 255.0).astype(np.uint8)
    a = img8[:, :, 3].astype(np.float64)
    border = int(max(a[0].max(), a[-1].max(), a[:, 0].max(), a[:, -1].max()))
    a = np.minimum(a * gain, 255.0)
    img8[:, :, 3] = np.rint(a * edge_ramp(a.shape[0], a.shape[1], width)).astype(np.uint8)
    write_png(path, img8)
    return border


def write_png(path, img8):
    """(H, W, 3|4) uint8 → RGBA 8bit PNG(3채널이면 알파 255). 메타데이터 없음 → 바이트 동일."""
    h, w, c = img8.shape
    rgba = img8 if c == 4 else np.dstack([img8, np.full((h, w, 1), 255, dtype=np.uint8)])
    raw = b"".join(b"\x00" + rgba[y].tobytes() for y in range(h))

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) +
                chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def to_srgb(lin):
    lin = np.clip(lin, 0.0, 1.0)
    return np.where(lin <= 0.0031308, lin * 12.92, 1.055 * np.power(lin, 1 / 2.4) - 0.055)


def glow_shape(img8, kind, info, view, k):
    """글로 모양 수치(8bit 최대 채널 기준). navi·top(차 앞이 화면 위)은 차 가운데 세로 띠(±0.3 m)의 행별 최대로
    앞(head)·뒤(tail) 빛이 범퍼에서 얼마나 뻗는지(reach_m, 8/255 넘는 마지막 행), 가장 밝은 값(peak),
    범퍼 바로 앞 0.2 m 의 밝기 / peak(attach, 빛이 차에 붙어 있는지)를 잰다. k = 해상도 배율."""
    m = img8.max(axis=2).astype(np.float64)
    if view.get("camera") != "ortho" and view.get("azimuth_deg") != 180:
        return "peak=%d" % m.max()
    ox = info["origin_px"][0] * k
    band = max(1, int(round(0.3 * info["px_per_m"] * k)))
    col = m[:, max(0, int(ox) - band):int(ox) + band + 1].max(axis=1)
    fwd = info["px_per_m_fwd"] * k
    if kind == "head":
        edge = int(round(info["nose_ground_px"][1] * k))
        prof = col[:edge][::-1]  # 범퍼에서 앞으로(화면 위로)
    else:
        edge = int(round(info["tail_ground_px"][1] * k))
        prof = col[edge:]
    lit = np.nonzero(prof > 8)[0]
    if prof.size == 0 or lit.size == 0:
        return "reach_m=0 peak=0 attach=0"
    near = prof[:max(1, int(round(0.2 * fwd)))].max()
    return "reach_m=%.2f peak=%d attach=%.2f" % ((lit[-1] + 1) / fwd, prof.max(), near / max(prof.max(), 1.0))


def render_glow(sc, path, kind, P, hidden, S, glow_cfg, view, info, k):
    """글로 한 장. 선형 EXR 로 렌더 → 렌즈(1.0 을 넘는 픽셀)에서 번짐을 만들어 더함 → 테두리 페더 → sRGB PNG.
    info = 이 뷰의 view_info(기준 해상도 px), k = 해상도 배율. 통계 문자열을 돌려준다."""
    ppm = info["px_per_m"] * k  # 이 해상도에서 원점 깊이의 px/m(번짐 반경을 m 로 주기 위해)
    gcfg, vcfg = glow_cfg[kind], view.get("glow", {}).get(kind)
    marks = P.marks.get(kind, [])
    sgn = 1.0 if kind == "head" else -1.0
    tip = sgn * max(sgn * (o.matrix_world @ Vector(c)).y for objs in P.g.values() for o in objs
                    if o.type == "MESH" for c in o.bound_box)
    spots = glow_spots(kind, marks, gcfg, vcfg, tip) if (marks and vcfg) else []
    lens_groups = {id(o) for g, objs in P.g.items() if g not in hidden for o in objs}
    saved = []
    for o in marks:
        if id(o) not in lens_groups:
            continue  # 이 뷰가 건너뛰는 레이어의 렌즈는 숨긴 채 둔다(스폿 위치로만 쓴다)
        o.is_holdout = False
        o.hide_render = False
        saved.append((o, [s.material for s in o.material_slots]))
        if not o.material_slots:
            o.data.materials.append(S["glow_" + kind])
        for s in o.material_slots:
            s.material = S["glow_" + kind]
    tmp = path[:-4] + ".tmp.exr"
    set_exr_output(sc)
    try:
        render(sc, tmp)
    finally:
        set_png_output(sc)
        for o, mats in saved:
            for s, m in zip(o.material_slots, mats):
                s.material = m
        remove_lights(spots)
    lin = load_pixels(tmp)[:, :, :3].astype(np.float64)
    os.remove(tmp)
    lens = lin.max(axis=2) > 1.0  # 렌즈 자체(HDR)만 번진다. 바닥 빛은 1.0 아래.
    # 번짐 원천은 렌즈 밝기를 cap 으로 자른 값: 렌즈가 넓게 보이는 뷰(hero)에서 번짐이 차 전체를 덮지 않게.
    hot = np.minimum(lin, glow_cfg.get("bloom_cap", 1e9)) * lens[:, :, None]
    gain = view.get("glow", {}).get("bloom_gain", 1.0)
    # bloom_scale = 번짐 반경 배수(렌즈가 작은 2륜은 번짐이 차체보다 넓어지지 않게 줄인다)
    bscale = view.get("glow", {}).get("bloom_scale", 1.0)
    out = lin.copy()
    for b in glow_cfg["bloom"]:
        out += gain * b["gain"] * gauss_blur(hot, b["sigma_m"] * bscale * ppm)
    h, w, _ = out.shape
    border_pre = max(out[0].max(), out[-1].max(), out[:, 0].max(), out[:, -1].max())
    # 스프라이트 테두리에서 0 이 되게(지도 위에서 잘린 선이 보이지 않게)
    out *= edge_ramp(h, w, glow_cfg["edge_fade_px"] * k)[:, :, None]
    img8 = np.rint(to_srgb(out) * 255.0).astype(np.uint8)
    write_png(path, img8)
    floor = np.where(lens, 0.0, lin.max(axis=2))
    return "spots=%d lens_px=%d floor_peak8=%d lit>8=%.3f border_pre8=%d %s" % (
        len(spots), int(lens.sum()), int(round(to_srgb(floor.max()) * 255)), (img8.max(axis=2) > 8).mean(),
        int(round(to_srgb(border_pre) * 255)), glow_shape(img8, kind, info, view, k))


# ---------- 통계 ----------
def image_stats(path, layer):
    px = load_pixels(path)
    a = px[:, :, 3]
    border = max(a[0].max(), a[-1].max(), a[:, 0].max(), a[:, -1].max())  # 0 이 아니면 스프라이트 테두리에서 잘린 것
    if layer == "shadow":
        return "amax=%.3f a>0.1=%.3f border_a8=%d" % (a.max(), (a > 0.1).mean(), int(round(border * 255)))
    m = a > 0.5
    if not m.any():
        return "empty border_a=%.3f" % border
    lum = px[:, :, :3].max(axis=2)[m]
    return "cover=%.3f p50=%.3f p90=%.3f p99=%.3f max=%.3f border_a=%.3f" % (
        m.mean(), *np.percentile(lum, [50, 90, 99]), lum.max(), border)


# ---------- 메인 ----------
def deep_merge(base, over):
    """base 위에 over 를 깊게 덮어쓴 새 dict(둘 다 dict 인 키만 안으로 들어간다). over 값이 null(None)이면 그 키를 지운다
    (예: 2륜 navi 의 후미등 바닥 웅덩이를 끄기). base 는 바꾸지 않는다."""
    out = dict(base)
    for k, val in over.items():
        if val is None:
            out.pop(k, None)
        else:
            out[k] = deep_merge(out[k], val) if isinstance(val, dict) and isinstance(out.get(k), dict) else val
    return out


def main():
    args = parse_args()
    with open(args.spec, "r", encoding="utf-8") as f:
        spec = json.load(f)
    if HERE not in sys.path:
        sys.path.insert(0, HERE)
    import models  # 형상·재질(같은 폴더). sys.dont_write_bytecode 가 위에서 켜져 있어 .pyc 를 남기지 않는다.
    vehicles = list(spec["vehicles"].keys()) if args.vehicle == "all" else args.vehicle.split(",")
    views = list(spec["views"].keys()) if args.view == "all" else args.view.split(",")
    layers = LAYERS if args.layers == "all" else args.layers.split(",")
    for l in layers:
        assert l in LAYERS, l
    lighting = spec["lighting"]
    os.makedirs(args.out, exist_ok=True)
    meta = {"version": 2, "blender": bpy.app.version_string, "samples": args.samples, "seed": spec.get("seed", 7),
            "vehicles": {}}
    meta["navi_pitch_deg"] = args.navi_pitch
    t_all = time.time()
    count = 0
    for vid in vehicles:
        v = spec["vehicles"][vid]
        sc = setup_scene(spec, args.samples)
        world = World(lighting["world"])
        M = models.build_materials(spec)
        S = scene_materials(spec)
        floor = make_floor(S["floor"])
        P = models.build_vehicle(vid, v, M)
        for g in P.g:
            assert g in LAYERS and g not in GLOW and g not in ("shadow", "spec"), f"모르는 그룹: {g}"
        for o in P.g["body"]:
            assert len(o.data.materials) == 1, f"body 객체 {o.name} 의 재질 슬롯이 {len(o.data.materials)}개다(1개여야 한다)"
        receivers = receiver_sets(P, M)
        bpy.context.view_layer.update()
        meta["vehicles"][vid] = {"views": {}}
        for view_name in views:
            view = deep_merge(spec["views"][view_name], v.get("view_overrides", {}).get(view_name, {}))
            sc.render.resolution_x = int(round(view["px"][0] * args.scale))
            sc.render.resolution_y = int(round(view["px"][1] * args.scale))
            zoom = v.get("hero_zoom", 1.0) if view_name == "hero" else v.get("marker_zoom", 1.0)
            cam = make_camera(view, zoom, args.navi_pitch if view_name == "navi" else None)
            origin = aim_origin(cam, view)
            info = view_info(cam, view, v, P, origin)
            meta["vehicles"][vid]["views"][view_name] = info
            print(f"[{vid}/{view_name}] {json.dumps(info)}")
            rig_cfg = lighting["rigs"][view.get("rig", view_name)]
            bpy.context.view_layer.update()  # 카메라 행렬(glass_card 가 쓴다)
            rig = [(lt, c) for lt, c in ((add_light(c, receivers, cam, M), c) for c in rig_cfg["lights"]) if lt]
            skip = set(view.get("skip", []))
            # 휠 3종을 모두 건너뛰는 뷰(navi·top)에서는 휠을 고를 수 없으므로 기본 휠(타이어 포함)을 fixed 에 함께 굽는다.
            baked = {"wheel_steel": "fixed"} if {g for g in P.g if g.startswith("wheel")} <= skip else {}
            hidden = {g for g in P.g if g in skip and g not in baked}
            outdir = os.path.join(args.out, vid, view_name)
            os.makedirs(outdir, exist_ok=True)
            for layer in layers:
                if layer in skip:
                    continue
                configure(layer, P, hidden, baked, floor, M, S, rig, rig_cfg, world, view)
                path = os.path.join(outdir, layer + ".png")
                t0 = time.time()
                extra = ""
                if layer in GLOW:
                    extra = " " + render_glow(sc, path, GLOW[layer], P, hidden, S, lighting["glow"], view,
                                              info, args.scale)
                else:
                    render(sc, path)
                    if layer == "shadow":
                        extra = " border_pre8=%d" % feather_png(
                            path, view.get("shadow_edge_fade_px", lighting.get("shadow_edge_fade_px", 8)) * args.scale,
                            view.get("shadow_gain", 1.0))
                    if args.stats:
                        extra += " " + image_stats(path, layer)
                count += 1
                print(f"[{vid}/{view_name}/{layer}] {time.time() - t0:.1f}s{extra}", flush=True)
            remove_lights([lt for lt, _ in rig])
            bpy.data.objects.remove(cam, do_unlink=True)
    with open(os.path.join(args.out, "render_meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1, sort_keys=True)
    print(f"total {time.time() - t_all:.1f}s for {count} images ({len(vehicles)} vehicles x {len(views)} views)")


if __name__ == "__main__":
    main()
