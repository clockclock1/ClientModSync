# ClientModSync (Forge 1.20.1)

Server provides mod files from `clientmod`, and clients synchronize based on configuration.

服务端提供 `clientmod` 内的模组文件，客户端按配置执行同步。

## Features
- Server:
  - Reads `server_root/clientmod`
  - Starts an HTTP sync service
  - Exposes:
    - `GET /manifest.json`
    - `GET /mods/<fileName>`
- Client:
  - Checks whether synchronization is needed after mod load (at title screen)
  - If no sync is needed, no sync UI is shown
  - If sync is needed, shows sync UI
  - Supports manual click (`Sync Now`)
  - Supports auto mode (`Auto Next Time`) with 5-second countdown
  - Shows progress bar (by mod count), current mod, and realtime speed during sync
  - Automatically restarts client after sync completes to apply mod changes

## 功能说明
- 服务端：
  - 读取 `server_root/clientmod`
  - 启动 HTTP 同步服务
  - 提供接口：
    - `GET /manifest.json`
    - `GET /mods/<fileName>`
- 客户端：
  - 模组加载后（主菜单）先检查是否需要同步
  - 若检测为“不需要同步”，不会弹出同步界面
  - 若检测为“需要同步”，弹出同步界面
  - 支持手动点击（`立即同步`）
  - 支持自动模式（`下次及以后自动同步`，展示 5 秒后自动执行）
  - 同步过程中显示进度条（按模组数量）、当前同步模组、实时速度
  - 同步完成后自动重启客户端以应用变更

## Sync Modes
- `ADD_ONLY`: Download missing mods only; do not delete extra local mods
- `STRICT`: Strictly match server `clientmod` (download/update missing or mismatched mods, delete extra local mods)

## 同步模式
- `ADD_ONLY`：缺少则补，多余不处理
- `STRICT`：严格同步（缺少补齐、哈希不一致更新、多余删除）

## Configuration
Config file: `config/clientmodsync-common.toml`

- Server:
  - `server.httpPort=58235`

- Client:
  - `client.enabled=true`
  - `client.serverIp=""` (empty = use current connected server address)
  - `client.serverPort=58235`
  - `client.downloadThreads=4` (parallel download threads; higher is usually faster)
  - `client.syncMode="ADD_ONLY"` (`ADD_ONLY` or `STRICT`)

Client auto preference file:
- `config/clientmodsync-client.properties`

## 配置
配置文件：`config/clientmodsync-common.toml`

- 服务端：
  - `server.httpPort=58235`

- 客户端：
  - `client.enabled=true`
  - `client.serverIp=""`（留空则使用当前连接服务器地址）
  - `client.serverPort=58235`
  - `client.downloadThreads=4`（并行下载线程数，越高通常越快）
  - `client.syncMode="ADD_ONLY"`（`ADD_ONLY` 或 `STRICT`）

客户端自动偏好配置文件：
- `config/clientmodsync-client.properties`

## Build
- Requires JDK 17
- Windows: `gradlew build`
- Output: `build/libs/clientmodsync-1.0.0.jar`

## 构建
- 需要 JDK 17
- Windows：`gradlew build`
- 产物：`build/libs/clientmodsync-1.0.0.jar`

## Deployment
1. Put `clientmodsync-1.0.0.jar` into both server and client `mods`
2. Put required client mods into server `clientmod`
3. Start server and launch client
4. Run sync in UI and wait for automatic restart

## 部署
1. 将 `clientmodsync-1.0.0.jar` 放入服务端和客户端的 `mods`
2. 将需要同步的模组放入服务端 `clientmod`
3. 启动服务端并打开客户端
4. 在弹窗执行同步，随后客户端会自动重启