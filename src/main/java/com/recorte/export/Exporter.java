package com.recorte.export;

import com.recorte.Recorte;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Exports players, entities/mobs, item models and block models to glTF + OBJ, and can batch a whole
 * mod's items and blocks. Runs on the render thread (reads GPU state), so callers schedule via
 * {@code Minecraft#execute}.
 */
public final class Exporter {
    private Exporter() {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    // --- entry points -----------------------------------------------------------------------------

    public static void exportLookedAtOrSelf() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.crosshairPickEntity != null) {
            exportEntity(mc.crosshairPickEntity);
        } else if (mc.player != null) {
            exportPlayer(mc.player);
        }
    }

    /** Silent live snapshot (world + nearby entities + you) to a fixed "_live" folder, for the link. */
    public static void exportLive() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        try {
            BlockPos center = mc.player.blockPosition();
            Ir.Model ir = buildSnapshot(center, 8, null);   // scene + all nearby living entities (incl. you)
            Path dir = mc.gameDirectory.toPath().resolve("recorte_exports").resolve("_live");
            Files.createDirectories(dir);
            for (Ir.Material m : ir.materials) {
                if (m.png != null && m.textureFile != null) Files.write(dir.resolve(m.textureFile), m.png);
            }
            Path glb = dir.resolve("live.glb");
            GltfWriter.write(ir, glb);
            HttpBridge.setLastGlb(glb);
        } catch (Throwable t) {
            Recorte.LOGGER.warn("Live export failed", t);
        }
    }

    public static void toggleLive() {
        HttpBridge.liveMode = !HttpBridge.liveMode;
        feedback(HttpBridge.liveMode
                ? "§a● Live link LIGADO §7— Blender atualiza ~1×/s (marque 'Live link' no addon)"
                : "§e○ Live link DESLIGADO");
    }

    public static void exportPlayerByName(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p.getGameProfile().getName().equalsIgnoreCase(name)) {
                exportPlayer(p);
                return;
            }
        }
        feedback("§cJogador '" + name + "' não está visível no mundo.");
    }

    public static void exportPlayer(AbstractClientPlayer target) {
        if (target == null) return;
        try {
            Ir.Model ir = buildPlayer(target);
            if (ir == null) {
                feedback("§cRenderer do jogador não encontrado.");
                return;
            }
            Path dir = newDir(target.getGameProfile().getName());
            writeAll(ir, dir, "player");
            report(target.getGameProfile().getName(), ir, dir);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Full player model: rigged body + skin + captured accessories (armour/Curios), or null. */
    static Ir.Model buildPlayer(AbstractClientPlayer target) throws java.io.IOException {
        Minecraft mc = Minecraft.getInstance();
        EntityRenderer<?> renderer = mc.getEntityRenderDispatcher().getRenderer(target);
        if (!(renderer instanceof PlayerRenderer playerRenderer)) return null;
        PlayerModel<?> model = playerRenderer.getModel();
        Ir.Model ir = new ModelExtractor().extract(model, target, "skin.png");
        ir.materials.get(0).png = TextureExporter.skinBytes(target);
        LayerCapturer.captureExtras(playerRenderer, target, model, ir);
        return ir;
    }

    public static void exportEntity(Entity entity) {
        if (entity instanceof AbstractClientPlayer player) {
            exportPlayer(player);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            @SuppressWarnings("rawtypes")
            EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(entity);

            // Prefer a rigged walk (bones) for ModelPart-based mobs; fall back to capturing the real
            // render path for GeckoLib / anything that yields no walkable geometry.
            Ir.Model ir = null;
            if (renderer instanceof LivingEntityRenderer<?, ?> living) {
                try {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    EntityModel model = living.getModel();
                    Ir.Model rigged = new ModelExtractor().extractEntity(entity, model, "texture.png");
                    if (rigged.triangleCount() > 0) {
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        ResourceLocation tex = ((EntityRenderer) renderer).getTextureLocation(entity);
                        rigged.materials.get(0).png = TextureExporter.bytesFor(tex);
                        ir = rigged;
                    }
                } catch (Throwable t) {
                    Recorte.LOGGER.warn("Rigged entity extract failed, capturing instead", t);
                }
            }
            if (ir == null) {
                ir = LayerCapturer.captureEntity(renderer, entity);
            }

            String name = entity.getType().getDescriptionId().replaceAll(".*\\.", "");
            Path dir = newDir(name);
            writeAll(ir, dir, name);
            report(name, ir, dir);
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void exportEntityType(ResourceLocation id) {
        Minecraft mc = Minecraft.getInstance();
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (type == null || mc.level == null) {
            feedback("§cEntidade desconhecida: " + id);
            return;
        }
        Entity entity = type.create(mc.level);
        if (entity == null) {
            feedback("§cNão consegui instanciar: " + id);
            return;
        }
        exportEntity(entity);
    }

    public static void exportItem(ResourceLocation id) {
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null) {
            feedback("§cItem desconhecido: " + id);
            return;
        }
        try {
            Ir.Model ir = bakeItem(item);
            Path dir = newDir(id.getPath());
            writeAll(ir, dir, id.getPath());
            report(id.toString(), ir, dir);
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void exportBlock(ResourceLocation id) {
        Block block = ForgeRegistries.BLOCKS.getValue(id);
        if (block == null) {
            feedback("§cBloco desconhecido: " + id);
            return;
        }
        try {
            Ir.Model ir = bakeBlock(block);
            Path dir = newDir(id.getPath());
            writeAll(ir, dir, id.getPath());
            report(id.toString(), ir, dir);
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void exportScene(int radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            feedback("§cEntre num mundo primeiro.");
            return;
        }
        int r = Math.max(1, Math.min(radius, 64));
        try {
            BlockPos center = mc.player.blockPosition();
            feedback("§7Capturando cena (raio " + r + ")... pode travar alguns segundos.");
            Ir.Model ir = new SceneExtractor().extract(mc.level, center, r);
            ir.camera = playerCamera(center);
            ir.sun = worldSun();
            ir.extraCameras.addAll(presetCameras(r));
            Path dir = newDir("scene_r" + r);
            writeAll(ir, dir, "scene");
            report("cena (raio " + r + ")", ir, dir);
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void exportSnapshot(int radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            feedback("§cEntre num mundo primeiro.");
            return;
        }
        int r = Math.max(1, Math.min(radius, 48));
        try {
            BlockPos center = mc.player.blockPosition();
            feedback("§7Capturando snapshot (raio " + r + ")... pode travar alguns segundos.");
            int[] count = {0};
            Ir.Model ir = buildSnapshot(center, r, count);
            Path dir = newDir("snapshot_r" + r);
            writeAll(ir, dir, "snapshot");
            Recorte.LOGGER.info("Snapshot to {}: {} materials, {} tris, {} entities",
                    dir, ir.materials.size(), ir.triangleCount(), count[0]);
            feedback(String.format("§aSnapshot§a: cena + %d entidades, %d triângulos §7→ §f%s",
                    count[0], ir.triangleCount(), dir));
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Builds a snapshot model: scene + camera + sun + every nearby rigged/captured entity. */
    static Ir.Model buildSnapshot(BlockPos center, int r, int[] entityCount) {
        Minecraft mc = Minecraft.getInstance();
        Ir.Model ir = new SceneExtractor().extract(mc.level, center, r);
        ir.camera = playerCamera(center);
        ir.sun = worldSun();
        ir.extraCameras.addAll(presetCameras(r));
        int entities = 0;
        AABB box = new AABB(center).inflate(r);
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, box)) {
            try {
                @SuppressWarnings("rawtypes")
                EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(e);
                float ox = -((float) e.getX() - center.getX());
                float oz = (float) e.getZ() - center.getZ();
                String name = e.getType().getDescriptionId().replaceAll(".*\\.", "");
                // the local player gets the full model (body + skin + armour/Curios), not a bare body
                Ir.Model rigged = (e == mc.player)
                        ? buildPlayer((AbstractClientPlayer) e) : riggedEntity(e, renderer);
                if (rigged != null) {
                    float oy = (float) (e.getY() - center.getY()) - minY(rigged);
                    mergeRigged(ir, rigged, ox, oy, oz, "entity_" + name, "e" + entities + "_");
                } else {
                    Ir.Model cap = LayerCapturer.captureEntity(renderer, e);
                    float oy = (float) (e.getY() - center.getY());
                    mergeInto(ir, cap, ox, oy, oz, "entity_" + name, "e" + entities + "_");
                }
                entities++;
            } catch (Throwable t) {
                Recorte.LOGGER.warn("Snapshot entity {} failed", e.getType(), t);
            }
        }
        if (entityCount != null && entityCount.length > 0) entityCount[0] = entities;
        return ir;
    }

    /** Appends {@code src} (one captured object) into {@code target}, offset to a world position. */
    private static void mergeInto(Ir.Model target, Ir.Model src, float ox, float oy, float oz,
                                  String group, String texPrefix) {
        java.util.Map<Integer, Integer> matMap = new java.util.HashMap<>();
        for (int i = 0; i < src.materials.size(); i++) {
            Ir.Material sm = src.materials.get(i);
            Ir.Material tm = new Ir.Material(group + "_" + sm.name);
            tm.png = sm.png;
            tm.textureFile = sm.textureFile != null ? texPrefix + sm.textureFile : null;
            tm.emissive = sm.emissive;
            target.materials.add(tm);
            matMap.put(i, target.materials.size() - 1);
        }
        for (Ir.Primitive sp : src.primitives) {
            Ir.Primitive tp = target.primitiveForMaterial(matMap.get(sp.materialIndex));
            tp.group = group;
            int base = tp.vertices.size();
            for (Ir.Vertex v : sp.vertices) {
                tp.vertices.add(new Ir.Vertex(v.px + ox, v.py + oy, v.pz + oz,
                        v.nx, v.ny, v.nz, v.u, v.v, 0, v.r, v.g, v.b, v.a));
            }
            for (int idx : sp.indices) tp.indices.add(base + idx);
        }
    }

    /** A rigged (boned) IR for a living entity, or null if it can't be walked (GeckoLib). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Ir.Model riggedEntity(LivingEntity e, EntityRenderer renderer) {
        if (!(renderer instanceof LivingEntityRenderer<?, ?> living)) return null;
        try {
            EntityModel model = living.getModel();
            Ir.Model r = new ModelExtractor().extractEntity(e, model, "texture.png");
            if (r.triangleCount() <= 0) return null;
            ResourceLocation tex = ((EntityRenderer) renderer).getTextureLocation(e);
            r.materials.get(0).png = TextureExporter.bytesFor(tex);
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private static float minY(Ir.Model m) {
        float min = Float.MAX_VALUE;
        for (Ir.Primitive p : m.primitives) {
            for (Ir.Vertex v : p.vertices) if (v.py < min) min = v.py;
        }
        return min == Float.MAX_VALUE ? 0f : min;
    }

    /** Appends a rigged {@code src} into {@code target}, keeping its bones, offset to a world position. */
    private static void mergeRigged(Ir.Model target, Ir.Model src, float ox, float oy, float oz,
                                    String group, String texPrefix) {
        int boneOffset = target.bones.size();
        org.joml.Matrix4f t = new org.joml.Matrix4f().translate(ox, oy, oz);
        for (Ir.Bone sb : src.bones) {
            org.joml.Matrix4f gb = new org.joml.Matrix4f(t).mul(sb.globalBind);
            int parent = sb.parentIndex < 0 ? -1 : sb.parentIndex + boneOffset;
            Ir.Bone nb = new Ir.Bone(group + "_" + sb.name, parent, gb);
            if (parent < 0) {
                nb.localTransform = new org.joml.Matrix4f(gb);
            } else {
                nb.localTransform = new org.joml.Matrix4f(target.bones.get(parent).globalBind).invert().mul(gb);
            }
            target.bones.add(nb);
        }
        java.util.Map<Integer, Integer> matMap = new java.util.HashMap<>();
        for (int i = 0; i < src.materials.size(); i++) {
            Ir.Material sm = src.materials.get(i);
            Ir.Material tm = new Ir.Material(group + "_" + sm.name);
            tm.png = sm.png;
            tm.textureFile = sm.textureFile != null ? texPrefix + sm.textureFile : null;
            tm.emissive = sm.emissive;
            target.materials.add(tm);
            matMap.put(i, target.materials.size() - 1);
        }
        for (Ir.Primitive sp : src.primitives) {
            Ir.Primitive tp = target.primitiveForMaterial(matMap.get(sp.materialIndex));
            tp.group = group;
            int base = tp.vertices.size();
            for (Ir.Vertex v : sp.vertices) {
                tp.vertices.add(new Ir.Vertex(v.px + ox, v.py + oy, v.pz + oz,
                        v.nx, v.ny, v.nz, v.u, v.v, v.joint + boneOffset, v.r, v.g, v.b, v.a));
            }
            for (int idx : sp.indices) tp.indices.add(base + idx);
        }
    }

    public static void exportRecording(Ir.Model model, Ir.Animation anim, String name, int frames) {
        try {
            Path dir = newDir("anim_" + name);
            Files.createDirectories(dir);
            for (Ir.Material m : model.materials) {
                if (m.png != null && m.textureFile != null) Files.write(dir.resolve(m.textureFile), m.png);
            }
            Path glb = dir.resolve(name + ".glb");
            GltfWriter.write(model, anim, glb);
            ObjWriter.write(model, dir.resolve(name + ".obj"), dir.resolve(name + ".mtl"));
            HttpBridge.setLastGlb(glb);
            writeEvents(anim, dir);
            Recorte.LOGGER.info("Recorded {} ({} frames) to {}", name, frames, dir);
            feedback(String.format("§a■ Animação gravada §f%s§a: %d frames §7→ §f%s", name, frames, dir));
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void exportSceneRecording(Ir.Model model, Ir.Animation anim, int frames, int mobs) {
        try {
            Path dir = newDir("cinematic");
            Files.createDirectories(dir);
            for (Ir.Material m : model.materials) {
                if (m.png != null && m.textureFile != null) Files.write(dir.resolve(m.textureFile), m.png);
            }
            Path glb = dir.resolve("cinematic.glb");
            GltfWriter.write(model, anim, glb);
            ObjWriter.write(model, dir.resolve("cinematic.obj"), dir.resolve("cinematic.mtl"));
            HttpBridge.setLastGlb(glb);
            writeEvents(anim, dir);
            writeSunTrack(anim, dir);
            int evs = anim.events.size();
            Recorte.LOGGER.info("Cinematic to {}: {} frames, {} mobs, {} tris, {} events",
                    dir, frames, mobs, model.triangleCount(), evs);
            feedback(String.format("§a■ Cinematic gravado§a: %d frames, %d mobs, %d triângulos, %d eventos §7→ §f%s",
                    frames, mobs, model.triangleCount(), evs, dir));
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void exportMod(String modid) {
        Minecraft mc = Minecraft.getInstance();
        try {
            Path base = newDir("mod_" + modid);
            int items = 0;
            for (var e : ForgeRegistries.ITEMS.getEntries()) {
                ResourceLocation id = e.getKey().location();
                if (!id.getNamespace().equals(modid)) continue;
                try {
                    Ir.Model ir = bakeItem(e.getValue());
                    writeAll(ir, base.resolve("items").resolve(sanitize(id.getPath())), id.getPath());
                    items++;
                } catch (Throwable t) {
                    Recorte.LOGGER.warn("Item {} failed", id, t);
                }
            }
            int blocks = 0;
            for (var e : ForgeRegistries.BLOCKS.getEntries()) {
                ResourceLocation id = e.getKey().location();
                if (!id.getNamespace().equals(modid)) continue;
                try {
                    Ir.Model ir = bakeBlock(e.getValue());
                    writeAll(ir, base.resolve("blocks").resolve(sanitize(id.getPath())), id.getPath());
                    blocks++;
                } catch (Throwable t) {
                    Recorte.LOGGER.warn("Block {} failed", id, t);
                }
            }
            Recorte.LOGGER.info("Exported mod {}: {} items, {} blocks to {}", modid, items, blocks, base);
            feedback(String.format("§aMod §f%s§a: %d itens + %d blocos §7→ §f%s", modid, items, blocks, base));
        } catch (Throwable t) {
            fail(t);
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static Ir.Model bakeItem(Item item) {
        Minecraft mc = Minecraft.getInstance();
        ItemStack stack = new ItemStack(item);
        BakedModel baked = mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0);
        return new BakedModelExtractor().extract(baked, null, sanitize(item.toString()));
    }

    private static Ir.Model bakeBlock(Block block) {
        Minecraft mc = Minecraft.getInstance();
        BlockState state = block.defaultBlockState();
        BakedModel baked = mc.getBlockRenderer().getBlockModel(state);
        return new BakedModelExtractor().extract(baked, state, sanitize(block.toString()));
    }

    private static void writeAll(Ir.Model ir, Path dir, String base) throws IOException {
        Files.createDirectories(dir);
        for (Ir.Material m : ir.materials) {
            if (m.png != null && m.textureFile != null) {
                Files.write(dir.resolve(m.textureFile), m.png);
            }
        }
        Path glb = dir.resolve(base + ".glb");
        GltfWriter.write(ir, glb);
        ObjWriter.write(ir, dir.resolve(base + ".obj"), dir.resolve(base + ".mtl"));
        HttpBridge.setLastGlb(glb);
    }

    /**
     * Writes recorded timeline events (block break/place) as a sidecar {@code events.json} and pushes
     * them to the HTTP bridge so the Blender add-on can drop timeline markers (to time VFX/sound).
     * Call <em>after</em> {@link HttpBridge#setLastGlb} (which clears stale markers).
     */
    private static void writeEvents(Ir.Animation anim, Path dir) {
        if (anim == null || anim.events.isEmpty()) return;
        StringBuilder sb = new StringBuilder("{\"fps\":30,\"events\":[");
        synchronized (anim.events) {
            for (int i = 0; i < anim.events.size(); i++) {
                Ir.Event e = anim.events.get(i);
                if (i > 0) sb.append(',');
                sb.append(String.format(java.util.Locale.ROOT, "{\"time\":%.4f,\"name\":\"%s\"",
                        e.time, esc(e.name)));
                if (e.position != null) {
                    sb.append(String.format(java.util.Locale.ROOT, ",\"pos\":[%.3f,%.3f,%.3f]",
                            e.position[0], e.position[1], e.position[2]));
                }
                sb.append('}');
            }
        }
        sb.append("]}");
        String json = sb.toString();
        try {
            Files.writeString(dir.resolve("events.json"), json);
            // CSV too, for audio/video editors (time in seconds, position in scene space)
            StringBuilder csv = new StringBuilder("time,name,x,y,z\n");
            synchronized (anim.events) {
                for (Ir.Event e : anim.events) {
                    csv.append(String.format(java.util.Locale.ROOT, "%.4f,%s,", e.time,
                            e.name.replace(",", " ")));
                    if (e.position != null) {
                        csv.append(String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f",
                                e.position[0], e.position[1], e.position[2]));
                    }
                    csv.append('\n');
                }
            }
            Files.writeString(dir.resolve("events.csv"), csv.toString());
        } catch (Throwable ignored) {
        }
        HttpBridge.setEvents(json);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Writes the day/night timelapse track (sun direction/color/intensity + sky color per keyframe)
     * as a sidecar {@code sun.json} and pushes it to the bridge, so the add-on can keyframe the Blender
     * Sun lamp and World background over the recording.
     */
    private static void writeSunTrack(Ir.Animation anim, Path dir) {
        if (anim == null || anim.sunDirections.isEmpty()) return;
        int n = anim.sunDirections.size();
        StringBuilder sb = new StringBuilder("{\"fps\":30,\"times\":[");
        for (int i = 0; i < n && i < anim.times.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.ROOT, "%.4f", anim.times.get(i)));
        }
        appendVecArray(sb, "],\"dir\":[", anim.sunDirections);
        appendVecArray(sb, "],\"color\":[", anim.sunColors);
        sb.append("],\"intensity\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.ROOT, "%.4f", anim.sunIntensities.get(i)));
        }
        appendVecArray(sb, "],\"sky\":[", anim.skyColors);
        sb.append("]}");
        String json = sb.toString();
        try {
            Files.writeString(dir.resolve("sun.json"), json);
        } catch (Throwable ignored) {
        }
        HttpBridge.setSun(json);
    }

    private static void appendVecArray(StringBuilder sb, String opener, java.util.List<float[]> vecs) {
        sb.append(opener);
        for (int i = 0; i < vecs.size(); i++) {
            float[] v = vecs.get(i);
            if (i > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.ROOT, "[%.4f,%.4f,%.4f]", v[0], v[1], v[2]));
        }
    }

    /** The in-game camera, converted into the scene's export space (X negated, relative to {@code center}). */
    static Ir.Camera playerCamera(BlockPos center) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.Camera cam = mc.gameRenderer.getMainCamera();
        net.minecraft.world.phys.Vec3 p = cam.getPosition();
        float px = -(float) (p.x - center.getX());
        float py = (float) (p.y - center.getY());
        float pz = (float) (p.z - center.getZ());
        org.joml.Vector3f f = cam.getLookVector();
        org.joml.Vector3f u = cam.getUpVector();
        org.joml.Quaternionf q = new org.joml.Quaternionf()
                .lookAlong(-f.x, f.y, f.z, -u.x, u.y, u.z).conjugate();
        float yfov = (float) Math.toRadians(mc.options.fov().get());
        return new Ir.Camera(new float[]{px, py, pz}, new float[]{q.x, q.y, q.z, q.w}, yfov);
    }

    /** A directional sun light approximating the in-game time of day (export space, +Y up). */
    static Ir.Light worldSun() {
        Minecraft mc = Minecraft.getInstance();
        float celestial = mc.level.getTimeOfDay(1.0f);       // 0 = noon, 0.5 = midnight
        double a = celestial * 2.0 * Math.PI;
        float height = (float) Math.cos(a);                  // 1 noon, -1 midnight
        org.joml.Vector3f sun = new org.joml.Vector3f(
                (float) (-0.5 * Math.sin(a)), Math.max(height, 0.05f) + 0.25f, -0.45f).normalize();
        float[] dir = {-sun.x, -sun.y, -sun.z};              // light travels opposite the sun

        float[] color;
        float intensity;
        if (height > 0.25f) {
            color = new float[]{1.0f, 0.97f, 0.92f};
            intensity = 4.0f;
        } else if (height > -0.05f) {
            color = new float[]{1.0f, 0.66f, 0.40f};
            intensity = 2.0f;
        } else {
            color = new float[]{0.45f, 0.55f, 0.85f};
            intensity = 0.4f;
        }
        return new Ir.Light(dir, color, intensity);
    }

    /** Preset render cameras framing the scene centre: four 3/4 orbit views + one top-down. */
    static java.util.List<Ir.Camera> presetCameras(int radius) {
        java.util.List<Ir.Camera> cams = new java.util.ArrayList<>();
        float yfov = (float) Math.toRadians(50);
        float d = radius * 2.0f, h = radius * 1.2f;
        for (float deg : new float[]{45, 135, 225, 315}) {
            double a = Math.toRadians(deg);
            cams.add(lookAtOrigin((float) (d * Math.cos(a)), h, (float) (d * Math.sin(a)), 0, 1, 0, yfov));
        }
        cams.add(lookAtOrigin(0.001f, radius * 2.5f, 0.001f, 0, 0, -1, yfov));   // top-down
        return cams;
    }

    /** A camera at (px,py,pz) in export space, framed to look at the origin (scene centre). */
    private static Ir.Camera lookAtOrigin(float px, float py, float pz, float ux, float uy, float uz, float yfov) {
        org.joml.Vector3f dir = new org.joml.Vector3f(-px, -py, -pz);
        if (dir.lengthSquared() < 1e-8f) dir.set(0, -1, 0);
        dir.normalize();
        org.joml.Quaternionf q = new org.joml.Quaternionf()
                .lookAlong(dir.x, dir.y, dir.z, ux, uy, uz).conjugate();
        return new Ir.Camera(new float[]{px, py, pz}, new float[]{q.x, q.y, q.z, q.w}, yfov);
    }

    private static Path newDir(String label) {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("recorte_exports")
                .resolve(LocalDateTime.now().format(STAMP) + "_" + sanitize(label));
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static void report(String name, Ir.Model ir, Path dir) {
        Recorte.LOGGER.info("Exported {} to {} ({} materials, {} tris)",
                name, dir, ir.materials.size(), ir.triangleCount());
        feedback(String.format("§aExportado §f%s§a: %d materiais, %d triângulos §7→ §f%s",
                name, ir.materials.size(), ir.triangleCount(), dir));
    }

    private static void fail(Throwable t) {
        Recorte.LOGGER.error("Export failed", t);
        feedback("§cFalha ao exportar: " + t.getMessage());
    }

    private static void feedback(String message) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(message), false);
        }
    }
}
