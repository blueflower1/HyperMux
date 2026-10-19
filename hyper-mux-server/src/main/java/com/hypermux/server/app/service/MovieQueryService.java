package com.hypermux.server.app.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hypermux.server.app.vo.TvVos;
import com.hypermux.server.category.CategoryEntity;
import com.hypermux.server.category.CategoryMapper;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.common.PageResult;
import com.hypermux.server.common.Ts;
import com.hypermux.server.movie.MovieEntity;
import com.hypermux.server.movie.MovieMapper;
import com.hypermux.server.movie.MovieTagMapper;
import com.hypermux.server.progress.PlayProgressMapper;
import com.hypermux.server.video.VideoEntity;
import com.hypermux.server.video.VideoMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** App 影片查询：类型内列表、搜索、详情、剧集列表（契约 §3.3/§3.4/§3.5/§3.10）。 */
@Service
public class MovieQueryService {

    private static final int MAX_SIZE = 100;
    private static final int MAX_TAGS = 5;

    private final MovieMapper movieMapper;
    private final MovieTagMapper movieTagMapper;
    private final PlayProgressMapper progressMapper;
    private final CategoryMapper categoryMapper;
    private final VideoMapper videoMapper;

    public MovieQueryService(MovieMapper movieMapper, MovieTagMapper movieTagMapper,
                             PlayProgressMapper progressMapper, CategoryMapper categoryMapper,
                             VideoMapper videoMapper) {
        this.movieMapper = movieMapper;
        this.movieTagMapper = movieTagMapper;
        this.progressMapper = progressMapper;
        this.categoryMapper = categoryMapper;
        this.videoMapper = videoMapper;
    }

    // ---------------- 列表 ----------------

    public PageResult<TvVos.MovieCardVo> listByCategory(Long categoryId, int page, int size, String keyword, Long userId) {
        CategoryEntity c = categoryMapper.selectById(categoryId);
        if (c == null || c.getStatus() == null || c.getStatus() != 1) {
            throw new BizException(ErrorCode.NOT_FOUND, "该影视类型不存在");
        }
        LambdaQueryWrapper<MovieEntity> qw = visibleWrapper().eq(MovieEntity::getCategoryId, categoryId);
        return pageQuery(qw, page, size, keyword, userId);
    }

    public PageResult<TvVos.MovieCardVo> search(String keyword, int page, int size, Long userId) {
        if (keyword == null || keyword.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "keyword 不能为空");
        }
        return pageQuery(visibleWrapper(), page, size, keyword, userId);
    }

    /** 可见性铁律：PUBLISHED + episode_total>0 + 分类启用（30-api-contract §3.3）。 */
    private LambdaQueryWrapper<MovieEntity> visibleWrapper() {
        return new LambdaQueryWrapper<MovieEntity>()
                .eq(MovieEntity::getStatus, MovieEntity.STATUS_PUBLISHED)
                .gt(MovieEntity::getEpisodeTotal, 0)
                .inSql(MovieEntity::getCategoryId, "SELECT id FROM category WHERE status = 1");
    }

    private PageResult<TvVos.MovieCardVo> pageQuery(LambdaQueryWrapper<MovieEntity> qw, int page, int size,
                                                    String keyword, Long userId) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), MAX_SIZE);
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword.trim() + "%";
            qw.and(w -> w.apply("title ILIKE {0}", pattern).or().apply("original_title ILIKE {0}", pattern));
        }
        qw.orderByAsc(MovieEntity::getSortNo).orderByAsc(MovieEntity::getId);
        Page<MovieEntity> result = movieMapper.selectPage(new Page<>(p, s), qw);
        List<TvVos.MovieCardVo> cards = assembleCards(result.getRecords(), userId);
        return PageResult.of(cards, result.getTotal(), p, s);
    }

    /** 批量装配卡片（标签 + 断点），避免 N+1。 */
    public List<TvVos.MovieCardVo> assembleCards(List<MovieEntity> movies, Long userId) {
        List<TvVos.MovieCardVo> out = new ArrayList<>();
        if (movies == null || movies.isEmpty()) {
            return out;
        }
        List<Long> ids = new ArrayList<>();
        for (MovieEntity m : movies) {
            ids.add(m.getId());
        }
        Map<Long, List<String>> tagMap = tagsOf(ids);
        Map<Long, TvVos.ProgressLiteVo> progressMap = progressOf(userId, ids);

        for (MovieEntity m : movies) {
            List<String> tags = tagMap.getOrDefault(m.getId(), List.of());
            if (tags.size() > MAX_TAGS) {
                tags = tags.subList(0, MAX_TAGS);
            }
            out.add(new TvVos.MovieCardVo(
                    m.getId(), m.getTitle(), m.getCoverUrl(), m.getBackdropUrl(), toDouble(m.getRating()),
                    m.getYear(), m.getArea(), m.getEpisodeTotal() == null ? 0 : m.getEpisodeTotal(),
                    tags, progressMap.get(m.getId()), Ts.milli(m.getUpdateTime())));
        }
        return out;
    }

    private Map<Long, List<String>> tagsOf(List<Long> movieIds) {
        Map<Long, List<String>> map = new LinkedHashMap<>();
        for (MovieTagMapper.MovieTagRow row : movieTagMapper.rowsOfMovies(movieIds)) {
            map.computeIfAbsent(row.getMovieId(), k -> new ArrayList<>()).add(row.getName());
        }
        return map;
    }

    private Map<Long, TvVos.ProgressLiteVo> progressOf(Long userId, List<Long> movieIds) {
        Map<Long, TvVos.ProgressLiteVo> map = new LinkedHashMap<>();
        if (userId == null) {
            return map;
        }
        Map<Long, java.time.OffsetDateTime> times = new HashMap<>();
        for (PlayProgressMapper.CardProgressRow row : progressMapper.cardProgress(userId, movieIds)) {
            java.time.OffsetDateTime prev = times.get(row.getMovieId());
            boolean newer = prev == null || (row.getUpdateTime() != null && row.getUpdateTime().isAfter(prev));
            if (newer) {
                times.put(row.getMovieId(), row.getUpdateTime());
                map.put(row.getMovieId(), new TvVos.ProgressLiteVo(
                        row.getVideoId(), nz(row.getSeasonNo()), nz(row.getEpisodeNo()),
                        nzL(row.getPositionMs()), nzL(row.getDurationMs())));
            }
        }
        return map;
    }

    // ---------------- 详情 ----------------

    public TvVos.MovieDetailVo detail(Long movieId, Long userId) {
        MovieEntity m = requireVisible(movieId);
        List<MovieTagMapper.MovieTagRow> tagRows = movieTagMapper.rowsOfMovies(List.of(movieId));
        List<TvVos.TagRefVo> tags = new ArrayList<>();
        for (MovieTagMapper.MovieTagRow row : tagRows) {
            tags.add(new TvVos.TagRefVo(row.getTagId(), row.getName(), row.getType() == null ? 0 : row.getType()));
        }
        TvVos.CategoryRefVo category = null;
        if (m.getCategoryId() != null) {
            CategoryEntity c = categoryMapper.selectById(m.getCategoryId());
            if (c != null) {
                category = new TvVos.CategoryRefVo(c.getId(), c.getName());
            }
        }
        return new TvVos.MovieDetailVo(
                m.getId(), m.getTitle(), m.getOriginalTitle(), m.getYear(), m.getCoverUrl(), m.getBackdropUrl(),
                toDouble(m.getRating()), m.getArea(), m.getLanguage(), m.getIntro(), tags,
                m.getEpisodeTotal() == null ? 0 : m.getEpisodeTotal(), category, latestProgress(userId, movieId));
    }

    private TvVos.ProgressFullVo latestProgress(Long userId, Long movieId) {
        if (userId == null) {
            return null;
        }
        List<PlayProgressMapper.CardProgressRow> rows = progressMapper.cardProgress(userId, List.of(movieId));
        PlayProgressMapper.CardProgressRow best = null;
        for (PlayProgressMapper.CardProgressRow row : rows) {
            if (best == null || (row.getUpdateTime() != null && best.getUpdateTime() != null
                    && row.getUpdateTime().isAfter(best.getUpdateTime()))) {
                best = row;
            }
        }
        if (best == null) {
            return null;
        }
        return new TvVos.ProgressFullVo(best.getVideoId(), nz(best.getSeasonNo()), nz(best.getEpisodeNo()),
                nzL(best.getPositionMs()), nzL(best.getDurationMs()), 0);
    }

    // ---------------- 剧集列表 ----------------

    public TvVos.SeasonsVo seasons(Long movieId, Long userId) {
        requireVisible(movieId);
        List<VideoEntity> videos = videoMapper.selectList(new LambdaQueryWrapper<VideoEntity>()
                .eq(VideoEntity::getMovieId, movieId)
                .and(w -> w.isNull(VideoEntity::getMissing).or().eq(VideoEntity::getMissing, 0))
                .orderByAsc(VideoEntity::getSeasonNo)
                .orderByAsc(VideoEntity::getEpisodeNo));

        Map<Long, TvVos.VideoProgressVo> progressMap = new LinkedHashMap<>();
        if (userId != null && !videos.isEmpty()) {
            List<Long> vids = new ArrayList<>();
            for (VideoEntity v : videos) {
                vids.add(v.getId());
            }
            for (PlayProgressMapper.VideoProgressRow row : progressMapper.videoProgressBatch(userId, vids)) {
                progressMap.put(row.getVideoId(), new TvVos.VideoProgressVo(
                        nzL(row.getPositionMs()), nzL(row.getDurationMs()), nz(row.getFinished())));
            }
        }

        Map<Integer, List<TvVos.VideoItemVo>> bySeason = new LinkedHashMap<>();
        for (VideoEntity v : videos) {
            TvVos.VideoItemVo item = new TvVos.VideoItemVo(v.getId(), nz(v.getEpisodeNo()), v.getTitle(),
                    v.getDuration(), progressMap.get(v.getId()));
            bySeason.computeIfAbsent(nz(v.getSeasonNo()), k -> new ArrayList<>()).add(item);
        }
        List<TvVos.SeasonVo> seasons = new ArrayList<>();
        for (Map.Entry<Integer, List<TvVos.VideoItemVo>> e : bySeason.entrySet()) {
            seasons.add(new TvVos.SeasonVo(e.getKey(), e.getValue()));
        }
        seasons.sort(Comparator.comparingInt(TvVos.SeasonVo::seasonNo));
        return new TvVos.SeasonsVo(movieId, seasons);
    }

    // ---------------- 可见性 ----------------

    public MovieEntity requireVisible(Long movieId) {
        MovieEntity m = movieId == null ? null : movieMapper.selectById(movieId);
        if (m == null || !MovieEntity.STATUS_PUBLISHED.equals(m.getStatus())) {
            throw new BizException(ErrorCode.NOT_FOUND, "影片不存在或不可见");
        }
        if (m.getCategoryId() != null) {
            CategoryEntity c = categoryMapper.selectById(m.getCategoryId());
            if (c != null && c.getStatus() != null && c.getStatus() == 0) {
                throw new BizException(ErrorCode.NOT_FOUND, "影片不存在或不可见");
            }
        }
        return m;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nzL(Long v) {
        return v == null ? 0L : v;
    }

    private static Double toDouble(java.math.BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }
}
