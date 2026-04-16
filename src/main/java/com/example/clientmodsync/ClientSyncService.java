package com.example.clientmodsync;

import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class ClientSyncService {
    private static final Gson GSON = new Gson();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().build();

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static void startNeedSyncCheckAsync(Consumer<NeedSyncCheckResult> callback) {
        CompletableFuture
                .supplyAsync(() -> {
                    String host = resolveServerHost();
                    if (host == null || host.isBlank()) {
                        return new NeedSyncCheckResult(false, false, 0, 0,
                                "未找到目标服务端地址，请先配置 client.serverIp。", null);
                    }

                    int port = ModConfigHolder.CLIENT_SERVER_PORT.get();
                    ModConfigHolder.SyncMode mode = ModConfigHolder.CLIENT_SYNC_MODE.get();

                    try {
                        SyncPlan plan = buildPlan(host, port, mode);
                        boolean needSync = !plan.downloadTasks().isEmpty() || !plan.deleteTasks().isEmpty();
                        return new NeedSyncCheckResult(true, needSync,
                                plan.downloadTasks().size(), plan.deleteTasks().size(),
                                "检查完成", host);
                    } catch (Exception e) {
                        ClientModSyncLog.LOGGER.warn("[{}] 检查是否需要同步失败", ClientModSyncMod.MOD_ID, e);
                        return new NeedSyncCheckResult(false, false, 0, 0,
                                e.getMessage() == null ? "未知错误" : e.getMessage(), host);
                    }
                })
                .thenAccept(callback);
    }

    public static void startSyncAsync(Consumer<SyncProgress> progressCallback, Consumer<SyncResult> resultCallback) {
        String host = resolveServerHost();
        if (host == null || host.isBlank()) {
            resultCallback.accept(new SyncResult(false, "未找到目标服务端地址，请先配置 client.serverIp。", 0, 0));
            return;
        }

        int port = ModConfigHolder.CLIENT_SERVER_PORT.get();
        ModConfigHolder.SyncMode mode = ModConfigHolder.CLIENT_SYNC_MODE.get();

        if (!RUNNING.compareAndSet(false, true)) {
            resultCallback.accept(new SyncResult(false, "已有同步任务正在执行。", 0, 0));
            return;
        }

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        SyncCounts counts = syncMods(host, port, mode, progressCallback);
                        String message = "模式=" + mode + "，新增/更新=" + counts.synced() + "，删除=" + counts.deleted();
                        return new SyncResult(true, message, counts.synced(), counts.deleted());
                    } catch (Exception e) {
                        ClientModSyncLog.LOGGER.warn("[{}] 客户端模组同步失败", ClientModSyncMod.MOD_ID, e);
                        return new SyncResult(false, e.getMessage() == null ? "未知错误" : e.getMessage(), 0, 0);
                    } finally {
                        RUNNING.set(false);
                    }
                })
                .thenAccept(resultCallback);
    }

    private static SyncCounts syncMods(String host,
                                       int port,
                                       ModConfigHolder.SyncMode mode,
                                       Consumer<SyncProgress> progressCallback) throws Exception {
        SyncPlan plan = buildPlan(host, port, mode);
        int totalMods = plan.downloadTasks().size() + plan.deleteTasks().size();
        AtomicInteger finishedMods = new AtomicInteger(0);

        emitProgress(progressCallback, new SyncProgress(totalMods, 0, "", 0.0, false));

        int synced = runParallelDownloads(host, port, plan.downloadTasks(), totalMods, finishedMods, progressCallback);
        int deleted = runDeletePhase(plan.deleteTasks(), totalMods, finishedMods, progressCallback);
        return new SyncCounts(synced, deleted);
    }

    private static int runParallelDownloads(String host,
                                            int port,
                                            List<DownloadTask> downloadTasks,
                                            int totalMods,
                                            AtomicInteger finishedMods,
                                            Consumer<SyncProgress> progressCallback) throws Exception {
        if (downloadTasks.isEmpty()) {
            return 0;
        }

        int configuredThreads = ModConfigHolder.CLIENT_DOWNLOAD_THREADS.get();
        int threadCount = Math.max(1, Math.min(configuredThreads, downloadTasks.size()));

        ThreadFactory downloadFactory = runnable -> {
            Thread thread = new Thread(runnable, "clientmodsync-download");
            thread.setDaemon(true);
            return thread;
        };
        ThreadFactory progressFactory = runnable -> {
            Thread thread = new Thread(runnable, "clientmodsync-progress");
            thread.setDaemon(true);
            return thread;
        };

        ExecutorService downloadPool = Executors.newFixedThreadPool(threadCount, downloadFactory);
        ScheduledExecutorService progressReporter = Executors.newSingleThreadScheduledExecutor(progressFactory);
        CompletionService<String> completionService = new ExecutorCompletionService<>(downloadPool);

        AtomicLong bytesBucket = new AtomicLong(0L);
        AtomicReference<String> lastMod = new AtomicReference<>("");
        ConcurrentHashMap<String, Boolean> runningMods = new ConcurrentHashMap<>();

        progressReporter.scheduleAtFixedRate(() -> {
            long bytes = bytesBucket.getAndSet(0L);
            double speed = bytes / 0.2D;
            String current = summarizeCurrent(runningMods, lastMod.get());
            emitProgress(progressCallback,
                    new SyncProgress(totalMods, finishedMods.get(), current, speed, !runningMods.isEmpty()));
        }, 200L, 200L, TimeUnit.MILLISECONDS);

        for (DownloadTask task : downloadTasks) {
            completionService.submit(() -> {
                runningMods.put(task.name(), Boolean.TRUE);
                lastMod.set(task.name());
                try {
                    downloadFile(host, port, task.name(), task.target(), bytesBucket::addAndGet);
                    return task.name();
                } finally {
                    runningMods.remove(task.name());
                }
            });
        }

        int synced = 0;
        Exception failure = null;
        for (int i = 0; i < downloadTasks.size(); i++) {
            try {
                String finishedName = completionService.take().get();
                synced++;
                int done = finishedMods.incrementAndGet();
                emitProgress(progressCallback, new SyncProgress(totalMods, done, finishedName, 0.0, false));
                ClientModSyncLog.LOGGER.info("[{}] 已同步模组文件：{}", ClientModSyncMod.MOD_ID, finishedName);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                failure = cause instanceof Exception ex ? ex : new Exception(cause);
                break;
            }
        }

        progressReporter.shutdownNow();
        if (failure != null) {
            downloadPool.shutdownNow();
            throw failure;
        }

        downloadPool.shutdown();
        downloadPool.awaitTermination(30, TimeUnit.SECONDS);
        emitProgress(progressCallback, new SyncProgress(totalMods, finishedMods.get(), "", 0.0, false));
        return synced;
    }

    private static int runDeletePhase(List<Path> deleteTasks,
                                      int totalMods,
                                      AtomicInteger finishedMods,
                                      Consumer<SyncProgress> progressCallback) {
        int deleted = 0;
        for (Path path : deleteTasks) {
            String name = path.getFileName().toString();
            emitProgress(progressCallback, new SyncProgress(totalMods, finishedMods.get(), "删除：" + name, 0.0, false));

            try {
                Files.deleteIfExists(path);
                deleted++;
                ClientModSyncLog.LOGGER.info("[{}] 严格模式已删除本地多余模组：{}", ClientModSyncMod.MOD_ID, name);
            } catch (IOException ex) {
                ClientModSyncLog.LOGGER.warn("[{}] 删除本地多余模组失败：{}", ClientModSyncMod.MOD_ID, name, ex);
            }

            int done = finishedMods.incrementAndGet();
            emitProgress(progressCallback, new SyncProgress(totalMods, done, name, 0.0, false));
        }
        return deleted;
    }

    private static String summarizeCurrent(ConcurrentHashMap<String, Boolean> runningMods, String fallback) {
        if (runningMods.isEmpty()) {
            return fallback == null ? "" : fallback;
        }

        List<String> list = new ArrayList<>(runningMods.keySet());
        int count = list.size();
        if (count == 1) {
            return list.get(0);
        }
        if (count == 2) {
            return list.get(0) + "、" + list.get(1);
        }
        return list.get(0) + " 等 " + count + " 个";
    }

    private static void emitProgress(Consumer<SyncProgress> progressCallback, SyncProgress progress) {
        if (progressCallback != null) {
            progressCallback.accept(progress);
        }
    }

    private static SyncPlan buildPlan(String host, int port, ModConfigHolder.SyncMode mode) throws Exception {
        ManifestResponse manifest = fetchManifest(host, port);

        Path modsDir = FMLPaths.MODSDIR.get().toAbsolutePath().normalize();
        Files.createDirectories(modsDir);

        Map<String, ModEntry> serverMods = new HashMap<>();
        if (manifest.mods != null) {
            for (ModEntry entry : manifest.mods) {
                if (entry == null || entry.name == null || entry.name.isBlank()) {
                    continue;
                }
                serverMods.put(entry.name, entry);
            }
        }

        List<DownloadTask> downloadTasks = new ArrayList<>();
        for (ModEntry entry : serverMods.values()) {
            Path target = modsDir.resolve(entry.name).normalize();
            if (!target.startsWith(modsDir)) {
                continue;
            }

            boolean needsDownload = !Files.exists(target);
            if (!needsDownload
                    && mode == ModConfigHolder.SyncMode.STRICT
                    && entry.sha1 != null
                    && !entry.sha1.isBlank()) {
                String localSha1 = sha1(target);
                if (!entry.sha1.equalsIgnoreCase(localSha1)) {
                    needsDownload = true;
                }
            }

            if (needsDownload) {
                downloadTasks.add(new DownloadTask(entry.name, target));
            }
        }

        List<Path> deleteTasks = new ArrayList<>();
        if (mode == ModConfigHolder.SyncMode.STRICT) {
            try (Stream<Path> files = Files.list(modsDir)) {
                for (Path localFile : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .toList()) {
                    String name = localFile.getFileName().toString();
                    if (serverMods.containsKey(name) || isProtectedLocalMod(name)) {
                        continue;
                    }
                    deleteTasks.add(localFile);
                }
            }
        }

        return new SyncPlan(downloadTasks, deleteTasks);
    }

    private static ManifestResponse fetchManifest(String host, int port) throws Exception {
        URI uri = new URI("http", null, host, port, "/manifest.json", null, null);
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("获取服务端清单失败，HTTP 状态码=" + response.statusCode());
        }

        ManifestResponse parsed = GSON.fromJson(response.body(), ManifestResponse.class);
        if (parsed == null) {
            parsed = new ManifestResponse();
        }
        return parsed;
    }

    private static void downloadFile(String host,
                                     int port,
                                     String fileName,
                                     Path target,
                                     LongConsumer byteCounter) throws Exception {
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = new URI("http", null, host, port, "/mods/" + encodedName, null, null);

        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("下载失败：" + fileName + "，HTTP 状态码=" + response.statusCode());
        }

        Path temp = Files.createTempFile(target.getParent(), "clientmodsync-", ".part");
        try (InputStream input = response.body();
             OutputStream output = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                if (byteCounter != null) {
                    byteCounter.add(read);
                }
            }
        } catch (Exception e) {
            Files.deleteIfExists(temp);
            throw e;
        }

        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha1(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean isProtectedLocalMod(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.startsWith(ClientModSyncMod.MOD_ID.toLowerCase(Locale.ROOT));
    }

    private static String resolveServerHost() {
        String configuredHost = ModConfigHolder.CLIENT_SERVER_IP.get().trim();
        if (!configuredHost.isBlank()) {
            return configuredHost;
        }

        ClientPacketListener packetListener = Minecraft.getInstance().getConnection();
        if (packetListener == null) {
            return null;
        }

        Connection connection = packetListener.getConnection();
        if (connection == null) {
            return null;
        }

        SocketAddress remoteAddress = connection.getRemoteAddress();
        if (!(remoteAddress instanceof InetSocketAddress inetAddress)) {
            return null;
        }

        String host = inetAddress.getHostString();
        return Objects.equals(host, "localhost") ? "127.0.0.1" : host;
    }

    public record NeedSyncCheckResult(boolean success,
                                      boolean needSync,
                                      int synced,
                                      int deleted,
                                      String message,
                                      String resolvedHost) {
    }

    public record SyncProgress(int totalMods,
                               int finishedMods,
                               String currentMod,
                               double speedBytesPerSec,
                               boolean downloading) {
    }

    public record SyncResult(boolean success, String message, int synced, int deleted) {
    }

    private record SyncCounts(int synced, int deleted) {
    }

    private record SyncPlan(List<DownloadTask> downloadTasks, List<Path> deleteTasks) {
    }

    private record DownloadTask(String name, Path target) {
    }

    private static final class ManifestResponse {
        private List<ModEntry> mods;
    }

    private static final class ModEntry {
        private String name;
        private String sha1;
    }

    @FunctionalInterface
    private interface LongConsumer {
        void add(long value);
    }

    private ClientSyncService() {
    }
}
