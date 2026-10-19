# 60 · 解析器与导入刮削（用例 / 边界 / 回归）

> 隶属：[PLAN.md](../../PLAN.md) · 版本 v2.0 · 对应里程碑：M4'（2026-10-11）
> **v2 变更**：全量/增量引擎、diff、任务调度章节**已废弃**（OpenList 收敛为数据源，见 PLAN §5）；
> **解析器用例表（§1：M1~14 / E1~16 / 季 / 自然序 / ignore / NFO）仍为新建影片「目录导入」复用**，本文档保留 §1 为事实源。
> 引擎设计部分（§2 起，v1 内容）仅存档，勿再引用。

---

## 1. 解析器用例表

> 算法约定（与 20-backend §13.3/13.4 一致）：
> - 文件级处理顺序：**取 stem（去扩展名）→ 剥离质量尾缀（1080p/2160p/4K/BluRay/WEBRip/x264/x265/HEVC/HDR/HDR10/REMUX）→ 依次尝试规则 → 降级自然序**。
> - 数值合法域：解析出的 `s ≤ 99`、`e ≤ 999`，超出视该规则未命中（继续降级）。**补偿序号（自然序）不受该限**。
> - `parsed` 置信位：规则 1~3 命中 = true（审核页绿点），兜底自然序 = false（橙点"建议核对"）。

### 1.1 MovieNameParser（目录名 / 单文件电影 stem）

| # | 输入 | 期望 title | year | parsed | 说明 |
|---|---|---|---|---|---|
| M1 | `狂飙 (2023)` | 狂飙 | 2023 | true | 半角空格+括号 标准 |
| M2 | `龙虾排盘（1984）` | 龙虾排盘 | 1984 | true | 中文括号 |
| M3 | ` 狂飙 (2023) ` | 狂飙 | 2023 | true | 首尾空白 trim |
| M4 | `Oppenheimer 2023` | Oppenheimer | 2023 | true | 规则2 尾年份 |
| M5 | `Oppenheimer.2019.1080p.BluRay.x265` | Oppenheimer | 2019 | true | 先剥质量缀→尾年份成立 |
| M6 | `老友记 (1994)` | 老友记 | 1994 | true | |
| M7 | `The.Wire (2002)` | The.Wire | 2002 | true | 点分名保留原样 |
| M8 | `Inventing` | Inventing | null | false | 无年份 |
| M9 | `狂飙.mkv` | 狂飙 | null | false | 单文件电影，去扩展名 |
| M10 | `电影 (1899)` | 电影 (1899) | null | false | 年份域 1900~2100，不猜 |
| M11 | `电影 (2099)` | 电影 | 2099 | true | 上界内可命中 |
| M12 | ` movie (2008remake)` | movie (2008remake) | null | false | 括号内非纯 4 位年 → 原样保留 |
| M13 | `S01E01 (sample)` | (整目录命中 ignore) | - | - | 见 §1.5，不进解析 |
| M14 | `.hidden (2020)` | (目录跳过) | - | - | 隐藏目录整跳过 |

### 1.2 EpisodeNameParser（文件 stem，season 由目录决定，见 §1.3）

| # | 输入 | 期望 e | parsed | 命中规则 |
|---|---|---|---|---|
| E1 | `狂飙.S01E02.1080P` | 2 | true | R1 SxxExx |
| E2 | `x s01 e02 1080p` | 2 | true | R1（CI+分隔符宽松） |
| E3 | `SS01EE02` | 2 | true | R1 连续写法（`S\d{1,2}E\d{1,3}` 整体匹配） |
| E4 | `第3集` | 3 | true | R2 |
| E5 | `第二季第1集` | 1 | true | R3（季取 2，见 1.3） |
| E6 | `第三季第10集 x264` | 10 | true | R3 |
| E7 | `EP004` | 4 | false | R4 独立数字 |
| E8 | `04` | 4 | false | R4 |
| E9 | `E011` | 11 | false | R4（无 S 前缀，降级不猜季） |
| E10 | `xx.2023.E12.1080p` | 12 | false | R4 取**首个合法数字段**（2023>999 跳过） |
| E11 | `2023.mkv` | 自然序 | false | 2023>999 全弃 → R5 |
| E12 | `E1080` | 自然序 | false | 1080>999 弃 |
| E13 | `S2`（纯季标记文件） | 自然序 | false | 无 E → R5（season 取目录 2） |
| E14 | `007` | 7 | false | 前导零 |
| E15 | `S01E02 第2集 [sub]` | 2 | true | 多特征 R1 优先 |
| E16 | `第零集` | 自然序 | false | 中文数字仅支持"一~十"，"零"不映射 |

### 1.3 Season 判定（目录名）

| 目录名 | season |
|---|---|
| `S01` / `S1` / `S 02` / `s03` | 1 / 1 / 2 / 3（`S\d{1,2}` CI，≤99） |
| `Season03` / `Season 4` | 3 / 4（`Season\s*(\d{1,2})` CI） |
| `第2季` | 2（中文一~十） |
| 其他（含 `原始备份`、`Extras`） | 1 |

匹配方式：目录名**去空白后整体等于** 模式，或 `S\d{1,2}` 出现在目录名首段；不允许子串（防 `Assassin S01 风云` 误判——该目录按普通目录处理，季=1）。

### 1.4 自然序（R5）

- 规则：对**未解析出 e 的文件按自然排序**（数字段按数值比：`7 < 12`），在该 season 内被定为 `e = 序号(1..N)`；**解析出 e 的文件保持原 e**，仅当与同 season 序号冲突时按 20-backend §13.6 补偿后缀。
- 用例：`[EP010, EP02, 2023.mkv, EP007]` 中 2023 被判自然序 → 该文件 e 取其在同批自然序文件中的位置（这里该批=`[2023.mkv]` → e=1）。
- 用例：`[07, 12, 3]`（全自然序）→ e = 3,7,12 → 重新编号 1,2,3；warn 日志记录原文件名。
- 混合：`[E01(合法→1), E03(合法→3), fx(自然序→2)]` → 1,3,2 保持，不扰动。

### 1.5 忽略（ignore）规则用例

- 匹配方式：**文件名/路径片段 子串匹配，大小写敏感**；命中文件直接跳过；**目录名**命中则该目录整棵跳过（进 visited、不遍历、不计数进 scanned 之外单独计 skippedDirs）。
- 默认 `sample,Sample,样本,.part,.!qB,aria2`：

| 输入 | 期望 |
|---|---|
| `xx.sample.1080p.mkv` | 跳过 |
| `xx.SAMPLE.mkv` | **不跳过**（大小写敏感——配置文档明示需手补 `SAMPLE`） |
| 目录 `Sample` | 整目录跳过 |
| `movie.mkv.part` | 跳过 |
| `.hidden dir/xx.mkv`（目录以 `.` 开头） | 跳过（硬编码隐藏规则） |
| `xx.aria2.lnk` | 跳过 |
| `字幕外挂.ass` / `.srt` | 跳过（非白名单扩展名，天然过滤） |

---

## 2. 冲突与幂等场景表（→ 集成测试 S 系列）

| # | 场景 | 期望行为（对 DB 的最终断言） |
|---|---|---|
| S1 | 同一 (s,e) 批内两个文件先后解析出（同任务内） | 第一个落库；第二个走补偿序号（同季 max+1），任务日志 warn |
| S2 | 重跑刮削：文件未变 | new=0 update=0 remove=0；无新候选行；人工字段逐字节不变 |
| S3 | 候选 DRAFT 重复重跑 | 候选行数不变（source_path 锚点命中复用），posterCandidates 去重更新 |
| S4 | 已发布影片重跑，且期间**人工改过 title/cover/rating** | 引擎 UPDATE 列白名单生效：仅 size/fingerprint/missing/剧集行变化；`title` 等与任务前逐字段相等 |
| S5 | 文件 fingerprint 相同但路径变化（被移到家目录另一处） | 旧路径行 missing=1；"另一处"若属新影片目录→新候选；不自动搬行 |
| S6 | 同影片 1500 集综艺（e>999 解析全部失败落自然序） | 全部落库，e=1..1500 连续，无 409，任务 FINISHED |
| S7 | 两源共享同一路径（源A `/a`，源B 根盘 `/`） | 两部影片（source_id 不同）共存；不互删 |
| S8 | DRAFT 候选与同名 PUBLISHED 手工片（同池） | 发布该候选 → 失败项 `{msg: 同池片名已存在，请改名或换池}`；候选行保留可编辑 |
| S9 | 手动影片（MANUAL）后被源目录"覆盖"出现相同 source_path 影片 | MANUAL 与 SCRAPE 并存；SCRAPE 侧正常候选流程；无级联影响 |
| S10 | 删除源（无关联影片）→ 再建同名同路径 → 重刮 | 全新候选，与旧数据无关联（旧 movie SOURCE 随源删已清） |
| S11 | 人工建影片时 episodes 用相对路径（sourceId=1），随后对源 1 刮削到同路径文件 | 人工影片 video 为 OPENLIST 行；刮削侧只管理 SCRAPE 影片，不触碰人工行的 fingerprint；同路径两条 video 分属不同 movie ——播放各自指同一 OpenList 文件，正常 |
| S12 | 刮削中人工同时对该候选 publish/编辑 | 引擎写列白名单 → 无覆盖；发布后引擎新增剧集仍并入该 PUBLISHED 影片（source 锚点命中） |

---

## 3. diff 边界场景矩阵（→ 集成测试 D 系列）

> 前置：源目录 `/videos`，已有一部 PUBLISHED 影片 A（S1 10 集 + S2 5 集）。

| # | 硬盘变化 | 重扫结果 | TV 端可见性变化 |
|---|---|---|---|
| D1 | 无变化 | 零 diff | 无 |
| D2 | A/S1 新增 `E11.mkv` | A 新 video (1,11)；A.episode_total=16 | A 列表见 11 集进度条空 |
| D3 | 新增整目录 `B (2004)`（8 集） | 新 DRAFT 影片 B（候选）；A 不动 | B 不可见，直到发布 |
| D4 | A/S1 删 1 个文件 | 对应 video missing=1；remove_count=1 | A 该剧集 TV 剧集页消失，其余可播 |
| D5 | A 整目录消失 | A 全部 video missing=1，episode_total=0 | **A 从 TV 列表/详情消失**（TV 查询过滤 `episode_total>0`），状态仍 PUBLISHED（重出现可自愈） |
| D6 | D5 后目录恢复（文件未变） | missing=0，episode_total 复原 | A 恢复可见，断点仍在（play_progress 未删） |
| D7 | A 部分文件改名扩展（`.mkv`→`.mp4`） | 旧行 missing=1，新行 (s,e 冲突?) —— 冲突则补偿；**强烈建议任务页提示"出现 missing 的影片"人工核对** | 视频断裂，人工在剧集页可修 |
| D8 | 候选 B 被审核 ignore → 再刮 | B 重新出现（锚点释放） | - |
| D9 | auto_publish 源新增影片 C | C 直接 PUBLISHED 入 `__auto_<id>` 池 | C 首页立即可见 |
| D10 | 源改名 `A` → `A  Tomatoes` | 旧锚点 D5 逻辑；新名=新候选 | 旧 A 消失 + 新候选待审（产品口径：改名=重加，接受） |
| D11 | 扫描中 OpenList 401（token 中途失效） | 已完成批次保留；任务 FAILED + error=openlist 401 | 无破坏 |
| D12 | 服务在扫描中重启 | 启动钩子把 RUNNING→FAILED；重跑幂等（§S2 保证） | 无 |
| D13 | 点击停止 | ≤2s 落 STOPPED；已完成批次保留 | 部分候选可审 |
| D14 | 硬顶触顶（目录>20000 或深度>8） | 任务 FAILED `超顶`；**不写部分结果**；source.last_error 记录 | 无 |
| D15 | 源被停用（status=0）后任务仍在跑 | 用启动快照跑完；列表侧刮削按钮禁用（双保险） | 无 |

---

## 4. 任务调度演练表（→ 集成测试 T 系列）

| # | 场景 | 期望 |
|---|---|---|
| T1 | 源 1 有 RUNNING 任务，再次 POST scrape | 423，响应 msg 含任务 id |
| T2 | 源 1 / 源 2 同时发起 | 各自成功（源级互斥，非全局） |
| T3 | STOPPING 状态期间再 POST stop | 409（非 RUNNING 拒绝） |
| T4 | 任务总超时（mock 慢 IO 超 30min） | 落 FAILED，error=timeout；锁释放 |
| T5 | RUNNING 时进程重启 | 启动钩子全部置 FAILED（§D12） |
| T6 | 同一源连发 10 次（并发） | 恰好 1 个 RUNNING，9 个 423 |
| T7 | 任务历史 50+ 条后查询 | 列表 <200ms（source_id 索引） |
| T8 | 删除源（无关联）时 | 该源历史任务随 FK cascade 消失（任务页不再列） |

---

## 5. 性能回归口径（M4 实测留档）

| 场景 | 规模 | 指标 |
|---|---|---|
| 典型库 | ~200 影片 / ~5000 文件 | 全量 < 10 分钟；进度可读（scanned ≥2s 增长） |
| 大树硬顶 | 构造 20000+ 目录 | 在 3~5 分钟内**快速触发 FAILED**（防误配全盘扫一晚） |
| 大族 | 1500 集影片 × 2 | 落库 < 60s（批量写入生效） |
| 接口 | 候选列表（200 候选） | p95 < 300ms |
| 增量小 diff | 增 5 文件 | 全任务 < 遍历时间主成本，无额外全表扫 |

跑法：真实 OpenList（公网）一个测试盘；异常样例盘（含 §1 全部边缘命名）常驻 `/videos/_testlab` 供每次回归。

---

## 6. Publish 与可见性联动回归

| # | 动作 | TV 端断言 |
|---|---|---|
| P1 | 候选缺 pool/cover → publish 单条/批量 | 400 明细命中对应项；DB 仍 DRAFT |
| P2 | 补齐后 publish → home | home.pools 含该池新 count；continueWatching 无该影片 |
| P3 | publish 后立即 play-url 任 1 集 | 200 且 URL 非空（OPENLIST 编码正确，抽查中文路径） |
| P4 | OFF 下架 | 列表/详情/历史中均消失；历史点"继续看" → play-url 404 提示"暂不可用" |
| P5 | 源删除前把关 | movieCount>0 → 409；停用 → 历史播放不受影响（play-url 组装靠已存 server_url 快照? ——**锁定：play-url 运行时读源表，源停用后仍可播，源不可能物理删（有片即禁删），天然闭环**） |

---

## 7. M4 发布 Checklist（与 PLAN §5.7 / 50-admin §9 M4 对齐）

- [ ] §1 全部用例进 UT（MovieNameParser ≥14、EpisodeNameParser ≥16、Season ≥4、自然序 ≥4、ignore ≥7）且全绿
- [ ] §2 S1~S12 + §3 D1~D15 + §4 T1~T8 集成用例全绿（testcontainers PG 或本地 PG）
- [ ] §5 性能三项实测数据截图归档
- [ ] PLAN §5.7 DoD 五条逐条过（含"公网 OpenList 地址"形态）
- [ ] OpenList 真实版本冒烟：`source/test` 版本回显 + 200 影片目录全量 + 增量 5 片 + 停止 + 重启恢复各一次
- [ ] 任务页/候选页 UI 与 50-admin §5.10/5.11 验收 9~15 逐条过
- [ ] 10-data-model `openlist_source`/`scrape_task` 种子/索引无变更（如有 → 先改 10 文档）
