package com.hypermux.server.app.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hypermux.server.config.ConfigService;
import com.hypermux.server.movie.MovieEntity;
import com.hypermux.server.movie.MovieMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 首页 Banner 轮播池：**每 6 小时**随机抽选若干部已发布影片写入 sys_config，
 * App 每次打开都读同一份池子（不随机、不抖动），到点由本任务刷新。
 */
@Slf4j
@Component
public class HomeBannerScheduler {

    /** sys_config 键：轮播影片 id（逗号分隔，保序） */
    public static final String KEY_BANNER_IDS = "home_banner_ids";

    /** 轮播张数 */
    private static final int COUNT = 6;
    /** 6 小时 */
    private static final long SIX_HOURS_MS = 6L * 60 * 60 * 1000;

    private final MovieMapper movieMapper;
    private final ConfigService configService;

    public HomeBannerScheduler(MovieMapper movieMapper, ConfigService configService) {
        this.movieMapper = movieMapper;
        this.configService = configService;
    }

    /** 启动 20s 后先跑一次（保证首次访问就有数据），之后每 6 小时一次 */
    @Scheduled(initialDelay = 20_000, fixedDelay = SIX_HOURS_MS)
    public void refresh() {
        List<Long> ids = pickIds();
        configService.put(KEY_BANNER_IDS, ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
        log.info("[banner] 首页轮播池已刷新（{} 张）: {}", ids.size(), ids);
    }

    private List<Long> pickIds() {
        // 优先：已发布 + 有横版剧照 + 有剧集
        List<MovieEntity> pool = movieMapper.selectList(new LambdaQueryWrapper<MovieEntity>()
                .eq(MovieEntity::getStatus, MovieEntity.STATUS_PUBLISHED)
                .gt(MovieEntity::getEpisodeTotal, 0)
                .isNotNull(MovieEntity::getBackdropUrl)
                .ne(MovieEntity::getBackdropUrl, ""));
        if (pool.size() < COUNT) {
            // 兜底：把没有横图的也纳入（前端会用竖版封面兜底）
            List<MovieEntity> all = movieMapper.selectList(new LambdaQueryWrapper<MovieEntity>()
                    .eq(MovieEntity::getStatus, MovieEntity.STATUS_PUBLISHED)
                    .gt(MovieEntity::getEpisodeTotal, 0));
            for (MovieEntity m : all) {
                if (pool.stream().noneMatch(x -> x.getId().equals(m.getId()))) {
                    pool.add(m);
                }
            }
        }
        Collections.shuffle(pool);
        List<Long> ids = new ArrayList<>();
        for (MovieEntity m : pool) {
            if (ids.size() >= COUNT) {
                break;
            }
            ids.add(m.getId());
        }
        return ids;
    }

    /** 读取当前池子（App 首页用），保序。 */
    public List<Long> currentIds() {
        String raw = configService.get(KEY_BANNER_IDS, "");
        List<Long> ids = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String s : raw.split(",")) {
            try {
                ids.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignore) {
                // 脏数据跳过
            }
        }
        return ids;
    }
}
