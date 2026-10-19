package com.hypermux.server.admin.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.config.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 元数据刮削（后台「一键填充」）：豆瓣（免 Key，需 Referer）与 TMDB（需 Key，取自系统设置）。
 */
@Slf4j
@Service
public class MetadataService {

    private static final String TMDB_BASE = "https://api.themoviedb.org/3";
    private static final String TMDB_IMG = "https://image.tmdb.org/t/p/w500";
    private static final String TMDB_BACKDROP = "https://image.tmdb.org/t/p/w780";

    private final ConfigService configService;
    private final HttpClient http;

    public MetadataService(ConfigService configService) {
        this.configService = configService;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 按片名搜索（provider 缺省用系统设置里的当前来源）。 */
    public List<AdminVos.MetadataItemVo> search(String keyword, Integer year, String provider) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "keyword 不能为空");
        }
        String p = resolveProvider(provider);
        if (ConfigService.PROVIDER_TMDB.equals(p)) {
            return tmdbSearch(kw, year);
        }
        return doubanSearch(kw, year);
    }

    /** 取详情用于一键填充表单。 */
    public AdminVos.MetadataDetailVo detail(String providerId, String mediaType, String provider) {
        if (providerId == null || providerId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "providerId 不能为空");
        }
        String p = resolveProvider(provider);
        if (ConfigService.PROVIDER_TMDB.equals(p)) {
            return tmdbDetail(providerId, mediaType);
        }
        return doubanDetail(providerId, mediaType);
    }

    /** 解析生效的元数据来源（入参为空则取系统设置，默认豆瓣）。 */
    public String resolveProvider(String provider) {
        String p = provider == null || provider.isBlank() ? configService.metadataProvider() : provider.trim();
        p = p == null ? ConfigService.PROVIDER_DOUBAN : p.toLowerCase();
        if (!ConfigService.PROVIDER_TMDB.equals(p) && !ConfigService.PROVIDER_DOUBAN.equals(p)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "provider 只能是 tmdb 或 douban");
        }
        return p;
    }

    // ==================== 豆瓣 ====================

    private List<AdminVos.MetadataItemVo> doubanSearch(String keyword, Integer year) {
        String url = "https://movie.douban.com/j/subject_suggest?q=" + enc(keyword);
        JsonElement root = getJson(url, "https://movie.douban.com/");
        List<AdminVos.MetadataItemVo> out = new ArrayList<>();
        if (root == null || !root.isJsonArray()) {
            return out;
        }
        for (JsonElement el : root.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            Integer y = optInt(o, "year");
            if (year != null && y != null && !year.equals(y)) {
                continue;
            }
            String type = optStr(o, "type");
            out.add(new AdminVos.MetadataItemVo(optStr(o, "id"), doubanMediaType(type),
                    optStr(o, "title"), optStr(o, "sub_title"), y, optStr(o, "img")));
        }
        return out;
    }

    private AdminVos.MetadataDetailVo doubanDetail(String providerId, String mediaType) {
        String type = "tv".equalsIgnoreCase(mediaType) ? "tv" : "movie";
        String url = "https://m.douban.com/rexxar/api/v2/" + type + "/" + enc(providerId) + "?for_mobile=1";
        JsonObject o = asObject(getJson(url, "https://m.douban.com/"));
        if (o == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "豆瓣未找到该条目");
        }
        String title = optStr(o, "title");
        Integer year = parseInt(optStr(o, "year"));
        Double rating = null;
        JsonObject r = asObject(o.get("rating"));
        if (r != null) {
            rating = parseDouble(optStr(r, "value"));
        }
        String poster = null;
        JsonObject pic = asObject(o.get("pic"));
        if (pic != null) {
            poster = firstNonBlank(optStr(pic, "large"), optStr(pic, "normal"));
        }
        if (poster == null) {
            poster = optStr(o, "cover_url");
        }
        String area = null;
        JsonArray countries = o.getAsJsonArray("countries");
        if (countries != null && !countries.isEmpty()) {
            area = countries.get(0).getAsString();
        }
        List<String> genres = strList(o.getAsJsonArray("genres"));
        return new AdminVos.MetadataDetailVo(providerId, type, title, optStr(o, "original_title"), year,
                rating, optStr(o, "intro"), upgradeDoubanPoster(poster), doubanBackdrop(o), area, genres);
    }

    /** 豆瓣剧照：预告片封面即 16:9 横图（无独立剧照接口）。 */
    private static String doubanBackdrop(JsonObject o) {
        JsonArray trailers = o.getAsJsonArray("trailers");
        if (trailers != null) {
            for (JsonElement el : trailers) {
                JsonObject t = asObject(el);
                if (t == null) {
                    continue;
                }
                String cover = optStr(t, "cover_url");
                if (cover != null && !cover.isBlank()) {
                    return cover;
                }
            }
        }
        return null;
    }

    /** 豆瓣海报升到大图口径（s_/m_ratio_poster → l_ratio_poster）。 */
    private static String upgradeDoubanPoster(String url) {
        if (url == null) {
            return null;
        }
        return url.replace("/s_ratio_poster/", "/l_ratio_poster/").replace("/m_ratio_poster/", "/l_ratio_poster/");
    }

    private static String doubanMediaType(String type) {
        if (type == null) {
            return "movie";
        }
        return switch (type) {
            case "tv", "movie_tv" -> "tv";
            default -> "movie";
        };
    }

    // ==================== TMDB ====================

    private List<AdminVos.MetadataItemVo> tmdbSearch(String keyword, Integer year) {
        String key = requireTmdbKey();
        String url = TMDB_BASE + "/search/multi?api_key=" + enc(key) + "&language=zh-CN&include_adult=false&query="
                + enc(keyword) + (year == null ? "" : "&year=" + year);
        JsonObject root = asObject(getJson(url, null));
        List<AdminVos.MetadataItemVo> out = new ArrayList<>();
        JsonArray results = root == null ? null : root.getAsJsonArray("results");
        if (results == null) {
            return out;
        }
        for (JsonElement el : results) {
            JsonObject o = asObject(el);
            if (o == null) {
                continue;
            }
            String mediaType = optStr(o, "media_type");
            if ("person".equals(mediaType)) {
                continue;
            }
            String title = firstNonBlank(optStr(o, "title"), optStr(o, "name"));
            String original = firstNonBlank(optStr(o, "original_title"), optStr(o, "original_name"));
            Integer y = yearOf(firstNonBlank(optStr(o, "release_date"), optStr(o, "first_air_date")));
            String poster = optStr(o, "poster_path");
            out.add(new AdminVos.MetadataItemVo(optStr(o, "id"), mediaType == null ? "movie" : mediaType,
                    title, original, y, poster == null ? null : TMDB_IMG + poster));
        }
        return out;
    }

    private AdminVos.MetadataDetailVo tmdbDetail(String providerId, String mediaType) {
        String key = requireTmdbKey();
        String type = "tv".equalsIgnoreCase(mediaType) ? "tv" : "movie";
        String url = TMDB_BASE + "/" + type + "/" + enc(providerId) + "?api_key=" + enc(key) + "&language=zh-CN";
        JsonObject o = asObject(getJson(url, null));
        if (o == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "TMDB 未找到该条目");
        }
        String title = firstNonBlank(optStr(o, "title"), optStr(o, "name"));
        String original = firstNonBlank(optStr(o, "original_title"), optStr(o, "original_name"));
        Integer year = yearOf(firstNonBlank(optStr(o, "release_date"), optStr(o, "first_air_date")));
        Double rating = parseDouble(optStr(o, "vote_average"));
        String poster = optStr(o, "poster_path");
        String area = null;
        JsonArray countries = o.getAsJsonArray("production_countries");
        if (countries != null && !countries.isEmpty()) {
            JsonObject c = asObject(countries.get(0));
            if (c != null) {
                area = optStr(c, "name");
            }
        }
        List<String> genres = new ArrayList<>();
        JsonArray gs = o.getAsJsonArray("genres");
        if (gs != null) {
            for (JsonElement el : gs) {
                JsonObject g = asObject(el);
                if (g != null) {
                    genres.add(optStr(g, "name"));
                }
            }
        }
        return new AdminVos.MetadataDetailVo(providerId, type, title, original, year, rating,
                optStr(o, "overview"), poster == null ? null : TMDB_IMG + poster,
                firstNonBlank(optStr(o, "backdrop_path"), null) == null ? null
                        : TMDB_BACKDROP + optStr(o, "backdrop_path"),
                area, genres);
    }

    private String requireTmdbKey() {
        String key = configService.tmdbApiKey();
        if (key == null || key.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未配置 TMDB API Key，请先到系统设置填写");
        }
        return key.trim();
    }

    // ==================== 候选图片（挑封面用） ====================

    /** 某个条目的所有可用图片：竖版候选（posters）与横版候选（backdrops）。 */
    public AdminVos.MetadataImagesVo images(String providerId, String mediaType, String provider) {
        if (providerId == null || providerId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "providerId 不能为空");
        }
        String p = resolveProvider(provider);
        if (ConfigService.PROVIDER_TMDB.equals(p)) {
            return tmdbImages(providerId, mediaType);
        }
        return doubanImages(providerId, mediaType);
    }

    private static final int MAX_CANDIDATES = 40;

    private AdminVos.MetadataImagesVo doubanImages(String providerId, String mediaType) {
        String type = "tv".equalsIgnoreCase(mediaType) ? "tv" : "movie";
        List<AdminVos.ImageCandidateVo> posters = new ArrayList<>();
        List<AdminVos.ImageCandidateVo> backdrops = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1) 详情：官方海报 + 预告片封面（16:9）
        JsonObject o = asObject(getJson("https://m.douban.com/rexxar/api/v2/" + type + "/" + enc(providerId)
                + "?for_mobile=1", "https://m.douban.com/"));
        if (o != null) {
            String pic = null;
            JsonObject picObj = asObject(o.get("pic"));
            if (picObj != null) {
                pic = firstNonBlank(optStr(picObj, "large"), optStr(picObj, "normal"));
            }
            if (pic == null) {
                pic = optStr(o, "cover_url");
            }
            addCandidate(posters, seen, upgradeDoubanPoster(pic), "官方海报", null, null);
            collectTrailerCovers(o.getAsJsonArray("trailers"), backdrops, seen);
        }

        // 2) 预告片接口（多条 16:9 封面）
        JsonObject trailers = asObject(getJson("https://m.douban.com/rexxar/api/v2/" + type + "/" + enc(providerId)
                + "/trailers", "https://m.douban.com/"));
        if (trailers != null) {
            collectTrailerCovers(trailers.getAsJsonArray("trailers"), backdrops, seen);
        }

        // 3) 相册：多张剧照/海报（按宽高自动归类到竖版/横版）
        JsonObject album = asObject(getJson("https://m.douban.com/rexxar/api/v2/" + type + "/" + enc(providerId)
                + "/photos?count=60&start=0&for_mobile=1", "https://m.douban.com/"));
        JsonArray photos = album == null ? null : album.getAsJsonArray("photos");
        if (photos != null) {
            for (JsonElement el : photos) {
                JsonObject ph = asObject(el);
                if (ph == null) {
                    continue;
                }
                JsonObject image = asObject(ph.get("image"));
                JsonObject large = image == null ? null : asObject(image.get("large"));
                String url = large == null ? null : optStr(large, "url");
                if (url == null || url.isBlank()) {
                    continue;
                }
                Integer w = intOf(large, "width");
                Integer h = intOf(large, "height");
                if (w != null && h != null && w > h) {
                    addCandidate(backdrops, seen, url, "横版剧照", w, h);
                } else {
                    addCandidate(posters, seen, url, "剧照/海报", w, h);
                }
            }
        }
        return new AdminVos.MetadataImagesVo(cap(posters), cap(backdrops));
    }

    private void collectTrailerCovers(JsonArray trailers, List<AdminVos.ImageCandidateVo> backdrops, Set<String> seen) {
        if (trailers == null) {
            return;
        }
        for (JsonElement el : trailers) {
            JsonObject t = asObject(el);
            if (t == null) {
                continue;
            }
            String cover = optStr(t, "cover_url");
            addCandidate(backdrops, seen, cover, "预告片封面", 16, 9);
        }
    }

    private static void addCandidate(List<AdminVos.ImageCandidateVo> list, Set<String> seen, String url,
                                     String label, Integer w, Integer h) {
        if (url == null || url.isBlank() || !seen.add(url)) {
            return;
        }
        list.add(new AdminVos.ImageCandidateVo(url, label, w, h));
    }

    private static List<AdminVos.ImageCandidateVo> cap(List<AdminVos.ImageCandidateVo> list) {
        return list.size() <= MAX_CANDIDATES ? list : new ArrayList<>(list.subList(0, MAX_CANDIDATES));
    }

    private AdminVos.MetadataImagesVo tmdbImages(String providerId, String mediaType) {
        String key = requireTmdbKey();
        String type = "tv".equalsIgnoreCase(mediaType) ? "tv" : "movie";
        JsonObject o = asObject(getJson(TMDB_BASE + "/" + type + "/" + enc(providerId)
                + "/images?api_key=" + enc(key) + "&include_image_language=zh,en,null", null));
        List<AdminVos.ImageCandidateVo> posters = new ArrayList<>();
        List<AdminVos.ImageCandidateVo> backdrops = new ArrayList<>();
        if (o != null) {
            JsonArray ps = o.getAsJsonArray("posters");
            if (ps != null) {
                for (JsonElement el : ps) {
                    JsonObject it = asObject(el);
                    String path = it == null ? null : optStr(it, "file_path");
                    if (path != null) {
                        posters.add(new AdminVos.ImageCandidateVo(TMDB_IMG + path, "TMDB 海报",
                                optInt(it, "width"), optInt(it, "height")));
                    }
                }
            }
            JsonArray bs = o.getAsJsonArray("backdrops");
            if (bs != null) {
                for (JsonElement el : bs) {
                    JsonObject it = asObject(el);
                    String path = it == null ? null : optStr(it, "file_path");
                    if (path != null) {
                        backdrops.add(new AdminVos.ImageCandidateVo(TMDB_BACKDROP + path, "TMDB 横图",
                                optInt(it, "width"), optInt(it, "height")));
                    }
                }
            }
        }
        return new AdminVos.MetadataImagesVo(cap(posters), cap(backdrops));
    }

    private static Integer intOf(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        try {
            return o.get(key).getAsInt();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== HTTP ====================

    private JsonElement getJson(String url, String referer) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) HyperMux/1.0")
                    .header("Accept", "application/json,text/plain,*/*")
                    .GET();
            if (referer != null) {
                b.header("Referer", referer);
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new BizException(ErrorCode.BAD_REQUEST, "元数据服务返回 HTTP " + resp.statusCode());
            }
            String text = resp.body();
            if (text == null || text.isBlank()) {
                throw new BizException(ErrorCode.NOT_FOUND, "元数据服务返回为空");
            }
            return JsonParser.parseString(text);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("元数据请求失败 url={} err={}", url, e.toString());
            throw new BizException(ErrorCode.BAD_REQUEST, "元数据请求失败：" + e.getMessage());
        }
    }

    private static JsonObject asObject(JsonElement el) {
        return el == null || el.isJsonNull() || !el.isJsonObject() ? null : el.getAsJsonObject();
    }

    private static String optStr(JsonObject o, String key) {
        if (o == null || !o.has(key)) {
            return null;
        }
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? null : el.getAsString();
    }

    private static Integer optInt(JsonObject o, String key) {
        String s = optStr(o, key);
        return parseInt(s);
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer yearOf(String date) {
        if (date == null || date.length() < 4) {
            return null;
        }
        return parseInt(date.substring(0, 4));
    }

    private static List<String> strList(JsonArray arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonNull()) {
                    out.add(el.getAsString());
                }
            }
        }
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
