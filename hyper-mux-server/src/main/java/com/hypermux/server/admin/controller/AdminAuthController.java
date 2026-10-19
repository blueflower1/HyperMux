package com.hypermux.server.admin.controller;

import com.hypermux.server.auth.AuthService;
import com.hypermux.server.auth.LoginReq;
import com.hypermux.server.auth.SessionVo;
import com.hypermux.server.common.R;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 后台登录（契约 §4.1）。 */
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final AuthService authService;

    public AdminAuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public R<SessionVo> login(@Valid @RequestBody LoginReq req) {
        return R.ok(authService.login(req.username(), req.password(), AuthService.ROLE_ADMIN));
    }
}
