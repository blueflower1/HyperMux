# 70 · 部署与运维手册

> 隶属：[PLAN.md](../../PLAN.md) · 版本 v1.0 · 对应里程碑：M5 加固与部署
> 前提（PLAN 已锁定）：**OpenList 已在公网**（直链安全归 OpenList 自身策略）；本产品后端部署在**单节点**（NAS/轻量云/家用主机均可）。

---

## 1. 部署拓扑

```
Internet
  │
  ├─ OpenList（公网，已存在，本方案不改）
  │        ▲ TV 盒子直连播放直链（/d/**）
  │
  └─ 服务器（单节点，域名/公网 IP，443 或 8080）
        ├─ 8080  hypermux-server（Caddy 反代 443 → 8080，可选）
        │        ├─ /api/app/**     TV 端 API
        │        ├─ /api/admin/**   后台 API
        │        ├─ /（static）     admin 后台（同源部署，外置卷热替换）
        │        └─ 9090            actuator（仅本机 127.0.0.1，不映射公网）
        └─ PostgreSQL 16（仅容器内网，不映射公网）
```

端口暴露原则：**对外只有 8080（建议叠 443 反代）**；PG 5432 与 actuator 9090 均不映射宿主机公网接口。

---

## 2. docker-compose.yml（仓库根 `docker-compose.yml`）

```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: hypermux-pg
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${PG_DB:-hypermux}
      POSTGRES_USER: ${PG_USER:-hypermux}
      POSTGRES_PASSWORD: ${PG_PASSWORD:?err: 请在 .env 设置 PG_PASSWORD}
      TZ: Asia/Shanghai
    volumes:
      - pgdata:/var/lib/postgresql/data
    command:
      - -c
      - shared_buffers=256MB
      - -c
      - work_mem=16MB
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${PG_USER:-hypermux} -d ${PG_DB:-hypermux}"]
      interval: 5s
      timeout: 3s
      retries: 12
    # 不映射 5432。宿主机需要 psql 时临时: docker compose exec -it postgres psql -U hypermux

  server:
    build:
      context: ./server
      dockerfile: Dockerfile
    image: hypermux/server:${IMAGE_TAG:-latest}
    container_name: hypermux-server
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      PG_HOST: postgres
      PG_PORT: "5432"
      PG_DB: ${PG_DB:-hypermux}
      PG_USER: ${PG_USER:-hypermux}
      PG_PASSWORD: ${PG_PASSWORD:?err}
      JWT_SECRET: ${JWT_SECRET:?err: openssl rand -base64 32}
      HYPERMUX_SECRET: ${HYPERMUX_SECRET:?err: openssl rand -base64 32}
      SWAGGER_ENABLED: ${SWAGGER_ENABLED:-false}
      HYPERMUX_AUTO_MIGRATE: ${HYPERMUX_AUTO_MIGRATE:-true}
      ADMIN_BOOTSTRAP_PASSWORD: ${ADMIN_BOOTSTRAP_PASSWORD:-}
      HYPERMUX_STATIC_DIRS: ${HYPERMUX_STATIC_DIRS:-file:/app/static/}
      HYPERMUX_APK_DIR: ${HYPERMUX_APK_DIR:-/data/apks}
      TZ: Asia/Shanghai
    ports:
      - "8080:8080"
    volumes:
      - ./admin-dist:/app/static:ro        # admin 构建产物外置卷：换前端不需要重建 jar
      - ./apks:/data/apks                  # App 自更新 APK 落盘（发布上传后存此处，必须持久化）
    logging: &hypermux-logging
      driver: json-file
      options: { max-size: "10m", max-file: "5" }

volumes:
  pgdata:
```

`.env`（同源，权限 600，**不入库**）：

```ini
PG_DB=hypermux
PG_USER=hypermux
PG_PASSWORD=<openssl rand -base64 18>
JWT_SECRET=<openssl rand -base64 32>
HYPERMUX_SECRET=<openssl rand -base64 32>
ADMIN_BOOTSTRAP_PASSWORD=<首次安装的初始管理员密码，6~20 位（登录只校验长度，可含 @ 等符号；本仓库 dev 配置默认 Admin@1234，务必修改）>
IMAGE_TAG=20261009-1
```

> **密钥红线**：`HYPERMUX_SECRET` 变更 = 所有 `openlist_source.api_token` 立即不可解密 = 所有 OpenList 源要重填 Token。
> 备份磁盘时，`.env` 与 `pgdata` **同等级**重要；`JWT_SECRET` 变更 = 所有客户端强制重登（可接受）。
> `HYPERMUX_APK_DIR`（App 自更新 APK 目录，可选）：compose 默认 `/data/apks`（绑定 `./apks`）；改到容器内其他路径即**丢失已发布安装包**（记录仍在，App 下载 404，需重新发布）。备份/迁移部署时 `./apks` 与 `pgdata` 同级处理。

---

## 3. server/Dockerfile（multi-stage）

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /w
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY --from=build /w/target/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", -Xlog:gc*:file=/tmp/gc.log:time, "-jar", "/app/app.jar"]
```

`.dockerignore`（server/）：`target/**`、`*.iml`、`.git`、`src/main/resources/static/**`（静态走外置卷）。

启动参数说明：`HYPERMUX_STATIC_DIRS=file:/app/static/` 使 Spring 静态解析**优先找到外置卷**（classpath 兜底），admin 构建到 `./admin-dist` 后 `docker kill -s HUP?` 不需要——**静态文件每次请求读盘，刷新即生效**。`HYPERMUX_APK_DIR=/data/apks` 为 App 自更新（契约 §3.11）上传 APK 的落盘目录，每平台一个文件 `{platform}-latest.apk`（绑定挂载，跨容器重建保留）。

---

## 4. admin 前端发布流程（热替换，零停机）

```
cd admin && npm ci && npm run build     # outDir = ../admin-dist（vite 配置）
# 容器挂载 ./admin-dist:ro → 立即生效（无版本号冲突：resources 带 hash）
```

- 发版校验：`curl -sI localhost:8080/` 返回 200 且 index.html `<script src>` 新 hash；`GET /api/admin/dashboard` 后续加 `staticBuild` 字段对版（M5 小项）。
- 回滚前端：admin-dist 打 tag 目录（`admin-dist-v1` 软链切换）。

---

## 5. 首次安装（Runbook）

| 步 | 命令/动作 | 成功标志 |
|---|---|---|
| 1 | 部署 `.env`（生成密钥） | 文件 600 |
| 2 | `docker compose up -d --build` | 两个容器；`docker compose logs -f server` 无 ERROR |
| 3 | server 启动自动迁移（`HYPERMUX_AUTO_MIGRATE=true`）：幂等执行 `schema.sql` + `seed.sql`，并按 `ADMIN_BOOTSTRAP_PASSWORD` 创建 admin（已存在则跳过） | SQL 日志 "migrate done" |
| 4 | 健康检查 | `curl -s localhost:8080/actuator/health?mgmt` —— **actuator 走 127.0.0.1:9090**（见 §10）→ `{"status":"UP"}` |
| 5 | 后台登录 `/`（admin / 初始密码）→ **立即改密码**（用户管理→重置） | 旧密码失登 |
| 6 | 建池 + 手工 1 部影片（含 1 条 REMOTE 直链）→ 用 TV 端 curl 链路冒烟（30 契约 §6） | play-url 返回 https 直链 |
| 7 | 手机/盒子装 APK（API_BASE 填本服务地址）登录冒烟 | 首页见影片 |
| 8 | （M4）建 OpenList 源 → 全量刮削 → 发布候选 | 首页见刮出影片 |

> 手动迁移备选（不开 auto-migrate 时）：
> `docker compose exec -T postgres psql -U hypermux -d hypermux -f - < server/sql/schema.sql`
> `... -f - < server/sql/seed.sql`

**admin 自举规则**：`sys_user` 中 role=1 计数 = 0 且 `ADMIN_BOOTSTRAP_PASSWORD` 非空 → 创建 `admin`；否则完全跳过（防误覆盖）。初始密码在 `login` 成功后**首次进入后台强制改密**弹窗（M5 前端小需求，阻塞式——未改前其他菜单不可用）。

---

## 6. HTTPS 反代（推荐；Caddy 示例）

```
# /etc/caddy/Caddyfile
tv.example.com {
    encode gzip
    reverse_proxy 127.0.0.1:8080 {
        header_up Host {host}
    }
    # 登录接口限流防爆破（后端 429 之外的第二道篱笆）
    @login path /api/app/auth/login /api/admin/auth/login
    handle @login {
        rate_limit 30/s 60
        reverse_proxy 127.0.0.1:8080
    }
}
```

Caddy 自签自动续期；TV 端 release 构建 `API_BASE=https://tv.example.com`（`usesCleartextTraffic` release 关闭，只此一路）。
客户自签/内网 http 走 debug APK 或 `network-security-config` 例外（不推荐生产）。

---

## 7. 性能预算与压测口径

### 7.1 预算（P95，单节点 4C8G）

| 场景 | 接口 | 达标线 |
|---|---|---|
| 库 500 片 | home / 池列表(page=1) / 影片详情 | ≤300ms / ≤200ms / ≤150ms |
| 库 5000 片 | 同上 | ≤500ms / ≤400ms / ≤250ms |
| 任意 | play-url / progress / finish | ≤80ms |
| 任意 | 候选列表(200) / 任务列表 | ≤300ms |
| 库 5000 片 | 首页渲染(App 侧, 含图首刷) | ≤3s 首帧（网络正常） |

### 7.2 压测脚本（M5 留档）

```bash
T=<app token>
ab -k -n 2000 -c 30 -H "Authorization: Bearer $T" http://host:8080/api/app/home
ab -k -n 2000 -c 30 -H "Authorization: Bearer $T" "http://host:8080/api/app/category/1/movies?page=1&size=20"
ab -k -n 2000 -c 30 -p body.json -T application/json -H "Authorization: Bearer $T" http://host:8080/api/app/progress
```
关注：5xx = 0；P95 达标；PG active_conn < pool(10)；server RSS < 2GB。
数据准备：`insertMany` 脚本造 5000 片 / 50000 集（封面指同一 3 张占位图，避免海量 IO 干扰）。

### 7.3 缓存策略回检（20-backend §10 的部署验证）

- homeCache 命中：同用户 1min 内二刷 home → 日志 `cache=hit`；动影片后 invalidate 行为过一遍（编辑影片 10s 内首页已反映）。

---

## 8. 日志与监控

### 8.1 日志

- 统一 stdout 输出（docker json-file 滚动 10m×5），格式：
  `2026-10-09T14:22:01.120+08:00 INFO [trace=9f3k2] hypermux - [api] uid=1001 role=0 GET /api/app/home 200 41ms`
- MDC `trace=短uuid`；响应头回 `X-Trace-Id`（排障口径：用户报 bug → 先要 Trace-Id）。
- 敏感信息红线：不打 token、password、api_token；OpenList url 只打 host。
- 常用：
```bash
docker compose logs -f --since 10m server | grep -E "ERROR|trace=<id>"
docker compose exec postgres psql -U hypermux -d hypermux -c "SELECT status,count(*) FROM scrape_task GROUP BY 1;"
```

### 8.2 监控（轻方案）

| 项 | 做法 |
|---|---|
| 存活 | 云端 crontab：每 3min `curl -fsS 127.0.0.1:9090/actuator/health`，非 UP → 推送到 IM webhook；连续 2 失败 `docker compose restart server` |
| 磁盘 | `pgdata` 卷 > 80% → 告警（断点/任务表小，主要是日志与未来缩略图） |
| 业务指标 | `scrape_task` 最近 24h FINISHED 率、`app 接口 5xx`（grep 日志计数）每日摘要进 IM |
| 端口/证书 | https 证书到期（Caddy 自身负责续期，日志抽查即可） |

不引入 Prometheus/Grafana（单节点规模不值；M6 可选）。

### 8.3 排障速查表

| 现象 | 首要怀疑 | 处置 |
|---|---|---|
| TV 播流失败但 play-url 200 | OpenList 侧（直链 404/限速） | 浏览器直开 play-url 的 url 定位；OpenList 健康页 |
| TV 一直 401 | token 过期/服务器时间偏移 | 核对服务器 NTP；App 端"重新登录" |
| 首页慢、列表快 | home 缓存没建/PG 慢查询 | `EXPLAIN ANALYZE` home 对应 SQL；确认 Caffeine 开启 |
| DB 连接耗尽 | 泄漏长事务/连接未还 | 查 server 日志 HikariCP leak warning；重启容器止血 |
| 刮削卡死不动（RUNNING 不变） | OpenList 半死/超时 | source test；停任务 → FAILED；查 openlist 侧日志 |
| 后台 DNS 打不开但 API 200 | 静态外置卷空/挂载失效 | `docker inspect` 挂载；确认 `./admin-dist/index.html` 存在 |
| 磁盘文件都在但 TV 看不到某片 | missing 标记脏 / episode_total=0 | 管理台→影片详情→剧集看 missing；源 incremental 重扫 |

---

## 9. 备份 / 恢复 / 升级 / 回滚

### 9.1 备份

```bash
# 每日 cron（03:30），保留 30 天
docker compose exec -T postgres pg_dump -U hypermux -d hypermux -Fc > /backup/hypermux_$(date +\%F).dump
find /backup -name 'hypermux_*.dump' -mtime +30 -delete
# .env 随备份走（同目录 tar，权限 600）
```
恢复演练**每做一次大版本升级**（步骤见 9.2 备份恢复段）。

### 9.2 升级（版本迁移）

```
1. 备份（9.1）
2. git pull → docker compose build server → 打标: IMAGE_TAG=YYYYMMDD-n
3. docker compose up -d（postgres 不动；server 滚动）
4. 冒烟：health 200 / 后台登录 / 首页接口 / 1 次 play-url
5. 观察 10min 日志无 ERROR，完成
```
DB 变更规则：schema 改动必须**追加式 + 幂等**（先建新后弃旧，禁止原地改列类型）；auto-migrate 负责执行；不满足条件的变更走手工窗口（停写→迁移→起服）。

### 9.3 回滚

- 镜像：保留上一代 tag（compose `IMAGE_TAG` 切回）→ `docker compose up -d` 即刻回滚（DB 变更已执行的**只能前滚**：因此 schema 规则要求可容忍旧版本读取——前期无大改造，风险可接受）。
- 回滚演练纳入每次升级。

### 9.4 密钥轮换（高风险，单独口径）

| 密钥 | 轮换场景 | 代价 | 操作 |
|---|---|---|---|
| `JWT_SECRET` | 怀疑泄露 | 全部客户端重登 | 改 .env → restart server |
| `HYPERMUX_SECRET` | 怀疑泄露 | **所有 OpenList 源 Token 需重录**（加密件不可逆） | 备份 pgdata → 先备份 source 表明文 token?（存的是密文，无法导出！）——**因此要求：.env 入备份，轮换前把每个源的 Token 先重新录一遍**；日常不轮换 |
| `PG_PASSWORD` | 怀疑泄露 | 需 server 同窗口改 env | 备份 → pg 容器改名保留数据 → 新容器读新密码 |

---

## 10. 安全清单（上线前逐项过）

- [ ] PG 5432 / actuator 9090 **未**映射公网（`docker compose ps` 检查 PORTS）
- [ ] compose 未映射 9090；server 内 `management.server.port=9090`、`endpoints.web.exposure.include=health,info`（yml 迁入 20-backend §4 配套）
- [ ] `.env` 600，不在仓库；`.gitignore` 覆盖（含 `admin-dist/`、`keystore`）
- [ ] `SWAGGER_ENABLED=false`（生产）；401/423/429 三接口探针验证
- [ ] 管理员初始口令已改；用户口令规则生效（后台建号页前端校验 + 后端 400）
- [ ] HTTPS + 登录限流（Caddy）；证书自动续期观察一次
- [ ] `API_BASE` release 构建指向 https 域名；TV 端 debug/release 双 APK 命名区分
- [ ] OpenList 侧自查（属 OpenList 责任边界，提示用户）：`/d` 直链是否需 token？建议至少关不必要的云盘挂载。
- [ ] 容器 `healthcheck`（pg）+ 宿主 restart 策略正常；`docker system df` 无失控卷
- [ ] 日志中 grep 不到 `Bearer `、`password=`、`api_token=` 明文

---

## 11. 运维命令速查

| 意图 | 命令 |
|---|---|
| 起 / 停 / 重建 | `docker compose up -d` / `down`（留着 pgdata）/ `up -d --build server` |
| 看日志 | `docker compose logs -f server`（+` --since 10m`、` \| grep ERROR`） |
| 进 PG | `docker compose exec -it postgres psql -U hypermux -d hypermux` |
| 手动建表 | `docker compose exec -T postgres psql -U hypermux -d hypermux < ../sql/schema.sql`（卷内或 -f） |
| 备份 / 恢复 | 9.1 / `pg_restore -U hypermux -d hypermux --clean` (先停 server 写) |
| 看刮削任务 | `psql -c "SELECT id,status,new_count,update_count,remove_count,start_time,end_time FROM scrape_task ORDER BY start_time DESC LIMIT 10;"` |
| 快速建测试数据 | `docs/scripts/gen-mock.sh 500`（M5 提供，postgREST 风格 insertMany） |
| 证书监控 | `caddy cert-inspector`（或到期 IM 提醒脚本） |

---

## 12. M5 发布 Checklist（对 PLAN 里程碑）

- [ ] compose 一键起（全新 VM 环境实测：clone + .env + up，≤15min 到运行台）
- [ ] admin 外置卷热替换演练：改 index → build → curl 新 hash 无重启
- [ ] §7 压测三项达标留档（500 片 & 5000 片两档）
- [ ] §8 存活告警 cron 上线；日志 Trace-Id 全链可见
- [ ] §9 备份 cron + 一次备份恢复演练（DRY RUN 留记录）
- [ ] §10 清单逐项打钩
- [ ] APK release 签名包 + 安装说明（README 随 APK 子包）
- [ ] 升级/回滚各一次演练（含一次 schema 追加变更）
