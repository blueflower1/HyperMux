package com.hypermux.server.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 登录入参。
 * 注意：**登录只校验密码长度 6~20**，不校验字符集（字符集 [a-zA-Z0-9_.]{6,20} 仅用于建用户/重置密码）。
 * 例如初始管理员密码 Admin@1234 含 @，登录必须允许。
 */
public record LoginReq(
        @NotBlank
        @Size(min = 3, max = 32)
        @Pattern(regexp = "[a-zA-Z0-9_]+", message = "用户名仅允许字母/数字/下划线")
        String username,
        @NotBlank
        @Size(min = 6, max = 20, message = "密码长度 6~20")
        String password) {
}