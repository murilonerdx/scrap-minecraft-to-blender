package com.recorte.export;

import com.recorte.Recorte;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A tiny local HTTP server so external tools (the Blender add-on) can pull the latest export straight
 * from the running game — no file juggling. Bound to localhost only.
 *
 * <ul>
 *   <li>{@code GET /ping}   → "recorte" (health check)</li>
 *   <li>{@code GET /latest} → the bytes of the most recently exported {@code .glb}</li>
 * </ul>
 *
 * This is phase A of the real-time Blender link; phase B would stream bone transforms live.
 */
public final class HttpBridge {
    private HttpBridge() {}

    public static final int PORT = 25599;
    public static volatile boolean liveMode = false;   // auto-export the target each second for the live link
    private static volatile Path lastGlb;
    private static volatile int generation = 0;        // bumped on every export, polled by the Blender add-on
    private static volatile String env = "{}";
    private static volatile String events = "{\"fps\":30,\"events\":[]}";   // timeline markers of the latest recording
    private static volatile String sun = "{}";                             // day/night timelapse track
    private static volatile String animTextures = "{\"textures\":[]}";     // animated-texture manifest
    private static volatile java.util.Map<String, java.util.List<byte[]>> animFrames = java.util.Collections.emptyMap();
    private static HttpServer server;

    public static void setLastGlb(Path glb) {
        lastGlb = glb;
        events = "{\"fps\":30,\"events\":[]}";   // clear stale markers; a recording sets them after
        sun = "{}";                              // clear stale sun track too
        animTextures = "{\"textures\":[]}";
        animFrames = java.util.Collections.emptyMap();
        generation++;
    }

    /** Latest environment (sky color, time of day) as JSON, refreshed on the client tick. */
    public static void setEnv(String json) {
        env = json;
    }

    /** Timeline events (block break/place) of the latest recording, for the add-on to drop markers. */
    public static void setEvents(String json) {
        events = json;
    }

    /** Day/night timelapse track (sun + sky per keyframe) for the add-on to keyframe in Blender. */
    public static void setSun(String json) {
        sun = json;
    }

    /** Animated-texture frames (water/lava/fire…) so the add-on can build an Image Sequence per material. */
    public static void setAnimTextures(String manifestJson, java.util.Map<String, java.util.List<byte[]>> frames) {
        animTextures = manifestJson;
        animFrames = frames != null ? frames : java.util.Collections.emptyMap();
    }

    public static void start() {
        if (server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            server.createContext("/ping", exchange -> respond(exchange, 200, "text/plain", "recorte".getBytes()));
            server.createContext("/env", exchange -> respond(exchange, 200, "application/json",
                    env.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            server.createContext("/gen", exchange -> respond(exchange, 200, "text/plain",
                    String.valueOf(generation).getBytes()));
            server.createContext("/events", exchange -> respond(exchange, 200, "application/json",
                    events.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            server.createContext("/sun", exchange -> respond(exchange, 200, "application/json",
                    sun.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            server.createContext("/anim_textures", exchange -> respond(exchange, 200, "application/json",
                    animTextures.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            server.createContext("/anim_frame", exchange -> {
                String q = exchange.getRequestURI().getRawQuery();
                String mat = null;
                int idx = -1;
                if (q != null) {
                    for (String part : q.split("&")) {
                        int eq = part.indexOf('=');
                        if (eq <= 0) continue;
                        String k = part.substring(0, eq), v = part.substring(eq + 1);
                        if (k.equals("m")) {
                            mat = java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8);
                        } else if (k.equals("i")) {
                            try { idx = Integer.parseInt(v); } catch (NumberFormatException ignored) { }
                        }
                    }
                }
                java.util.List<byte[]> frames = mat == null ? null : animFrames.get(mat);
                if (frames == null || idx < 0 || idx >= frames.size()) {
                    respond(exchange, 404, "text/plain", "no frame".getBytes());
                    return;
                }
                respond(exchange, 200, "image/png", frames.get(idx));
            });
            server.createContext("/latest", exchange -> {
                Path glb = lastGlb;
                if (glb == null || !Files.exists(glb)) {
                    respond(exchange, 404, "text/plain", "no export yet".getBytes());
                    return;
                }
                respond(exchange, 200, "model/gltf-binary", Files.readAllBytes(glb));
            });
            server.setExecutor(null);
            server.start();
            Recorte.LOGGER.info("Recorte HTTP bridge listening on http://127.0.0.1:{}/latest", PORT);
        } catch (IOException e) {
            Recorte.LOGGER.warn("Could not start HTTP bridge (port {} in use?)", PORT, e);
            server = null;
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String type, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
