package com.hypermux.server.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.category.CategoryEntity;
import com.hypermux.server.category.CategoryMapper;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.common.PageResult;
import com.hypermux.server.common.Ts;
import com.hypermux.server.movie.MovieEntity;
import com.hypermux.server.movie.MovieMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 影视类型（原「影片池」）后台管理（契约 §4.3）。 */
@Service
public class CategoryAdminService {

    private final CategoryMapper categoryMapper;
    private final MovieMapper movieMapper;

    public CategoryAdminService(CategoryMapper categoryMapper, MovieMapper movieMapper) {
        this.categoryMapper = categoryMapper;
        this.movieMapper = movieMapper;
    }

    public PageResult<AdminVos.CategoryVo> page(int page, int size, String keyword) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        LambdaQueryWrapper<CategoryEntity> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.like(CategoryEntity::getName, keyword.trim());
        }
        qw.orderByAsc(CategoryEntity::getSortNo).orderByAsc(CategoryEntity::getId);
        Page<CategoryEntity> result = categoryMapper.selectPage(new Page<>(p, s), qw);

        Map<Long, Long> counts = counts();
        List<AdminVos.CategoryVo> list = new ArrayList<>();
        for (CategoryEntity c : result.getRecords()) {
            list.add(new AdminVos.CategoryVo(c.getId(), c.getName(), c.getCoverUrl(), c.getDescription(),
                    c.getSortNo(), nz(c.getStatus()),
                    counts.getOrDefault(c.getId(), 0L), Ts.milli(c.getCreateTime()), Ts.milli(c.getUpdateTime())));
        }
        return PageResult.of(list, result.getTotal(), p, s);
    }

    private Map<Long, Long> counts() {
        Map<Long, Long> map = new HashMap<>();
        for (CategoryMapper.CountRow row : categoryMapper.counts()) {
            map.put(row.getId(), row.getCnt() == null ? 0L : row.getCnt());
        }
        return map;
    }

    public AdminVos.IdResultVo create(AdminVos.CategoryForm form) {
        String name = requireName(form.name());
        ensureNameFree(name, null);
        CategoryEntity entity = new CategoryEntity();
        entity.setName(name);
        entity.setCoverUrl(form.coverUrl());
        entity.setDescription(form.description());
        entity.setSortNo(form.sortNo() == null ? 0 : form.sortNo());
        entity.setStatus(form.status() == null ? 1 : form.status());
        categoryMapper.insert(entity);
        return new AdminVos.IdResultVo(entity.getId());
    }

    public AdminVos.IdResultVo update(Long id, AdminVos.CategoryForm form) {
        CategoryEntity entity = require(id);
        if (form.name() != null) {
            String name = requireName(form.name());
            ensureNameFree(name, id);
            entity.setName(name);
        }
        if (form.coverUrl() != null) {
            entity.setCoverUrl(form.coverUrl());
        }
        if (form.description() != null) {
            entity.setDescription(form.description());
        }
        if (form.sortNo() != null) {
            entity.setSortNo(form.sortNo());
        }
        if (form.status() != null) {
            entity.setStatus(form.status());
        }
        categoryMapper.updateById(entity);
        return new AdminVos.IdResultVo(id);
    }

    @Transactional
    public void remove(Long id) {
        require(id);
        long count = movieMapper.selectCount(new LambdaQueryWrapper<MovieEntity>()
                .eq(MovieEntity::getCategoryId, id));
        if (count > 0) {
            throw new BizException(ErrorCode.CONFLICT, "该类型下已有 " + count + " 部影片，禁止删除",
                    Map.of("id", id, "movieCount", count));
        }
        categoryMapper.deleteById(id);
    }

    public CategoryEntity require(Long id) {
        CategoryEntity entity = id == null ? null : categoryMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "影视类型不存在");
        }
        return entity;
    }

    private String requireName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "名称不能为空");
        }
        if (n.length() > 64) {
            throw new BizException(ErrorCode.BAD_REQUEST, "名称不能超过 64 字");
        }
        return n;
    }

    private void ensureNameFree(String name, Long excludeId) {
        LambdaQueryWrapper<CategoryEntity> qw = new LambdaQueryWrapper<CategoryEntity>()
                .eq(CategoryEntity::getName, name);
        if (excludeId != null) {
            qw.ne(CategoryEntity::getId, excludeId);
        }
        if (categoryMapper.selectCount(qw) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "已存在同名影视类型");
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
