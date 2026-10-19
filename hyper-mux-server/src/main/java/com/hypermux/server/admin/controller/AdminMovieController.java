package com.hypermux.server.admin.controller;

import com.hypermux.server.admin.service.MovieAdminService;
import com.hypermux.server.admin.service.MovieImageService;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.common.PageResult;
import com.hypermux.server.common.R;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 影片后台接口（契约 §4.4）。 */
@RestController
@RequestMapping("/api/admin/movies")
public class AdminMovieController {

    private final MovieAdminService service;
    private final MovieImageService movieImageService;

    public AdminMovieController(MovieAdminService service, MovieImageService movieImageService) {
        this.service = service;
        this.movieImageService = movieImageService;
    }

    /** 阶段 3：刮削海报 + 横图并写入 OpenList 影片目录，回写自有服务直链。 */
    @PostMapping("/{id}/scrape-images")
    public R<AdminVos.ScrapeImagesResultVo> scrapeImages(@PathVariable Long id,
                                                        @RequestParam(required = false) String provider,
                                                        @RequestParam(defaultValue = "false") boolean overwrite) {
        return R.ok(movieImageService.scrape(id, provider, overwrite));
    }

    /** 批量刮削（ids 省略时默认只补「还没有横图」的已发布影片）。 */
    @PostMapping("/scrape-images")
    public R<AdminVos.ScrapeImagesBatchResultVo> scrapeImagesBatch(
            @RequestBody(required = false) AdminVos.ScrapeImagesBatchReq req) {
        boolean onlyMissing = req == null || req.onlyMissing() == null || req.onlyMissing();
        return R.ok(movieImageService.scrapeBatch(req == null ? null : req.ids(), onlyMissing,
                req == null ? null : req.provider()));
    }

    @GetMapping
    public R<PageResult<AdminVos.MovieListVo>> page(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) Long categoryId,
                                                    @RequestParam(required = false) String sortBy) {
        return R.ok(service.page(page, size, keyword, status, categoryId, sortBy));
    }

    @GetMapping("/{id}")
    public R<AdminVos.MovieDetailVo> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping
    public R<AdminVos.MovieSaveResultVo> create(@RequestBody AdminVos.MovieMainBody body) {
        return R.ok(service.create(body));
    }

    @PutMapping("/{id}")
    public R<AdminVos.IdResultVo> update(@PathVariable Long id, @RequestBody AdminVos.MovieMainBody body) {
        return R.ok(service.update(id, body));
    }

    @PostMapping("/{id}/publish")
    public R<AdminVos.MovieSaveResultVo> publish(@PathVariable Long id) {
        return R.ok(service.publish(id));
    }

    @PostMapping("/{id}/offline")
    public R<AdminVos.MovieSaveResultVo> offline(@PathVariable Long id) {
        return R.ok(service.offline(id));
    }

    @DeleteMapping("/{id}")
    public R<AdminVos.DeleteMovieResultVo> remove(@PathVariable Long id) {
        return R.ok(service.remove(id));
    }
}
