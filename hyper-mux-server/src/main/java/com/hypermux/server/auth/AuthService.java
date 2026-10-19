package com.hypermux.server.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.user.SysUserEntity;
import com.hypermux.server.user.UserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录：账号/密码校验 → 状态/角色校验 → 签发 JWT（契约 §3.1）。
 * 同一用户名 1 分钟内失败 >= 5 次 → 429。
 */
@Service
public class AuthService {

    public static final int ROLE_APP_USER = 0;
    public static final int ROLE_ADMIN = 1;

    private static final int MAX_FAIL_PER_MIN = 5;

    private final UserMapper userMapper;
    private final JwtTokenService jwt;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private final Cache<String, AtomicInteger> failCounts = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    public AuthService(UserMapper userMapper, JwtTokenService jwt) {
        this.userMapper = userMapper;
        this.jwt = jwt;
    }

    public SessionVo login(String rawUsername, String rawPassword, int expectRole) {
        String username = rawUsername == null ? "" : rawUsername.trim();

        AtomicInteger fails = failCounts.get(username, k -> new AtomicInteger(0));
        if (fails.get() >= MAX_FAIL_PER_MIN) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "尝试过于频繁，请稍候再试");
        }

        SysUserEntity user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUserEntity>().eq(SysUserEntity::getUsername, username));

        if (user == null || !encoder.matches(rawPassword, user.getPasswordHash())) {
            fails.incrementAndGet();
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() == 0) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已停用");
        }
        if (!Integer.valueOf(expectRole).equals(user.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "该账号无此端访问权限");
        }

        failCounts.invalidate(username);
        user.setLastLoginTime(OffsetDateTime.now());
        userMapper.updateById(user);

        JwtTokenService.Issued issued = jwt.issue(
                user.getId(), user.getUsername(), user.getRole(), jwt.ttlForRole(user.getRole()));
        return new SessionVo(issued.token(), issued.expiresAt(),
                new SessionVo.UserInfo(user.getId(), user.getUsername(), user.getNickName()));
    }
}