package com.hypermux.server.admin.vo;

import java.util.List;

/**
 * 后台出参与请求体（契约 §4）：字段名与 hyper-mux-admin/src/api/types.ts 严格一致。
 * 时间一律毫秒时间戳（long）。
 */
public final class AdminVos {

    private AdminVos() {
    }

    // ==================== 出参 ====================

    public record TagRef(Long id, String name) {
    }

    public record CategoryVo(Long id, String name, String coverUrl, String description, Integer sortNo,
                             int status, long movieCount, long createTime, long updateTime) {
    }

    public record MovieListVo(Long id, String title, String originalTitle, Long categoryId, String categoryName,
                              Integer year, String coverUrl, Double rating, String area, String language,
                              String intro, int episodeTotal, int missingCount, List<TagRef> tags,
                              String status, String sourceType, Long sourceId, String sourceName, String sourcePath,
                              Integer sortNo, long createTime, long updateTime) {
    }

    public record MovieDetailVo(Long id, String title, String originalTitle, Long categoryId, Integer year,
                                String coverUrl, String backdropUrl, Double rating, String area, String language,
                                String intro, Integer sortNo, Long sourceId, String status, List<TagRef> tags) {
    }

    public record VideoVo(Long id, int seasonNo, int episodeNo, String title, String urlKind,
                          String openlistPath, String remoteUrl, Long size, Integer duration, String sourceFile,
                          String fingerprint, int missing, long updateTime) {
    }

    public record VideoListData(Long movieId, List<VideoVo> list, int missingCount) {
    }

    public record TagVo(Long id, String name, int type, Integer sortNo, int status, long movieCount) {
    }

    public record UserVo(Long id, String username, String nickName, int role, int status, Long lastLoginTime,
                         long createTime) {
    }

    public record DashboardVo(long categories, long publishedMovies, long draftMovies, long users, long sources) {
    }

    public record SourceVo(Long id, String name, String serverUrl, String rootPath, int status, long movieCount,
                           long createTime, long updateTime) {
    }

    public record OpenlistEntry(String name, String path, long size, boolean dir, long modifiedMs) {
    }

    public record OpenlistListResult(List<OpenlistEntry> list, long total, int page, boolean hasMore) {
    }

    public record OpenlistSessionInfo(String sessionId, String version) {
    }

    public record SourceTestResult(boolean ok, String version, String message) {
    }

    public record ImportedEpisodeVo(int seasonNo, int episodeNo, String title, String relPath, String fileName,
                                    long size) {
    }

    public record MovieImportVo(Long sourceId, String serverUrl, String rootPath, String path, String relPath,
                                String title, Integer year, boolean parsed, List<ImportedEpisodeVo> episodes,
                                List<String> posters, int dirCount, int skippedDirs) {
    }

    public record MetadataItemVo(String providerId, String mediaType, String title, String originalTitle,
                                 Integer year, String posterUrl) {
    }

    public record MetadataDetailVo(String providerId, String mediaType, String title, String originalTitle,
                                   Integer year, Double rating, String intro, String posterUrl, String backdropUrl,
                                   String area, List<String> genres) {
    }

    public record MetadataImagesVo(List<ImageCandidateVo> posters, List<ImageCandidateVo> backdrops) {
    }

    public record ImageCandidateVo(String url, String label, Integer width, Integer height) {
    }

    public record SettingsVo(String metadataProvider, String tmdbApiKey) {
    }

    public record ImportErrorVo(int line, String msg) {
    }

    public record ImportResultVo(int added, int updated, List<ImportErrorVo> errors) {
    }

    public record ReplaceVideosResultVo(int total, int added, int updated, int removed) {
    }

    public record MovieSaveResultVo(Long id, String status, int episodeTotal) {
    }

    public record IdResultVo(Long id) {
    }

    public record UserCreateResultVo(Long id, String username) {
    }

    public record SourceDeleteResultVo(Long id, int deletedTasks) {
    }

    public record ResetPasswordResultVo(Long userId, String username, String newPassword) {
    }

    public record DeleteMovieResultVo(Long id, int deletedEpisodes, int deletedProgress) {
    }

    public record DeleteUserResultVo(Long id, int deletedProgress) {
    }

    public record UnboundResultVo(Long id, int unboundMovies) {
    }

    public record AdminMeVo(Long id, String username, String nickName, int role, Long lastLoginTime) {
    }

    public record ScrapeImagesResultVo(Long id, String title, String provider, String matchedTitle,
                                       Integer matchedYear, String coverUrl, String backdropUrl,
                                       List<String> uploaded, String message) {
    }

    public record BatchErrorVo(Long id, String msg) {
    }

    public record ScrapeImagesBatchResultVo(int total, int ok, int failed, List<BatchErrorVo> errors) {
    }

    // ==================== 请求体 ====================

    public record CategoryForm(String name, String coverUrl, String description, Integer sortNo, Integer status) {
    }

    public record EpisodeBody(Long id, Integer seasonNo, Integer episodeNo, String title, String url,
                              Integer duration) {
    }

    public record MovieMainBody(String title, String originalTitle, Long categoryId, Integer year, String coverUrl,
                                String backdropUrl, Double rating, String area, String language, String intro,
                                Integer sortNo, Long sourceId, String sourcePath, List<Long> tagIds,
                                List<EpisodeBody> episodes) {
    }

    public record VideoReplaceReq(List<EpisodeBody> episodes) {
    }

    public record VideoImportReq(Integer seasonNo, String text) {
    }

    public record TagForm(String name, Integer type, Integer sortNo, Integer status) {
    }

    public record UserForm(String username, String password, String nickName, Integer role, Integer status) {
    }

    public record ResetPasswordReq(String password) {
    }

    public record SourceForm(String name, String serverUrl, String apiToken, String sessionId, String rootPath,
                             Integer status) {
    }

    public record OpenlistSessionReq(String serverUrl, String apiToken, String username, String password,
                                     String otpCode, Long sourceId) {
    }

    public record OpenlistListReq(String path, Integer page) {
    }

    public record ImportFolderReq(Long sourceId, String path) {
    }

    public record SettingsUpdateReq(String metadataProvider, String tmdbApiKey) {
    }

    public record ScrapeImagesBatchReq(List<Long> ids, Boolean onlyMissing, String provider) {
    }

    // ==================== 应用自更新 ====================

    /** 当前发布（无发布时 data 为 null） */
    public record AppReleaseVo(String platform, int versionCode, String versionName, String sha256,
                               long sizeBytes, String releaseNotes, boolean mandatory, long updateTime) {
    }
}
