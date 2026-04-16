package com.example.clientmodsync;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ClientPreferenceStore {
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("clientmodsync-client.properties");
    private static final String KEY_AUTO_CLICK = "autoClickSync";

    private static boolean loaded = false;
    private static boolean autoClickSync = false;

    public static synchronized boolean isAutoClickSync() {
        ensureLoaded();
        return autoClickSync;
    }

    public static synchronized void setAutoClickSync(boolean enabled) {
        ensureLoaded();
        autoClickSync = enabled;
        save();
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }

        loaded = true;
        if (!Files.isRegularFile(FILE)) {
            return;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(FILE)) {
            properties.load(input);
            autoClickSync = Boolean.parseBoolean(properties.getProperty(KEY_AUTO_CLICK, "false"));
        } catch (IOException e) {
            ClientModSyncLog.LOGGER.warn("[{}] 读取客户端偏好配置失败", ClientModSyncMod.MOD_ID, e);
        }
    }

    private static void save() {
        Properties properties = new Properties();
        properties.setProperty(KEY_AUTO_CLICK, Boolean.toString(autoClickSync));

        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream output = Files.newOutputStream(FILE)) {
                properties.store(output, "ClientModSync 客户端偏好");
            }
        } catch (IOException e) {
            ClientModSyncLog.LOGGER.warn("[{}] 保存客户端偏好配置失败", ClientModSyncMod.MOD_ID, e);
        }
    }

    private ClientPreferenceStore() {
    }
}
