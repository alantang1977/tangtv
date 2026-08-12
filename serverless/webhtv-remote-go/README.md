# WebHTV Go Remote Relay

Go 版远程托管中转服务，兼容现有 HTTP API，并额外提供 WebSocket 实时命令通道。

## 运行

已编译好的 Linux 二进制在：

```text
dist/webhtv-remote-go-linux-amd64
dist/webhtv-remote-go-linux-arm64
```

直接运行：

```bash
chmod +x dist/webhtv-remote-go-linux-amd64
WEBHTV_REMOTE_ADDR=:8787 ./dist/webhtv-remote-go-linux-amd64
```

也可以从源码运行：

```bash
cd serverless/webhtv-remote-go
go mod tidy
go run .
```

默认监听 `:8787`，可通过环境变量覆盖：

```bash
WEBHTV_REMOTE_ADDR=:8787 go run .
```

观影记录默认保存到当前工作目录的 `webhtv-playback.json`。生产环境建议使用绝对路径：

```bash
WEBHTV_REMOTE_ADDR=:8787 \
WEBHTV_PLAYBACK_DATA=/var/lib/webhtv/webhtv-playback.json \
go run .
```

仅测试协议、不落盘时可设置 `WEBHTV_PLAYBACK_DATA=:memory:`。

## 能力

- 兼容现有接口：设备注册、绑定码、设备列表、命令、同步分片。
- 新增 WebSocket：`/api/device/ws`。
- App 会优先使用 WebSocket；服务端不支持时自动回退 HTTP 轮询。
- 远程托管 relay 仍为内存状态，进程重启会丢失绑定码、命令队列和同步分片。
- 观影记录同步单独使用本地 JSON 文件持久化，不随 relay 重启丢失。

## 观影记录同步

在 App 的“远端同步”和“Webhook 上报”中填写同一个地址与 token：

```text
https://<你的服务域名>/api/playback/sync
```

也兼容 `/playback/sync`。`POST` 接收进度、完播和删除事件，`GET` 按 `X-WebHTV-Since` 返回增量，`GET /api/playback/sync/status` 返回记录数和最新游标。请求必须携带 `X-WebHTV-Token` 与 `X-WebHTV-Config-Key`，持久文件中只保存它们的 SHA-256 分区键。

文件通过临时文件、`fsync` 和原子 rename 更新，权限为 `0600`。请定期备份 `WEBHTV_PLAYBACK_DATA` 指向的文件；同一文件只应由一个 Go 服务进程写入，不要让多个副本或容器共享写入。请求体最多 128 KiB，单批最多 100 条，单次拉取最多 1000 条；删除墓碑和幂等事件保留 90 天。

## 反向代理

若部署在 Nginx/Caddy 后面，需要透传 WebSocket：

```nginx
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";
proxy_set_header Host $host;
proxy_set_header X-Forwarded-Host $host;
proxy_set_header X-Forwarded-Proto $scheme;
```

新版 App 会自动发送 `X-WebHTV-Origin`，服务端优先使用该值作为 `serverOrigin`；没有该头时才回退到 `X-Forwarded-Host` / `Host`。因此新版 App 通常不需要额外配置，反向代理仍建议正确透传外部访问的 scheme/host，兼容旧客户端和手动调试请求。
