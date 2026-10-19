package com.hypermux.server.app.controller;

import com.hypermux.server.app.service.PlayUrlService;
import com.hypermux.server.app.vo.TvVos;
import com.hypermux.server.auth.UserContext;
import com.hypermux.server.common.R;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 换取播放直链（契约 §3.6）。 */
@RestController
@RequestMapping("/api/app")
public class AppPlayController {

    private final PlayUrlService playUrlService;

    public AppPlayController(PlayUrlService playUrlService) {
        this.playUrlService = playUrlService;
    }

    @PostMapping("/play-url")
    public R<TvVos.PlayUrlVo> playUrl(@RequestBody TvVos.PlayUrlReq req) {
        return R.ok(playUrlService.playUrl(UserContext.userId(), req));
    }
}
