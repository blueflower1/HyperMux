package com.hypermux.server.app.vo;

import java.util.List;
import java.util.Map;

/** App 端出参（契约 §3）：字段名与 Android data/model/Models.kt 严格一致。 */
public final class TvVos {

    private TvVos() {
    }

    // ---- 3.1 登录 ----
    public record SessionUserVo(Long id, String username, String nickName) {
    }

    public record SessionVo(String token, long expiresAt, SessionUserVo user) {
    }

    // ---- 3.2 首页 ----
    public record CategoryVo(Long id, String name, String coverUrl, int movieCount) {
    }

    public record LastVideoVo(Long id, int seasonNo, int episodeNo) {
    }

    public record ContinueVo(Long movieId, String title, String coverUrl, String backdropUrl, String categoryName,
                             LastVideoVo lastVideo, Long positionMs, Long durationMs) {
    }

    public record HomeVo(List<CategoryVo> categories, List<ContinueVo> continueWatching,
                         List<MovieCardVo> banners) {
    }

    // ---- 3.3 / 3.10 影片卡片 ----
    public record ProgressLiteVo(Long videoId, int seasonNo, int episodeNo, long positionMs, long durationMs) {
    }

    public record MovieCardVo(Long id, String title, String coverUrl, String backdropUrl, Double rating,
                              Integer year, String area, int episodeTotal, List<String> tags,
                              ProgressLiteVo progress, long updateTime) {
    }

    // ---- 3.4 详情 ----
    public record TagRefVo(Long id, String name, int type) {
    }

    public record CategoryRefVo(Long id, String name) {
    }

    public record ProgressFullVo(Long videoId, int seasonNo, int episodeNo, long positionMs, long durationMs,
                                 int finished) {
    }

    public record MovieDetailVo(Long id, String title, String originalTitle, Integer year, String coverUrl,
                                String backdropUrl, Double rating, String area, String language, String intro,
                                List<TagRefVo> tags, int episodeTotal, CategoryRefVo category,
                                ProgressFullVo progress) {
    }

    // ---- 3.5 剧集列表 ----
    public record VideoProgressVo(long positionMs, long durationMs, int finished) {
    }

    public record VideoItemVo(Long id, int episodeNo, String title, Integer duration, VideoProgressVo progress) {
    }

    public record SeasonVo(int seasonNo, List<VideoItemVo> videos) {
    }

    public record SeasonsVo(Long movieId, List<SeasonVo> seasons) {
    }

    // ---- 3.6 播放直链 ----
    public record PlayUrlReq(Long videoId) {
    }

    public record PlayUrlVo(String url, Map<String, String> headers, Long expiresAt) {
    }

    // ---- 3.7 历史 ----
    // 注意：新增字段一律追加在**最后一个参数**，避免破坏既有位置参数构造的兼容性。
    public record HistoryVo(Long videoId, Long movieId, String title, String coverUrl, String categoryName,
                            int seasonNo, int episodeNo, long positionMs, long durationMs, int finished,
                            long updateTime, String backdropUrl) {
    }

    // ---- 3.8 / 3.9 断点 ----
    public record ProgressReq(Long videoId, Long positionMs, Long durationMs, Integer finished) {
    }

    public record ProgressVo(Long videoId, long positionMs, int finished, long updateTime) {
    }

    public record FinishReq(Long videoId, String action) {
    }

    public record FinishVo(Long videoId, String action) {
    }

    // ---- 3.11 应用自更新 ----
    public record UpdateCheckVo(int versionCode, String versionName, String sha256, long sizeBytes,
                                String releaseNotes, boolean mandatory) {
    }
}
