package com.example.clientmodsync;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;

public final class SyncPromptScreen extends Screen {
    private static final int AUTO_DELAY_SECONDS = 5;

    private final Screen parent;

    private Button syncButton;
    private Button autoButton;
    private Button skipButton;

    private boolean autoClick;
    private int countdownTicks = -1;
    private boolean syncing;
    private boolean restarting;
    private String status = "请选择同步方式。";

    private int totalMods = 0;
    private int finishedMods = 0;
    private String currentMod = "";
    private double speedBytesPerSec = 0.0;
    private boolean downloadingNow = false;

    public SyncPromptScreen(Screen parent) {
        super(Component.literal("客户端模组同步"));
        this.parent = parent;
        this.autoClick = ClientPreferenceStore.isAutoClickSync();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = this.height / 2 - 24;

        this.syncButton = this.addRenderableWidget(Button.builder(Component.literal("立即同步"), button -> startSync())
                .bounds(centerX - 100, startY, 200, 20)
                .build());

        this.autoButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> toggleAutoClick())
                .bounds(centerX - 100, startY + 24, 200, 20)
                .build());

        this.skipButton = this.addRenderableWidget(Button.builder(Component.literal("本次跳过"), button -> closeScreen())
                .bounds(centerX - 100, startY + 48, 200, 20)
                .build());

        refreshAutoButtonLabel();
        if (autoClick) {
            countdownTicks = AUTO_DELAY_SECONDS * 20;
            status = "已开启自动同步，5 秒后将自动执行。";
        }
    }

    @Override
    public void tick() {
        super.tick();

        if (!autoClick || syncing || restarting) {
            return;
        }

        if (countdownTicks > 0) {
            countdownTicks--;
            refreshSyncButtonLabel();
            return;
        }

        if (countdownTicks == 0) {
            startSync();
        }
    }

    private void toggleAutoClick() {
        autoClick = !autoClick;
        ClientPreferenceStore.setAutoClickSync(autoClick);
        if (autoClick) {
            countdownTicks = AUTO_DELAY_SECONDS * 20;
            status = "已开启下次及以后自动同步，展示 5 秒后自动执行。";
        } else {
            countdownTicks = -1;
            status = "已关闭自动同步。";
        }
        refreshAutoButtonLabel();
        refreshSyncButtonLabel();
    }

    private void startSync() {
        if (syncing || restarting || ClientSyncService.isRunning()) {
            return;
        }

        syncing = true;
        countdownTicks = -1;
        status = "正在同步，请稍候...";
        totalMods = 0;
        finishedMods = 0;
        currentMod = "";
        speedBytesPerSec = 0.0;
        downloadingNow = false;

        refreshSyncButtonLabel();
        syncButton.active = false;
        autoButton.active = false;
        skipButton.active = false;

        ClientSyncService.startSyncAsync(progress -> {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> applyProgress(progress));
        }, result -> {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> {
                syncing = false;
                syncButton.active = true;
                autoButton.active = true;
                skipButton.active = true;

                if (result.success()) {
                    restarting = true;
                    syncButton.active = false;
                    autoButton.active = false;
                    skipButton.active = false;
                    status = "同步完成：新增/更新 " + result.synced() + " 个，删除 " + result.deleted() + " 个。3 秒后自动重启客户端。";
                    if (minecraft.player != null) {
                        minecraft.player.sendSystemMessage(Component.literal("[ClientModSync] 同步完成，客户端将自动重启。"));
                    }
                    ClientRestartHelper.restartClientAsync(3);
                } else {
                    status = "同步失败：" + (result.message() == null ? "未知错误" : result.message());
                }
                refreshSyncButtonLabel();
            });
        });
    }

    private void applyProgress(ClientSyncService.SyncProgress progress) {
        this.totalMods = progress.totalMods();
        this.finishedMods = progress.finishedMods();
        this.currentMod = progress.currentMod() == null ? "" : progress.currentMod();
        this.speedBytesPerSec = Math.max(0.0, progress.speedBytesPerSec());
        this.downloadingNow = progress.downloading();

        if (syncing && totalMods > 0) {
            status = "正在同步：" + finishedMods + "/" + totalMods;
        }
    }

    private void refreshAutoButtonLabel() {
        if (autoButton == null) {
            return;
        }
        autoButton.setMessage(Component.literal("下次及以后自动同步：" + (autoClick ? "开启" : "关闭")));
    }

    private void refreshSyncButtonLabel() {
        if (syncButton == null) {
            return;
        }

        if (syncing) {
            syncButton.setMessage(Component.literal("同步中..."));
            return;
        }

        if (autoClick && countdownTicks >= 0) {
            int seconds = countdownTicks / 20;
            syncButton.setMessage(Component.literal("立即同步（" + seconds + " 秒后自动执行）"));
            return;
        }

        syncButton.setMessage(Component.literal("立即同步"));
    }

    private void closeScreen() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        closeScreen();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);

        int centerX = this.width / 2;
        guiGraphics.drawCenteredString(this.font, this.title, centerX, 40, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font,
                Component.literal("服务端：" + displayServerHost() + " : " + ModConfigHolder.CLIENT_SERVER_PORT.get()),
                centerX, 62, 0xC0C0C0);
        guiGraphics.drawCenteredString(this.font,
                Component.literal("模式：" + displaySyncMode(ModConfigHolder.CLIENT_SYNC_MODE.get())),
                centerX, 74, 0xC0C0C0);

        int barWidth = 220;
        int barHeight = 10;
        int barX = centerX - barWidth / 2;
        int barY = this.height / 2 + 84;

        if (syncing || restarting || totalMods > 0) {
            float ratio = totalMods <= 0 ? 0.0F : Mth.clamp((float) finishedMods / (float) totalMods, 0.0F, 1.0F);
            int fillWidth = (int) (barWidth * ratio);
            guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF303030);
            guiGraphics.fill(barX, barY, barX + fillWidth, barY + barHeight, 0xFF55CC55);
            guiGraphics.drawCenteredString(this.font,
                    Component.literal("进度：" + finishedMods + "/" + totalMods),
                    centerX, barY - 12, 0xA0FFA0);
            guiGraphics.drawCenteredString(this.font,
                    Component.literal("当前模组：" + (currentMod.isBlank() ? "无" : currentMod)),
                    centerX, barY + 14, 0xE0E0E0);
            guiGraphics.drawCenteredString(this.font,
                    Component.literal("速度：" + (downloadingNow ? formatSpeed(speedBytesPerSec) : "0 B/s")),
                    centerX, barY + 26, 0xE0E0E0);
        } else {
            guiGraphics.drawCenteredString(this.font,
                    Component.literal("同步后需重启客户端才能加载变更。"),
                    centerX, 96, 0xFFE080);
        }

        guiGraphics.drawCenteredString(this.font, Component.literal(status), centerX, this.height / 2 + 122, 0xA0FFA0);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 1024.0) {
            return String.format(Locale.ROOT, "%.0f B/s", bytesPerSecond);
        }
        if (bytesPerSecond < 1024.0 * 1024.0) {
            return String.format(Locale.ROOT, "%.1f KB/s", bytesPerSecond / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MB/s", bytesPerSecond / (1024.0 * 1024.0));
    }

    private static String displayServerHost() {
        String configured = ModConfigHolder.CLIENT_SERVER_IP.get().trim();
        return configured.isBlank() ? "当前连接服务器" : configured;
    }

    private static String displaySyncMode(ModConfigHolder.SyncMode mode) {
        return mode == ModConfigHolder.SyncMode.STRICT ? "严格同步" : "缺少则补";
    }
}
