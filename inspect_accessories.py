"""Geometrically checks whether captured accessories sit OUTSIDE the player body, by decoding the GLB
mesh positions — no Blender needed. Lists every mesh's bounding box and, for an Accessories mesh, how
far it extends beyond the body's horizontal footprint."""
import json, struct, sys, os

path = sys.argv[1]
with open(path, "rb") as f:
    data = f.read()
magic, ver, length = struct.unpack_from("<III", data, 0)
assert magic == 0x46546C67
off, chunks = 12, {}
while off < len(data):
    clen, ctype = struct.unpack_from("<II", data, off)
    off += 8
    chunks[ctype] = data[off:off + clen]
    off += clen
js = json.loads(chunks[0x4E4F534A].decode("utf-8"))
binc = chunks.get(0x004E4942, b"")
accs, bvs = js["accessors"], js["bufferViews"]


def positions(acc_idx):
    a = accs[acc_idx]
    bv = bvs[a["bufferView"]]
    base = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    n = a["count"]
    out = []
    for i in range(n):
        x, y, z = struct.unpack_from("<fff", binc, base + i * 12)
        out.append((x, y, z))
    return out


def bbox(verts):
    xs = [v[0] for v in verts]; ys = [v[1] for v in verts]; zs = [v[2] for v in verts]
    return (min(xs), max(xs), min(ys), max(ys), min(zs), max(zs))


meshes = {}
for node in js.get("nodes", []):
    if "mesh" in node:
        m = js["meshes"][node["mesh"]]
        name = node.get("name") or m.get("name") or "?"
        verts = []
        for p in m["primitives"]:
            verts += positions(p["attributes"]["POSITION"])
        if verts:
            meshes[name] = verts

print("=== mesh bounding boxes (x:[min,max] y:[min,max] z:[min,max]) ===")
for name, verts in meshes.items():
    b = bbox(verts)
    print(f"  {name:24s} verts={len(verts):5d}  x:[{b[0]:+.3f},{b[1]:+.3f}]  y:[{b[2]:+.3f},{b[3]:+.3f}]  z:[{b[4]:+.3f},{b[5]:+.3f}]")

# find a body mesh and an accessories mesh
body = next((v for n, v in meshes.items() if n.lower() in ("player", "body") or "player" in n.lower()), None)
acc = next((v for n, v in meshes.items() if "ccessor" in n.lower()), None)
if body and acc:
    bb = bbox(body); ab = bbox(acc)
    print("\n=== accessory vs body footprint ===")
    print(f"  body   x:[{bb[0]:+.3f},{bb[1]:+.3f}] z:[{bb[4]:+.3f},{bb[5]:+.3f}]")
    print(f"  access x:[{ab[0]:+.3f},{ab[1]:+.3f}] z:[{ab[4]:+.3f},{ab[5]:+.3f}]")
    # fraction of accessory verts inside the body's horizontal box (= still clipping)
    inside = sum(1 for (x, y, z) in acc if bb[0] <= x <= bb[1] and bb[4] <= z <= bb[5])
    print(f"  accessory verts inside body XZ box: {inside}/{len(acc)} ({100*inside//max(1,len(acc))}%)")
    print(f"  accessory extends beyond body  +X:{ab[1]-bb[1]:+.3f}  -X:{bb[0]-ab[0]:+.3f}  +Z:{ab[5]-bb[5]:+.3f}  -Z:{bb[4]-ab[4]:+.3f}")
else:
    print("\n(no obvious body+accessories pair — listing names above; this glb may be a snapshot/scene)")
