package com.hypermux.server.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hypermux.server.admin.service.CategoryAdminService;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.category.CategoryEntity;
import com.hypermux.server.category.CategoryMapper;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.common.PageResult;
import com.hypermux.server.common.Ts;
import com.hypermux.server.movie.MovieEntity;
import com.hypermux.server.movie.MovieMapper;
import com.hypermux.server.movie.MovieTagEntity;
import com.hypermux.server.movie.MovieTagMapper;
import com.hypermux.server.progress.PlayProgressEntity;
import com.hypermux.server.progress.PlayProgressMapper;
import com.hypermux.server.scrape.OpenListSourceEntity;
import com.hypermux.server.scrape.SourceMapper;
import com.hypermux.server.video.VideoEntity;
import com.hypermux.server.video.VideoMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 影片 + 剧集后台管理（契约 §4.4 / §4.5）。 */
@Service
public class MovieAdminService {

    private static final int INTRO_MAX = 100;
    private static final Set<String> VALID_STATUS = Set.of("PUBLISHED", "DRAFT", "OFF");

    private final MovieMapper movieMapper;
    private final VideoMapper videoMapper;
    private final MovieTagMapper movieTagMapper;
    private final CategoryMapper categoryMapper;
    private final SourceMapper sourceMapper;
    private final PlayProgressMapper progressMapper;
    private final CategoryAdminService categoryAdminService;

    public MovieAdminService(MovieMapper movieMapper, VideoMapper videoMapper, MovieTagMapper movieTagMapper,
                             CategoryMapper categoryMapper, SourceMapper sourceMapper,
                             PlayProgressMapper progressMapper, CategoryAdminService categoryAdminService) {
        this.movieMapper = movieMapper;
        this.videoMapper = videoMapper;
        this.movieTagMapper = movieTagMapper;
        this.categoryMapper = categoryMapper;
        this.sourceMapper = sourceMapper;
        this.progressMapper = progressMapper;
        this.categoryAdminService = categoryAdminService;
    }

    // ==================== 列表 ====================

    public PageResult<AdminVos.MovieListVo> page(int page, int size, String keyword, String status,
                                                 Long categoryId, String sortBy) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        LambdaQueryWrapper<MovieEntity> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            qw.and(w -> w.like(MovieEntity::getTitle, k).or().like(MovieEntity::getOriginalTitle, k));
        }
        List<String> statuses = parseStatuses(status);
        qw.in(MovieEntity::getStatus, statuses);
        if (categoryId != null) {
            qw.eq(MovieEntity::getCategoryId, categoryId);
        }
        if ("sortNo".equalsIgnoreCase(sortBy)) {
            qw.orderByAsc(MovieEntity::getSortNo).orderByAsc(MovieEntity::getId);
        } else {
            qw.orderByDesc(MovieEntity::getUpdateTime).orderByDesc(MovieEntity::getId);
        }
        Page<MovieEntity> result = movieMapper.selectPage(new Page<>(p, s), qw);

        List<AdminVos.MovieListVo> list = new ArrayList<>();
        List<MovieEntity> rows = result.getRecords();
        if (!rows.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (MovieEntity m : rows) {
                ids.add(m.getId());
            }
            Map<Long, List<AdminVos.TagRef>> tagMap = tagsOf(ids);
            Map<Long, VideoMapper.MovieCountRow> countMap = new HashMap<>();
            for (VideoMapper.MovieCountRow row : videoMapper.countsByMovie()) {
                countMap.put(row.getId(), row);
            }
            Map<Long, String> categoryNames = categoryNames();
            Map<Long, String> sourceNames = sourceNames();

            for (MovieEntity m : rows) {
                VideoMapper.MovieCountRow c = countMap.get(m.getId());
                list.add(new AdminVos.MovieListVo(
                        m.getId(), m.getTitle(), m.getOriginalTitle(), m.getCategoryId(),
                        m.getCategoryId() == null ? null : categoryNames.get(m.getCategoryId()),
                        m.getYear(), m.getCoverUrl(), toDouble(m.getRating()), m.getArea(), m.getLanguage(),
                        truncate(m.getIntro()),
                        c == null || c.getTotal() == null ? 0 : c.getTotal().intValue(),
                        c == null || c.getMissingCnt() == null ? 0 : c.getMissingCnt().intValue(),
                        tagMap.getOrDefault(m.getId(), List.of()),
                        m.getStatus(), m.getSourceType(), m.getSourceId(),
                        m.getSourceId() == null ? null : sourceNames.get(m.getSourceId()),
                        m.getSourcePath(), m.getSortNo(), Ts.milli(m.getCreateTime()), Ts.milli(m.getUpdateTime())));
            }
        }
        return PageResult.of(list, result.getTotal(), p, s);
    }

    private List<String> parseStatuses(String status) {
        if (status == null || status.isBlank()) {
            return List.of("PUBLISHED", "OFF");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String raw : status.split(",")) {
            String v = raw.trim().toUpperCase();
            if (v.isEmpty()) {
                continue;
            }
            if (!VALID_STATUS.contains(v)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "status 取值非法: " + raw);
            }
            out.add(v);
        }
        return out.isEmpty() ? List.of("PUBLISHED", "OFF") : new ArrayList<>(out);
    }

    private Map<Long, List<AdminVos.TagRef>> tagsOf(List<Long> movieIds) {
        Map<Long, List<AdminVos.TagRef>> map = new HashMap<>();
        for (MovieTagMapper.MovieTagRow row : movieTagMapper.rowsOfMovies(movieIds)) {
            map.computeIfAbsent(row.getMovieId(), k -> new ArrayList<>())
                    .add(new AdminVos.TagRef(row.getTagId(), row.getName()));
        }
        return map;
    }

    private Map<Long, String> categoryNames() {
        Map<Long, String> map = new HashMap<>();
        for (CategoryEntity c : categoryMapper.selectList(new LambdaQueryWrapper<>())) {
            map.put(c.getId(), c.getName());
        }
        return map;
    }

    private Map<Long, String> sourceNames() {
        Map<Long, String> map = new HashMap<>();
        for (OpenListSourceEntity s : sourceMapper.selectList(new LambdaQueryWrapper<>())) {
            map.put(s.getId(), s.getName());
        }
        return map;
    }

    // ==================== 详情 ====================

    public AdminVos.MovieDetailVo detail(Long id) {
        MovieEntity m = require(id);
        List<AdminVos.TagRef> tags = tagsOf(List.of(id)).getOrDefault(id, List.of());
        return new AdminVos.MovieDetailVo(m.getId(), m.getTitle(), m.getOriginalTitle(), m.getCategoryId(),
                m.getYear(), m.getCoverUrl(), m.getBackdropUrl(), toDouble(m.getRating()), m.getArea(),
                m.getLanguage(), m.getIntro(), m.getSortNo(), m.getSourceId(), m.getStatus(), tags);
    }

    // ==================== 新建 / 更新 ====================

    @Transactional
    public AdminVos.MovieSaveResultVo create(AdminVos.MovieMainBody body) {
        String title = requireTitle(body.title());
        if (body.categoryId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "必须归入影视类型");
        }
        CategoryEntity category = categoryAdminService.require(body.categoryId());
        if (category.getStatus() != null && category.getStatus() == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该影视类型已停用");
        }
        List<AdminVos.EpisodeBody> episodes = body.episodes();
        if (episodes == null || episodes.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少需要 1 集剧集");
        }
        ensureTitleFree(body.categoryId(), title, null);

        MovieEntity entity = new MovieEntity();
        entity.setTitle(title);
        entity.setCategoryId(body.categoryId());
        entity.setStatus(MovieEntity.STATUS_PUBLISHED);
        entity.setSourceType(MovieEntity.SOURCE_MANUAL);
        applyMainFields(entity, body);
        movieMapper.insert(entity);

        List<VideoEntity> videos = buildVideos(entity.getId(), entity.getSourceId(), episodes, true);
        for (VideoEntity v : videos) {
            videoMapper.insert(v);
        }
        entity.setEpisodeTotal(countUsable(videos));
        movieMapper.updateById(entity);

        if (body.tagIds() != null) {
            replaceTags(entity.getId(), body.tagIds());
        }
        return new AdminVos.MovieSaveResultVo(entity.getId(), entity.getStatus(), entity.getEpisodeTotal());
    }

    @Transactional
    public AdminVos.IdResultVo update(Long id, AdminVos.MovieMainBody body) {
        MovieEntity entity = require(id);
        if (body.title() != null) {
            String title = requireTitle(body.title());
            ensureTitleFree(entity.getCategoryId(), title, id);
            entity.setTitle(title);
        }
        if (body.categoryId() != null) {
            CategoryEntity category = categoryAdminService.require(body.categoryId());
            if (category.getStatus() != null && category.getStatus() == 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "该影视类型已停用");
            }
            ensureTitleFree(body.categoryId(), entity.getTitle(), id);
            entity.setCategoryId(body.categoryId());
        }
        applyMainFields(entity, body);
        movieMapper.updateById(entity);
        if (body.tagIds() != null) {
            replaceTags(id, body.tagIds());
        }
        return new AdminVos.IdResultVo(id);
    }

    /** 主信息部分更新（null = 不改）。 */
    private void applyMainFields(MovieEntity entity, AdminVos.MovieMainBody body) {
        if (body.originalTitle() != null) {
            entity.setOriginalTitle(body.originalTitle());
        }
        if (body.year() != null) {
            entity.setYear(body.year());
        }
        if (body.coverUrl() != null) {
            entity.setCoverUrl(body.coverUrl());
        }
        if (body.backdropUrl() != null) {
            entity.setBackdropUrl(body.backdropUrl());
        }
        if (body.rating() != null) {
            entity.setRating(BigDecimal.valueOf(body.rating()));
        }
        if (body.area() != null) {
            entity.setArea(body.area());
        }
        if (body.language() != null) {
            entity.setLanguage(body.language());
        }
        if (body.intro() != null) {
            entity.setIntro(body.intro());
        }
        if (body.sortNo() != null) {
            entity.setSortNo(body.sortNo());
        }
        if (body.sourceId() != null) {
            entity.setSourceId(body.sourceId());
        }
        if (body.sourcePath() != null) {
            entity.setSourcePath(body.sourcePath());
        }
    }

    private List<VideoEntity> buildVideos(Long movieId, Long movieSourceId, List<AdminVos.EpisodeBody> episodes,
                                          boolean strict) {
        Set<String> seen = new HashSet<>();
        List<VideoEntity> out = new ArrayList<>();
        int order = 0;
        for (AdminVos.EpisodeBody ep : episodes) {
            int seasonNo = ep.seasonNo() == null ? 1 : ep.seasonNo();
            int episodeNo = ep.episodeNo() == null ? 0 : ep.episodeNo();
            if (episodeNo <= 0) {
                if (strict) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "集号不能为空");
                }
                continue;
            }
            if (!seen.add(seasonNo + "#" + episodeNo)) {
                throw new BizException(ErrorCode.CONFLICT, "S" + seasonNo + "E" + episodeNo + " 重复");
            }
            String url = ep.url() == null ? "" : ep.url().trim();
            if (url.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "第 " + episodeNo + " 集缺少 url");
            }
            VideoEntity v = new VideoEntity();
            v.setMovieId(movieId);
            v.setSeasonNo(seasonNo);
            v.setEpisodeNo(episodeNo);
            v.setTitle(ep.title());
            v.setDuration(ep.duration());
            v.setMissing(0);
            v.setSortNo(++order);
            if (url.startsWith("http://") || url.startsWith("https://")) {
                v.setUrlKind(VideoEntity.KIND_REMOTE);
                v.setRemoteUrl(url);
            } else {
                if (movieSourceId == null) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "OpenList 相对路径必须在请求中携带 sourceId（数据源）");
                }
                v.setUrlKind(VideoEntity.KIND_OPENLIST);
                v.setOpenlistPath(stripLeadingSlash(url));
            }
            out.add(v);
        }
        if (out.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少需要 1 集有效剧集");
        }
        return out;
    }

    private void replaceTags(Long movieId, List<Long> tagIds) {
        movieTagMapper.delete(new LambdaQueryWrapper<MovieTagEntity>().eq(MovieTagEntity::getMovieId, movieId));
        Set<Long> unique = new LinkedHashSet<>(tagIds);
        for (Long tagId : unique) {
            if (tagId == null) {
                continue;
            }
            MovieTagEntity link = new MovieTagEntity();
            link.setMovieId(movieId);
            link.setTagId(tagId);
            movieTagMapper.insert(link);
        }
    }

    // ==================== 发布 / 下架 / 删除 ====================

    public AdminVos.MovieSaveResultVo publish(Long id) {
        MovieEntity entity = require(id);
        if (MovieEntity.STATUS_PUBLISHED.equals(entity.getStatus())) {
            return new AdminVos.MovieSaveResultVo(id, entity.getStatus(), nz(entity.getEpisodeTotal()));
        }
        if (entity.getCategoryId() == null) {
            throw new BizException(ErrorCode.CONFLICT, "未归入影视类型");
        }
        if (entity.getCoverUrl() == null || entity.getCoverUrl().isBlank()) {
            throw new BizException(ErrorCode.CONFLICT, "缺少封面");
        }
        int usable = usableCount(id);
        if (usable == 0) {
            throw new BizException(ErrorCode.CONFLICT, "无可用剧集");
        }
        entity.setStatus(MovieEntity.STATUS_PUBLISHED);
        entity.setEpisodeTotal(usable);
        movieMapper.updateById(entity);
        return new AdminVos.MovieSaveResultVo(id, entity.getStatus(), usable);
    }

    public AdminVos.MovieSaveResultVo offline(Long id) {
        MovieEntity entity = require(id);
        if (!MovieEntity.STATUS_OFF.equals(entity.getStatus())) {
            entity.setStatus(MovieEntity.STATUS_OFF);
            movieMapper.updateById(entity);
        }
        return new AdminVos.MovieSaveResultVo(id, entity.getStatus(), nz(entity.getEpisodeTotal()));
    }

    @Transactional
    public AdminVos.DeleteMovieResultVo remove(Long id) {
        MovieEntity entity = require(id);
        long episodes = videoMapper.selectCount(new LambdaQueryWrapper<VideoEntity>()
                .eq(VideoEntity::getMovieId, id));
        long progress = progressMapper.selectCount(new LambdaQueryWrapper<PlayProgressEntity>()
                .eq(PlayProgressEntity::getMovieId, id));
        videoMapper.delete(new LambdaQueryWrapper<VideoEntity>().eq(VideoEntity::getMovieId, id));
        movieTagMapper.delete(new LambdaQueryWrapper<MovieTagEntity>().eq(MovieTagEntity::getMovieId, id));
        progressMapper.delete(new LambdaQueryWrapper<PlayProgressEntity>().eq(PlayProgressEntity::getMovieId, id));
        movieMapper.deleteById(id);
        return new AdminVos.DeleteMovieResultVo(id, (int) episodes, (int) progress);
    }

    // ==================== 剧集 ====================

    public AdminVos.VideoListData videos(Long movieId) {
        require(movieId);
        List<VideoEntity> rows = videoMapper.selectList(new LambdaQueryWrapper<VideoEntity>()
                .eq(VideoEntity::getMovieId, movieId)
                .orderByAsc(VideoEntity::getSeasonNo)
                .orderByAsc(VideoEntity::getEpisodeNo));
        List<AdminVos.VideoVo> list = new ArrayList<>();
        int missing = 0;
        for (VideoEntity v : rows) {
            int miss = v.getMissing() == null ? 0 : v.getMissing();
            if (miss == 1) {
                missing++;
            }
            list.add(new AdminVos.VideoVo(v.getId(), nz(v.getSeasonNo()), nz(v.getEpisodeNo()), v.getTitle(),
                    v.getUrlKind(), v.getOpenlistPath(), v.getRemoteUrl(), v.getSize(), v.getDuration(),
                    v.getSourceFile(), v.getFingerprint(), miss, Ts.milli(v.getUpdateTime())));
        }
        return new AdminVos.VideoListData(movieId, list, missing);
    }

    /** 全量替换（未提交的既有 id 视为删除）。 */
    @Transactional
    public AdminVos.ReplaceVideosResultVo replaceVideos(Long movieId, List<AdminVos.EpisodeBody> episodes) {
        MovieEntity movie = require(movieId);
        if (episodes == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "episodes 不能为空");
        }
        Map<Long, VideoEntity> existing = new HashMap<>();
        for (VideoEntity v : videoMapper.selectList(new LambdaQueryWrapper<VideoEntity>()
                .eq(VideoEntity::getMovieId, movieId))) {
            existing.put(v.getId(), v);
        }
        Set<String> seen = new HashSet<>();
        Set<Long> kept = new HashSet<>();
        int added = 0;
        int updated = 0;
        int order = 0;

        for (AdminVos.EpisodeBody ep : episodes) {
            int seasonNo = ep.seasonNo() == null ? 1 : ep.seasonNo();
            int episodeNo = ep.episodeNo() == null ? 0 : ep.episodeNo();
            if (episodeNo <= 0 || seasonNo <= 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "seasonNo / episodeNo 必须为正整数");
            }
            if (!seen.add(seasonNo + "#" + episodeNo)) {
                throw new BizException(ErrorCode.CONFLICT, "S" + seasonNo + "E" + episodeNo + " 重复");
            }
            String url = ep.url() == null ? "" : ep.url().trim();
            if (url.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "S" + seasonNo + "E" + episodeNo + " 缺少 url");
            }
            boolean remote = url.startsWith("http://") || url.startsWith("https://");
            if (!remote && movie.getSourceId() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "OpenList 相对路径必须先在影片上设置数据源");
            }

            VideoEntity v;
            if (ep.id() != null) {
                v = existing.get(ep.id());
                if (v == null) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "剧集 id=" + ep.id() + " 不属于该影片");
                }
                kept.add(ep.id());
                updated++;
            } else {
                v = new VideoEntity();
                v.setMovieId(movieId);
                added++;
            }
            v.setSeasonNo(seasonNo);
            v.setEpisodeNo(episodeNo);
            v.setTitle(ep.title());
            v.setDuration(ep.duration());
            v.setMissing(0);
            v.setSortNo(++order);
            if (remote) {
                v.setUrlKind(VideoEntity.KIND_REMOTE);
                v.setRemoteUrl(url);
                v.setOpenlistPath(null);
            } else {
                v.setUrlKind(VideoEntity.KIND_OPENLIST);
                v.setOpenlistPath(stripLeadingSlash(url));
                v.setRemoteUrl(null);
            }
            if (v.getId() == null) {
                videoMapper.insert(v);
            } else {
                videoMapper.updateById(v);
            }
        }

        int removed = 0;
        for (Long id : existing.keySet()) {
            if (!kept.contains(id)) {
                videoMapper.deleteById(id);
                removed++;
            }
        }
        int total = usableCount(movieId);
        movie.setEpisodeTotal(total);
        movieMapper.updateById(movie);
        return new AdminVos.ReplaceVideosResultVo(total, added, updated, removed);
    }

    /** 粘贴导入（追加模式）：行格式 `url` 或 `N,url`。 */
    @Transactional
    public AdminVos.ImportResultVo importVideos(Long movieId, Integer seasonNo, String text) {
        MovieEntity movie = require(movieId);
        int season = seasonNo == null || seasonNo <= 0 ? 1 : seasonNo;
        if (text == null || text.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "导入内容为空");
        }
        int next = videoMapper.maxEpisodeNo(movieId, season);
        int added = 0;
        int updated = 0;
        List<AdminVos.ImportErrorVo> errors = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int order = Math.toIntExact(videoMapper.selectCount(new LambdaQueryWrapper<VideoEntity>()
                .eq(VideoEntity::getMovieId, movieId)));

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            int lineNo = i + 1;
            String url;
            Integer epNo = null;
            int comma = line.indexOf(',');
            if (comma > 0) {
                String head = line.substring(0, comma).trim();
                String tail = line.substring(comma + 1).trim();
                if (head.matches("\\d+")) {
                    epNo = Integer.parseInt(head);
                    url = tail;
                } else {
                    url = line;
                }
            } else {
                url = line;
            }
            if (url.isEmpty()) {
                errors.add(new AdminVos.ImportErrorVo(lineNo, "缺少 url"));
                continue;
            }
            boolean remote = url.startsWith("http://") || url.startsWith("https://");
            if (!remote && movie.getSourceId() == null) {
                errors.add(new AdminVos.ImportErrorVo(lineNo, "OpenList 相对路径需要影片先设置数据源"));
                continue;
            }
            int targetEp = epNo != null ? epNo : ++next;

            VideoEntity existing = videoMapper.selectOne(new LambdaQueryWrapper<VideoEntity>()
                    .eq(VideoEntity::getMovieId, movieId)
                    .eq(VideoEntity::getSeasonNo, season)
                    .eq(VideoEntity::getEpisodeNo, targetEp));
            VideoEntity v = existing == null ? new VideoEntity() : existing;
            v.setMovieId(movieId);
            v.setSeasonNo(season);
            v.setEpisodeNo(targetEp);
            v.setMissing(0);
            v.setSortNo(++order);
            if (remote) {
                v.setUrlKind(VideoEntity.KIND_REMOTE);
                v.setRemoteUrl(url);
                v.setOpenlistPath(null);
            } else {
                v.setUrlKind(VideoEntity.KIND_OPENLIST);
                v.setOpenlistPath(stripLeadingSlash(url));
                v.setRemoteUrl(null);
            }
            if (existing == null) {
                videoMapper.insert(v);
                added++;
            } else {
                videoMapper.updateById(v);
                updated++;
            }
        }
        int total = usableCount(movieId);
        movie.setEpisodeTotal(total);
        movieMapper.updateById(movie);
        return new AdminVos.ImportResultVo(added, updated, errors);
    }

    // ==================== 工具 ====================

    public MovieEntity require(Long id) {
        MovieEntity entity = id == null ? null : movieMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "影片不存在");
        }
        return entity;
    }

    private void ensureTitleFree(Long categoryId, String title, Long excludeId) {
        if (categoryId == null || title == null) {
            return;
        }
        LambdaQueryWrapper<MovieEntity> qw = new LambdaQueryWrapper<MovieEntity>()
                .eq(MovieEntity::getCategoryId, categoryId)
                .eq(MovieEntity::getTitle, title)
                .in(MovieEntity::getStatus, List.of("PUBLISHED", "OFF"));
        if (excludeId != null) {
            qw.ne(MovieEntity::getId, excludeId);
        }
        if (movieMapper.selectCount(qw) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "该类型下已存在同名影片");
        }
    }

    private int usableCount(Long movieId) {
        return Math.toIntExact(videoMapper.selectCount(new LambdaQueryWrapper<VideoEntity>()
                .eq(VideoEntity::getMovieId, movieId)
                .and(w -> w.isNull(VideoEntity::getMissing).or().eq(VideoEntity::getMissing, 0))));
    }

    private static int countUsable(List<VideoEntity> videos) {
        int n = 0;
        for (VideoEntity v : videos) {
            if (v.getMissing() == null || v.getMissing() == 0) {
                n++;
            }
        }
        return n;
    }

    private static String requireTitle(String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "片名不能为空");
        }
        if (t.length() > 191) {
            throw new BizException(ErrorCode.BAD_REQUEST, "片名不能超过 191 字");
        }
        return t;
    }

    private static String truncate(String intro) {
        if (intro == null) {
            return null;
        }
        return intro.length() <= INTRO_MAX ? intro : intro.substring(0, INTRO_MAX);
    }

    private static String stripLeadingSlash(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static Double toDouble(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }
}
