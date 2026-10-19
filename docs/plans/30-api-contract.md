# 30 · API 接口契约

> 隶属：[PLAN.md](../../PLAN.md) · 版本 v1.6 · 对应文档：[10-data-model](./10-data-model.md) / [20-backend-detailed](./20-backend-detailed.md) / [50-admin-detailed](./50-admin-detailed.md)
> v1.6（2026-10-11）：新增 §3.10 全局搜索 `GET /api/app/search`（片名/原名模糊，仅 PUBLISHED 且分类启用，支持分页）；供 web 首页全局搜索框使用
> v1.5（2026-10-11，v3）：**影片池 → 影视类型**——`GET /api/app/home` 的 `pools[]` 改 `categories[]`；`GET /api/app/pool/{id}/movies` 改 `/api/app/category/{id}/movies`；后台 `/api/admin/pools` 改 `/api/admin/categories`；`movie` 的 `poolId/poolName` 全部改 `categoryId/categoryName`。§3/§4 相关示例旧字段名以本则为准。
> v1.4（2026-10-11，v2 定位调整）：**移除** §4.9 刮削任务 / §4.10 候选审核 两节全部端点；**新增** `POST /openlist/import`（目录导入解析）、`GET /metadata/search` + `GET /metadata/detail`（元数据刮削）、`GET|PUT /settings`（系统设置）；`source` 列表/请求去掉 刮削范围/识别规则 字段（includePaths/matchMode/extWhitelist/ignorePatterns/autoPublish/lastScrapeAt/lastError）。sql 索引、token 形态不变
> v1.3（2026-10-10）：§4.8 新增**建源向导**——`POST /openlist/session`（apiToken/账密/sourceId 三选一会话，TTL 30min，token 永不出参）、`POST /openlist/session/{id}/list` 目录浏览；源增 `includePaths` **刮削范围**（create/update/VO 同步，空=整个根目录，v1.4 已随 v2 移除）
> v1.2（2026-10-10）：§4.4 `GET /movies` 的 `status` 支持逗号多值（如 `PUBLISHED,OFF`）；§4.8 补充播放地址实锤格式（`{serverUrl}/d/{路径分段编码}`）与创建时两步探测（version + fs/list）
> v1.1（2026-10-09）：§4.4 补充 `GET /api/admin/movies/{id}`（admin 编辑页回显）
> 本文档是三个工程的**唯一接口事实源**。字段有出入时以本文档为准改代码。

---

## 1. 全局约定

| 项 | 约定 |
|---|---|
| Base URL | `http(s)://<host>:8080`（如部署在 443 域名下则去掉端口） |
| 认证 | Header `Authorization: Bearer <token>`；TV 端 token 与后台 token 不互通（role 强校验） |
| 请求体 | `Content-Type: application/json; charset=utf-8` |
| 时间 | **一律毫秒时间戳（`long`）**，字段名 `*Time` / `*At` / `expiresAt` |
| ID | `long`（JSON number；PG max 9.2e18 内安全，JS 53bit 安全 < 2^53） |
| 分页 | 请求 `page`（从 1，缺省 1）、`size`（缺省 20，上限 100）；响应 `data = { list, total, page, size }` |
| 统一响应 | `{ "code": 0, "msg": "ok", "data": <T或null> }`；失败时 `data=null`，`msg` 人类可读 |
| 错误码 | code 与 HTTP 状态一致，见 §2 |
| 大小 | 字节数（`size` 字段），展示单位由前端换算 |
| 评分 | `rating`：`number`，0~10 一位小数（`3.5`/`null`） |

**鉴权范围**：`/api/app/auth/login`、`/api/admin/auth/login` 免认证；App 自更新端点 `GET /api/app/update/check`、`GET /api/app/update/download`（§3.11）同免认证（检查须在登录前可用）；`/api/app/**` 要普通用户 token；`/api/admin/**` 要管理员 token。静态资源（`/**`）开放；`actuator/health` 位于**管理端口**（默认 `127.0.0.1:9090`，见 [70-deploy-ops.md](./70-deploy-ops.md)），**不对外暴露**。客户端连通性探测改用受保护接口（如 `GET /api/app/home`，返回 401 即代表服务可达且为本服务）。

---

## 2. 全局错误码

| code | HTTP | msg 示例 |
|---|---|---|
| 0 | 200 | ok |
| 400 | 400 | 参数错误：xxx / 发布失败：缺少封面 |
| 401 | 401 | 用户名或密码错误 / 登录已过期 / token 无效 |
| 403 | 403 | 无权限 / 账号已停用 |
| 404 | 404 | 影片不存在: 42 |
| 409 | 409 | 名称已存在 / 该池下已有影片，禁止删除 / 状态不允许该操作 |
| 423 | 423 | 该源正在刮削中，请稍后重试 |
| 429 | 429 | 尝试太频繁，请稍后再试 |
| 500 | 500 | 系统内部错误 |

失败响应示例：

```json
{ "code": 409, "msg": "名称已存在：狂飙", "data": null }
```

---

## 3. TV 端接口（`/api/app/*`）

### 3.1 登录 `POST /api/app/auth/login`（免认证）

请求：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| username | string | ✔ | 3~32 位字母数字下划线 |
| password | string | ✔ | 6~20 位 |

响应 `data`：

```json
{
  "token": "eyJhbGciOi...",
  "expiresAt": 1791504000000,
  "user": { "id": 1001, "username": "wang", "nickName": "老王" }
}
```

错误：401（用户名或密码错误）、403（账号已停用）、429（5 次/分钟/username）。
App 行为：成功后本地持久化 token + expiresAt；后续请求 401 → 清 token 回登录页。

### 3.2 首页聚合 `GET /api/app/home`

响应 `data`：

```json
{
  "pools": [
    { "id": 1, "name": "家庭影院", "coverUrl": "https://.../pool1.jpg", "movieCount": 36 }
  ],
  "continueWatching": [
    {
      "movieId": 12, "title": "狂飙", "coverUrl": "https://.../kuangbiao.jpg", "poolName": "家庭影院",
      "lastVideo": { "id": 301, "seasonNo": 1, "episodeNo": 5 },
      "positionMs": 5240000, "durationMs": 28800000
    }
  ]
}
```

- `pools`：`sort_no` 升序，仅 `status=1` 池；`movieCount=0` 的池**不下发**。
- `continueWatching`：最多 10 条，`finished=0` 且影片仍可见；**同一影片只保留一条**（SQL `ROW_NUMBER() OVER (PARTITION BY movie ORDER BY update_time DESC)` 取最近观看的那一集），结果按该最新记录的 `updateTime` 降序。

### 3.3 池内影片分页 `GET /api/app/pool/{poolId}/movies`

Query：`page`、`size`、`keyword?`（片名/原名模糊，ILIKE）。

响应 `data`：`{ list: MovieCard[], total, page, size }`

`MovieCard`：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | long | 影片 id |
| title | string | 片名 |
| coverUrl | string? | 封面（缺省 App 用占位图） |
| rating | number? | 0~10 |
| year | int? | |
| area | string? | 地区 |
| episodeTotal | int | 非 missing 剧集数 |
| tags | string[] | 标签名（≤5 个，超出不补） |
| progress | object? | `{ videoId, seasonNo, episodeNo, positionMs, durationMs }`，无断点为 null |
| updateTime | long | 内容更新时间（用于 TV 端"更新"角标，可选用） |

错误：poolId 不存在/已隐藏 → 404。

### 3.4 影片详情 `GET /api/app/movie/{id}`

响应 `data`：

```json
{
  "id": 12,
  "title": "狂飙", "originalTitle": "The Knockout",
  "year": 2023, "coverUrl": "https://...", "rating": 9.4,
  "area": "中国", "language": "普通话",
  "intro": "京海市一线刑警安欣……",
  "tags": [ { "id": 1, "name": "电视剧", "type": 0 }, { "id": 11, "name": "中国", "type": 1 } ],
  "episodeTotal": 39,
  "pool": { "id": 1, "name": "家庭影院" },
  "progress": { "videoId": 301, "seasonNo": 1, "episodeNo": 5, "positionMs": 5240000, "durationMs": 28800000, "finished": 0 }
}
```

错误：404（不存在或不可见：DRAFT/OFF/池停用）。

### 3.5 剧集列表 `GET /api/app/movie/{id}/videos`

响应 `data`：

```json
{
  "movieId": 12,
  "seasons": [
    { "seasonNo": 1, "videos": [
      { "id": 300, "episodeNo": 1, "title": "风起", "duration": 2880000, "progress": null },
      { "id": 301, "episodeNo": 2, "title": null,  "duration": 2874000,
        "progress": { "positionMs": 5240000, "durationMs": 28800000, "finished": 0 } }
    ]}
  ]
}
```

- 只含 `missing=0`；`seasons` 按 `season_no` 升序，videos 按 `episode_no` 升序；`title` 为 null 时 TV 端展示"第 {episodeNo} 集"。
- `duration` 单位**秒**（与断点的 ms 区分，注意）；`progress` 单位 ms。

### 3.6 换取播放直链 `POST /api/app/play-url`

请求：`{ "videoId": 301 }`

响应 `data`：

```json
{
  "url": "https://openlist.example.com/d/%E5%8A%A8%E4%BD%9C/%E7%8B%82%E9%A9%B0%20(2023)/S01/%E7%8B%82%E9%A9%B0.S01E02.1080P.mkv",
  "headers": { "Referer": "https://openlist.example.com/" },
  "expiresAt": null
}
```

- `url`：OPENLIST 型 = `<source.server_url>/d/<rootPath 拼接 openlistPath 后逐段 percent-encode 的完整路径>`；REMOTE 型 = 原直链。
- `headers`：可空对象/缺省；TV 端必须合并进 ExoPlayer MediaItem 请求头。
- 注意：`video.openlist_path` 是**相对 source.rootPath** 的落库口径（导入服务产出），拼 /d/ 直链前服务端先 `join(rootPath, openlistPath)` 还原完整路径；123pan 等挂载源缺失该步会 500 `storage not found`。
- 错误：404（video 不存在 / 所属影片已不可见 / missing / 源已删）、400（videoId 缺失）。

### 3.7 历史记录 `GET /api/app/history`

Query：`limit`（缺省 20，上限 50）。

响应 `data`（数组，updateTime 降序）：

```json
[
  {
    "videoId": 301,
    "movieId": 12, "title": "狂飙", "coverUrl": "https://...", "poolName": "家庭影院",
    "seasonNo": 1, "episodeNo": 5,
    "positionMs": 5240000, "durationMs": 28800000,
    "finished": 0, "updateTime": 1791490000000
  }
]
```

### 3.8 断点上报 `PUT /api/app/progress`

请求：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| videoId | long | ✔ | |
| positionMs | long | ✔ | ≥0 |
| durationMs | long | | 视频总长（首次/变化时带上） |
| finished | int? | | 显式标记（缺省=0；传 1 等价 3.9） |

响应 `data`：`{ "videoId": 301, "positionMs": 5240000, "finished": 0, "updateTime": 1791490000000 }`

- 幂等 upsert（user×video 唯一）。`positionMs >= durationMs - 30000` 时服务端自动置 `finished=1`。
- App 上报节奏：播放中每 10s 一次；暂停/前后台切换/退出播放时立即一次。写入失败静默重试 1 次，不阻塞播放。

### 3.9 标记看完/移除 `POST /api/app/progress/finish`

请求：`{ "videoId": 301, "action": "finish" | "remove" }`（缺省 `finish`）
响应 `data`：`{ "videoId": 301, "action": "finish" }`
错误：404（该用户无此记录 / video 已不可见）。
- `finish`：置 finished=1（退出继续观看区，历史保留）
- `remove`：物理删该行（历史清空该集，可再播重建）

### 3.10 全局搜索 `GET /api/app/search`

Query：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| keyword | string | ✔ | 片名 / 原名模糊（LIKE），空白报 400 |
| page | int | | 1 起，默认 1 |
| size | int | | 默认 20，上限 100 |

响应 `data`：`Page<TvMovieCard>`（结构与 3.3 一致，含 `progressLite` 断点）。

- 可见性铁律同 3.3：`status='PUBLISHED' AND episode_total>0 AND category.status=1`（分类停用整类隐藏）。
- 排序：`sort_no ASC, id ASC`（与类型内列表一致）。

### 3.11 App 自更新（匿名端点）

更新检查/下载跟随**用户配置的服务器**（公开版查公开服务器，自托管查自己的服务器），与 login 同等免认证（见 §1 鉴权范围）。每平台仅保留**最新一条**（发布即覆盖 upsert，无版本历史）；下载匿名可访问。

#### `GET /api/app/update/check`

Query：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| code | int | ✔ | 客户端自身 versionCode |
| platform | string | | 默认 `mobile`；白名单 `mobile`/`tv`，其他值 400 |

响应：**仅当**该平台最新 versionCode > `code` 时 `200 + R<UpdateCheckVo>`；无更新（未发布 / 已最新）→ **204 无 body**。

`data`：

```json
{ "versionCode": 9, "versionName": "0.1.8", "sha256": "…", "sizeBytes": 26841731, "releaseNotes": "…", "mandatory": false }
```

- 客户端下载产物按 `sha256` 流式校验，不符即删包重下（MVP 无断点续传）。
- `mandatory=true`（强制更新，默认 false）：客户端不得提供「稍后」、下载安装前不可进入主界面。
- `sizeBytes` 供进度条分母；`releaseNotes` 在更新弹窗展示。

#### `GET /api/app/update/download`

Query：`platform`（同上）。响应：APK 全量流式（`application/vnd.android.package-archive` + `Content-Length` + `Content-Disposition: attachment; filename="hypermux-{platform}-{versionName}.apk"`）；**不支持 Range**，客户端失败即整包重下。
错误：404（该平台未发布 / 文件缺失——文件被手工删除后记录仍在，提示"请重新发布"）。

---

## 4. 后台接口（`/api/admin/*`）

### 4.1 认证

#### `POST /api/admin/auth/login`（免认证）
同 3.1（role 要求管理员）。

#### `GET /api/admin/auth/me`
响应 `data`：`{ id, username, nickName, role, lastLoginTime }`
前端路由守卫刷新用；token 无效 → 401 → 跳登录页。

### 4.2 总览 `GET /api/admin/dashboard`

```json
{
  "pools": 3, "publishedMovies": 412, "draftMovies": 17,
  "users": 26, "runningTasks": 1,
  "recentTasks": [
    { "id": 9, "sourceName": "NAS-电影盘", "type": "INCREMENT", "status": "FINISHED",
      "newCount": 23, "updateCount": 4, "removeCount": 1, "startTime": 1791480000000, "endTime": 1791480612000 }
  ]
}
```

### 4.3 影片池

#### `GET /api/admin/pools`（Query: page, size, keyword）
`data.list[]`: `{ id, name, coverUrl, description, sortNo, status, movieCount, createTime, updateTime }`

#### `POST /api/admin/pools`
请求：`{ "name": "家庭影院", "coverUrl": "https://...?", "description": "...?", "sortNo": 0? }`
- `name` 必填 ≤64，全局唯一（409）；`status` 缺省 1。
响应：`data = { id: 3 }`

#### `PUT /api/admin/pools/{id}`
请求：任意以上字段部分更新（PATCH 语义，缺省不更）。错误：404 / 409 重名。

#### `DELETE /api/admin/pools/{id}`
错误：404；409 `该池下已有 N 部影片，禁止删除` → 响应 data `{ id, movieCount }` 供前端提示。

### 4.4 影片

#### `GET /api/admin/movies`
Query：`page`、`size`、`keyword?`（片名/原名）、`status?`、`poolId?`、`sortBy?`（`updateTime` 缺省 / `sortNo`）。
- `status`：**支持逗号多值**（如 `PUBLISHED,OFF`，逐项枚举校验，缺省 = `PUBLISHED,OFF` 即"全部非 DRAFT"；候选恒走 /candidates）。

`data.list[]`：

```json
{
  "id": 12, "title": "狂飙", "originalTitle": "The Knockout",
  "poolId": 1, "poolName": "家庭影院",
  "year": 2023, "coverUrl": "https://...", "rating": 9.4,
  "area": "中国", "language": "普通话", "intro": "（截断 100 字）",
  "episodeTotal": 39, "missingCount": 0,
  "tags": [ { "id": 1, "name": "电视剧" } ],
  "status": "PUBLISHED", "sourceType": "MANUAL",
  "sourceName": null, "sourcePath": null,
  "sortNo": 0, "createTime": 1791000000000, "updateTime": 1791400000000
}
```

#### `POST /api/admin/movies`
请求：

```json
{
  "title": "狂飙", "originalTitle": "The Knockout",
  "poolId": 1,
  "year": 2023, "coverUrl": "https://...", "rating": 9.4,
  "area": "中国", "language": "普通话",
  "intro": "……", "sortNo": 0,
  "tagIds": [1, 11],
  "episodes": [
    { "seasonNo": 1, "episodeNo": 1, "title": "风起", "url": "https://cdn.example.com/kb.e01.mp4", "duration": 2880 },
    { "seasonNo": 1, "episodeNo": 2, "url": "/动作/狂飙 (2023)/S01/狂飙.S01E02.1080P.mkv" }
  ]
}
```

规则与校验：
- `title` 必填 ≤191；`poolId` 必填（池需启用）；`episodes` 必填 ≥1 条；同池 (pool_id,title) 已 PUBLISHED/OFF → 409。
- `episodes[].url` 必填：`http(s)://` 开头 → REMOTE 直链；否则 → OpenList 相对路径（OPENLIST）。后者必须同时在请求中携带影片级字段 `"sourceId": 1?`（该相对路径所属的 OpenList 源，play-url 组装 server_url 用），缺失 → 400。
- `(seasonNo,episodeNo)` 重复 → 409。
- 创建成功即 `status=PUBLISHED`，`sourceType=MANUAL`。

响应 `data`：`{ "id": 13, "status": "PUBLISHED", "episodeTotal": 2 }`

#### `PUT /api/admin/movies/{id}`
主信息部分更新（字段同上，除 episodes）；`tagIds` 出现即**全量替换**。404/409（同池重名）。
注意：改 `title` 触发同池唯一校验；已发布影片改 `poolId` 允许。

#### `GET /api/admin/movies/{id}`（M1 补充）
admin 编辑页回显：`{ id, title, originalTitle, poolId, year, coverUrl, rating, area, language, intro, sortNo, sourceId, status, tags[] }`。TV 端详情恒走 §3.4。

#### `POST /api/admin/movies/{id}/publish`
DRAFT→PUBLISHED（校验：poolId ✓、coverUrl ✓、非 missing 集 ≥1，缺哪项报哪项："缺少封面/未归入影片池/无可用剧集"）。错误 404/409（已是 ON 状态：幂等——**约定：重发 PUBLISHED 视为成功空操作**，OFF/PUBLISHED 重复 publish 返回 200）。

#### `POST /api/admin/movies/{id}/offline`
PUBLISHED→OFF；幂等（OFF 重复 → 200）。

#### `DELETE /api/admin/movies/{id}`
级联删 video、断点、tag 绑定。响应 `data`: `{ "id": 12, "deletedEpisodes": 39, "deletedProgress": 3 }`（统计用）。

### 4.5 剧集

#### `GET /api/admin/movies/{id}/videos`
`data.list[]`（sort: seasonNo, episodeNo）：

```json
{
  "id": 301, "seasonNo": 1, "episodeNo": 2, "title": null,
  "urlKind": "OPENLIST",
  "openlistPath": "/动作/狂飙 (2023)/S01/狂飙.S01E02.1080P.mkv",
  "remoteUrl": null,
  "size": 16106127360, "duration": 2874,
  "sourceFile": "狂飙.S01E02.1080P.mkv", "fingerprint": "16106127360-1791300000000",
  "missing": 0, "updateTime": 1791400000000
}
```
另 `data.movieId`、`data.missingCount`。

#### `PUT /api/admin/movies/{id}/videos`（**全量替换**）

```json
{
  "episodes": [
    { "id": 300, "seasonNo": 1, "episodeNo": 1, "title": "风起", "url": "...", "duration": 2880 },
    { "seasonNo": 1, "episodeNo": 2, "url": "/动作/狂飙 (2023)/S01/xx.mkv" }
  ]
}
```

- 未带 `id` = 新增（seasonNo/episodeNo 必填）；带 `id` = 保留/更新（id 必须属于该影片，否则 400）；**现有但本次未提交的 id = 删除**。
- 409：`episodes` 内 (seasonNo,episodeNo) 重复；400：url 为空。
- 成功后回写 `movie.episode_total`。响应 `data`: `{ "total": 3, "added": 1, "updated": 1, "removed": 1 }`

#### `POST /api/admin/movies/{id}/videos/import`（粘贴导入，**追加模式**）

```json
{ "seasonNo": 1, "text": "https://cdn.example.com/kb.e40.mp4\n41,https://cdn.example.com/kb.e41.mp4\n坏行没有分隔" }
```

- 行格式：`url` 或 `N,url`（N=集号，`N` 缺省 = 该季 `max(episodeNo)+1` 续号）。
- 单行失败跳过：响应 `{ "added": 2, "updated": 0, "errors": [ { "line": 3, "msg": "缺少 url" } ] }`
- 已存在 (s,e) → 更新该行的 url/size（更新计入 `updated`）。

### 4.6 标签

#### `GET /api/admin/tags`
`data.list[]`: `{ id, name, type, sortNo, status, movieCount }`（type 0 类型/1 地区/2 自定义；Query: `type?`）

#### `POST /api/admin/tags`
`{ "name": "古装", "type": 0, "sortNo": 10 }` → 409 重名。响应 `{ id: 42 }`

#### `PUT /api/admin/tags/{id}` / `DELETE /api/admin/tags/{id}`
删除自动解绑所有影片（movie_tag cascade），响应 `{ "id": 42, "unboundMovies": 7 }`。

### 4.7 用户

#### `GET /api/admin/users`
Query: page, size, keyword(username/nickName), role?, status?
`data.list[]`: `{ id, username, nickName, role, status, lastLoginTime, createTime }`

#### `POST /api/admin/users`
`{ "username": "wang", "password": "wang123", "nickName": "老王", "role": 0 }`
- username `^[a-zA-Z0-9_]{3,32}$` 全局唯一（409）；password 6~20；role 0/1。
响应 `{ id: 1001, username: "wang" }`

#### `PUT /api/admin/users/{id}`
`{ "nickName"?, "role"?, "status"? }` 部分更新。保护：自己改自己 role→0 或 status→0 → 409 `不能停用自己`；删到 0 启用管理员 → 409。

#### `POST /api/admin/users/{id}/reset-password`
`{ "password": "新密码or省略" }` → 省略则服务端随机 8 位。
响应 `{ "userId": 1001, "username": "wang", "newPassword": "aB3xK9mQ" }`（**明文仅本次返回**）

#### `DELETE /api/admin/users/{id}`
409：自己 / 最后一个启用管理员。级联删其断点。响应 `{ "id": 1001, "deletedProgress": 12 }`

### 4.8 OpenList 源

#### `GET /api/admin/sources`
`data.list[]`（token 永不出参）：

```json
{
  "id": 1, "name": "NAS-电影盘",
  "serverUrl": "https://openlist.example.com",
  "rootPath": "/videos",
  "includePaths": ["/videos/电影/阿凡达 (2009)"],
  "matchMode": "A",
  "extWhitelist": "mkv,mp4,ts", "ignorePatterns": "sample,.part",
  "autoPublish": 0, "status": 1,
  "movieCount": 214,
  "lastScrapeAt": 1791480612000, "lastError": null,
  "createTime": 1791000000000, "updateTime": 1791400000000
}
```

- `includePaths` = **刮削范围**（建源向导第 3 步勾选的子目录，绝对路径数组）；空数组 = 刮整个 `rootPath`（既有行为）。数组持久化在 `openlist_source.include_paths`（JSON 文本）。

#### `POST /api/admin/sources`
请求（同出参字段，create 用）：`{ name, serverUrl, apiToken?, sessionId?, rootPath, includePaths?, matchMode, extWhitelist?, ignorePatterns?, autoPublish? }`
- `serverUrl` 必须 http(s) 且服务端自动去尾 `/`；**`apiToken` 与 `sessionId` 二选一**（直连客户端带 `apiToken`；建源向导带 `sessionId`，明文 token 由服务端会话代持，两者皆缺 → 400 `缺少 API Token（apiToken 或 sessionId 二选一）`）。token 服务端 AES-256-GCM 加密存储，**永不出参**。
- `includePaths`：每项须等于 `rootPath` 或在其之下，含 `.`/`..` 独立段、越界、超 200 项均 → 400（服务端归一化前导 `/`、去尾 `/` 并去重后落库）。
- `matchMode`：`A`（目录内含媒体文件即视为影片，支持 Sxx 季目录展开，**create 缺省值**）/ `B`（一级目录=影片，其下 Sxx 目录=季）；服务端校验 `^[AB]$`。
- **创建时自动两步探测**（OpenList v4 实机口径）：① `GET {serverUrl}/api/public/settings`（免鉴权，取 `data.version`）；② 带 Token 的 `GET {serverUrl}/api/fs/list?path=<rootPath>&page=1&per_page=100`（`Authorization: <裸 token>`，**无 Bearer 前缀**）。v4 错误也返回 HTTP 200，以 body `code` 判定：200 成功（`data.content[]`/`total`）、401 token 无效、403 guest 被禁用、404 目录不存在。任一步失败 → 400 `连不通 OpenList: <原因>`，成功才落库（`sessionId` 代持的 token 同样过这道闸）。
响应 `{ id: 2 }`

#### `PUT /api/admin/sources/{id}`
部分更新（null=不修改）；`apiToken` / `sessionId` 带值=更换 token（重新加密），不带=保留。`includePaths`：null=不修改，**空数组=清空范围（改刮整个根目录）**；越界/非法仍按上述规则 400（校验按更新后的 `rootPath`）。改名 409 重名。

#### 向导浏览（建源向导专用）

##### `POST /api/admin/openlist/session`
建连接会话（**三选一**），探测通过才返回：

```json
// ① 粘贴 Token：{ "serverUrl": "https://openlist.example.com", "apiToken": "xxx" }
// ② 账密登录：{ "serverUrl": "...", "username": "admin", "password": "...", "otpCode": "可选" }
//    后端 POST {serverUrl}/api/auth/login 换 token（验证码/2FA 实例可能拒绝，错误文案透传）；
// ③ 沿用已存：{ "sourceId": 1 }   // 编辑场景，服务端解密该源 token
{ "sessionId": "3f2a9c...", "version": "4.1.8" }
```
- 凭据校验失败 → 400（`连不通 OpenList: <原因>` / `登录失败 code=...`）；三者皆缺 → 400。
- `sessionId` 为服务端内存会话（**TTL 30 分钟**，每次使用续期，重启即失效），**明文 token 永不出参**。

##### `POST /api/admin/openlist/session/{sessionId}/list`
请求 `{ "path": "/电视剧", "page": 1 }`（page 1 起，页大小取服务端 `hyper_mux.openlist.page-size`）；
响应 `{ "list": [{ "name", "path", "size", "dir", "modifiedMs" }], "total", "page", "hasMore": false }`。
- `path` 恒为**前导 `/` 的绝对路径**：部分网盘挂载（123pan/夸克等 mount）上游条目不带 `path` 字段，服务端以「请求父路径 + name」兜底拼出（`OpenListClient.listDir`），前端与刮削引擎可直接依赖。
- 会话不存在/过期 → 400 `连接会话已过期，请重新连接`；上游错误（401/403/404 等）→ 400 + 透传文案（**401 保留给管理员登录态语义**，不可复用）。

#### `DELETE /api/admin/sources/{id}`
有关联影片（movieCount>0）→ 409 `该源已刮出 N 部影片，请改用停用`。无关联且有 RUNNING 任务 → 423。响应 `{ id, deletedTasks: 5 }`

#### `POST /api/admin/sources/{id}/test`
响应：`{ "ok": true, "version": "4.10.0", "message": "连接正常" }` 或 `{ "ok": false, "message": "token 无效(HTTP 403)" }`（ok=false 时 HTTP 200，msg 进 data，便于前端展示不弹全局错）。

**播放地址组装（实锤格式）**：OPENLIST 剧集的最终直链 = `trimTrailingSlash(serverUrl) + "/d/" + encodeSegments(join(rootPath, openlistPath))`。
- `openlist_path` 落库为**相对 `rootPath` 的路径（无前导 `/`）**（导入服务 `relative(root, full)` 产出）；手工录入可带前导 `/`，`join` 归一化。拼直链前必须 `join(rootPath, openlistPath)` 还原完整路径——123pan 等挂载源若直接用相对路径会 500 `storage not found`。
- `join`：`OpenListPaths.join`（前导 /、无重复 /；rootPath=`/` 时即原路径）。
- `encodeSegments`：去前导 `/` → 合并连续斜杠 → 按 `/` 分段逐段百分号编码。字面保留：字母数字与 `-._~!$&'()*,;=`（RFC3986 非保留字符 + sub-delims）；空格→`%20`；非 ASCII 按 UTF-8 编码为大写 `%XX`；字面 `+` 编码为 `%2B`（防止 OpenList 侧解成空格）。
- 例：源 `http://127.0.0.1:18080`（rootPath=`/tv`）+ 落库 `my show/01.mkv` → `http://127.0.0.1:18080/d/tv/my%20show/01.mkv`（`/d/` 为 OpenList 共享直链约定路由）。
- REMOTE 剧集直链直通不做任何改写（原样返回）。

### 4.9 刮削任务

#### `POST /api/admin/sources/{id}/scrape`
请求 `{ "type": "FULL" | "INCREMENT" }`
- 源 status=0 → 400 `源已停用，不能发起刮削`；已有 RUNNING/STOPPING → 423（msg 形如 `源正在刮削中（任务 #11），请稍后再试`）。
- 缺省建议：该源 `lastScrapeAt` 为空 → 用 FULL（前端按此默认勾选）。
- 响应（HTTP 200，code=0）：`{ "taskId": 11 }`

#### `GET /api/admin/scrape-tasks`
Query: page, size, sourceId?, status?
`data.list[]`：`{ id, sourceId, sourceName, type, status, scanned, newCount, updateCount, removeCount, error, startTime, endTime }`（startTime 降序）

#### `GET /api/admin/scrape-tasks/{id}`
同单条 + `{ "operator": 2, "durationMs": 612000 }`。
RUNNING/STOPPING 时前端 2s 轮询本接口（`scanned` 增长即可感知进度；不保证百分数——目录总量本次才知）。

#### `POST /api/admin/scrape-tasks/{id}/stop`
仅 RUNNING 可停 → 置 STOPPING（引擎 ≤2s 收尾落 STOPPED，已扫部分正常落库、不做 missing 标记）。409：任务非运行态（msg 含当前状态）。响应（HTTP 200，code=0）：`{ "id": 11, "status": "STOPPING" }`

> 任务状态机：RUNNING →（stop）STOPPING → STOPPED；RUNNING → FINISHED / FAILED。服务重启时遗留 RUNNING/STOPPING 一律置 FAILED（启动钩子）。

### 4.10 候选审核（DRAFT）

#### `GET /api/admin/candidates`
Query: page, size, keyword?, sourceId?, unparsed?
- `keyword`：片名/原名 like。`unparsed=true`：仅含"未完全解析"的候选（`nameParsed=false` 或存在猜号剧集，前端"只看集数猜的"筛选）。
`data.list[]`（films detail-lite，候选专属字段加粗）：

```json
{
  "id": 56, "title": "Oppenheimer", "originalTitle": null, "year": 2023,
  "parsed": true,
  "coverUrl": null,
  "posterCandidates": ["Oppenheimer (2023)/poster.jpg", "Oppenheimer (2023)/cover.png"],
  "rating": null, "area": null, "language": null, "intro": null,
  "episodeTotal": 1,
  "episodeSample": [ "Oppenheimer (2023).mp4" ],
  "seasonSummary": "共1季 · 1集",
  "sourceId": 1, "sourceName": "NAS-电影盘",
  "sourcePath": "/videos/Oppenheimer (2023)",
  "poolId": null, "poolName": null,
  "serverUrl": "https://openlist.example.com", "rootPath": "/videos",
  "createTime": 1791480612000
}
```

> - `parsed` = 片名已解析 **且** 0 个猜号剧集（自然序补位或 R4 裸数字/E 前缀猜号均算"猜"）。
> - `posterCandidates` 为相对路径；前端预览图 URL = `<source.serverUrl>/d/<逐段encode>`（公网直读，M4 前端拼）；`serverUrl`/`rootPath` 随候选返回即为此用。
> - `poolId`/`poolName` 为"直接归池"按钮提供数据（空 = 未归池，发布前必须先归池）。

#### `PUT /api/admin/candidates/{id}`
请求（部分更新）：`{ title?, originalTitle?, year?, coverUrl?, rating?, area?, language?, intro?, poolId?, tagIds? }`
- `coverUrl` 接受 ① http(s) URL，或 ② `posterCandidates` 中的任一相对路径。**实现锁定**：相对路径由后端在落库时转绝对 URL（`source.serverUrl + /d/` + 逐段 percent-encode），TV 端永不处理相对封面；`scrape_assets.posterPaths` 保持相对路径存储，admin 预览图由前端自行拼 `<serverUrl>/d/...` 直读。
- 不校验 poolId/coverUrl（发布才校验）。

#### `POST /api/admin/candidates/publish`
`{ "ids": [56, 57, 58] }`（上限 200）
响应：`{ "published": 2, "failed": [ { "id": 58, "msg": "缺少封面或未归入影片池" } ] }`
- 逐条校验（4.4 publish 规则），失败不中断；已 published 的 id 幂等成功。

#### `DELETE /api/admin/candidates/{id}`
忽略候选（DRAFT 物理删，含其剧集行）。响应 `{ "id": 56, "deletedEpisodes": 8 }`
404：非 DRAFT（已发布的去影片列表处理）。

### 4.11 App 自更新发布

#### `GET /api/admin/app-release?platform=mobile|tv`
响应 `data`：`null`（未发布）或 `{ platform, versionCode, versionName, sha256, sizeBytes, releaseNotes, mandatory, updateTime }`（毫秒时间戳）。管理端卡片回显用。

#### `POST /api/admin/app-release`（multipart/form-data）

| 字段 | 必填 | 说明 |
|---|---|---|
| file | ✔ | `.apk` 文件（服务端自算 sha256 / sizeBytes，客户端无需上传） |
| platform | ✔ | `mobile` / `tv`（白名单） |
| versionCode | ✔ | int > 0；**必须大于该平台当前已发布值**，否则 409 |
| versionName | ✔ | ≤ 32 字符 |
| releaseNotes | | 展示于 App 更新弹窗 |
| mandatory | | 默认 false（强制更新，见 §3.11） |

服务端行为：校验（空文件 / 非 .apk / >300MB / 版本号回退 → 400/409）→ 先落 `{platform}-latest.apk.uploading` 临时文件 → 计算 sha256 → 原子替换正式文件 → **文件成功后才落库**（记录存在即文件必存）。存储目录由 `hypermux.apk.dir` 配置（env `HYPERMUX_APK_DIR`，默认 `~/.hypermux/apks`），见 [70-deploy-ops.md](./70-deploy-ops.md)。

---

## 5. 关键交互时序

### 5.1 TV 端 Session

```
启动 → 本地有 token 且未过期 → 直拉 /home
      无/过期 → /login（成功后存 token，/home）
任意 /api/app/** 401 → 清 token → 登录页
播放：/movie/{id}/videos → 选集 → /play-url → ExoPlayer(url+headers)
      播放中 PUT /progress ×10s；暂停/退出再 PUT
返回首页 → 重拉 /home（continueWatching 更新）
```

### 5.2 刮削全流程（admin 侧）

```
POST /sources（建源，自动 test）
POST /sources/{id}/scrape {type}      → {taskId}
GET  /scrape-tasks/{id} ×2s 轮询
GET  /candidates?page=1               → 审核
PUT  /candidates/{id}（补 pool/cover/rating/tags）
POST /candidates/publish {ids:[...]}  → {published,failed[]}
（出片即上架，TV 端 /home 立即可见——App 无本地缓存依赖，每次进首页拉）
```

---

## 6. curl 示例（冒烟用）

```bash
# ── TV 端 ──
TOKEN_APP=$(curl -s -X POST localhost:8080/api/app/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"wang","password":"wang123"}' \
  | jq -r .data.token)

curl -s "localhost:8080/api/app/home" -H "Authorization: Bearer $TOKEN_APP" | jq
curl -s "localhost:8080/api/app/pool/1/movies?page=1&size=20&keyword=狂" -H "Authorization: Bearer $TOKEN_APP" | jq
curl -s localhost:8080/api/app/movie/12 -H "Authorization: Bearer $TOKEN_APP" | jq
curl -s localhost:8080/api/app/movie/12/videos -H "Authorization: Bearer $TOKEN_APP" | jq
curl -s -X POST localhost:8080/api/app/play-url -H "Authorization: Bearer $TOKEN_APP" \
  -H 'Content-Type: application/json' -d '{"videoId":301}' | jq
curl -s -X PUT localhost:8080/api/app/progress -H "Authorization: Bearer $TOKEN_APP" \
  -H 'Content-Type: application/json' -d '{"videoId":301,"positionMs":5240000,"durationMs":28800000}' | jq

# ── 后台 ──
TOKEN_ADM=$(curl -s -X POST localhost:8080/api/admin/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"Hypermux@123"}' | jq -r .data.token)

curl -s -X POST localhost:8080/api/admin/pools -H "Authorization: Bearer $TOKEN_ADM" \
  -H 'Content-Type: application/json' -d '{"name":"家庭影院","sortNo":0}' | jq

curl -s -X POST localhost:8080/api/admin/movies -H "Authorization: Bearer $TOKEN_ADM" \
  -H 'Content-Type: application/json' -d '{
  "title":"狂飙","poolId":1,"year":2023,"rating":9.4,"area":"中国",
  "tagIds":[1,11],"episodes":[
    {"seasonNo":1,"episodeNo":1,"url":"https://cdn.example.com/kb.e01.mp4"}]}' | jq

curl -s -X POST localhost:8080/api/admin/sources -H "Authorization: Bearer $TOKEN_ADM" \
  -H 'Content-Type: application/json' -d '{
  "name":"NAS-电影盘","serverUrl":"https://openlist.example.com","apiToken":"xxx",
  "rootPath":"/videos","includePaths":["/videos/电影/阿凡达 (2009)"],"matchMode":"A"}' | jq

# 建源向导：会话 -> 浏览 -> 带 sessionId 创建（明文 token 不经过前端）
curl -s -X POST localhost:8080/api/admin/openlist/session -H "Authorization: Bearer $TOKEN_ADM" \
  -H 'Content-Type: application/json' -d '{"serverUrl":"https://openlist.example.com","apiToken":"xxx"}' | jq
curl -s -X POST localhost:8080/api/admin/openlist/session/$SID/list -H "Authorization: Bearer $TOKEN_ADM" \
  -H 'Content-Type: application/json' -d '{"path":"/","page":1}' | jq

curl -s -X POST localhost:8080/api/admin/sources/1/scrape -H "Authorization: Bearer $TOKEN_ADM" \
  -H 'Content-Type: application/json' -d '{"type":"FULL"}' | jq
curl -s localhost:8080/api/admin/scrape-tasks -H "Authorization: Bearer $TOKEN_ADM" | jq
curl -s localhost:8080/api/admin/candidates -H "Authorization: Bearer $TOKEN_ADM" | jq
```

---

## 7. 版本策略

- 路径已分离角色前缀（`/api/app`、`/api/admin`）；**破坏性变更 → 加 `/api/2/` 前缀**，本期不做。
- 新增可选字段 = 兼容变更，直接加；删除/改义字段 = 破坏性，走大版本。
- 本文档改动需同步：`server` DTO、`admin` api 模块、`app` Retrofit 接口（改一处漏两处是常见病，契约变更时三处对照 checklist）。
