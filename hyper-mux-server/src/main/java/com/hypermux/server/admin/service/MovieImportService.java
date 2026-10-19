package com.hypermux.server.admin.service;

import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.scrape.OpenListClient;
import com.hypermux.server.scrape.OpenListSourceEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「从 OpenList 导入」：扫描指定影片目录，解析出剧集清单 + 海报候选（契约 §4.8 向导 import）。
 * 只读，不落库；落库由 /api/admin/movies 完成。
 */
@Slf4j
@Service
public class MovieImportService {

    private static final int MAX_FILES = 5000;
    private static final int MAX_DIRS = 500;
    private static final List<String> MEDIA_EXT = List.of("mp4", "mkv", "ts", "avi", "mov", "flv", "wmv",
            "rmvb", "rm", "m4v", "webm", "mpg", "mpeg", "iso");
    private static final List<String> POSTER_EXT = List.of("jpg", "jpeg", "png", "webp");

    private static final Pattern P_SEASON_DIR = Pattern.compile("(?i)^(?:s|season\\s*|第\\s*)(\\d{1,2})\\s*(?:季)?$");
    private static final Pattern P_SXXEYY = Pattern.compile("(?i)s(\\d{1,2})[\\s._-]*e(\\d{1,4})");
    private static final Pattern P_EP_CN = Pattern.compile("第\\s*(\\d{1,4})\\s*[集话話]");
    private static final Pattern P_EP_EN = Pattern.compile("(?i)(?:^|[^a-z])(?:ep?|e)[\\s._-]?(\\d{1,4})(?:[^0-9]|$)");
    private static final Pattern P_EP_TAIL = Pattern.compile("(?:^|[\\s._\\-])(\\d{1,4})(?:v\\d+)?$");
    private static final Pattern P_YEAR = Pattern.compile("[（(\\[\\s]((?:19|20)\\d{2})[)）\\]\\s]*");

    private final SourceAdminService sourceAdminService;
    private final OpenListClient client;

    public MovieImportService(SourceAdminService sourceAdminService, OpenListClient client) {
        this.sourceAdminService = sourceAdminService;
        this.client = client;
    }

    public AdminVos.MovieImportVo importFolder(Long sourceId, String path) {
        if (sourceId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 sourceId");
        }
        if (path == null || path.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 path");
        }
        OpenListSourceEntity source = sourceAdminService.require(sourceId);
        String token = sourceAdminService.tokenOf(source);
        String rootPath = source.getRootPath() == null || source.getRootPath().isBlank() ? "/" : source.getRootPath();
        String abs = normalize(path);
        if (!rootPath.equals("/") && !abs.equals(rootPath) && !abs.startsWith(rootPath + "/")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目录必须位于该数据源的 rootPath 之下");
        }

        List<Raw> medias = new ArrayList<>();
        List<String> posters = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(abs);
        int dirCount = 0;
        int skippedDirs = 0;

        while (!queue.isEmpty() && medias.size() < MAX_FILES && dirCount < MAX_DIRS) {
            String current = queue.poll();
            List<OpenListClient.Entry> entries;
            try {
                entries = listAll(source.getServerUrl(), token, current);
            } catch (Exception e) {
                // 单个子目录读不到（挂载异常/脏目录/已删除）不应让整个导入失败
                if (current.equals(abs)) {
                    throw e instanceof BizException be ? be
                            : new BizException(ErrorCode.BAD_REQUEST, "连不通 OpenList: " + e.getMessage());
                }
                skippedDirs++;
                log.warn("导入跳过不可读目录 {}: {}", current, e.getMessage());
                continue;
            }
            for (OpenListClient.Entry e : entries) {
                if (e.dir()) {
                    if (isSkipDir(e.name())) {
                        skippedDirs++;
                        continue;
                    }
                    dirCount++;
                    queue.add(e.path());
                } else {
                    String ext = extOf(e.name());
                    if (MEDIA_EXT.contains(ext)) {
                        medias.add(new Raw(e, current));
                    } else if (POSTER_EXT.contains(ext) && isPosterName(e.name())) {
                        posters.add(relative(rootPath, e.path()));
                    }
                }
            }
        }

        medias.sort(Comparator.comparingInt((Raw r) -> seasonOf(r)).thenComparingInt(MovieImportService::episodeOf));

        String folderName = leafName(abs);
        String title = cleanTitle(folderName);
        Integer year = extractYear(folderName);

        List<AdminVos.ImportedEpisodeVo> episodes = new ArrayList<>();
        int guessCount = 0;
        int idx = 0;
        for (Raw raw : medias) {
            idx++;
            int season = seasonOf(raw);
            int ep = episodeOf(raw);
            boolean guessed = ep <= 0;
            if (guessed) {
                ep = idx;
                guessCount++;
            }
            episodes.add(new AdminVos.ImportedEpisodeVo(season, ep, null,
                    relative(rootPath, raw.entry().path()), raw.entry().name(), raw.entry().size()));
        }

        boolean parsed = !title.isBlank() && !episodes.isEmpty() && guessCount == 0;
        return new AdminVos.MovieImportVo(source.getId(), source.getServerUrl(), rootPath, abs,
                relative(rootPath, abs), title, year, parsed, episodes, posters, dirCount, skippedDirs);
    }

    // ---------------- 解析 ----------------

    private record Raw(OpenListClient.Entry entry, String parent) {
    }

    private static int seasonOf(Raw raw) {
        Matcher m = P_SXXEYY.matcher(raw.entry().name());
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        String rel = raw.entry().path();
        String[] segs = rel.split("/");
        for (int i = segs.length - 2; i >= 0; i--) {
            Matcher sm = P_SEASON_DIR.matcher(segs[i].trim());
            if (sm.matches()) {
                return Integer.parseInt(sm.group(1));
            }
        }
        return 1;
    }

    private static int episodeOf(Raw raw) {
        String name = raw.entry().name();
        Matcher m = P_SXXEYY.matcher(name);
        if (m.find()) {
            return Integer.parseInt(m.group(2));
        }
        m = P_EP_CN.matcher(name);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = P_EP_EN.matcher(stripExt(name));
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = P_EP_TAIL.matcher(stripExt(name).trim());
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    private static boolean isPosterName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("poster") || n.startsWith("cover") || n.startsWith("folder")
                || n.startsWith("thumb") || n.startsWith("fanart") || n.contains("海报");
    }

    private static boolean isSkipDir(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("sample") || n.contains("extras") || n.contains("featurette")
                || n.equals("bdiso") || n.contains("subs") || n.contains("字幕");
    }

    private static String cleanTitle(String folderName) {
        String t = P_YEAR.matcher(folderName).replaceAll(" ");
        t = t.replaceAll("[（(\\[【].*?[)）\\]】]", " ");
        t = t.replaceAll("(?i)\\b(19|20)\\d{2}\\b", " ");
        t = t.replaceAll("(?i)\\b(1080p|2160p|4k|8k|bluray|web-?dl|hdtv|remux|hevc|av1|x26[45]|h26[45]|aac|dts|ddp?5\\.?1|10bit|hdr|dolby|vision|nf|amzn|dsnp)\\b", " ");
        t = t.replaceAll("[._\\-]+", " ");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    private static Integer extractYear(String s) {
        Matcher m = P_YEAR.matcher(s);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return null;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String leafName(String path) {
        String p = normalize(path);
        int idx = p.lastIndexOf('/');
        return idx < 0 ? p : p.substring(idx + 1);
    }

    private static String relative(String rootPath, String abs) {
        String root = rootPath == null || rootPath.isBlank() ? "/" : rootPath;
        String a = normalize(abs);
        String rel = a;
        if (!root.equals("/") && a.startsWith(root + "/")) {
            rel = a.substring(root.length() + 1);
        } else if (root.equals("/") && a.startsWith("/")) {
            rel = a.substring(1);
        }
        return rel;
    }

    private static String normalize(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private List<OpenListClient.Entry> listAll(String serverUrl, String token, String path) {
        List<OpenListClient.Entry> all = new ArrayList<>();
        int page = 1;
        while (page <= 5) {
            OpenListClient.ListPage result = client.list(serverUrl, token, path, page);
            all.addAll(result.entries());
            if (result.entries().size() < client.pageSize()) {
                break;
            }
            page++;
        }
        return all;
    }
}
