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
    js = json.loads(chunks[0x4E4F534A].decode("utf-8"))
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
    check(any("normalTexture" in mm for mm in mats), "a material has normalTexture")
    check(any("metallicRoughnessTexture" in mm.get("pbrMetallicRoughness", {}) for mm in mats),
          "a material has metallicRoughnessTexture")
    check(len(js.get("images", [])) == 4, f"4 images (2 base + normal + mr) -> {len(js.get('images', []))}")
    cams = js.get("cameras", [])
    check(len(cams) == 3, f"3 cameras (POV + 2 presets) -> {len(cams)}")
    check(len([n for n in nodes if "camera" in n]) == 3, "3 camera nodes")
    check(any(n.get("name") == "cam_hero" for n in nodes), "named (placed) camera node 'cam_hero' present")
    check(any("dof_focus" in (n.get("extras") or {}) for n in nodes), "a camera node carries DOF extras")
    check("KHR_lights_punctual" in js.get("extensions", {}), "KHR_lights_punctual present")
    check("KHR_lights_punctual" in js.get("extensionsUsed", []), "KHR_lights_punctual in extensionsUsed")
    llist = js.get("extensions", {}).get("KHR_lights_punctual", {}).get("lights", [])
    types = [l.get("type") for l in llist]
    check(types.count("directional") == 1, f"1 directional sun -> {types}")
    check(types.count("point") == 2, f"2 point lights (lamps) -> {types}")
    check(len([n for n in nodes if (n.get("name") or "").startswith("Lamp_")]) == 2, "2 Lamp_ light nodes")
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

print("\n" + ("ALL SELF-TEST CHECKS PASSED" if not fails else f"{len(fails)} CHECK(S) FAILED"))
sys.exit(1 if fails else 0)
