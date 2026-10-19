package com.hypermux.server.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.movie.MovieTagEntity;
import com.hypermux.server.movie.MovieTagMapper;
import com.hypermux.server.tag.TagEntity;
import com.hypermux.server.tag.TagMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 标签后台管理（契约 §4.6）。 */
@Service
public class TagAdminService {

    private final TagMapper tagMapper;
    private final MovieTagMapper movieTagMapper;

    public TagAdminService(TagMapper tagMapper, MovieTagMapper movieTagMapper) {
        this.tagMapper = tagMapper;
        this.movieTagMapper = movieTagMapper;
    }

    public List<AdminVos.TagVo> list(Integer type) {
        LambdaQueryWrapper<TagEntity> qw = new LambdaQueryWrapper<>();
        if (type != null) {
            qw.eq(TagEntity::getType, type);
        }
        qw.orderByAsc(TagEntity::getType).orderByAsc(TagEntity::getSortNo).orderByAsc(TagEntity::getId);

        Map<Long, Long> counts = new HashMap<>();
        for (TagMapper.CountRow row : tagMapper.counts()) {
            counts.put(row.getId(), row.getCnt() == null ? 0L : row.getCnt());
        }
        List<AdminVos.TagVo> list = new ArrayList<>();
        for (TagEntity t : tagMapper.selectList(qw)) {
            list.add(new AdminVos.TagVo(t.getId(), t.getName(), t.getType() == null ? 0 : t.getType(),
                    t.getSortNo(), t.getStatus() == null ? 0 : t.getStatus(),
                    counts.getOrDefault(t.getId(), 0L)));
        }
        return list;
    }

    public AdminVos.IdResultVo create(AdminVos.TagForm form) {
        String name = requireName(form.name());
        ensureNameFree(name, form.type() == null ? 0 : form.type(), null);
        TagEntity entity = new TagEntity();
        entity.setName(name);
        entity.setType(form.type() == null ? 0 : form.type());
        entity.setSortNo(form.sortNo() == null ? 0 : form.sortNo());
        entity.setStatus(form.status() == null ? 1 : form.status());
        tagMapper.insert(entity);
        return new AdminVos.IdResultVo(entity.getId());
    }

    public AdminVos.IdResultVo update(Long id, AdminVos.TagForm form) {
        TagEntity entity = require(id);
        if (form.name() != null) {
            String name = requireName(form.name());
            ensureNameFree(name, entity.getType() == null ? 0 : entity.getType(), id);
            entity.setName(name);
        }
        if (form.type() != null) {
            entity.setType(form.type());
        }
        if (form.sortNo() != null) {
            entity.setSortNo(form.sortNo());
        }
        if (form.status() != null) {
            entity.setStatus(form.status());
        }
        tagMapper.updateById(entity);
        return new AdminVos.IdResultVo(id);
    }

    @Transactional
    public AdminVos.UnboundResultVo remove(Long id) {
        require(id);
        long bound = movieTagMapper.selectCount(new LambdaQueryWrapper<MovieTagEntity>()
                .eq(MovieTagEntity::getTagId, id));
        movieTagMapper.delete(new LambdaQueryWrapper<MovieTagEntity>().eq(MovieTagEntity::getTagId, id));
        tagMapper.deleteById(id);
        return new AdminVos.UnboundResultVo(id, (int) bound);
    }

    private TagEntity require(Long id) {
        TagEntity entity = id == null ? null : tagMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "标签不存在");
        }
        return entity;
    }

    private String requireName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "标签名不能为空");
        }
        if (n.length() > 32) {
            throw new BizException(ErrorCode.BAD_REQUEST, "标签名不能超过 32 字");
        }
        return n;
    }

    private void ensureNameFree(String name, int type, Long excludeId) {
        LambdaQueryWrapper<TagEntity> qw = new LambdaQueryWrapper<TagEntity>()
                .eq(TagEntity::getName, name)
                .eq(TagEntity::getType, type);
        if (excludeId != null) {
            qw.ne(TagEntity::getId, excludeId);
        }
        if (tagMapper.selectCount(qw) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "同类型下已存在同名标签");
        }
    }
}
