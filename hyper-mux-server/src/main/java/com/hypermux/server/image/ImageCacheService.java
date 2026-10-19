package com.hypermux.server.image;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.scrape.OpenListSourceEntity;
import com.hypermux.server.scrape.SourceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;

/**
 * 远程图片本地缓存。
 * <p>豆瓣图床无 Referer 会返回 418，这里带上 {@code Referer: https://movie.douban.com/} 再取图；
 * 取回后落盘，后续直接由本服务出图。
 */
@Slf4j
@Service
public class ImageCacheService {

    public record CachedImage(byte[] bytes, String contentType, String etag) {
    }

    private final ImageCacheProperties props;
    private final SourceMapper sourceMapper;
    private final HttpClient http;

    public ImageCacheService(ImageCacheProperties props, SourceMapper sourceMapper) {
        this.props = props;
        this.sourceMapper = sourceMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CachedImage load(String rawUrl) {
        String url = normalize(rawUrl);
        URI uri = parse(url);
        if (!hostAllowed(uri.getHost())) {
            throw new BizException(ErrorCode.FORBIDDEN, "该域名不允许代理");
        }
        String key = sha256Hex(url);
        Path dir = Path.of(props.getDir());
        Path file = findByKey(dir, key);
        if (file != null) {
            return read(file, key);
        }
        return download(url, uri, key, dir, file);
    }

    private CachedImage download(String url, URI uri, String key, Path dir, Path existing) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) HyperMux/1.0")
                    .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    .GET();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.contains("douban")) {
                builder.header("Referer", "https://movie.douban.com/");
            } else if (host.contains("tmdb")) {
                builder.header("Referer", "https://www.themoviedb.org/");
            }
            HttpResponse<byte[]> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                throw new BizException(ErrorCode.NOT_FOUND, "远程图片获取失败 HTTP " + resp.statusCode());
            }
            byte[] body = resp.body();
            if (body == null || body.length == 0) {
                throw new BizException(ErrorCode.NOT_FOUND, "远程图片为空");
            }
            if (body.length > props.getMaxBytes()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "图片超出大小限制");
            }
            String contentType = resp.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                // 部分网盘直链返回 application/octet-stream：按 URL 扩展名兜底，避免 App 侧拿不到图片类型
                contentType = contentTypeOf(pathOf(uri));
            }
            String ext = extOf(contentType);
            Files.createDirectories(dir);
            Path target = existing != null ? existing : dir.resolve(key + ext);
            Files.write(target, body);
            return new CachedImage(body, contentType, key);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("图片下载失败 url={} err={}", url, e.toString());
            throw new BizException(ErrorCode.NOT_FOUND, "远程图片获取失败");
        }
    }

    private CachedImage read(Path file, String key) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            String contentType = contentTypeOf(file.getFileName().toString());
            return new CachedImage(bytes, contentType, key);
        } catch (Exception e) {
            throw new BizException(ErrorCode.NOT_FOUND, "缓存读取失败");
        }
    }

    private Path findByKey(Path dir, String key) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith(key + ".")).findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少图片地址");
        }
        String url = raw.trim();
        if (url.startsWith("//")) {
            url = "https:" + url;
        } else if (url.startsWith("http://")) {
            url = "https://" + url.substring("http://".length());
        }
        if (!url.startsWith("https://")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图片地址不合法");
        }
        return url;
    }

    private URI parse(String url) {
        try {
            return URI.create(url);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图片地址不合法");
        }
    }

    private boolean hostAllowed(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String allowed : props.getAllowHosts()) {
            String a = allowed.toLowerCase(Locale.ROOT).trim();
            if (h.equals(a) || h.endsWith("." + a)) {
                return true;
            }
        }
        // 已配置的 OpenList 数据源（刮削图片写入影片目录后，直链与视频同源，也要允许代理）
        for (OpenListSourceEntity source : sourceMapper.selectList(
                new LambdaQueryWrapper<OpenListSourceEntity>().select(OpenListSourceEntity::getServerUrl))) {
            String srcHost = hostOf(source.getServerUrl());
            if (srcHost != null && (h.equals(srcHost) || h.endsWith("." + srcHost))) {
                return true;
            }
        }
        return false;
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url == null ? "" : url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
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
        if (ct.contains("avif")) {
            return ".avif";
        }
        return ".jpg";
    }

    private static String contentTypeOf(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        if (n.endsWith(".avif")) {
            return "image/avif";
        }
        return "image/jpeg";
    }

    /** 取 URI 的最后一段（按扩展名推断图片类型用）。 */
    private static String pathOf(URI uri) {
        String p = uri.getPath();
        if (p == null || p.isBlank()) {
            return "";
        }
        int idx = p.lastIndexOf('/');
        return idx < 0 ? p : p.substring(idx + 1);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
