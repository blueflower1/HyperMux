package com.hypermux.server.scrape;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * OpenList 直链拼接（契约 §3.6）：
 * {@code {serverUrl}/d/{join(rootPath, openlistPath) 逐段 percent-encode}}。
 */
public final class OpenListPaths {

    private OpenListPaths() {
    }

    /** 归一化：保证以 / 开头、无重复斜杠、无结尾斜杠。 */
    public static String normalize(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** 拼接 rootPath + 相对路径 → 绝对路径。 */
    public static String join(String rootPath, String relative) {
        String root = normalize(rootPath == null || rootPath.isBlank() ? "/" : rootPath);
        String rel = normalize(relative == null ? "" : relative);
        if (root.equals("/")) {
            return rel;
        }
        return normalize(root + rel);
    }

    /** 逐段 percent-encode（保留 / 分隔符）。 */
    public static String encodeSegments(String absolutePath) {
        String p = normalize(absolutePath);
        StringBuilder sb = new StringBuilder();
        for (String seg : p.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            sb.append('/').append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    /** 组装直链。 */
    public static String directUrl(String serverUrl, String rootPath, String openlistPath) {
        return trimTrailingSlash(serverUrl) + "/d" + encodeSegments(join(rootPath, openlistPath));
    }

    /** 播放器请求头：OpenList 需要 Referer 才允许直链播放。 */
    public static String refererOf(String serverUrl) {
        return trimTrailingSlash(serverUrl) + "/";
    }

    private static String trimTrailingSlash(String s) {
        String base = s == null ? "" : s.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }
}
