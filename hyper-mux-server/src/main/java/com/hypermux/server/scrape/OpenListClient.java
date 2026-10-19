package com.hypermux.server.scrape;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * OpenList（AList 系）客户端。实机口径（契约 §4.8）：
 * <ul>
 *   <li>v4 错误也返回 HTTP 200，一律以 body {@code code} 判定（200 成功 / 401 token 无效 / 403 guest 禁用 / 404 目录不存在）</li>
 *   <li>鉴权头为 {@code Authorization: <裸 token>}，**无 Bearer 前缀**</li>
 *   <li>部分挂载源条目不带 path 字段 → 用「请求父路径 + name」兜底拼绝对路径</li>
 * </ul>
 */
@Slf4j
@Component
public class OpenListClient {

    public record Entry(String name, String path, long size, boolean dir, long modifiedMs) {
    }

    public record ListPage(List<Entry> entries, long total) {
    }

    private final HttpClient http;
    private final int pageSize;

    public OpenListClient(@org.springframework.beans.factory.annotation.Value("${hypermux.openlist.page-size:100}") int pageSize) {
        this.pageSize = pageSize;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public int pageSize() {
        return pageSize;
    }

    /** 免鉴权探测：取 OpenList 版本号。 */
    public String version(String serverUrl) {
        JsonObject body = getJson(trimSlash(serverUrl) + "/api/public/settings", null);
        requireOk(body, "读取站点信息");
        JsonObject data = asObject(body.get("data"));
        JsonElement v = data == null ? null : data.get("version");
        return v == null || v.isJsonNull() ? "" : v.getAsString();
    }

    /** 带 token 列目录（page 从 1 起）；对 123pan 等挂载源的「深层目录首次访问」自动预热父目录并重试。 */
    public ListPage list(String serverUrl, String token, String path, int page) {
        String abs = normalize(path);
        try {
            return listOnce(serverUrl, token, abs, page);
        } catch (BizException first) {
            // 现象：failed get parent list: failed get dir: object not found
            // 原因：直接跳进未被上游缓存的深层目录；先列一次父目录预热缓存后重试即可
            String parent = parentOf(abs);
            if (parent == null) {
                throw first;
            }
            log.info("列目录首次失败，预热父目录后重试 path={} 原因={}", abs, first.getMessage());
            try {
                listOnce(serverUrl, token, parent, 1);
            } catch (Exception ignore) {
                // 预热失败不致命，继续重试目标目录
            }
            return listOnce(serverUrl, token, abs, page);
        }
    }

    private ListPage listOnce(String serverUrl, String token, String abs, int page) {
        String url = trimSlash(serverUrl) + "/api/fs/list?path=" + enc(abs)
                + "&page=" + Math.max(page, 1) + "&per_page=" + pageSize + "&refresh=true";
        JsonObject body = getJson(url, token);
        requireOk(body, "列目录");
        JsonObject data = asObject(body.get("data"));
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        if (data != null) {
            if (data.has("total") && !data.get("total").isJsonNull()) {
                total = data.get("total").getAsLong();
            }
            JsonArray content = data.getAsJsonArray("content");
            if (content != null) {
                for (JsonElement el : content) {
                    JsonObject o = asObject(el);
                    if (o == null) {
                        continue;
                    }
                    String name = str(o, "name");
                    String entryPath = str(o, "path");
                    if (entryPath == null || entryPath.isBlank()) {
                        entryPath = joinPath(abs, name);
                    }
                    entries.add(new Entry(name, entryPath, num(o, "size"),
                            o.has("is_dir") && !o.get("is_dir").isJsonNull() && o.get("is_dir").getAsBoolean(),
                            parseTimeMs(str(o, "modified"))));
                }
            }
        }
        return new ListPage(entries, total);
    }

    private static String parentOf(String abs) {
        int idx = abs.lastIndexOf('/');
        if (idx <= 0) {
            return null;
        }
        return abs.substring(0, idx);
    }

    /** 账密登录换 token（部分实例有验证码/2FA，会直接拒绝，文案透传）。 */
    public String login(String serverUrl, String username, String password, String otpCode) {
        JsonObject req = new JsonObject();
        req.addProperty("username", username);
        req.addProperty("password", password);
        if (otpCode != null && !otpCode.isBlank()) {
            req.addProperty("otp_code", otpCode);
        }
        JsonObject body = postJson(trimSlash(serverUrl) + "/api/auth/login", req.toString(), null);
        JsonObject data = asObject(body.get("data"));
        String token = data == null ? null : str(data, "token");
        if (token == null || token.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "登录失败 code=" + code(body));
        }
        return token;
    }

    /** 建目录（幂等：已存在返回 code=200 或 409，均视为可继续）。 */
    public void mkdir(String serverUrl, String token, String dirPath) {
        JsonObject req = new JsonObject();
        req.addProperty("path", normalize(dirPath));
        JsonObject body = postJson(trimSlash(serverUrl) + "/api/fs/mkdir", req.toString(), token);
        int code = code(body);
        if (code != 200 && code != 409) {
            throw new BizException(ErrorCode.BAD_REQUEST, "连不通 OpenList: 建目录失败 code=" + code);
        }
    }

    /**
     * 上传文件（OpenList v3/v4：{@code PUT /api/fs/put}）：
     * 绝对路径放在 {@code File-Path} 头里（整体 URL-encode），body 为原始字节。
     */
    public void upload(String serverUrl, String token, String absFilePath, byte[] bytes, String contentType) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(trimSlash(serverUrl) + "/api/fs/put"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", token == null ? "" : token.trim())
                    .header("File-Path", enc(normalize(absFilePath)))
                    .header("Content-Type", contentType == null || contentType.isBlank()
                            ? "application/octet-stream" : contentType)
                    .header("As-Task", "false")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String text = resp.body();
            JsonObject body = text == null || text.isBlank() ? null : JsonParser.parseString(text).getAsJsonObject();
            int code = code(body);
            if (code != 200) {
                String reason = body == null ? "HTTP " + resp.statusCode() : str(body, "message");
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "连不通 OpenList: 上传失败 code=" + code + (reason == null ? "" : " " + reason));
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OpenList 上传失败 path={} err={}", absFilePath, e.toString());
            throw new BizException(ErrorCode.BAD_REQUEST, "连不通 OpenList: " + shortErr(e));
        }
    }

    // ---------------- 内部 ----------------

    private void requireOk(JsonObject body, String what) {
        int code = code(body);
        if (code == 200) {
            return;
        }
        String reason = str(body, "message");
        if (reason == null || reason.isBlank()) {
            reason = switch (code) {
                case 401 -> "token 无效";
                case 403 -> "guest 访问被禁用";
                case 404 -> "目录不存在";
                default -> "HTTP code=" + code;
            };
        } else {
            reason = reason + "(code=" + code + ")";
        }
        String hint = translate(reason);
        throw new BizException(ErrorCode.BAD_REQUEST,
                "连不通 OpenList: " + what + "失败，" + (hint != null ? hint : reason));
    }

    /** 把上游常见错误翻译成可直接照做的中文提示。 */
    private static String translate(String reason) {
        if (reason == null) {
            return null;
        }
        String r = reason.toLowerCase(Locale.ROOT);
        if (r.contains("object not found")) {
            return "该目录在网盘/OpenList 侧不可读（object not found）—— 多半是目录已失效、被改名或上游缓存未刷新；"
                    + "可在 OpenList 网页端刷新该目录后重试，或改选其他目录";
        }
        if (r.contains("failed get parent list")) {
            return "上游父目录缓存未就绪（failed get parent list）—— 请点「重试」，或先在 OpenList 网页端打开上一层目录";
        }
        return null;
    }

    private static int code(JsonObject body) {
        if (body == null || !body.has("code") || body.get("code").isJsonNull()) {
            return -1;
        }
        return body.get("code").getAsInt();
    }

    private JsonObject getJson(String url, String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .GET();
            if (token != null && !token.isBlank()) {
                b.header("Authorization", token.trim());
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String text = resp.body();
            if (text == null || text.isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "连不通 OpenList: HTTP " + resp.statusCode());
            }
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OpenList 请求失败 url={} err={}", url, e.toString());
            throw new BizException(ErrorCode.BAD_REQUEST, "连不通 OpenList: " + shortErr(e));
        }
    }

    private JsonObject postJson(String url, String json, String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (token != null && !token.isBlank()) {
                b.header("Authorization", token.trim());
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String text = resp.body();
            if (text == null || text.isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "连不通 OpenList: HTTP " + resp.statusCode());
            }
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "连不通 OpenList: " + shortErr(e));
        }
    }

    private static String shortErr(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    static JsonObject asObject(JsonElement el) {
        return el == null || el.isJsonNull() || !el.isJsonObject() ? null : el.getAsJsonObject();
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? null : el.getAsString();
    }

    private static long num(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? 0L : el.getAsLong();
    }

    private static long parseTimeMs(String iso) {
        if (iso == null || iso.isBlank()) {
            return 0L;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (Exception e) {
            try {
                return java.time.Instant.parse(iso).toEpochMilli();
            } catch (Exception ignored) {
                return 0L;
            }
        }
    }

    private static String trimSlash(String s) {
        String v = s == null ? "" : s.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private static String normalize(String path) {
        String p = path == null || path.isBlank() ? "/" : path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String joinPath(String parent, String name) {
        String base = normalize(parent);
        String n = name == null ? "" : name;
        return base.equals("/") ? "/" + n : base + "/" + n;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
