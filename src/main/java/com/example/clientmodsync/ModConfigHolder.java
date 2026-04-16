package com.example.clientmodsync;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ModConfigHolder {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue SERVER_HTTP_PORT;
    public static final ForgeConfigSpec.BooleanValue CLIENT_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> CLIENT_SERVER_IP;
    public static final ForgeConfigSpec.IntValue CLIENT_SERVER_PORT;
    public static final ForgeConfigSpec.IntValue CLIENT_DOWNLOAD_THREADS;
    public static final ForgeConfigSpec.EnumValue<SyncMode> CLIENT_SYNC_MODE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("server");
        SERVER_HTTP_PORT = builder
                .comment("服务端用于提供 clientmod 文件同步的 HTTP 端口")
                .defineInRange("httpPort", 58235, 1024, 65535);
        builder.pop();

        builder.push("client");
        CLIENT_ENABLED = builder
                .comment("是否启用客户端同步功能")
                .define("enabled", true);
        CLIENT_SERVER_IP = builder
                .comment("同步服务端 IP 或主机名，留空时使用当前连接服务器地址")
                .define("serverIp", "");
        CLIENT_SERVER_PORT = builder
                .comment("同步服务端 HTTP 端口")
                .defineInRange("serverPort", 58235, 1024, 65535);
        CLIENT_DOWNLOAD_THREADS = builder
                .comment("客户端下载线程数（多线程可提升下载速度）")
                .defineInRange("downloadThreads", 4, 1, 16);
        CLIENT_SYNC_MODE = builder
                .comment("同步模式：ADD_ONLY=缺少则补，STRICT=严格与服务端 clientmod 一致")
                .defineEnum("syncMode", SyncMode.ADD_ONLY);
        builder.pop();

        SPEC = builder.build();
    }

    private ModConfigHolder() {
    }

    public enum SyncMode {
        ADD_ONLY,
        STRICT
    }
}
