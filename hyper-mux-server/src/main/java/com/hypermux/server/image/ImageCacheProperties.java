package com.hypermux.server.image;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * hypermux.image.*：把远程图床图片（豆瓣/TMDB 等）下载到本机后由本站提供（防防盗链、防远程失效）。
 */
@ConfigurationProperties(prefix = "hypermux.image")
public class ImageCacheProperties {

    /** 本地缓存目录 */
    private String dir = System.getProperty("user.home") + "/.hypermux/images";

    /** 单张图片最大字节（超出拒绝，防被当图床刷） */
    private long maxBytes = 12L * 1024 * 1024;

    /** 允许代理的远程主机（后缀匹配） */
    private List<String> allowHosts = List.of("doubanio.com", "douban.com", "image.tmdb.org",
            "tmdb.org", "themoviedb.org");

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public List<String> getAllowHosts() {
        return allowHosts;
    }

    public void setAllowHosts(List<String> allowHosts) {
        this.allowHosts = allowHosts;
    }
}
