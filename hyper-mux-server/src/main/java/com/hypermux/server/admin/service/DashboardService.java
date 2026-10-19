package com.hypermux.server.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.category.CategoryEntity;
import com.hypermux.server.category.CategoryMapper;
import com.hypermux.server.movie.MovieEntity;
import com.hypermux.server.movie.MovieMapper;
import com.hypermux.server.scrape.OpenListSourceEntity;
import com.hypermux.server.scrape.SourceMapper;
import com.hypermux.server.user.SysUserEntity;
import com.hypermux.server.user.UserMapper;
import org.springframework.stereotype.Service;

/** 后台总览（契约 §4.2）。 */
@Service
public class DashboardService {

    private final CategoryMapper categoryMapper;
    private final MovieMapper movieMapper;
    private final UserMapper userMapper;
    private final SourceMapper sourceMapper;

    public DashboardService(CategoryMapper categoryMapper, MovieMapper movieMapper,
                            UserMapper userMapper, SourceMapper sourceMapper) {
        this.categoryMapper = categoryMapper;
        this.movieMapper = movieMapper;
        this.userMapper = userMapper;
        this.sourceMapper = sourceMapper;
    }

    public AdminVos.DashboardVo get() {
        long categories = categoryMapper.selectCount(new LambdaQueryWrapper<CategoryEntity>());
        long published = movieMapper.selectCount(new LambdaQueryWrapper<MovieEntity>()
                .eq(MovieEntity::getStatus, MovieEntity.STATUS_PUBLISHED));
        long draft = movieMapper.selectCount(new LambdaQueryWrapper<MovieEntity>()
                .eq(MovieEntity::getStatus, MovieEntity.STATUS_DRAFT));
        long users = userMapper.selectCount(new LambdaQueryWrapper<SysUserEntity>());
        long sources = sourceMapper.selectCount(new LambdaQueryWrapper<OpenListSourceEntity>());
        return new AdminVos.DashboardVo(categories, published, draft, users, sources);
    }
}
