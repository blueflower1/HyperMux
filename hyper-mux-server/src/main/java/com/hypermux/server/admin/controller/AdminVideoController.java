package com.hypermux.server.admin.controller;

import com.hypermux.server.admin.service.MovieAdminService;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 剧集后台接口（契约 §4.5）。 */
@RestController
@RequestMapping("/api/admin/movies/{movieId}/videos")
public class AdminVideoController {

    private final MovieAdminService service;

    public AdminVideoController(MovieAdminService service) {
        this.service = service;
    }

    @GetMapping
    public R<AdminVos.VideoListData> list(@PathVariable Long movieId) {
        return R.ok(service.videos(movieId));
    }

    @PutMapping
    public R<AdminVos.ReplaceVideosResultVo> replace(@PathVariable Long movieId,
                                                     @RequestBody AdminVos.VideoReplaceReq req) {
        return R.ok(service.replaceVideos(movieId, req == null ? null : req.episodes()));
    }

    @PostMapping("/import")
    public R<AdminVos.ImportResultVo> importText(@PathVariable Long movieId,
                                                 @RequestBody AdminVos.VideoImportReq req) {
        return R.ok(service.importVideos(movieId, req == null ? null : req.seasonNo(),
                req == null ? null : req.text()));
    }
}
