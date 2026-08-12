# WebHTV Remote + Playback Sync Cloudflare Worker

这是 WebHTV 的 Cloudflare Worker serverless 服务，包含两套相互隔离的 Durable Object：

- `RELAY_DO`：为“远程托管”提供在线命令和一次性同步文件中转。
- `PLAYBACK_DO`：为“增强功能 -> 观影记录同步”持久保存最新进度、增量游标和 90 天删除墓碑。

两项能力复用同一个 Worker 域名，但不会共用状态。服务不需要 KV、R2、外部数据库或必填环境变量；观影记录由 Durable Object 内置 SQLite 持久化。

Cloudflare Worker 普通全局变量不能保证两台设备命中同一个运行实例，所以默认配置使用 Durable Object 统一承载绑定码、在线设备快照和命令队列，并保存轻量状态快照。长期绑定状态仍保存在 App/主控端本地，`deviceId/groupId/grantId` 由 `serverOrigin + token` 派生。同步文件分片仍是短期中转数据，不适合作为长期备份存储。需要离线队列、大文件暂存或长期备份时，再换完整版 Go/Rust 服务端或给 serverless 版本加 R2 等存储增强。

新版 App 会在请求头中自动携带 `X-WebHTV-Origin`，服务端优先用该值作为 `serverOrigin`。因此 Cloudflare 同时存在 `*.workers.dev` 和自定义域名时，只要 App 中填写的是同一个自定义域名，就不需要额外配置环境变量，也不会因为平台默认域名参与 ID 派生而导致绑定码校验失败。旧客户端未携带该头时仍回退到请求实际 origin。

## 部署

```bash
cd serverless/webhtv-remote-cloudflare
npm install
cp wrangler.toml.example wrangler.toml
npm run deploy
```

`wrangler.toml.example` 已包含两套 Durable Object 绑定和迁移配置，复制后可以直接部署。旧版本如果已经部署过 `RELAY_DO`，保留原有 `v1`，再追加 `PLAYBACK_DO` 绑定和 `v2` 迁移：

```toml
[[durable_objects.bindings]]
name = "PLAYBACK_DO"
class_name = "WebHTVPlaybackSyncDO"

[[migrations]]
tag = "v2"
new_sqlite_classes = ["WebHTVPlaybackSyncDO"]
```

不要修改已经发布过的 `v1` migration tag；Cloudflare 升级时只需追加 `v2`。

## 观影记录同步

部署后使用同一个地址同时配置“远端同步源”和“Webhook 上报”：

```text
https://<你的 Worker 域名>/api/playback/sync
```

也兼容 `/playback/sync`。该地址的行为由 HTTP 方法区分：

| 方法 | 用途 |
| --- | --- |
| `POST` | 接收 `playback.progress`、`playback.ended`、`playback.deleted` Webhook，也支持最多 100 条的批量写入 |
| `GET` | 按 `X-WebHTV-Since` 拉取增量进度和删除墓碑 |
| `GET /api/playback/sync/status` | 查看当前 token、`configKey` 空间的记录数和最新游标 |

### App 配置

1. 自己生成一个足够随机的 token，例如 `openssl rand -hex 32`。服务端不负责签发 token，token 就是用户空间凭证，请勿公开。
2. 在“增强功能 -> 观影记录同步 -> 远端同步”中新增同步源，URL 填上面的地址，token 填刚生成的值。
3. 在“Webhook 上报”中新增端点，填写完全相同的 URL 和 token。字段预设使用“基础”“标准”或“完整”；匿名预设以及缺少 `siteKey/vodId/vodName/episodeName` 的自定义预设不能作为完整同步数据源。
4. 其它设备填写同一 URL 和 token，即进入同一个用户空间；不同用户或不同数据空间应使用不同 token。

内置服务端要求 `X-WebHTV-Token` 和 `X-WebHTV-Config-Key`。token 只用于计算不可逆的 Durable Object 分区名，不会以明文写入 SQLite 或日志；同一 token 下仍按 `configKey` 隔离不同点播接口。

### curl 联调

写入一条进度：

```bash
curl -X POST 'https://<你的 Worker 域名>/api/playback/sync' \
  -H 'Content-Type: application/json' \
  -H 'X-WebHTV-Token: <你的 token>' \
  -H 'X-WebHTV-Config-Key: <点播接口 configKey>' \
  -d '{
    "event": "playback.progress",
    "eventId": "test-progress-1",
    "timestamp": 1781170000000,
    "historyKey": "site_key@@@vod_id@@@1",
    "siteKey": "site_key",
    "vodId": "vod_id",
    "vodName": "影片名",
    "episodeName": "第1集",
    "positionMs": 123456,
    "durationMs": 456789
  }'
```

从头拉取增量：

```bash
curl 'https://<你的 Worker 域名>/api/playback/sync' \
  -H 'X-WebHTV-Token: <你的 token>' \
  -H 'X-WebHTV-Config-Key: <点播接口 configKey>' \
  -H 'X-WebHTV-Since: 0' \
  -H 'X-WebHTV-Limit: 100'
```

写入单条删除墓碑：

```bash
curl -X POST 'https://<你的 Worker 域名>/api/playback/sync' \
  -H 'Content-Type: application/json' \
  -H 'X-WebHTV-Token: <你的 token>' \
  -H 'X-WebHTV-Config-Key: <点播接口 configKey>' \
  -d '{
    "event": "playback.deleted",
    "eventId": "test-delete-1",
    "scope": "item",
    "historyKey": "site_key@@@vod_id@@@1",
    "siteKey": "site_key",
    "vodId": "vod_id",
    "deletedAt": 1781170005000
  }'
```

查看状态：

```bash
curl 'https://<你的 Worker 域名>/api/playback/sync/status' \
  -H 'X-WebHTV-Token: <你的 token>' \
  -H 'X-WebHTV-Config-Key: <点播接口 configKey>'
```

服务端使用单调数字游标，拉取响应格式为 `{ "changes": [...], "nextSince": "...", "hasMore": false }`。较旧的进度不能覆盖较新的删除；删除后产生的新进度可以恢复该条目。`scope=all` 必须显式提交，缺少范围和条目标识的删除请求会被拒绝，避免误清空。

请求体上限为 128 KiB，单次写入最多 100 条，单次拉取最多 1000 条。删除墓碑和 Webhook 幂等记录保留 90 天。其它内置版本也实现了相同协议：Deno 使用 Deno KV，Vercel 使用 Vercel KV/Upstash Redis REST，Go 与 Rust 使用本地原子 JSON 文件；部署边界见各目录 README。

## 核心流程

1. 任意 WebHTV App 调用 `/api/device/register` 注册设备并保存 `deviceId/deviceToken`。
2. 被控端生成 `bindGrantToken`，调用 `/api/device/bind-code` 生成 6 位绑定码。
3. 主控端 Web 控制台调用 `/api/groups/claim` 输入绑定码，服务端返回 `groupToken/groupTokenHash/bindGrantToken`，主控端本地保存。
4. 另一台 WebHTV App 可用同一个 `groupToken` 注册为来源设备，也可以通过绑定码加入同一个设备组。
5. 主控端 Web 控制台调用 `/api/sync/create`，选择来源设备、目标设备和 `SyncOptions`。
6. 来源设备轮询到 `remoteSync.export` 命令后自动生成 `backup`、`syncFiles` 和同步内部文件包并提交到 Worker。
7. 目标设备轮询到 `remoteSync.restore` 命令后自动拉取临时文件并恢复。

## 约定

设备请求头：

```text
X-Device-Id: <deviceId>
Authorization: Bearer <deviceToken>
```

主控端 Web 控制台请求头：

```text
Authorization: Bearer <groupToken>
```

`/api/server/capabilities` 会返回 `serverMode=cloudflare`、`relayMode=cloudflare-durable-object` 和能力清单；配置 `PLAYBACK_DO` 后 `capabilities.playbackSync=true`。没有配置 `RELAY_DO` 时远程托管会降级为 `origin-token-memory`，该模式只适合本地调试，不建议生产使用。完整版 Go/Rust 服务端应复用同一套字段，只是开放更多能力。
