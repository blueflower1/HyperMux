package com.hypermux.server.app.controller;

import com.hypermux.server.app.service.MovieQueryService;
import com.hypermux.server.app.vo.TvVos;
import com.hypermux.server.auth.UserContext;
import com.hypermux.server.common.PageResult;
import com.hypermux.server.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 影片列表 / 详情 / 剧集（契约 §3.3/§3.4/§3.5/§3.10）。 */
@RestController
@RequestMapping("/api/app")
public class AppMovieController {

    private final MovieQueryService movieQueryService;

    public AppMovieController(MovieQueryService movieQueryService) {
        this.movieQueryService = movieQueryService;
    }

    /** §3.3 类型内影片分页。 */
    @GetMapping("/category/{id}/movies")
    public R<PageResult<TvVos.MovieCardVo>> categoryMovies(@PathVariable("id") Long id,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int size,
                                                          @RequestParam(required = false) String keyword) {
        return R.ok(movieQueryService.listByCategory(id, page, size, keyword, UserContext.userId()));
    }

    /** §3.10 全局搜索。 */
    @GetMapping("/search")
    public R<PageResult<TvVos.MovieCardVo>> search(@RequestParam String keyword,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return R.ok(movieQueryService.search(keyword, page, size, UserContext.userId()));
    }

    /** §3.4 影片详情。 */
    @GetMapping("/movie/{id}")
    public R<TvVos.MovieDetailVo> detail(@PathVariable("id") Long id) {
        return R.ok(movieQueryService.detail(id, UserContext.userId()));
    }

    /** §3.5 剧集列表。 */
    @GetMapping("/movie/{id}/videos")
    public R<TvVos.SeasonsVo> videos(@PathVariable("id") Long id) {
        return R.ok(movieQueryService.seasons(id, UserContext.userId()));
    }
}
