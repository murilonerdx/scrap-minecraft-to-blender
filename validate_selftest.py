"""Validates the synthetic .glb files produced by `gradlew gltfSelfTest` — checks the structure the
Blender importer relies on (skin, multi-object meshes, vertex colors, PBR textures, multi-camera, sun,
single- and multi-clip animations) WITHOUT launching Minecraft. Usage: python validate_selftest.py build/selftest"""
import json, struct, sys, os

fails = []


def check(cond, msg):
    print(("  ok  " if cond else " FAIL ") + msg)
    if not cond:
        fails.append(msg)


def parse_glb(path):
    with open(path, "rb") as f:
        data = f.read()
    magic, ver, length = struct.unpack_from("<III", data, 0)
    assert magic == 0x46546C67, f"{path}: bad magic"
    assert ver == 2, f"{path}: bad version {ver}"
    assert length == len(data), f"{path}: header len {length} != file {len(data)}"
    off, chunks = 12, {}
    while off < len(data):
        clen, ctype = struct.unpack_from("<II", data, off)
        off += 8
        chunks[ctype] = data[off:off + clen]
        off += clen
    raw_json = chunks[0x4E4F534A].decode("utf-8")
    # JSON has no NaN/Infinity — Blender rejects "Bad glTF: json contained NaN". The writer must
    # sanitise every float, so the literal must never appear.
    check("NaN" not in raw_json and "Infinity" not in raw_json,
          f"{os.path.basename(path)}: JSON has no NaN/Infinity literal")
    js = json.loads(raw_json)
    return js, len(chunks.get(0x004E4942, b""))


def validate_common(name, js, bin_len):
    print(f"\n=== {name} ===")
    nodes = js.get("nodes", [])
    names = [n.get("name") for n in nodes]
    check(len(nodes) >= 3, f"nodes present ({len(nodes)}): {names}")
    for b in ("root", "body", "head"):
        check(b in names, f"bone node '{b}' present")
    # bone hierarchy: the writer turns parentIndex into node children (mount-parenting relies on this)
    idx = {n.get("name"): i for i, n in enumerate(nodes)}
    if "root" in idx and "body" in idx and "head" in idx:
        check(idx["body"] in (nodes[idx["root"]].get("children") or []), "'body' is a child of 'root'")
        check(idx["head"] in (nodes[idx["body"]].get("children") or []), "'head' is a child of 'body'")
    # #16 retarget: bone nodes carry a humanoid label in extras (root->Hips, body->Spine, head->Head)
    body_node = next((n for n in nodes if n.get("name") == "body"), None)
    if body_node is not None:
        check((body_node.get("extras") or {}).get("retarget") == "Spine",
              "body bone carries retarget extras 'Spine'")
    skins = js.get("skins", [])
    check(len(skins) == 1, f"exactly one skin ({len(skins)})")
    if skins:
        check(len(skins[0]["joints"]) == 3, f"skin has 3 joints ({len(skins[0]['joints'])})")
        check("inverseBindMatrices" in skins[0], "skin has inverseBindMatrices")
    meshes = js.get("meshes", [])
    check(len(meshes) == 2, f"2 mesh groups (Body/Head) -> {len(meshes)}")
    colored = 0
    for m in meshes:
        for p in m["primitives"]:
            at = p["attributes"]
            for req in ("POSITION", "NORMAL", "TEXCOORD_0", "JOINTS_0", "WEIGHTS_0"):
                check(req in at, f"{m.get('name')} prim has {req}")
            if "COLOR_0" in at:
                colored += 1
            check("indices" in p and "material" in p, f"{m.get('name')} prim has indices+material")
    check(colored >= 1, f"at least one primitive has COLOR_0 ({colored})")
    mats = js.get("materials", [])
    check(len(mats) == 2, f"2 materials ({len(mats)})")
    modes = [mm.get("alphaMode") for mm in mats]
    check("BLEND" in modes, f"a material is alphaMode BLEND (translucent) -> {modes}")
    check("MASK" in modes, f"a material is alphaMode MASK (cutout) -> {modes}")
    check(any((mm.get("pbrMetallicRoughness", {}).get("baseColorFactor") or [1, 1, 1, 1])[3] < 1.0 for mm in mats),
          "a material has fade alpha (baseColorFactor < 1, for ghosts)")
    check(any("normalTexture" in mm for mm in mats), "a material has normalTexture")
    # textureless coloured glow (beacon beams): an emissiveFactor that isn't plain white and has no emissiveTexture
    check(any((mm.get("emissiveFactor") not in (None, [1, 1, 1])) and "emissiveTexture" not in mm for mm in mats),
          "a material has a coloured emissiveFactor without a texture (beam glow)")
    check(any("metallicRoughnessTexture" in mm.get("pbrMetallicRoughness", {}) for mm in mats),
          "a material has metallicRoughnessTexture")
    check(len(js.get("images", [])) == 4, f"4 images (2 base + normal + mr) -> {len(js.get('images', []))}")
    cams = js.get("cameras", [])
    check(len(cams) == 3, f"3 cameras (POV + 2 presets) -> {len(cams)}")
    check(len([n for n in nodes if "camera" in n]) == 3, "3 camera nodes")
    check(any(n.get("name") == "cam_hero" for n in nodes), "named (placed) camera node 'cam_hero' present")
    check(any("dof_focus" in (n.get("extras") or {}) for n in nodes), "a camera node carries DOF extras")
    # every camera node rotation must be a NON-degenerate quaternion (a NaN/zero look becomes identity,
    # never [0,0,0,0] which has no orientation); buildRig injects a NaN-rotation camera to exercise this
    for cnode in [n for n in nodes if "camera" in n]:
        rot = cnode.get("rotation")
        if rot is not None:
            ln = sum(c * c for c in rot) ** 0.5
            check(ln > 0.5, f"camera '{cnode.get('name')}' rotation is non-degenerate ({ln:.4f})")
    check("KHR_lights_punctual" in js.get("extensions", {}), "KHR_lights_punctual present")
    check("KHR_lights_punctual" in js.get("extensionsUsed", []), "KHR_lights_punctual in extensionsUsed")
    llist = js.get("extensions", {}).get("KHR_lights_punctual", {}).get("lights", [])
    types = [l.get("type") for l in llist]
    check(types.count("directional") == 1, f"1 directional sun -> {types}")
    check(types.count("point") == 2, f"2 point lights (lamps) -> {types}")
    check(len([n for n in nodes if (n.get("name") or "").startswith("Lamp_")]) == 2, "2 Lamp_ light nodes")
    spk = [n for n in nodes if (n.get("name") or "").startswith("Speaker_")]
    check(len(spk) == 1, f"1 Speaker_ node (sound emitter) -> {len(spk)}")
    check(spk and "sound" in (spk[0].get("extras") or {}) and "time" in spk[0]["extras"],
          "Speaker node carries sound+time extras")
    accs, bvs = js.get("accessors", []), js.get("bufferViews", [])
    ok = all(0 <= a.get("bufferView", -1) < len(bvs) for a in accs) \
        and all(bv["byteOffset"] + bv["byteLength"] <= bin_len for bv in bvs)
    check(ok, "all accessors->bufferViews valid and within the BIN chunk")


root = sys.argv[1] if len(sys.argv) > 1 else "build/selftest"

js, bl = parse_glb(os.path.join(root, "static.glb"))
validate_common("static.glb", js, bl)
check(len(js.get("animations", [])) == 0, "static has no animations")

js, bl = parse_glb(os.path.join(root, "single.glb"))
validate_common("single.glb", js, bl)
anims = js.get("animations", [])
check(len(anims) == 1, f"single has 1 animation ({len(anims)})")
if anims:
    check(len(anims[0]["channels"]) > 0 and len(anims[0]["samplers"]) > 0, "single anim has channels+samplers")
    cam_nodes = [i for i, n in enumerate(js["nodes"]) if "camera" in n]
    targets = [c["target"]["node"] for c in anims[0]["channels"]]
    check(any(t in cam_nodes for t in targets), "single anim animates the POV camera node")

js, bl = parse_glb(os.path.join(root, "library.glb"))
validate_common("library.glb", js, bl)
anims = js.get("animations", [])
check(len(anims) == 4, f"library has 4 animations ({len(anims)})")
libnames = [a.get("name") for a in anims]
for n in ("idle", "walk", "run", "spin"):
    check(n in libnames, f"library has '{n}' clip")
for a in anims:
    check(len(a["channels"]) > 0 and len(a["samplers"]) > 0, f"clip '{a.get('name')}' has channels+samplers")


def _clip_time_max(js, anim):
    accs = js["accessors"]
    s = anim["samplers"][anim["channels"][0]["sampler"]]
    return accs[s["input"]]["max"][0]


# slow-mo time remap (#15): 'idle' was tagged timeScale=2, so its keyframe times stretch to ~2.0,
# while a normal clip ('walk') stays ~1.0
_by_name = {a.get("name"): a for a in anims}
if "idle" in _by_name and "walk" in _by_name:
    idle_max = _clip_time_max(js, _by_name["idle"])
    walk_max = _clip_time_max(js, _by_name["walk"])
    check(abs(idle_max - 2.0) < 1e-3, f"slow-mo clip 'idle' times stretched to ~2.0 -> {idle_max}")
    check(abs(walk_max - 1.0) < 1e-3, f"normal clip 'walk' times unchanged ~1.0 -> {walk_max}")

# --- points.glb: particle / VFX point cloud (studio #8) ---------------------------------------------
js, bl = parse_glb(os.path.join(root, "points.glb"))
print("\n=== points.glb ===")
pmeshes = js.get("meshes", [])
pprims = [p for m in pmeshes for p in m["primitives"]]
check(len(pprims) >= 1, f"point cloud has a primitive ({len(pprims)})")
if pprims:
    pp = pprims[0]
    check(pp.get("mode") == 0, f"primitive is POINTS (mode 0) -> {pp.get('mode')}")
    check("POSITION" in pp["attributes"], "point cloud has POSITION")
    check("COLOR_0" in pp["attributes"], "point cloud has per-point COLOR_0")
    # the point count: indices accessor 'count' must match the positions
    accs = js.get("accessors", [])
    idx_count = accs[pp["indices"]]["count"] if "indices" in pp else 0
    pos_count = accs[pp["attributes"]["POSITION"]]["count"]
    check(idx_count == pos_count == 16, f"16 points (idx {idx_count} == pos {pos_count})")
check(any(mm.get("name") == "Particles" for mm in js.get("materials", [])), "a 'Particles' material present")

# points.obj: POINTS export as OBJ point elements (p), never triangle faces (f)
obj_path = os.path.join(root, "points.obj")
if os.path.exists(obj_path):
    with open(obj_path, "r", encoding="utf-8") as f:
        obj = f.read()
    check("\np " in obj, "points.obj has OBJ point elements (p)")
    check("\nf " not in obj, "points.obj has NO triangle faces (f) for a point cloud")

# --- takes.glb: several recordings of one rig as clips (studio #13) + NLA name de-dup (studio #14) ---
js, bl = parse_glb(os.path.join(root, "takes.glb"))
validate_common("takes.glb", js, bl)
tanims = js.get("animations", [])
check(len(tanims) == 2, f"takes has 2 clips ({len(tanims)})")
tnames = [a.get("name") for a in tanims]
# both takes were named "take"; the writer must make them distinct Actions (NLA-ready)
check(len(set(tnames)) == 2, f"clip names are unique for NLA stacking -> {tnames}")
check("take" in tnames and "take_2" in tnames, f"colliding names de-duped to take/take_2 -> {tnames}")
for a in tanims:
    check(len(a["channels"]) > 0 and len(a["samplers"]) > 0, f"take '{a.get('name')}' has channels+samplers")

# --- retarget.glb: humanoid bone names for Mixamo-style retargeting (studio #16) --------------------
js, bl = parse_glb(os.path.join(root, "retarget.glb"))
print("\n=== retarget.glb ===")
rnames = [n.get("name") for n in js.get("nodes", [])]
for h in ("Hips", "Spine", "Head"):
    check(h in rnames, f"bone node renamed to humanoid '{h}'")
check("body" not in rnames and "head" not in rnames, f"MC bone names replaced -> {rnames}")
rskins = js.get("skins", [])
check(len(rskins) == 1 and len(rskins[0]["joints"]) == 3, "retarget rig still has the 3-joint skin")
# the original MC name is preserved in extras for round-tripping
spine = next((n for n in js["nodes"] if n.get("name") == "Spine"), None)
check(spine and (spine.get("extras") or {}).get("mcBone") == "body", "Spine keeps mcBone='body' in extras")

print("\n" + ("ALL SELF-TEST CHECKS PASSED" if not fails else f"{len(fails)} CHECK(S) FAILED"))
sys.exit(1 if fails else 0)
