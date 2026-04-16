package com.example.clientmodsync;

import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(ClientModSyncMod.MOD_ID)
public class ClientModSyncMod {
    public static final String MOD_ID = "clientmodsync";

    public ClientModSyncMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ModConfigHolder.SPEC);
    }
}