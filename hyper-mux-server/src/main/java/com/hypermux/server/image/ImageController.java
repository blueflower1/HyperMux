package com.hypermux.server.image;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 图片出图端点：{@code GET /img?u=<url-encoded 远程图址>}。
 * <p>App 侧把所有远程图片统一改写成该地址，由后端下载 + 本地缓存后提供（防防盗链 / 远程失效）。
 * 该路径不在 /api/** 下，无需鉴权。
 */
@RestController
public class ImageController {

    private final ImageCacheService imageCacheService;

    public ImageController(ImageCacheService imageCacheService) {
        this.imageCacheService = imageCacheService;
    }

    @GetMapping("/img")
    public ResponseEntity<byte[]> img(@RequestParam("u") String url,
                                      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        ImageCacheService.CachedImage image = imageCacheService.load(url);
        String etag = "\"" + image.etag() + "\"";
        if (ifNoneMatch != null && (ifNoneMatch.equals(etag) || ifNoneMatch.equals(image.etag()))) {
            return ResponseEntity.status(304).eTag(etag).build();
        }
        MediaType type;
        try {
            type = MediaType.parseMediaType(image.contentType());
        } catch (Exception e) {
            type = MediaType.IMAGE_JPEG;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(image.bytes());
    }
}
