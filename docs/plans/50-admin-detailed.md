# 50 · 管理后台前端详细设计（Vue 3 + Naive UI）

> 隶属：[PLAN.md](../../PLAN.md) · 版本 v1.1 · 对应里程碑：M3 手动配置全链路 / M4' 数据源 · 导入 · 元数据刮削（2026-10-11）
> **v1.1（v2/v3 定位调整）**：移除「候选审核」「刮削任务」页；OpenList 数据源页改为**仅配置**；新建影片新增「从 OpenList 导入」与「刮削元数据」弹窗；新增「系统设置」页；v3 起「影视类型」替代「影片池」（页面 `movie/PoolList.vue` → `movie/CategoryList.vue`，路由 `/pool` → `/categories`，影片表单「所属类型」）。
> 接口事实源：[30-api-contract.md](./30-api-contract.md)（本文只写页面与交互，字段以契约为准）

---

## 1. 技术栈与工程

| 项 | 选型 |
|---|---|
| 框架 | Vue 3（`<script setup>` + TypeScript）+ Vite 5 |
| UI | **Naive UI**（已锁定）+ `@vicons/ionicons5` 图标 |
| 状态 | Pinia（`stores/user.ts`、`stores/app.ts`） |
| 路由 | vue-router 4，**hash 模式**（同源部署零配置） |
| HTTP | axios 实例 `src/api/http.ts`（统一拦截） |
| 构建产物 | `vite build` → **直接输出到 `server/src/main/resources/static/`**（`vite.config.ts: build.outDir`），同源零跨域 |
| dev 模式 | `vite dev --port 5173` + `server.proxy['/api'] → http://127.0.0.1:8080` |

```
admin/
├─ vite.config.ts / tsconfig.json / index.html
└─ src/
   ├─ main.ts                     # 挂载 + Naive 全局（message/dialog 由 App 根 NMessageProvider 提供）
   ├─ App.vue
   ├─ api/
   │  ├─ http.ts                  # axios 实例：baseURL '/api/admin'、Bearer 注入、401 跳登录、错误 toast（见 §4）
   │  ├─ auth.ts   pool.ts   movie.ts   video.ts   tag.ts   user.ts
   │  └─ source.ts task.ts    candidate.ts  dashboard.ts
   ├─ stores/user.ts              # token/user 持久化（localStorage key: hypermux.admin.token/.user）
   ├─ router/index.ts
   ├─ layouts/AdminLayout.vue     # NLayout：侧边菜单 + 顶栏（用户名/退出）
   ├─ components/
   │  ├─ DirectLinkInput.vue      # 直链输入（http 或相对路径自动识别 + sourceId 关联提示）
   │  ├─ EpisodeTable.vue         # 剧集编辑表格（详见 §6.4，M3 核心组件）
   │  ├─ EpisodeImportModal.vue   # 粘贴批量导入
   │  ├─ CoverUploader.vue        # 封面（URL 输入 + 实时预览 + 候选海报三选一）
   │  ├─ TagPicker.vue            # 标签多选（分组：类型/地区/自定义）
   │  └─ RatingInput.vue          # NRate(0~10 步进 0.5→入库 1 位小数) + 数字联动
   └─ views/
     ├─ auth/Login.vue
     ├─ dashboard/Dashboard.vue
     ├─ movie/PoolList.vue  MovieList.vue  MovieEdit.vue  MovieDetail.vue  CandidateList.vue  CandidateEdit.vue
     ├─ tag/TagList.vue
     ├─ user/UserList.vue
     └─ scrape/SourceList.vue  TaskList.vue  TaskDetail.vue
```

---

## 2. 路由与信息架构

```
/login（白屏独立，UnauthOnly）
/（AdminLayout，requiresAuth 守卫：无 token → /login；me 401 → 清 token → /login）
├─ /dashboard                     总览
├─ /pool                          影片池
├─ /movies                        影片管理
├─ /movies/candidates             候选审核（DRAFT）      ← M4
├─ /movies/new                    新建影片
├─ /movies/:id                    影片详情（主信息 + 剧集 tab）
├─ /tags                          标签管理
├─ /sources                       OpenList 源            ← M4
├─ /tasks                         刮削任务               ← M4
├─ /tasks/:id                     任务详情（进度轮询）    ← M4
└─ /users                         用户管理
```

**侧边菜单**（NMenu，图标）：

```
📊 总览        /dashboard
🎬 影片管理
   ├ 影片池     /pool
   ├ 影片列表   /movies
   └ 候选审核   /movies/candidates   ← 角标：计数（GET /candidates?size=1 取 total）
🏷️ 标签       /tags
🧲 资源刮削
   ├ OpenList 源 /sources
   └ 刮削任务   /tasks
👥 用户       /users
```

权限：本后台只做管理员单角色，无细粒度按钮权限。

---

## 3. HTTP 统一层（`api/http.ts`）

```
request 拦截：Authorization: Bearer <token>（admin）
response 拦截：
  HTTP 200 && code===0        → resolve(data)
  HTTP 401                    → 清 token → window.location.hash='#/login' + toast "登录已过期"
  HTTP 403                    → toast msg
  HTTP 409/423/429/其他       → toast msg（差异化处理在调用点，如 409 删除池 → 弹明细对话框）
  网络异常/超时               → toast "网络异常，请检查后端"
```
- 超时 15s；上传类接口（未来若有）单独 60s。
- 所有 api 模块函数返回 `Promise<T>`（data 已解包）；分页函数签名 `fetchXxx(params): Promise<Page<T>>`。
- **轮询封装**：`usePolling(fn, 2000, {immediate, stopOn: cb})`——组件 onUnmounted 自动清理（任务页/候选角标用）。

---

## 4. 全局状态

`stores/user.ts`：`token, user{id,username,nickName,role}`；`login()`、`logout()`、`fetchMe()`；持久化 localStorage。
`stores/app.ts`：`pools`（下拉复用缓存，`refreshPools()` 写失效）、`tags`（TagPicker 复用，同上）、`toast queue`（message 注入）。

---

## 5. 页面规格

> 统一交互约定：CRUD 列表页 = 顶部筛选/操作栏 + NDataTable（服务端分页）+ 分页器；表单页编辑用**页面级**（新建/重名安全），池/标签/用户用**弹窗表单**（轻）；删除一律 NDialog 二次确认，高危文案带影响条数。

### 5.1 登录 `/login`

- 居中卡片（420px）：Logo 文案 `HyperMux 管理台`、`NInput(username)`、`NInputPassword(password, show-password-on=click)`、登录按钮（loading 态）。
- 回车提交；错误 → inline error（401 通用文案"用户名或密码错误"；403 "账号已停用，请联系管理员"）。
- 成功后 → `/dashboard` 并 `fetchMe()`。

### 5.2 总览 `/dashboard`

- 5 个统计卡（NStatistic）：书架影片 / 候选待审（点击跳候选页）/ 影片池 / 用户 / 进行中任务。
- 最近任务 NDataTable（5 条，紧凑列：源/类型/状态 tag/新增·更新·消失/时间，结束时间悬停 tooltip 显示起止）。
- 右上快捷按钮：**+ 新建影片** / **＋ 新建源** / **＋ 新建用户**。
- 候选角标与统计卡同源（dashboard 接口），进入页面即拉一次，不轮询。

### 5.3 影片池 `/pool`

- 列表列：缩略图(80×120, 无图占位) / 名称 / 描述 / 影片数 / 排序 / 状态(开关 NSwitch, 直接 PUT) / 更新时间 / 操作（编辑、删除）。
- 新建/编辑弹窗字段：
  | 字段 | 控件 | 规则 |
  |---|---|---|
  | 名称 * | NInput | 2~64 字符，后端 409 重名 toast |
  | 封面 | CoverUploader（仅 URL 模式） | 预览 2:3 |
  | 描述 | NInput textarea | ≤512 |
  | 排序 | NInputNumber | 默认 0，越小越前 |
- 删除：`movieCount>0` → 响应 409 → NDialog 展示"该池下已有 N 部影片，无法删除；可先停用池"（提供"去停用"按钮）。

### 5.4 影片列表 `/movies`

- 筛选栏：状态 Tab（全部(上架+下架) / 上架 / 下架；**候选不在这里**）+ 池 NSelect + 关键字 NInput(search, 防抖 400ms) + 右侧 `+ 新建影片`。
- 表格列：封面缩略图 / 标题(原名小字) / 所属池 / 年份 / 评分 / 剧集(`39集·1缺失`，缺失>0 红色) / 标签(≤3 + 更多 tooltip) / 来源(manual/源名 tag) / 状态 tag(绿·灰) / 更新时间 / 操作：
  - 详情 | 编辑 | 上下架(按状态，快捷按钮) | 删除(NPopconfirm→NDialog，展示将删剧集/断点数——后端 delete 响应带统计，前端展示 toast)
- 行点击 → 详情页 `/movies/:id`。

### 5.5 影片新建/编辑 `/movies/new`（编辑入口改 `/movies/:id/edit`，同页复用）

三区块（NCard 分节）：

1. **基本信息**：
   | 字段 | 控件 | 规则 |
   |---|---|---|
   | 片名 * | NInput | ≤191 |
   | 原名 | NInput | 可空 |
   | 所属池 * | NSelect（stores.app.pools） | 仅启用池 |
   | 年份 | NInputNumber | 1900~2100 |
   | 评分 | RatingInput | 0~10 一位小数 |
   | 地区 / 语言 | NSelect(标签 type=1) / NInput | 地区复用地区标签 |
   | 标签 | TagPicker | 多选 |
   | 摘要 | NInput textarea | ≤4000 |
   | 排序 | NInputNumber | 默认 0 |
   | 封面 *（新建时） | CoverUploader | URL + 2:3 预览，缺失显黄色警告（发布/创建要求） |
2. **剧集**（EpisodeTable，见 §6.4）：
   - 编辑已有影片：拉 `GET /movies/{id}/videos` 载入。
   - **OpenList 源关联**：当任一行直链为相对路径时，表上方出现 `NSelect sourceId`（源下拉，必选）+ 提示"相对路径将按此源组装播放直链"；全 REMOTE 时隐藏。
3. **底部操作栏**（sticky）：左 = 未保存标记（本地脏检查），右 = `保存并发布`（新建 POST / 编辑 PUT 主信息 → 若剧集有改动再 PUT videos）/ 取消。

脏检查：`beforeunload` + 路由离开守卫（NDialog"有未保存修改"）。

### 5.6 影片详情 `/movies/:id`

- 头卡：封面 140×210 + 标题/原名/年份/评分/标签/摘要 + 状态 tag + 上下架按钮 + 删除。
- NTab：
  - **剧集**：EpisodeTable 只读模式（列：季/集/标题/直链(truncate, 复制按钮)/大小/时长/状态(缺失红tag)/更新时间）；右上"批量导入"开 EpisodeImportModal。
  - **关联数据**（只读信息）：来源（manual/源名+sourcePath）、`episodeTotal`、`missingCount`、创建/更新时间。
- 编辑按钮 → `/movies/:id/edit`。

### 5.7 标签 `/tags`

- 按类型分三段（类型/地区/自定义），每段 NDataTable + `+ 添加`。
- 行内编辑（NInput 可编辑 name/sortNo）保存即 PUT；删除 NPopconfirm 提示"将自动从 N 部影片解除（响应 unboundMovies）"。

### 5.8 用户 `/users`

- 筛选：角色 Tab（TV 用户/管理员/全部）+ 状态 + 关键字。
- 列：用户名 / 昵称 / 角色 tag / 状态(开关) / 最近登录 / 创建时间 / 操作（编辑、重置密码、删除）。
- 新建弹窗：用户名（正则 `^[a-zA-Z0-9_]{3,32}$` 前端先行校验）/ 初始密码（NInputPassword + 强度弱提示 6~20）/ 昵称 / 角色 RB。
- 重置密码弹窗：`自定义` 或 `随机生成` 二选一；成功后弹**一次性明文**卡片（NInput readonly + 复制按钮 + "关闭即无法再查看"警告）。
- 自我保护：行是自己 → 隐藏删除/改角色/停用按钮（服务端 409 兜底）。

### 5.9 OpenList 源 `/sources`（M4，建源向导）

- 卡片式列表（NGrid，每卡）：
  - 头：名称 + 状态开关 + 连接测试按钮（结果小 tag：✓版本 / ✗原因）
  - 体：serverUrl / rootPath / **刮削范围（`includePaths.length` 个目录，tooltip 列全表；空="全部"）** / 规则 tag(A/B) / auto_publish 警示 tag(橙"自动上架") / 影片数 / 上次刮削(相对时间) / lastError(红字 truncate)
  - 脚：`全量刮削` | `增量刮削`（二按钮，source.lastScrapeAt 空时全量高亮）| 编辑 | 删除(无关联才可点)
- **新建 / 编辑统一走四步向导**（`SourceWizard.vue`，780px NModal，步骤条 1连接→2选根→3勾选→4确认）：

  **① 连接**：serverUrl* + 登录方式 NRadioGroup：
  - `API Token`：pwd-input 粘贴令牌；编辑态占位"留空=沿用已存"（空 + 地址未改 → 以 `{sourceId}` 建会话；**地址已改且空 → 拦截提示重填**）。
  - `账号密码`：username/password/otp（otp 提示"未开 2FA 留空"）→ 后端 `/api/auth/login` 换 token 存会话；附注"实例开验证码失败时改用 Token"。
  - 主按钮 `连接并下一步` → `POST /openlist/session`；成功显绿条 `连接成功 · OpenList vX`；任一凭据字段变动即视为需重连（authKey 快照比对）。

  **② 选根目录**：目录浏览器（面包屑可任意上下钻、分页"加载更多"）：每行 目录名（点击进入）+ `选为根目录` 按钮；顶栏 `将当前目录设为根目录` + 当前 `rootPath` 展示。编辑态以既有 rootPath 起步。

  **③ 勾选刮削影片**：同一浏览器限制在 `rootPath` 下（根外目录只读显示"根目录之外"）：每个子目录 `选择/✓已选` 切换 = **持久刮削范围**（整棵子树）；底部 NTag closable 已选清单 + `清空已选`；**不勾 = 整个根目录**（提示文案说明）。

  **④ 确认**：名称*(默认取 rootPath 末段/主机名) + 摘要卡（地址/根目录/范围 N 个目录全列表）+ 识别规则 A/B + 扩展名白名单 + 忽略模式 + 自动上架 + 说明 alert。
  - 新建：`创建并发起刮削` → POST sources（sessionId）→ 成功即 `POST /sources/{id}/scrape {type:FULL}` → toast `已创建并发起全量刮削（任务 #n）` → 关弹窗跳 `/tasks/:id`（自动刮削失败则降级为 warning 提示，源已建）。
  - 编辑：`保存` → PUT（带 sessionId 换/沿用 token、includePaths 落范围）→ toast **"已保存（如需立即按新范围刮削，请在列表点发起刮削）"**（防"改个名字就全量扫盘"）。
  - 共用护栏：会话过期（400 含"会话"）→ 回步①；范围越界 400、无凭据 400 由服务端文案直出。

### 5.10 刮削任务 `/tasks` + `/tasks/:id`（M4）

**列表**：筛选 源 NSelect + 状态 NSelect；列：#id / 源 / 类型 tag / 状态 tag(RUNNING 带 spin 图标) / 已扫目录 / 新增 / 更新 / 消失 / 开始~结束(耗时) / 错误(tooltip) / 操作（RUNNING→停止；全部→详情）。
RUNNING 行存在时，列表整页 3s 轻轮询。

**详情 `/tasks/:id`**：
- 顶部：大状态 tag + 源信息（名/地址/规则）+ 类型 + 发起人 + 起止。
- 进度卡（仅 RUNNING/STOPPING）：`已扫描 N 个目录` + 开始至今耗时 + NProgress(不确定态, status="warning" style)；STOPPING 文案"停止中，正在收尾…"。
- 结果卡（FINISHED/FAILED/STOPPED）：三统计（新增/更新/消失，NStatistic）+ 错误详情（FAILED，NAlert error，全量 error 文本）+ **若新增>0 → 主按钮"去审核候选（N 部新增）"**。
- RUNNING 期间 2s 轮询（usePolling，路由离开自动停）。

### 5.11 候选审核 `/movies/candidates` + 编辑（M4，核心页面）

**列表**：
- 筛选：源 NSelect + 关键字（片名）+ `仅未解析` 开关（parsed=false 优先处理）。
- 卡片流（NGrid 3~4 列，每项卡）：
  - 封面区 2:3：`posterCandidates[0]` 拼 `<serverUrl>/d/<encode>` 直读（失败占位图 + 路径小字）；无候选 → 占位图。
  - 标题（解析值）+ parsed 标记（✓ 已解析 / 橙 ⚠ 建议核对）+ 年份 tag。
  - `seasonSummary`（如"共 2 季 · 24 集"）+ sourcePath（truncate, tooltip 全路径）+ 来源源名。
  - 操作：`编辑` | `直接发布`(若已具备 pool+cover：单发 POST publish {ids:[id]}) | `忽略`(NPopconfirm)。
  - 多选模式（复选框）：底部 sticky 条 `已选 N · 批量发布 · 批量忽略(Secondary)`。
- 空态：`暂无候选，去发起一次刮削 →` 链接跳 /sources。

**编辑 `CandidateEdit`**（右抽屉 NDrawer 880px，不打断列表上下文）：
- 左 1/3：封面区——候选海报 NImage 列表（点选=设为封面）+ 或手填 URL 输入 + 实时预览。
- 右 2/3 表单：片名* / 原名 / 年份 / 评分(RatingInput) / 地区 / 语言 / 摘要 textarea / **所属池 *（NSelect）** / 标签 TagPicker。
- 底部只读：剧集预览（前 20 行：S/E/文件名，超 20 折叠"+N 集"）——防误判解析结果。
- 操作：`保存草稿`(PUT candidates/{id}) / `保存并发布`(PUT→ 单条 POST publish，成功关抽屉 + toast) / 取消。
- 发布失败 → 表单顶部 NAlert 列出失败原因（如"缺少封面"），定位到对应字段。

**批量发布**：POST {ids} → 结果 NDialog：`成功 N 部；失败明细表（id/标题/原因）`，失败行可"去编辑"。

---

## 6. 核心组件规格

### 6.1 `CoverUploader`
- 双模式输入：① URL 文本框（http 开头校验）② （候选场景）海报候选多选一。
- 右侧预览：NImage 2:3（240×360），加载中 skeleton，404 红色边 + 图裂占位。
- 对外 `v-model: url`，`props: candidates?[]`。

### 6.2 `TagPicker`
- NSelect multiple，options 按 type 分组（optgroup 标题"类型/地区/自定义"）；`stores.app.tags` 缓存，打开时刷新。
- 未选时 placeholder "选择标签（可多选）"。

### 6.3 `RatingInput`
- NRate（10 星，allow-half）+ 右侧数字 readout；入库 round(0.1)；empty = null（不评分）。

### 6.4 `EpisodeTable`（M3 核心）

| 列 | 说明 |
|---|---|
| 季 `seasonNo` | NInputNumber(1~99)，默认 1（同表首行值延续） |
| 集 `episodeNo` | NInputNumber(1~999) |
| 标题 `title` | NInput，可空（空=TV 端显示"第N集"） |
| 直链 `url` | 宽列 NInput + 识别标记：`http(s)://` 开头 → 蓝 tag `直链`；否则绿 tag `OpenList 相对路径` |
| 时长(秒) | NInputNumber，可空 |
| 缺失 | 只读红色 tag（编辑场景加载 existing 显示；新建行无） |
| 操作 | ↑ ↓（行内交换，不持久排号——放开 sort 语义，TV 端按 S/E 排序）| ✕ 删除行 |

- 新增行：`+ 添加一集`（追加在末尾，episodeNo = 当前季已填 max+1 自动建议）。
- **未保存态**：本地 rows 与 loaded 的 diff 打脏点；保存 = 整表 `PUT /movies/{id}/videos`（全量替换）；**离开页面前路由守卫 + beforeunload 拦截未保存**。
- 表格上方（相对路径场景）：`OpenList 源：NSelect *`（影片级 sourceId），全直链时整行隐藏。
- (s,e) 前端预校验：重复 → 行标红 + 禁用保存（后端 409 兜底）。

### 6.5 `EpisodeImportModal`
- NInput textarea（mono 字体，`min-h=180px`）+ 格式示例折叠（`https://...` 每行一条，或 `41,https://...` 指定集号）。
- 点"预览解析"：本地拆行 → 展示 `将新增 N 集（接到第 42 集起）/ 覆盖 M 集 / 跳过 K 行(原因)` 三行统计 + 前 5 行示例表。
- "确认导入" → POST import → 结果 toast `新增 N · 覆盖 M` + 若有 errors 弹 NDialog 行级明细；成功刷表。

### 6.6 通用
- `EmptyState`：插画位（内联 SVG）+ 文案 + 主按钮（各空列表复用）。
- `TimeText`：相对时间（<1min 实时、<7d 相对、否则 `yyyy-MM-dd HH:mm`），悬停绝对时间 tooltip。
- `CopyableText`：truncate + 复制图标（直链/路径/密码）。

---

## 7. 视觉与体验约定

- 主色沿用 Naive 默认 primary(#2080f0)；状态色：上架=success、下架=neutral、候选=warning、任务运行=info、失败=error。
- 表格密度 `medium`；全站字号 14；卡片圆角 8（Naive 默认）；操作列右对齐、次要操作收 NDropdown。
- 所有异步按钮必带 loading；所有批量操作结果必回显"成功 N / 失败明细"。
- 危险操作（删片/删用户/批量忽略）二次确认且文案含**量化影响**（"将删除 39 集、3 个用户的断点"）。
- 键盘：表单 Enter 提交；列表支持 `/` 聚焦搜索框（细节题，M3 顺手做）。

---

## 8. 构建与部署

```
dev : cd admin && npm i && npm run dev            # 5173，proxy /api → :8080
build: cd admin && npm run build                  # outDir=../server/src/main/resources/static
server: 启动后 http://<host>:8080/ 即为后台（hash 路由，refresh 无 404）
```

- `index.html` 内 `<title>HyperMux 管理台</title>`；dev 与 prod 共用一套，vite 注入 `import.meta.env.PROD` 开的构建 hash 文件名。
- **静态目录不进 git**（`server/src/main/resources/static/` 加 `.gitignore`），CI/手动 build 时产出；避免静态产物与源码混淆。
- 版本溯源：`GET /api/admin/dashboard` 响应后续加 `staticBuild`（构建时间戳）用于确认线上版本（M5 小项）。

---

## 9. 验收清单（对 PLAN M3/M4）

**M3 完成 = 能完整走通手动配置路径：**
1. 登录/登出/401 自动回登录页；admin 用 app 的 token 访问 → 403 toast。
2. 影片池 CRUD 全通（重名 409 toast、有影片删池 409 引导停用）。
3. 新建影片：表单校验全项 → 保存 → 列表可见 → 池选择/标签选择/封面预览正常。
4. 剧集：手填 2 集（一 REMOTE 一相对路径+选源）→ 保存；批量粘贴 10 行含 1 坏行 → 新增 9 + 错误明细 1。
5. 编辑影片改摘要/换池/删 1 集 → 全量替换语义正确（对比 GET 前后）。
6. 上下架即时（列表状态 tag）；删除二次确认展示影响数。
7. 用户：建 TV 用户 → 后台看其最后登录（App 登录后刷新可见）；重置密码一次性明文。
8. 所有接口报错路径（401/403/409/404/429）均有对应 toast/跳转，无静默失败（口径：控制台无未捕获 rejection）。

**M4 完成 = 能完整走通刮削路径：**
9. 建源：连通性失败（错 token）→ 创建被 400 拦住并展示原因；成功建源 card 信息完整。
10. 发起全量刮削 → /tasks/:id 进度可感（scanned 增长）→ FINISHED 结果卡统计正确 → 跳候选。
11. 候选：封面候选图直读成功；编辑抽屉补 池/封面/评分/标签 → 发布成功。
12. 批量选择 5 个发布且 1 个缺池 → 结果框 4 成功 + 1 失败定位编辑。
13. 忽略候选 → 列表消失、DB DRAFT 清除（重跑不恢复——source_path 锚点随候选删除释放，重跑再产生，**产品口径：忽略=放弃该目录**，如需再次刮回走重扫）。
14. 停止任务：2s 内 STOPPING → 最终 STOPPED，已落库候选保留可审核。
15. 重复发起 → 423 toast；源停用开关 → 刮削按钮禁用。
