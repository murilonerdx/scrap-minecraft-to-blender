package com.recorte.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes an {@link Ir.Model} as Wavefront {@code .obj} + {@code .mtl}. No skeleton (OBJ has none): the
 * geometry is written in its bind pose, grouped by material. Vertices keep their export-space positions,
 * so the OBJ lines up with the glTF. The V texture coordinate is flipped because OBJ's origin is
 * bottom-left whereas Minecraft/glTF use top-left.
 */
public final class ObjWriter {
    private ObjWriter() {}

    public static void write(Ir.Model model, Path objPath, Path mtlPath) throws IOException {
        String mtlName = mtlPath.getFileName().toString();

        StringBuilder obj = new StringBuilder();
        obj.append("# Exported by Recorte Player Model Exporter\n");
        obj.append("mtllib ").append(mtlName).append('\n');

        int vOffset = 1; // OBJ indices are 1-based
        for (Ir.Primitive prim : model.primitives) {
            if (prim.vertices.isEmpty()) continue;
            Ir.Material mat = model.materials.get(prim.materialIndex);
            obj.append("o ").append(prim.group).append('_').append(mat.name).append('\n');

            for (Ir.Vertex v : prim.vertices) {
                if (v.isWhite()) {
                    obj.append(String.format(Locale.ROOT, "v %.6f %.6f %.6f\n", v.px, v.py, v.pz));
                } else {
                    obj.append(String.format(Locale.ROOT, "v %.6f %.6f %.6f %.4f %.4f %.4f\n",
                            v.px, v.py, v.pz, v.r, v.g, v.b));
                }
            }
            for (Ir.Vertex v : prim.vertices) {
                obj.append(String.format(Locale.ROOT, "vt %.6f %.6f\n", v.u, 1f - v.v));
            }
            for (Ir.Vertex v : prim.vertices) {
                obj.append(String.format(Locale.ROOT, "vn %.6f %.6f %.6f\n", v.nx, v.ny, v.nz));
            }

            obj.append("usemtl ").append(mat.name).append('\n');
            if (prim.mode == Ir.Primitive.POINTS) {
                // particle/VFX clouds are points, not triangles — write OBJ point elements, not faces
                for (int i = 0; i < prim.vertices.size(); i++) {
                    obj.append("p ").append(vOffset + i).append('\n');
                }
            } else {
                for (int i = 0; i + 2 < prim.indices.size(); i += 3) {
                    int a = prim.indices.get(i) + vOffset;
                    int b = prim.indices.get(i + 1) + vOffset;
                    int c = prim.indices.get(i + 2) + vOffset;
                    obj.append("f ")
                            .append(a).append('/').append(a).append('/').append(a).append(' ')
                            .append(b).append('/').append(b).append('/').append(b).append(' ')
                            .append(c).append('/').append(c).append('/').append(c).append('\n');
                }
            }
            vOffset += prim.vertices.size();
        }

        StringBuilder mtl = new StringBuilder();
        mtl.append("# Exported by Recorte Player Model Exporter\n");
        for (Ir.Material mat : model.materials) {
            mtl.append("newmtl ").append(mat.name).append('\n');
            mtl.append("Ka 1.000 1.000 1.000\n");
            mtl.append("Kd 1.000 1.000 1.000\n");
            mtl.append("Ks 0.000 0.000 0.000\n");
            mtl.append("d 1.0\n");
            mtl.append("illum 1\n");
            if (mat.textureFile != null) {
                mtl.append("map_Kd ").append(mat.textureFile).append('\n');
                mtl.append("map_d ").append(mat.textureFile).append('\n');   // alpha channel for transparency
            }
            mtl.append('\n');
        }

        Files.write(objPath, obj.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(mtlPath, mtl.toString().getBytes(StandardCharsets.UTF_8));
    }
}
