package com.hypermux.server.auth;

import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.user.SysUserEntity;
import com.hypermux.server.user.UserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 双前缀鉴权（契约 §6）：
 * /api/admin/** 要求 role=1；/api/app/** 要求 role=0。
 * 每请求回库校验账号状态 —— 停用即时失效。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtTokenService jwt;
    private final UserMapper userMapper;

    public AuthInterceptor(JwtTokenService jwt, UserMapper userMapper) {
        this.jwt = jwt;
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        int expectRole = path.startsWith("/api/admin") ? AuthService.ROLE_ADMIN : AuthService.ROLE_APP_USER;

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少登录凭证");
        }
        String token = header.trim().substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少登录凭证");
        }

        Claims claims;
        try {
            claims = jwt.parse(token);
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "token 无效或已过期");
        }

        long userId;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "token 无效");
        }
        Integer role = claims.get("role", Integer.class);

        SysUserEntity user = userMapper.selectById(userId);
        if (user == null || (user.getStatus() != null && user.getStatus() == 0)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号不存在或已停用");
        }
        if (role == null || role != expectRole) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问该端接口");
        }

        UserContext.set(new UserContext.LoginUser(user.getId(), user.getUsername(), user.getRole()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}