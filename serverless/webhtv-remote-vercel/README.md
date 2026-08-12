# WebHTV Remote Vercel Edge Relay

这是 Vercel Edge Functions 版本的 WebHTV 服务端。远程托管 relay 保持零配置内存模式；“增强功能 -> 观影记录同步”可连接 Vercel KV 或 Upstash Redis REST 持久保存进度、增量游标和 90 天删除墓碑。

新版 App 会在请求头中自动携带 `X-WebHTV-Origin`，服务端优先用该值作为 `serverOrigin`。因此 Vercel 默认域名、自定义域名或反代入口同时存在时，只要 App 中填写的是同一个公开访问域名，就不需要额外配置环境变量，也不会因为入口域名不一致导致绑定码校验失败。旧客户端未携带该头时仍回退到请求实际 origin。

## 部署

在 Vercel 新建项目时，入口目录选择仓库里的：

```text
serverless/webhtv-remote-vercel
```

接口入口是：

```text
serverless/webhtv-remote-vercel/api/index.js
```

入口依赖的 relay、同步协议和 Redis 存储文件都在 `api` 目录，所以把 `serverless/webhtv-remote-vercel` 作为 Vercel 项目根目录即可部署。

如需观影记录同步，在 Vercel 项目中配置以下一组环境变量：

```text
KV_REST_API_URL
KV_REST_API_TOKEN
```

也兼容 Upstash 原生名称：

```text
UPSTASH_REDIS_REST_URL
UPSTASH_REDIS_REST_TOKEN
```

可选设置 `WEBHTV_PLAYBACK_REDIS_PREFIX` 修改键前缀。URL 和 Token 必须来自同一个 Redis 数据库，Token 应作为服务端环境变量保存，不要写入仓库。

部署后访问：

```text
GET /api/server/capabilities
```

返回 `serverMode=vercel`、`relayMode=origin-token-memory` 即表示 relay 可用；配置 Redis 后还会返回 `capabilities.playbackSync=true`。

## 观影记录同步

在 App 的“远端同步”和“Webhook 上报”中填写同一个地址与 token：

```text
https://<你的 Vercel 域名>/api/playback/sync
```

也兼容 `/playback/sync`。`POST` 接收进度、完播和删除事件，`GET` 按 `X-WebHTV-Since` 返回增量，`GET /api/playback/sync/status` 返回记录数和最新游标。请求必须携带 `X-WebHTV-Token` 与 `X-WebHTV-Config-Key`；服务通过 Redis `EVAL` 原子比较并替换同一用户空间状态，Token 和配置键只以 SHA-256 哈希参与分区。

未配置 Redis 时同步能力会明确报告为不可用，接口返回 503，不会退化成可能丢数据的内存同步。请求体最多 128 KiB，单批最多 100 条，单次拉取最多 1000 条；删除墓碑和幂等事件保留 90 天。

## 限制

运行实例重启或被平台回收后，relay 的绑定码、未投递命令和一次性同步分片会丢失；Redis 中的观影记录不会随实例回收丢失。同一个域名/origin 下不需要重新绑定，App/主控端保存的 `deviceToken/groupToken` 会在下一次 register/poll 时重建在线路由。Redis 观影状态仍不等同于远程托管完整状态或长期文件备份。
