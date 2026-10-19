package com.hypermux.server.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.auth.UserContext;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.common.PageResult;
import com.hypermux.server.common.Ts;
import com.hypermux.server.progress.PlayProgressEntity;
import com.hypermux.server.progress.PlayProgressMapper;
import com.hypermux.server.user.SysUserEntity;
import com.hypermux.server.user.UserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 用户后台管理（契约 §4.7）。 */
@Service
public class UserAdminService {

    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_]{3,32}$");
    private static final String PWD_CHARS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final UserMapper userMapper;
    private final PlayProgressMapper progressMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public UserAdminService(UserMapper userMapper, PlayProgressMapper progressMapper) {
        this.userMapper = userMapper;
        this.progressMapper = progressMapper;
    }

    public PageResult<AdminVos.UserVo> page(int page, int size, String keyword, Integer role, Integer status) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        LambdaQueryWrapper<SysUserEntity> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            qw.and(w -> w.like(SysUserEntity::getUsername, k).or().like(SysUserEntity::getNickName, k));
        }
        if (role != null) {
            qw.eq(SysUserEntity::getRole, role);
        }
        if (status != null) {
            qw.eq(SysUserEntity::getStatus, status);
        }
        qw.orderByDesc(SysUserEntity::getId);
        Page<SysUserEntity> result = userMapper.selectPage(new Page<>(p, s), qw);

        List<AdminVos.UserVo> list = new ArrayList<>();
        for (SysUserEntity u : result.getRecords()) {
            list.add(toVo(u));
        }
        return PageResult.of(list, result.getTotal(), p, s);
    }

    public AdminVos.UserVo me() {
        SysUserEntity u = userMapper.selectById(UserContext.require().id());
        if (u == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号不存在");
        }
        return toVo(u);
    }

    private AdminVos.UserVo toVo(SysUserEntity u) {
        return new AdminVos.UserVo(u.getId(), u.getUsername(), u.getNickName(),
                u.getRole() == null ? 0 : u.getRole(), u.getStatus() == null ? 0 : u.getStatus(),
                u.getLastLoginTime() == null ? null : Ts.milli(u.getLastLoginTime()),
                Ts.milli(u.getCreateTime()));
    }

    public AdminVos.IdResultVo create(AdminVos.UserForm form) {
        String username = form.username() == null ? "" : form.username().trim();
        if (!USERNAME.matcher(username).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用户名须为 3~32 位字母/数字/下划线");
        }
        String password = form.password() == null ? "" : form.password();
        if (password.length() < 6 || password.length() > 20) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码长度 6~20");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUserEntity>()
                .eq(SysUserEntity::getUsername, username)) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }
        int role = form.role() == null ? SysUserEntity.ROLE_APP_USER : form.role();
        if (role != SysUserEntity.ROLE_APP_USER && role != SysUserEntity.ROLE_ADMIN) {
            throw new BizException(ErrorCode.BAD_REQUEST, "role 只能是 0 或 1");
        }
        SysUserEntity entity = new SysUserEntity();
        entity.setUsername(username);
        entity.setPasswordHash(encoder.encode(password));
        entity.setNickName(form.nickName());
        entity.setRole(role);
        entity.setStatus(1);
        userMapper.insert(entity);
        return new AdminVos.IdResultVo(entity.getId());
    }

    public AdminVos.IdResultVo update(Long id, AdminVos.UserForm form) {
        SysUserEntity entity = require(id);
        Long selfId = UserContext.userId();
        boolean isSelf = selfId != null && selfId.equals(id);

        if (form.nickName() != null) {
            entity.setNickName(form.nickName());
        }
        if (form.role() != null && !form.role().equals(entity.getRole())) {
            if (isSelf && form.role() == SysUserEntity.ROLE_APP_USER) {
                throw new BizException(ErrorCode.CONFLICT, "不能把自己降为普通用户");
            }
            if (entity.getRole() != null && entity.getRole() == SysUserEntity.ROLE_ADMIN
                    && form.role() == SysUserEntity.ROLE_APP_USER) {
                ensureNotLastEnabledAdmin(id, "不能降级最后一个启用的管理员");
            }
            entity.setRole(form.role());
        }
        if (form.status() != null && !form.status().equals(entity.getStatus())) {
            if (isSelf && form.status() == 0) {
                throw new BizException(ErrorCode.CONFLICT, "不能停用自己");
            }
            if (entity.getRole() != null && entity.getRole() == SysUserEntity.ROLE_ADMIN && form.status() == 0) {
                ensureNotLastEnabledAdmin(id, "不能停用最后一个启用的管理员");
            }
            entity.setStatus(form.status());
        }
        userMapper.updateById(entity);
        return new AdminVos.IdResultVo(id);
    }

    public AdminVos.ResetPasswordResultVo resetPassword(Long id, String password) {
        SysUserEntity entity = require(id);
        String newPassword = password == null || password.isBlank() ? randomPassword(8) : password;
        if (newPassword.length() < 6 || newPassword.length() > 20) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码长度 6~20");
        }
        entity.setPasswordHash(encoder.encode(newPassword));
        userMapper.updateById(entity);
        return new AdminVos.ResetPasswordResultVo(id, entity.getUsername(), newPassword);
    }

    @Transactional
    public AdminVos.DeleteUserResultVo remove(Long id) {
        SysUserEntity entity = require(id);
        Long selfId = UserContext.userId();
        if (selfId != null && selfId.equals(id)) {
            throw new BizException(ErrorCode.CONFLICT, "不能删除自己");
        }
        if (entity.getRole() != null && entity.getRole() == SysUserEntity.ROLE_ADMIN) {
            ensureNotLastEnabledAdmin(id, "不能删除最后一个启用的管理员");
        }
        int progress = Math.toIntExact(progressMapper.selectCount(new LambdaQueryWrapper<PlayProgressEntity>()
                .eq(PlayProgressEntity::getUserId, id)));
        progressMapper.delete(new LambdaQueryWrapper<PlayProgressEntity>().eq(PlayProgressEntity::getUserId, id));
        userMapper.deleteById(id);
        return new AdminVos.DeleteUserResultVo(id, progress);
    }

    private void ensureNotLastEnabledAdmin(Long excludeId, String msg) {
        long remaining = userMapper.selectCount(new LambdaQueryWrapper<SysUserEntity>()
                .eq(SysUserEntity::getRole, SysUserEntity.ROLE_ADMIN)
                .eq(SysUserEntity::getStatus, 1)
                .ne(SysUserEntity::getId, excludeId));
        if (remaining <= 0) {
            throw new BizException(ErrorCode.CONFLICT, msg);
        }
    }

    private SysUserEntity require(Long id) {
        SysUserEntity entity = id == null ? null : userMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return entity;
    }

    private String randomPassword(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(PWD_CHARS.charAt(random.nextInt(PWD_CHARS.length())));
        }
        return sb.toString();
    }
}
