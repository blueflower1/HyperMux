package com.hypermux.server.app.controller;

import com.hypermux.server.auth.AuthService;
import com.hypermux.server.auth.LoginReq;
import com.hypermux.server.auth.QrLoginService;
import com.hypermux.server.auth.QrVos;
import com.hypermux.server.auth.SessionVo;
import com.hypermux.server.auth.UserContext;
import com.hypermux.server.common.R;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** App 端登录（契约 §3.1）。 */
@RestController
@RequestMapping("/api/app/auth")
public class AppAuthController {

    private final AuthService authService;
    private final QrLoginService qrLoginService;

    public AppAuthController(AuthService authService, QrLoginService qrLoginService) {
        this.authService = authService;
        this.qrLoginService = qrLoginService;
    }

    @PostMapping("/login")
    public R<SessionVo> login(@Valid @RequestBody LoginReq req) {
        return R.ok(authService.login(req.username(), req.password(), AuthService.ROLE_APP_USER));
    }

    // ==================== 扫码登录 TV 端 ====================

    /** 匿名：TV 进入登录页时创建一次性登录码（60s，TV 到期自动换新码）。 */
    @PostMapping("/qr/create")
    public R<QrVos.CreateVo> qrCreate(HttpServletRequest request) {
        return R.ok(qrLoginService.create(clientIp(request)));
    }

    /** 匿名：TV 轮询状态；CONFIRMED 时 session 只投递一次。 */
    @GetMapping("/qr/status")
    public R<QrVos.StatusVo> qrStatus(@RequestParam String id) {
        return R.ok(qrLoginService.status(id));
    }

    /** 手机端（需登录）：已扫到码，TV 显示「已扫码待确认」。返回 true（safeApiCall 不接受空 data） */
    @PostMapping("/qr/scan")
    public R<Boolean> qrScan(@RequestBody QrVos.QrIdReq req) {
        qrLoginService.scan(req.id());
        return R.ok(Boolean.TRUE);
    }

    /** 手机端（需登录）：确认登录，绑定当前用户并签发 token。 */
    @PostMapping("/qr/confirm")
    public R<Boolean> qrConfirm(@RequestBody QrVos.QrIdReq req) {
        qrLoginService.confirm(req.id(), UserContext.userId());
        return R.ok(Boolean.TRUE);
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
