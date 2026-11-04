package com.gary.ysplive.desktop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 播放页宿主服务。
 *
 * <p>把内置的 {@code player.html} 与 {@code hls.min.js} 通过本地 HTTP 提供出去，
 * 而不是用 {@code file://} 直接打开——实测 {@code file://} 下 hls.js 会因 Origin 限制
 * 报 {@code manifestLoadError} / {@code levelLoadError}，而 HTTP 托管就没有这个问题。
 *
 * <p>只监听回环地址，随机端口，不对外开放。
 */
final class LocalWebServer {

    private final HttpServer server;
    private final int port;

    private LocalWebServer(HttpServer server, int port) {
        this.server = server;
        this.port = port;
    }

    int port() {
        return port;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 启动播放页服务。
     *
     * @param playerHtml 播放页 HTML 内容
     * @param hlsJs      hls.js 内容
     */
    static LocalWebServer start(String playerHtml, String hlsJs) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "ysp-web");
            t.setDaemon(true);
            return t;
        }));

        final byte[] htmlBytes = playerHtml.getBytes(StandardCharsets.UTF_8);
        final byte[] hlsBytes = hlsJs.getBytes(StandardCharsets.UTF_8);

        server.createContext("/player.html", exchange -> respond(exchange, "text/html; charset=utf-8", htmlBytes));
        server.createContext("/hls.min.js", exchange -> respond(exchange, "application/javascript; charset=utf-8", hlsBytes));
        server.createContext("/health", exchange -> respond(exchange, "text/plain; charset=utf-8",
                "ok".getBytes(StandardCharsets.UTF_8)));

        // 播放页自身也要能探活，方便主界面显示
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String path = exchange.getRequestURI().getPath();
                if ("/".equals(path) || "/index.html".equals(path)) {
                    respond(exchange, "text/html; charset=utf-8", htmlBytes);
                } else {
                    respond(exchange, "text/plain; charset=utf-8",
                            ("not found: " + path).getBytes(StandardCharsets.UTF_8));
                }
            }
        });

        server.start();
        return new LocalWebServer(server, server.getAddress().getPort());
    }

    private static void respond(HttpExchange exchange, String contentType, byte[] body)
            throws IOException {
        try {
            // 播放页与 js 都是本地资源，允许任意来源，避免任何代理层再加限制
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Content-Type", contentType);
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        } finally {
            exchange.close();
        }
    }

    /** 解析 query string（仅用于拼播放地址）。 */
    static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                out.put(k, v);
            } catch (IOException ignored) {
                // 忽略畸形参数
            }
        }
        return out;
    }
}
