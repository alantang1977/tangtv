# WebHTV Remote Deno Relay

这是 Deno Deploy 版本的 WebHTV 服务端。远程托管 relay 保持零配置内存模式；“增强功能 -> 观影记录同步”使用 Deno KV 持久保存进度、增量游标和 90 天删除墓碑。

新版 App 会在请求头中自动携带 `X-WebHTV-Origin`，服务端优先用该值作为 `serverOrigin`。因此 Deno 默认域名、自定义域名或反代入口同时存在时，只要 App 中填写的是同一个公开访问域名，就不需要额外配置环境变量，也不会因为入口域名不一致导致绑定码校验失败。旧客户端未携带该头时仍回退到请求实际 origin。

## 本地运行

```bash
cd serverless/webhtv-remote-deno
deno run --allow-net --unstable-kv main.js
```

部分较新的 Deno 版本不再需要 `--unstable-kv`；如果本机提示该参数不存在，去掉后重试。本地 KV 数据库和 Deno Deploy 上的托管 KV 相互独立。

## 部署

在 Deno Deploy 新建项目，入口选择：

```text
serverless/webhtv-remote-deno/main.js
```

也可以只上传 `serverless/webhtv-remote-deno` 目录；入口依赖的 relay、同步协议和 KV 存储文件都在同一目录。

部署后访问：

```text
GET /api/server/capabilities
```

返回 `serverMode=deno`、`relayMode=origin-token-memory`，并且 `capabilities.playbackSync=true`，表示 relay 与 Deno KV 观影同步都已可用。

## 观影记录同步

在 App 的“远端同步”和“Webhook 上报”中填写同一个地址与 token：

```text
https://<你的 Deno 域名>/api/playback/sync
```

也兼容 `/playback/sync`。`POST` 接收进度、完播和删除事件，`GET` 按 `X-WebHTV-Since` 返回增量，`GET /api/playback/sync/status` 返回记录数和最新游标。请求必须携带 `X-WebHTV-Token` 与 `X-WebHTV-Config-Key`；Token 和配置键只以 SHA-256 哈希参与 KV 分区，不以明文写入状态。

Deno KV 单值有大小边界，因此实现使用 manifest 加 48 KiB 分块，并通过 versionstamp 原子比较更新。请求体最多 128 KiB，单批最多 100 条，单次拉取最多 1000 条；删除墓碑和幂等事件保留 90 天。

## 限制

运行实例重启或被平台回收后，relay 的绑定码、未投递命令和一次性同步分片会丢失；Deno KV 中的观影记录不会随实例回收丢失。同一个域名/origin 下不需要重新绑定，App/主控端保存的 `deviceToken/groupToken` 会在下一次 register/poll 时重建在线路由。Deno KV 仍不等同于远程托管完整状态或长期文件备份。
