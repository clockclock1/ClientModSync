package com.example.clientmodsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Stream;

public final class ServerManifestHttpService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static HttpServer server;
    private static ExecutorService executor;

    public static synchronized void start(MinecraftServer minecraftServer, int port) {
        stop();

        Path clientModDir = minecraftServer.getFile("clientmod").toPath().toAbsolutePath().normalize();
        try {
            Files.createDirectories(clientModDir);

            server = HttpServer.create(new InetSocketAddress(port), 0);
            executor = Executors.newCachedThreadPool(new NamedDaemonThreadFactory());
            server.setExecutor(executor);

            server.createContext("/manifest.json", exchange -> handleManifest(exchange, clientModDir));
            server.createContext("/mods/", exchange -> handleModFile(exchange, clientModDir));

            server.start();
            ClientModSyncLog.LOGGER.info("[{}] 同步服务已启动，端口={}，目录={}",
                    ClientModSyncMod.MOD_ID, port, clientModDir);
        } catch (Exception e) {
            ClientModSyncLog.LOGGER.error("[{}] 启动同步服务失败", ClientModSyncMod.MOD_ID, e);
            stop();
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static void handleManifest(HttpExchange exchange, Path clientModDir) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeText(exchange, 405, "不支持的请求方法");
            return;
        }

        List<ModEntry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(clientModDir)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> entries.add(toEntry(path)));
        }

        ManifestResponse response = new ManifestResponse(entries);
        byte[] body = GSON.toJson(response).getBytes(StandardCharsets.UTF_8);

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void handleModFile(HttpExchange exchange, Path clientModDir) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeText(exchange, 405, "不支持的请求方法");
            return;
        }

        String rawPath = exchange.getRequestURI().getPath();
        String encodedFileName = rawPath.substring("/mods/".length());
        String fileName = URLDecoder.decode(encodedFileName, StandardCharsets.UTF_8);
        if (fileName.isBlank() || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            writeText(exchange, 400, "文件名不合法");
            return;
        }

        Path filePath = clientModDir.resolve(fileName).normalize();
        if (!filePath.startsWith(clientModDir) || !Files.isRegularFile(filePath)) {
            writeText(exchange, 404, "文件不存在");
            return;
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/java-archive");
        long size = Files.size(filePath);
        exchange.sendResponseHeaders(200, size);
        try (OutputStream output = exchange.getResponseBody()) {
            Files.copy(filePath, output);
        }
    }

    private static ModEntry toEntry(Path path) {
        try {
            return new ModEntry(path.getFileName().toString(), sha1(path), Files.size(path));
        } catch (Exception e) {
            throw new RuntimeException("计算清单条目失败：" + path, e);
        }
    }

    private static String sha1(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] bytes = Files.readAllBytes(file);
        byte[] hash = digest.digest(bytes);
        return HexFormat.of().formatHex(hash);
    }

    private static void writeText(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private record ManifestResponse(List<ModEntry> mods) {
    }

    public record ModEntry(String name, String sha1, long size) {
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "clientmodsync-http");
            thread.setDaemon(true);
            return thread;
        }
    }

    private ServerManifestHttpService() {
    }
}
