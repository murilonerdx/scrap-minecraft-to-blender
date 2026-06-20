import json, struct, sys, os

d = sys.argv[1]
glb = os.path.join(d, "player.glb")
obj = os.path.join(d, "player.obj")
png = os.path.join(d, "skin.png")

print("=== GLB ===")
with open(glb, "rb") as f:
    data = f.read()
magic, ver, length = struct.unpack_from("<III", data, 0)
assert magic == 0x46546C67, "bad magic"
print(f"magic OK, version={ver}, total_len={length} (file={len(data)})")
off = 12
chunks = {}
while off < len(data):
    clen, ctype = struct.unpack_from("<II", data, off)
    off += 8
    body = data[off:off+clen]
    off += clen
    chunks[ctype] = body
js = json.loads(chunks[0x4E4F534A].decode("utf-8"))   # JSON chunk
bin_len = len(chunks.get(0x004E4942, b""))
print(f"JSON chunk ok, BIN chunk = {bin_len} bytes")

nodes = js.get("nodes", [])
print(f"nodes: {len(nodes)} -> {[n.get('name') for n in nodes]}")
print(f"meshes: {len(js.get('meshes', []))}, materials: {len(js.get('materials', []))}, "
      f"images: {len(js.get('images', []))}, textures: {len(js.get('textures', []))}")
skins = js.get("skins", [])
if skins:
    print(f"skin joints: {len(skins[0]['joints'])}, skeleton root node: {skins[0].get('skeleton')}")
accs = js.get("accessors", [])
# summarise primitive 0
for mi, m in enumerate(js.get("meshes", [])):
    for pi, p in enumerate(m["primitives"]):
        pos = accs[p["attributes"]["POSITION"]]
        idx = accs[p["indices"]]
        print(f"  mesh{mi} prim{pi}: verts={pos['count']}, tris={idx['count']//3}, "
              f"bbox min={pos.get('min')} max={pos.get('max')}")
# check joints referenced
print(f"accessors: {len(accs)}, bufferViews: {len(js.get('bufferViews', []))}")

print("\n=== PNG (skin) ===")
with open(png, "rb") as f:
    p = f.read()
assert p[:8] == b"\x89PNG\r\n\x1a\n", "bad png"
w, h = struct.unpack(">II", p[16:24])
print(f"PNG OK, {w}x{h}")

print("\n=== OBJ ===")
vt = vn = v = fcount = ocount = 0
mats = []
with open(obj) as f:
    for line in f:
        if line.startswith("v "): v += 1
        elif line.startswith("vt "): vt += 1
        elif line.startswith("vn "): vn += 1
        elif line.startswith("f "): fcount += 1
        elif line.startswith("o "): ocount += 1
        elif line.startswith("usemtl"): mats.append(line.split()[1])
print(f"v={v}, vt={vt}, vn={vn}, faces(tris)={fcount}, objects={ocount}, usemtl={mats}")
print("\nALL CHECKS PASSED" if (v>0 and fcount>0 and len(nodes)>0) else "\nSOMETHING EMPTY")
