-- HyperMux schema (idempotent; generated from live DB + rebuild additions)
-- Applied at startup by SchemaMigrateRunner. Safe to run on an existing DB.

CREATE TABLE IF NOT EXISTS category (
  id bigserial PRIMARY KEY,
  name varchar(64) NOT NULL,
  cover_url text,
  description varchar(512),
  sort_no int4 NOT NULL DEFAULT 0,
  status int2 NOT NULL DEFAULT 1,
  create_time timestamptz NOT NULL DEFAULT now(),
  update_time timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS movie (
  id bigserial PRIMARY KEY,
  title varchar(191) NOT NULL,
  original_title varchar(191),
  year int4,
  cover_url text,
  backdrop_url text,
  rating numeric,
  area varchar(64),
  language varchar(32),
  intro text,
  episode_total int4 NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'DRAFT',
  source_type varchar(16) NOT NULL DEFAULT 'MANUAL',
  source_id int8,
  source_path varchar(512),
  sort_no int4 NOT NULL DEFAULT 0,
  category_id int8,
  create_time timestamptz NOT NULL DEFAULT now(),
  update_time timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS movie_tag (
  movie_id int8 NOT NULL,
  tag_id int8 NOT NULL,
  PRIMARY KEY (movie_id, tag_id)
);

CREATE TABLE IF NOT EXISTS openlist_source (
  id bigserial PRIMARY KEY,
  name varchar(64) NOT NULL,
  server_url varchar(255) NOT NULL,
  api_token varchar(512) NOT NULL,
  root_path varchar(255) NOT NULL DEFAULT '/',
  status int2 NOT NULL DEFAULT 1,
  create_time timestamptz NOT NULL DEFAULT now(),
  update_time timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS play_progress (
  id bigserial PRIMARY KEY,
  user_id int8 NOT NULL,
  movie_id int8 NOT NULL,
  video_id int8 NOT NULL,
  position_ms int8 NOT NULL DEFAULT 0,
  duration_ms int8,
  finished int2 NOT NULL DEFAULT 0,
  update_time timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, video_id)
);

CREATE TABLE IF NOT EXISTS sys_config (
  config_key varchar(64) PRIMARY KEY,
  config_value text,
  update_time timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sys_user (
  id bigserial PRIMARY KEY,
  username varchar(32) NOT NULL UNIQUE,
  password_hash varchar(100) NOT NULL,
  nick_name varchar(64),
  avatar_url text,
  role int2 NOT NULL DEFAULT 0,
  status int2 NOT NULL DEFAULT 1,
  last_login_time timestamptz,
  create_time timestamptz NOT NULL DEFAULT now(),
  update_time timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS tag (
  id bigserial PRIMARY KEY,
  name varchar(32) NOT NULL,
  type int2 NOT NULL DEFAULT 0,
  sort_no int4 NOT NULL DEFAULT 0,
  status int2 NOT NULL DEFAULT 1,
  create_time timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS video (
  id bigserial PRIMARY KEY,
  movie_id int8 NOT NULL,
  season_no int4 NOT NULL DEFAULT 1,
  episode_no int4 NOT NULL DEFAULT 1,
  title varchar(191),
  duration int4,
  size int8,
  url_kind varchar(16) NOT NULL DEFAULT 'OPENLIST',
  openlist_path varchar(512),
  remote_url text,
  source_file varchar(255),
  fingerprint varchar(64),
  missing int2 NOT NULL DEFAULT 0,
  sort_no int4 NOT NULL DEFAULT 0,
  create_time timestamptz NOT NULL DEFAULT now(),
  update_time timestamptz NOT NULL DEFAULT now()
);

-- 应用自更新发布记录：每个平台（mobile/tv）仅保留最新一条；
-- APK 文件落 HYPERMUX_APK_DIR/{platform}-latest.apk（覆盖式发布）。
CREATE TABLE IF NOT EXISTS app_release (
  platform varchar(16) PRIMARY KEY,
  version_code int4 NOT NULL,
  version_name varchar(32) NOT NULL,
  file_name varchar(255) NOT NULL,
  sha256 varchar(64) NOT NULL,
  size_bytes int8 NOT NULL,
  release_notes text,
  mandatory int2 NOT NULL DEFAULT 0,
  update_time timestamptz NOT NULL DEFAULT now()
);

-- ===== rebuild additions (idempotent) =====
ALTER TABLE movie ADD COLUMN IF NOT EXISTS backdrop_url text;
-- 拼音检索列（TV 遥控器只能打拉丁字母：全拼/首字母子串匹配），存量回填见 SchemaMigrateRunner
ALTER TABLE movie ADD COLUMN IF NOT EXISTS title_pinyin varchar(512);
ALTER TABLE movie ADD COLUMN IF NOT EXISTS title_initials varchar(128);

-- ============ 账号设备（M7 投屏 · 2026-10-22） ============
-- 客户端安装级 UUID；"在线"不落库（内存 Caffeine 判定），last_seen 仅供展示且落库节流 30s。
CREATE TABLE IF NOT EXISTS user_device (
  id bigserial PRIMARY KEY,
  user_id int8 NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
  device_id varchar(64) NOT NULL,
  platform varchar(16) NOT NULL,
  device_name varchar(64),
  last_seen timestamptz,
  create_time timestamptz NOT NULL DEFAULT now(),
  update_time timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uk_device_user_device UNIQUE (user_id, device_id)
);
CREATE INDEX IF NOT EXISTS idx_device_user ON user_device (user_id, platform);

-- ============ 投屏请求（M7 投屏，单并发） ============
-- 状态机：PENDING → ACCEPTED | REJECTED | EXPIRED | CANCELLED；ACCEPTED → CLOSED。
-- 过期判定只认 expire_time（Caffeine 会被轮询续期，绝不能拿它判过期）。
CREATE TABLE IF NOT EXISTS cast_request (
  id bigserial PRIMARY KEY,
  user_id int8 NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
  sender_device_id varchar(64) NOT NULL,
  target_device_id varchar(64) NOT NULL,
  sender_name varchar(64),
  target_name varchar(64),
  movie_id int8 NOT NULL,
  video_id int8 NOT NULL,
  movie_title varchar(191) NOT NULL,
  episode_label varchar(64),
  cover_url text,
  position_ms int8 NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'PENDING',
  expire_time timestamptz NOT NULL,
  decided_time timestamptz,
  closed_time timestamptz,
  notified_at timestamptz,
  end_reason varchar(32),
  create_time timestamptz NOT NULL DEFAULT now(),
  update_time timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_cast_status CHECK (status IN ('PENDING','ACCEPTED','REJECTED','EXPIRED','CANCELLED','CLOSED'))
);
-- 单并发铁律：每账号同时最多 1 条 PENDING（部分唯一索引兜底，服务层先做抢占）
CREATE UNIQUE INDEX IF NOT EXISTS uk_cast_pending_user ON cast_request (user_id) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_cast_target_status ON cast_request (target_device_id, status, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_cast_user_time ON cast_request (user_id, create_time DESC);
-- cancelledIds 下发窗口：只下发"被终态化且尚未通知过目标设备"的请求
CREATE INDEX IF NOT EXISTS idx_cast_notify ON cast_request (target_device_id, notified_at);

-- ============ 影片关联（手动维护：其他季 / 衍生 / 前传 · 2026-11-17） ============
-- 语义：**双向对称** —— 服务层写入时总是成对落两条（A→B 与 B→A），删除也成对删；
-- 于是任意一侧的查询都只需 `movie_id = ?`（走 uk_movie_link 前缀索引），不必 OR 两列。
-- 读取侧另有一层兜底 `rowsIncomingOnly`：只有对方那一侧落了行的半截数据也会在本侧列出来（后台标 oneWay，保存即补齐成对）。
-- sort_no 默认 = 对方那一部是第几季（该影片剧集的 MIN(season_no)，第一季→1、第九季→9）；后台「按季号重排」两侧一起归位。
-- 只有 web 观看端与 Windows 桌面壳消费（§3.4 relatedMovies）；mobile/tv 忽略该字段。
-- relation_type = season / spinoff / custom（只影响后台分组展示，不做强校验）；
-- label = 卡片上的文案（如「第二季」），留空 = 客户端直接用目标片名。
CREATE TABLE IF NOT EXISTS movie_link (
  id bigserial PRIMARY KEY,
  movie_id int8 NOT NULL REFERENCES movie(id) ON DELETE CASCADE,
  related_movie_id int8 NOT NULL REFERENCES movie(id) ON DELETE CASCADE,
  relation_type varchar(16) NOT NULL DEFAULT 'season',
  label varchar(64),
  sort_no int4 NOT NULL DEFAULT 0,
  create_time timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uk_movie_link UNIQUE (movie_id, related_movie_id),
  CONSTRAINT ck_movie_link_not_self CHECK (movie_id <> related_movie_id)
);
-- 删除某部影片时要把"别人指向它"的那些反向行也收掉，没这个索引就得全表扫
CREATE INDEX IF NOT EXISTS idx_movie_link_related ON movie_link (related_movie_id);
