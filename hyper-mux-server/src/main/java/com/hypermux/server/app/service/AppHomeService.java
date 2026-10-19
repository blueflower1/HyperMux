package com.hypermux.server.app.service;

import com.hypermux.server.app.vo.TvVos;
import com.hypermux.server.category.CategoryMapper;
import com.hypermux.server.common.Ts;
import com.hypermux.server.movie.MovieEntity;
import com.hypermux.server.movie.MovieMapper;
import com.hypermux.server.progress.PlayProgressMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 首页聚合（契约 §3.2）：可见分类（有片才下发）+ 继续观看（≤10，同片只留一条）。 */
@Service
public class AppHomeService {

    private static final int CONTINUE_LIMIT = 10;

    private final CategoryMapper categoryMapper;
    private final PlayProgressMapper progressMapper;
    private final HomeBannerScheduler bannerScheduler;
    private final MovieMapper movieMapper;

    public AppHomeService(CategoryMapper categoryMapper, PlayProgressMapper progressMapper,
                          HomeBannerScheduler bannerScheduler, MovieMapper movieMapper) {
        this.categoryMapper = categoryMapper;
        this.progressMapper = progressMapper;
        this.bannerScheduler = bannerScheduler;
        this.movieMapper = movieMapper;
    }

    public TvVos.HomeVo home(Long userId) {
        List<TvVos.CategoryVo> categories = new ArrayList<>();
        for (CategoryMapper.CategoryCardRow row : categoryMapper.listVisibleWithCount()) {
            long count = row.getMovieCount() == null ? 0L : row.getMovieCount();
            if (count <= 0) {
                continue;
            }
            categories.add(new TvVos.CategoryVo(row.getId(), row.getName(), row.getCoverUrl(), (int) count));
        }

        List<TvVos.ContinueVo> continueWatching = new ArrayList<>();
        if (userId != null) {
            for (PlayProgressMapper.ContinueRow row : progressMapper.continueWatching(userId, CONTINUE_LIMIT)) {
                continueWatching.add(new TvVos.ContinueVo(row.getMovieId(), row.getTitle(), row.getCoverUrl(),
                        row.getBackdropUrl(), row.getCategoryName(),
                        new TvVos.LastVideoVo(row.getLastVideoId(),
                                row.getSeasonNo() == null ? 0 : row.getSeasonNo(),
                                row.getEpisodeNo() == null ? 0 : row.getEpisodeNo()),
                        row.getPositionMs(), row.getDurationMs()));
            }
        }
        return new TvVos.HomeVo(categories, continueWatching, bannerCards());
    }

    /** 首页顶部轮播：读 6 小时定时任务刷新的随机池（保序，只下发仍可见的） */
    private List<TvVos.MovieCardVo> bannerCards() {
        List<Long> ids = bannerScheduler.currentIds();
        List<TvVos.MovieCardVo> out = new ArrayList<>();
        if (ids.isEmpty()) {
            return out;
        }
        Map<Long, MovieEntity> byId = new HashMap<>();
        for (MovieEntity m : movieMapper.selectBatchIds(ids)) {
            byId.put(m.getId(), m);
        }
        for (Long id : ids) {
            MovieEntity m = byId.get(id);
            if (m == null || !MovieEntity.STATUS_PUBLISHED.equals(m.getStatus())
                    || m.getEpisodeTotal() == null || m.getEpisodeTotal() <= 0) {
                continue;
            }
            out.add(new TvVos.MovieCardVo(m.getId(), m.getTitle(), m.getCoverUrl(), m.getBackdropUrl(),
                    m.getRating() == null ? null : m.getRating().doubleValue(), m.getYear(), m.getArea(),
                    m.getEpisodeTotal(), List.of(), null, Ts.milli(m.getUpdateTime())));
        }
        return out;
    }
}
