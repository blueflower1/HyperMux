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
