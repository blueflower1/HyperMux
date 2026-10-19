package com.hypermux.server.app.controller;

import com.hypermux.server.app.service.AppHomeService;
import com.hypermux.server.app.vo.TvVos;
import com.hypermux.server.auth.UserContext;
import com.hypermux.server.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 首页聚合（契约 §3.2）。 */
@RestController
@RequestMapping("/api/app")
public class AppHomeController {

    private final AppHomeService homeService;

    public AppHomeController(AppHomeService homeService) {
        this.homeService = homeService;
    }

    @GetMapping("/home")
    public R<TvVos.HomeVo> home() {
        return R.ok(homeService.home(UserContext.userId()));
    }
}
