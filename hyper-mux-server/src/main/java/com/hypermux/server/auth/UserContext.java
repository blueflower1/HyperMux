package com.hypermux.server.auth;

/** 请求级登录用户上下文（拦截器写入，Controller/Service 读取）。 */
public final class UserContext {

    public record LoginUser(Long id, String username, Integer role) {
    }

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static LoginUser require() {
        LoginUser u = HOLDER.get();
        if (u == null) {
            throw new IllegalStateException("无登录上下文");
        }
        return u;
    }

    public static Long userId() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.id();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
