# hyper-mux-server

HyperMux 后端服务（Spring Boot 3.3.5 / Java 17 / MyBatis-Plus / PostgreSQL）。

> **重建说明**：2026-10-12 一次目录搬迁事故导致本服务源码丢失，本目录为**按接口契约（`docs/plans/30-api-contract.md`）+ 现网数据库结构 1:1 重建**的版本，
> 接口对 App / Web / 后台前端保持 100% 兼容；数据（影片/剧集/用户/断点/源/分类/标签）一直保存在数据库中，未受影响。
> 表结构由 `src/main/resources/sql/schema.sql` 幂等维护（启动时执行）。

## 一、环境要求

| 项 | 版本/路径（本机） |
|---|---|
| JDK | 17（`D:\DEV_HOME\jdk17`） |
| Maven | 3.9.9（`D:\ai-projects\hyper-mux\tools\apache-maven-3.9.9`） |
| 数据库 | PostgreSQL `127.0.0.1:5432/hypermux`（库内 schema 已存在；本机地址见 `application-dev.yml`，该文件不入库） |

> **Windows 必读**：中文 Windows 默认 GBK，需注入 `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8`，否则源码/日志中文乱码。

## 二、构建与启动

```powershell
$env:JAVA_HOME = 'D:\DEV_HOME\jdk17'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
cd D:\ai-projects\hyper-mux\hyper-mux-server
D:\ai-projects\hyper-mux\tools\apache-maven-3.9.9\bin\mvn.cmd -B package -DskipTests
# 产物：target\hypermux-server.jar（约 36 MB，fat jar）
java -jar target\hypermux-server.jar            # 默认 profile=dev，监听 8080，管理端口 9090
```

> jar 正在运行时会占用文件导致 `package` 失败（`Unable to rename ... jar.original`）→ 先停掉进程再打包。

## 三、配置

`src/main/resources/application.yml`（通用） + `application-dev.yml`（本机库地址、静态目录）。

| 配置 | 说明 |
|---|---|
| `hypermux.static-dirs` | 后台前端构建产物目录（`hyper-mux-admin` 执行 `npm run build:dist` → `../admin-dist`）；同源提供 `http://host:8080/` |
| `hypermux.image.dir` | 远程图片本地缓存目录（默认 `~/.hypermux/images`） |
| `hypermux.image.allow-hosts` | 允许代取的图床域名（豆瓣/TMDB） |
| `hypermux.jwt.secret` / `hypermux.crypto.token-key` | JWT 签名密钥 / OpenList token 落库加密密钥（**生产必须覆盖**，改变后旧 token 与已存源 token 失效） |
| `hypermux.migrate.*` | 启动幂等迁移 + 管理员自举（仅当库内无管理员时创建） |
| `hypermux.openlist.page-size` | OpenList 列目录页大小（默认 100） |

也可用环境变量：`SPRING_PROFILES_ACTIVE`、`PG_HOST/PG_PORT/PG_DB/PG_USER/PG_PASSWORD`、`HYPERMUX_STATIC_DIRS`、`HYPERMUX_IMAGE_DIR`、`JWT_SECRET`、`HYPERMUX_SECRET`、`ADMIN_BOOTSTRAP_PASSWORD`。

## 四、接口总览

统一响应 `R<T> = {code,msg,data}`，`code==0` 成功、否则等于 HTTP 状态码。鉴权 `Authorization: Bearer <jwt>`，双前缀隔离：
`/api/app/**` 需 App 用户（role=0）、`/api/admin/**` 需管理员（role=1）；两端的 `auth/login` 免鉴权。

### App 端（`/api/app/*`）
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/auth/login` | 登录（密码仅校验长度 6~20） |
| GET | `/home` | 首页聚合：分类 + 继续观看 |
| GET | `/category/{id}/movies` | 类型内影片分页（`page,size,keyword?`） |
| GET | `/search` | 全局搜索（`keyword,page,size`） |
| GET | `/movie/{id}` | 影片详情 |
| GET | `/movie/{id}/videos` | 剧集列表（按季分组） |
| POST | `/play-url` | 换取播放直链（OpenList 拼 `/d/` 直链 + Referer 头） |
| GET | `/history` | 历史记录（`limit` ≤50） |
| PUT | `/progress` | 断点上报（幂等 upsert，距片尾 30s 自动看完） |
| POST | `/progress/finish` | 看完 / 移除（`action=finish\|remove`） |

### 后台端（`/api/admin/*`）
`auth/login`、`auth/me`、`dashboard`、`categories`(CRUD)、`movies`(列表/详情/新建/更新/发布/下架/删除)、
`movies/{id}/scrape-images`(刮削海报+横图并写入 OpenList 影片目录)、`movies/scrape-images`(批量补横图)、
`movies/{id}/videos`(列表/全量替换/粘贴导入)、`tags`(CRUD)、`users`(CRUD/重置密码)、
`sources`(CRUD/测试连通)、`openlist/session`(建会话/列目录/目录解析导入)、`metadata/search|detail`(豆瓣/TMDB 刮削)、`settings`。

### 横图刮削与图片落 OpenList（阶段 3）
`POST /api/admin/movies/{id}/scrape-images?provider=&overwrite=` → 按片名(+年份)搜元数据 →
取海报与横图（**豆瓣**取 `pic.large` 海报 + `trailers[].cover_url` 16:9 剧照；**TMDB** 取 `poster_path` + `backdrop_path`）→ 下载 →
上传到 `{影片目录}/images/poster.<ext>` 与 `backdrop.<ext>`（`mkdir` + `PUT /api/fs/put`）→
把 `movie.cover_url` / `movie.backdrop_url` 回写为 **OpenList 直链**（与视频直链同源，规避远程图床防盗链/失效）。

- 影片目录取 `movie.source_path`，缺失时由剧集路径反推
- 图片同时落入 `hypermux.image.dir` 本地缓存；`/img` 代理已放行**已配置数据源域名**（App 侧全部远程图都会走 `/img`）
- 批量：`POST /api/admin/movies/scrape-images` body `{"ids":[...]}` 或 `{"onlyMissing":true}`（默认只补缺横图的已发布影片）
- 后台「影片详情」页有「刮削图片」按钮；App 端 `MovieCardVo.backdropUrl` 非空时首页/片单自动切 16:9 横图卡片

### 图片代理（免鉴权）
`GET /img?u=<url-encoded 远程图址>` —— 服务端带 Referer 下载（豆瓣图床无 Referer 会 418）并落盘缓存后由本站出图；
App 侧所有远程图片统一走它，规避防盗链与远程失效。支持 `If-None-Match` / 7 天强缓存。

## 五、目录结构

```
src/main/java/com/hypermux/server/
  common/    R、ErrorCode、BizException、GlobalExceptionHandler、TraceFilter、PageResult、Ts、WebConfig
  crypt/     AesGcmCipher（AES-256-GCM，源 token 落库加密）
  auth/      JwtTokenService、AuthService(登录+失败限流)、AuthInterceptor(双前缀鉴权)、UserContext
  user|config|category|movie|video|tag|progress/   实体 + Mapper（含聚合 SQL）
  app/       service（首页/影片查询/播放直链/断点）+ controller（App 端 10 接口）
  admin/     service（分类/影片/标签/用户/源/导入解析/元数据/设置/总览）+ controller
  image/     图片本地缓存端点
  scrape/    OpenListClient(v4 口径)、OpenListSessionStore、OpenListPaths、SourceMapper/Entity
  migrate/   SchemaMigrateRunner（幂等 schema + 管理员自举）
resources/   application*.yml、logback-spring.xml、sql/schema.sql
```
