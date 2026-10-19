package com.hypermux.server.app.service;

import com.hypermux.server.app.vo.TvVos;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.movie.MovieEntity;
import com.hypermux.server.scrape.OpenListPaths;
import com.hypermux.server.scrape.OpenListSourceEntity;
import com.hypermux.server.scrape.SourceMapper;
import com.hypermux.server.video.VideoEntity;
import com.hypermux.server.video.VideoMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** 换取播放直链（契约 §3.6）：OPENLIST 拼接 OpenList /d/ 直链；REMOTE 原样返回。 */
@Service
public class PlayUrlService {

    private final VideoMapper videoMapper;
    private final SourceMapper sourceMapper;
    private final MovieQueryService movieQueryService;

    public PlayUrlService(VideoMapper videoMapper, SourceMapper sourceMapper,
                          MovieQueryService movieQueryService) {
        this.videoMapper = videoMapper;
        this.sourceMapper = sourceMapper;
        this.movieQueryService = movieQueryService;
    }

    public TvVos.PlayUrlVo playUrl(Long userId, TvVos.PlayUrlReq req) {
        if (req == null || req.videoId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "videoId 不能为空");
        }
        VideoEntity video = videoMapper.selectById(req.videoId());
        if (video == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "剧集不存在");
        }
        if (video.getMissing() != null && video.getMissing() == 1) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件已丢失");
        }
        MovieEntity movie = movieQueryService.requireVisible(video.getMovieId());

        Map<String, String> headers = new LinkedHashMap<>();
        if (VideoEntity.KIND_REMOTE.equals(video.getUrlKind())) {
            if (video.getRemoteUrl() == null || video.getRemoteUrl().isBlank()) {
                throw new BizException(ErrorCode.NOT_FOUND, "该剧集未配置直链");
            }
            return new TvVos.PlayUrlVo(video.getRemoteUrl(), headers, null);
        }

        // OPENLIST 型
        OpenListSourceEntity source = sourceMapper.selectById(movie.getSourceId());
        if (source == null || (source.getStatus() != null && source.getStatus() == 0)) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据源不存在或已停用");
        }
        String path = video.getOpenlistPath();
        if (path == null || path.isBlank()) {
            throw new BizException(ErrorCode.NOT_FOUND, "剧集缺少 OpenList 路径");
        }
        String url = OpenListPaths.directUrl(source.getServerUrl(), source.getRootPath(), path);
        headers.put("Referer", OpenListPaths.refererOf(source.getServerUrl()));
        return new TvVos.PlayUrlVo(url, headers, null);
    }
}
