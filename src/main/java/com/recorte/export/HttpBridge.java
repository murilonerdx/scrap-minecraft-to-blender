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
    private static volatile Path lastGlb;
    private static HttpServer server;

    public static void setLastGlb(Path glb) {
        lastGlb = glb;
    }

    public static void start() {
        if (server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            server.createContext("/ping", exchange -> respond(exchange, 200, "text/plain", "recorte".getBytes()));
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
