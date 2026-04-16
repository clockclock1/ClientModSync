package com.example.clientmodsync;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClientRestartHelper {
    private static final AtomicBoolean RESTARTING = new AtomicBoolean(false);

    public static void restartClientAsync(int delaySeconds) {
        if (!RESTARTING.compareAndSet(false, true)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(Math.max(0, delaySeconds) * 1000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            boolean relaunched = tryRelaunch();
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> {
                if (!relaunched && minecraft.player != null) {
                    minecraft.player.sendSystemMessage(Component.literal("[ClientModSync] 自动重启失败，已关闭客户端，请手动重新启动。"));
                }
                minecraft.stop();
            });
        });
    }

    private static boolean tryRelaunch() {
        try {
            List<String> command = buildLaunchCommand();
            if (command.isEmpty()) {
                ClientModSyncLog.LOGGER.warn("[{}] 自动重启失败：无法解析当前启动命令", ClientModSyncMod.MOD_ID);
                return false;
            }

            new ProcessBuilder(command).start();
            ClientModSyncLog.LOGGER.info("[{}] 已拉起新的客户端进程，准备关闭当前客户端", ClientModSyncMod.MOD_ID);
            return true;
        } catch (Exception e) {
            ClientModSyncLog.LOGGER.warn("[{}] 自动重启失败：拉起新进程异常", ClientModSyncMod.MOD_ID, e);
            return false;
        }
    }

    private static List<String> buildLaunchCommand() {
        String javaExec = resolveJavaExec();
        String sunCommand = System.getProperty("sun.java.command");
        if (sunCommand == null || sunCommand.isBlank()) {
            return List.of();
        }

        List<String> commandArgs = splitCommandLine(sunCommand);
        if (commandArgs.isEmpty()) {
            return List.of();
        }

        List<String> command = new ArrayList<>();
        command.add(javaExec);
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());

        String mainOrJar = commandArgs.get(0);
        if (mainOrJar.toLowerCase().endsWith(".jar")) {
            command.add("-jar");
            command.add(mainOrJar);
        } else {
            command.add(mainOrJar);
        }

        if (commandArgs.size() > 1) {
            command.addAll(commandArgs.subList(1, commandArgs.size()));
        }

        return command;
    }

    private static String resolveJavaExec() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "javaw.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static List<String> splitCommandLine(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }

            if (Character.isWhitespace(c) && !inQuotes) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            current.append(c);
        }

        if (!current.isEmpty()) {
            result.add(current.toString());
        }

        return result;
    }

    private ClientRestartHelper() {
    }
}
