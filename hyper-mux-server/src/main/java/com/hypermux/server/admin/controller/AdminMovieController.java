package com.hypermux.server.admin.controller;

import com.hypermux.server.admin.service.MovieAdminService;
import com.hypermux.server.admin.service.MovieImageService;
import com.hypermux.server.admin.service.MovieLinkAdminService;
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

import java.util.List;

/** 影片后台接口（契约 §4.4）。 */
@RestController
@RequestMapping("/api/admin/movies")
public class AdminMovieController {

    private final MovieAdminService service;
    private final MovieImageService movieImageService;
    private final MovieLinkAdminService linkService;

    public AdminMovieController(MovieAdminService service, MovieImageService movieImageService,
                               MovieLinkAdminService linkService) {
        this.service = service;
        this.movieImageService = movieImageService;
        this.linkService = linkService;
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

    // ==================== 关联影片（「其他季」，契约 §4.13） ====================

    /** 本片当前的关联列表（含未上架的目标，后台要看得见才能纠正；App 侧由可见性铁律自动挡掉）。 */
    @GetMapping("/{id}/links")
    public R<List<AdminVos.MovieLinkVo>> links(@PathVariable Long id) {
        return R.ok(linkService.list(id));
    }

    /**
     * 添加 / 修改关联（同一条已存在时按本次提交更新）。
     * 服务端成对落两条（双向对称），所以管理员只需在一侧添加一次，另一侧自动出现；
     * `sortNo` 留空时按「对方那一部是第几季」落（第一季→1、第九季→9），反向那一行按本片季号。
     */
    @PostMapping("/{id}/links")
    public R<AdminVos.MovieLinkOpResultVo> addLinks(@PathVariable Long id,
                                                    @RequestBody AdminVos.MovieLinkAddReq req) {
        return R.ok(linkService.add(id, req));
    }

    /** 「按季号重排」：本片涉及的「同剧其他季」关联，两侧一起把 sort_no 归位成各自目标的季号。 */
    @PostMapping("/{id}/links/resort")
    public R<AdminVos.MovieLinkOpResultVo> resortLinks(@PathVariable Long id) {
        return R.ok(linkService.resort(id));
    }

    /** 删除一条关联（反向那条一起删）。 */
    @DeleteMapping("/{id}/links/{linkId}")
    public R<AdminVos.MovieLinkOpResultVo> removeLink(@PathVariable Long id, @PathVariable Long linkId) {
        return R.ok(linkService.remove(id, linkId));
    }
}
