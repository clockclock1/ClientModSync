package com.example.clientmodsync;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ClientModSyncMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientStartupEvents {
    private static volatile boolean checkStarted = false;
    private static volatile boolean checkFinished = false;
    private static volatile boolean needSync = false;

    private static boolean prompted = false;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || prompted || !ModConfigHolder.CLIENT_ENABLED.get()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null || minecraft.screen == null) {
            return;
        }

        if (!(minecraft.screen instanceof TitleScreen titleScreen)) {
            return;
        }

        if (!checkStarted) {
            checkStarted = true;
            ClientSyncService.startNeedSyncCheckAsync(result -> {
                checkFinished = true;
                needSync = result.success() && result.needSync();

                if (!result.success()) {
                    ClientModSyncLog.LOGGER.warn("[{}] 启动时同步检查失败：{}", ClientModSyncMod.MOD_ID, result.message());
                } else if (!result.needSync()) {
                    ClientModSyncLog.LOGGER.info("[{}] 检测到与服务端无需同步，不弹出同步界面。", ClientModSyncMod.MOD_ID);
                }
            });
            return;
        }

        if (!checkFinished) {
            return;
        }

        prompted = true;
        if (needSync) {
            minecraft.setScreen(new SyncPromptScreen(titleScreen));
        }
    }

    private ClientStartupEvents() {
    }
}