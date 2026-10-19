package com.hypermux.server.auth;

/** 登录结果（契约 §3.1）。 */
public record SessionVo(String token, long expiresAt, UserInfo user) {

    public record UserInfo(Long id, String username, String nickName) {
    }
}