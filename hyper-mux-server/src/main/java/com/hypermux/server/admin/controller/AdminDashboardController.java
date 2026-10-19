package com.hypermux.server.admin.controller;

import com.hypermux.server.admin.service.DashboardService;
import com.hypermux.server.admin.service.UserAdminService;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 后台总览 + 当前登录人（契约 §4.1/§4.2）。 */
@RestController
@RequestMapping("/api/admin")
public class AdminDashboardController {

    private final DashboardService dashboardService;
    private final UserAdminService userAdminService;

    public AdminDashboardController(DashboardService dashboardService, UserAdminService userAdminService) {
        this.dashboardService = dashboardService;
        this.userAdminService = userAdminService;
    }

    @GetMapping("/dashboard")
    public R<AdminVos.DashboardVo> dashboard() {
        return R.ok(dashboardService.get());
    }

    @GetMapping("/auth/me")
    public R<AdminVos.UserVo> me() {
        return R.ok(userAdminService.me());
    }
}
