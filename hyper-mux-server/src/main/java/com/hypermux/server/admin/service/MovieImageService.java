package com.hypermux.server.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.image.ImageCacheService;
import com.hypermux.server.movie.MovieEntity;
import com.hypermux.server.movie.MovieMapper;
import com.hypermux.server.scrape.OpenListClient;
import com.hypermux.server.scrape.OpenListPaths;
import com.hypermux.server.scrape.OpenListSourceEntity;
import com.hypermux.server.scrape.SourceMapper;
import com.hypermux.server.video.VideoEntity;
import com.hypermux.server.video.VideoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 横图刮削 + 图片写入 OpenList 影片目录（阶段 3）。
 * <p>流程：元数据搜索/详情 → 取海报 + 横图（豆瓣取预告片封面，TMDB 取 backdrop）→ 下载 →
 * 上传到 {@code {影片目录}/images/} → 把 {@code movie.cover_url} / {@code movie.backdrop_url}
 * 写成为 OpenList 直链（与视频直链同源，规避远程图床防盗链/失效）。
 */
@Slf4j
@Service
public class MovieImageService {

    private static final String IMAGES_DIR = "images";
    private static final String POSTER_NAME = "poster";
    private static final String BACKDROP_NAME = "backdrop";

    private final MovieMapper movieMapper;
    private final VideoMapper videoMapper;
    private final SourceMapper sourceMapper;
    private final SourceAdminService sourceAdminService;
    private final MetadataService metadataService;
    private final ImageCacheService imageCacheService;
    private final OpenListClient client;

    public MovieImageService(MovieMapper movieMapper, VideoMapper videoMapper, SourceMapper sourceMapper,
                             SourceAdminService sourceAdminService, MetadataService metadataService,
                             ImageCacheService imageCacheService, OpenListClient client) {
        this.movieMapper = movieMapper;
        this.videoMapper = videoMapper;
        this.sourceMapper = sourceMapper;
        this.sourceAdminService = sourceAdminService;
        this.metadataService = metadataService;
        this.imageCacheService = imageCacheService;
        this.client = client;
    }

    /** 单部影片：刮削 → 上传 → 回写。 */
    public AdminVos.ScrapeImagesResultVo scrape(Long movieId, String provider, boolean overwrite) {
        MovieEntity movie = movieMapper.selectById(movieId);
        if (movie == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "影片不存在");
        }
        if (movie.getTitle() == null || movie.getTitle().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "影片缺少片名，无法刮削");
        }
        if (movie.getSourceId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "影片未关联 OpenList 数据源，无法写入图片");
        }
        OpenListSourceEntity source = sourceMapper.selectById(movie.getSourceId());
        if (source == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据源不存在");
        }
        String dir = resolveMovieDir(movie);
        if (dir == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "无法确定影片在 OpenList 上的目录（缺少 sourcePath 与剧集路径）");
        }

        // 1) 判断图片来源优先级：
        //    ① 库里是「用户挑选的远程图（非自有直链）」→ 直接固化它
        //    ② 库里缺失 → 从元数据取
        //    ③ 已是自有直链 → 只有 overwrite=true 才重新从元数据刮
        String token = sourceAdminService.tokenOf(source);
        String imagesDir = OpenListPaths.join(dir, IMAGES_DIR);
        String coverUrl = movie.getCoverUrl();
        String backdropUrl = movie.getBackdropUrl();
        boolean ownCover = isSelfHosted(source, coverUrl);
        boolean ownBackdrop = isSelfHosted(source, backdropUrl);
        boolean needMetaCover = ownCover ? overwrite : isBlank(coverUrl);
        boolean needMetaBackdrop = ownBackdrop ? overwrite : isBlank(backdropUrl);

        AdminVos.MetadataDetailVo meta = null;
        if (needMetaCover || needMetaBackdrop) {
            List<AdminVos.MetadataItemVo> found = metadataService.search(movie.getTitle(), movie.getYear(), provider);
            if (!found.isEmpty()) {
                AdminVos.MetadataItemVo match = pickBest(movie, found);
                meta = metadataService.detail(match.providerId(), match.mediaType(), provider);
            } else if (isBlank(coverUrl) || isBlank(backdropUrl)) {
                throw new BizException(ErrorCode.NOT_FOUND, "元数据未找到匹配条目：" + movie.getTitle());
            }
        }
        String effProvider = metadataService.resolveProvider(provider);

        String coverSrc;
        if (ownCover) {
            coverSrc = overwrite ? (meta == null ? null : meta.posterUrl()) : null;
        } else if (notBlank(coverUrl)) {
            coverSrc = coverUrl;
        } else {
            coverSrc = meta == null ? null : meta.posterUrl();
        }
        String backdropSrc;
        if (ownBackdrop) {
            backdropSrc = overwrite ? (meta == null ? null : meta.backdropUrl()) : null;
        } else if (notBlank(backdropUrl)) {
            backdropSrc = backdropUrl;
        } else {
            backdropSrc = meta == null ? null : meta.backdropUrl();
        }

        // 2) 下载 + 上传到 {影片目录}/images/，回写自有直链
        List<String> uploaded = new ArrayList<>();
        if (notBlank(coverSrc)) {
            coverUrl = put(source, token, imagesDir, POSTER_NAME, coverSrc, uploaded);
        }
        if (notBlank(backdropSrc)) {
            backdropUrl = put(source, token, imagesDir, BACKDROP_NAME, backdropSrc, uploaded);
        }

        String message = null;
        if (uploaded.isEmpty()) {
            message = "没有需要处理的图片（已是自有服务直链；如需重刮请加 overwrite=true）";
        } else if (isBlank(backdropUrl)) {
            message = "未取到横图（该条目暂无剧照/backdrop），App 将按竖版封面展示";
        }

        movie.setCoverUrl(coverUrl);
        movie.setBackdropUrl(backdropUrl);
        movieMapper.updateById(movie);

        return new AdminVos.ScrapeImagesResultVo(movie.getId(), movie.getTitle(), effProvider,
                meta == null ? null : meta.title(), meta == null ? null : meta.year(),
                coverUrl, backdropUrl, uploaded, message);
    }

    /** 该 URL 是否已是本数据源自有的直链。 */
    private static boolean isSelfHosted(OpenListSourceEntity source, String url) {
        return notBlank(url) && source.getServerUrl() != null && !source.getServerUrl().isBlank()
                && url.startsWith(source.getServerUrl());
    }

    /** 批量刮削（ids 为空时按 onlyMissing 取全量已发布影片）。 */
    public AdminVos.ScrapeImagesBatchResultVo scrapeBatch(List<Long> ids, boolean onlyMissing, String provider) {
        LambdaQueryWrapper<MovieEntity> qw = new LambdaQueryWrapper<>();
        if (ids != null && !ids.isEmpty()) {
            qw.in(MovieEntity::getId, ids);
        } else {
            qw.in(MovieEntity::getStatus, List.of("PUBLISHED", "OFF"));
            if (onlyMissing) {
                qw.and(w -> w.isNull(MovieEntity::getBackdropUrl).or().eq(MovieEntity::getBackdropUrl, ""));
            }
        }
        List<MovieEntity> movies = movieMapper.selectList(qw);
        int ok = 0;
        List<AdminVos.BatchErrorVo> errors = new ArrayList<>();
        for (MovieEntity m : movies) {
            try {
                scrape(m.getId(), provider, false);
                ok++;
            } catch (Exception e) {
                errors.add(new AdminVos.BatchErrorVo(m.getId(), e.getMessage()));
                log.warn("刮削失败 movieId={} msg={}", m.getId(), e.getMessage());
            }
        }
        return new AdminVos.ScrapeImagesBatchResultVo(movies.size(), ok, errors.size(), errors);
    }

    // ---------------- 内部 ----------------

    /** 选最匹配的元数据条目：片名归一化相等优先，其次年份相同，最后取第一条。 */
    private AdminVos.MetadataItemVo pickBest(MovieEntity movie, List<AdminVos.MetadataItemVo> found) {
        String want = norm(movie.getTitle());
        for (AdminVos.MetadataItemVo item : found) {
            if (norm(item.title()).equals(want)
                    && (movie.getYear() == null || item.year() == null || movie.getYear().equals(item.year()))) {
                return item;
            }
        }
        for (AdminVos.MetadataItemVo item : found) {
            if (norm(item.title()).equals(want)) {
                return item;
            }
        }
        for (AdminVos.MetadataItemVo item : found) {
            if (movie.getYear() != null && movie.getYear().equals(item.year())) {
                return item;
            }
        }
        return found.get(0);
    }

    /** 下载并上传一张图片，返回 OpenList 直链。 */
    private String put(OpenListSourceEntity source, String token, String imagesDir, String baseName,
                       String remoteUrl, List<String> uploaded) {
        ImageCacheService.CachedImage image = imageCacheService.load(remoteUrl);
        String ext = extOf(image.contentType());
        String fileName = baseName + ext;
        String absPath = OpenListPaths.join(imagesDir, fileName);
        client.mkdir(source.getServerUrl(), token, imagesDir);
        client.upload(source.getServerUrl(), token, absPath, image.bytes(), image.contentType());
        String direct = OpenListPaths.directUrl(source.getServerUrl(), "/", absPath);
        uploaded.add(direct);
        return direct;
    }

    /** 影片在 OpenList 上的绝对目录：优先 sourcePath，否则由剧集路径推导。 */
    private String resolveMovieDir(MovieEntity movie) {
        if (notBlank(movie.getSourcePath())) {
            return OpenListPaths.normalize(movie.getSourcePath());
        }
        List<VideoEntity> videos = videoMapper.selectList(new LambdaQueryWrapper<VideoEntity>()
                .eq(VideoEntity::getMovieId, movie.getId())
                .eq(VideoEntity::getUrlKind, VideoEntity.KIND_OPENLIST)
                .orderByAsc(VideoEntity::getSeasonNo)
                .orderByAsc(VideoEntity::getEpisodeNo)
                .last("limit 1"));
        if (videos.isEmpty() || isBlank(videos.get(0).getOpenlistPath())) {
            return null;
        }
        OpenListSourceEntity source = sourceMapper.selectById(movie.getSourceId());
        String root = source == null ? "/" : source.getRootPath();
        String abs = OpenListPaths.join(root, videos.get(0).getOpenlistPath());
        int idx = abs.lastIndexOf('/');
        return idx <= 0 ? null : abs.substring(0, idx);
    }

    private static String extOf(String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("png")) {
            return ".png";
        }
        if (ct.contains("webp")) {
            return ".webp";
        }
        if (ct.contains("gif")) {
            return ".gif";
        }
        return ".jpg";
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("[\\s·:：\\-—]+", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
