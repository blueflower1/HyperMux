package com.hypermux.server.app.controller;

import com.hypermux.server.auth.AuthService;
import com.hypermux.server.auth.LoginReq;
import com.hypermux.server.auth.SessionVo;
import com.hypermux.server.common.R;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** App 端登录（契约 §3.1）。 */
@RestController
@RequestMapping("/api/app/auth")
public class AppAuthController {

    private final AuthService authService;

    public AppAuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public R<SessionVo> login(@Valid @RequestBody LoginReq req) {
        return R.ok(authService.login(req.username(), req.password(), AuthService.ROLE_APP_USER));
    }
}
