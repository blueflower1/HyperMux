package com.hypermux.server.app.controller;

import com.hypermux.server.app.service.ProgressService;
import com.hypermux.server.app.vo.TvVos;
import com.hypermux.server.auth.UserContext;
import com.hypermux.server.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 历史 / 断点上报 / 看完移除（契约 §3.7/§3.8/§3.9）。 */
@RestController
@RequestMapping("/api/app")
public class AppProgressController {

    private final ProgressService progressService;

    public AppProgressController(ProgressService progressService) {
        this.progressService = progressService;
    }

    /** §3.7 历史记录。 */
    @GetMapping("/history")
    public R<List<TvVos.HistoryVo>> history(@RequestParam(required = false) Integer limit) {
        return R.ok(progressService.history(UserContext.userId(), limit));
    }

    /** §3.8 断点上报（幂等 upsert）。 */
    @PutMapping("/progress")
    public R<TvVos.ProgressVo> report(@RequestBody TvVos.ProgressReq req) {
        return R.ok(progressService.report(UserContext.userId(), req));
    }

    /** §3.9 看完 / 移除。 */
    @PostMapping("/progress/finish")
    public R<TvVos.FinishVo> finish(@RequestBody TvVos.FinishReq req) {
        return R.ok(progressService.finish(UserContext.userId(), req));
    }
}
