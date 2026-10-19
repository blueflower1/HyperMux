package com.hypermux.server.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.common.Ts;
import com.hypermux.server.crypt.AesGcmCipher;
import com.hypermux.server.movie.MovieEntity;
import com.hypermux.server.movie.MovieMapper;
import com.hypermux.server.scrape.OpenListClient;
import com.hypermux.server.scrape.OpenListSessionStore;
import com.hypermux.server.scrape.OpenListSourceEntity;
import com.hypermux.server.scrape.SourceMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** OpenList 数据源管理（契约 §4.8）：token AES-256-GCM 加密落库、永不出参；创建/更换时实机探测。 */
@Service
public class SourceAdminService {

    private final SourceMapper sourceMapper;
    private final MovieMapper movieMapper;
    private final AesGcmCipher cipher;
    private final OpenListClient client;
    private final OpenListSessionStore sessions;

    public SourceAdminService(SourceMapper sourceMapper, MovieMapper movieMapper, AesGcmCipher cipher,
                              OpenListClient client, OpenListSessionStore sessions) {
        this.sourceMapper = sourceMapper;
        this.movieMapper = movieMapper;
        this.cipher = cipher;
        this.client = client;
        this.sessions = sessions;
    }

    public List<AdminVos.SourceVo> list() {
        Map<Long, Long> counts = new HashMap<>();
        for (SourceMapper.CountRow row : sourceMapper.movieCounts()) {
            counts.put(row.getId(), row.getCnt() == null ? 0L : row.getCnt());
        }
        List<AdminVos.SourceVo> out = new ArrayList<>();
        for (OpenListSourceEntity s : sourceMapper.selectList(new LambdaQueryWrapper<OpenListSourceEntity>()
                .orderByAsc(OpenListSourceEntity::getId))) {
            out.add(new AdminVos.SourceVo(s.getId(), s.getName(), s.getServerUrl(),
                    s.getRootPath() == null ? "/" : s.getRootPath(),
                    s.getStatus() == null ? 0 : s.getStatus(), counts.getOrDefault(s.getId(), 0L),
                    Ts.milli(s.getCreateTime()), Ts.milli(s.getUpdateTime())));
        }
        return out;
    }

    public AdminVos.IdResultVo create(AdminVos.SourceForm form) {
        String name = requireName(form.name());
        ensureNameFree(name, null);
        String serverUrl = requireServerUrl(form.serverUrl());
        String rootPath = normalizePath(form.rootPath());
        String token = resolveToken(form.apiToken(), form.sessionId());
        probe(serverUrl, token, rootPath);

        OpenListSourceEntity entity = new OpenListSourceEntity();
        entity.setName(name);
        entity.setServerUrl(serverUrl);
        entity.setRootPath(rootPath);
        entity.setApiToken(cipher.encrypt(token));
        entity.setStatus(form.status() == null ? 1 : form.status());
        sourceMapper.insert(entity);
        return new AdminVos.IdResultVo(entity.getId());
    }

    public AdminVos.IdResultVo update(Long id, AdminVos.SourceForm form) {
        OpenListSourceEntity entity = require(id);
        if (form.name() != null) {
            String name = requireName(form.name());
            ensureNameFree(name, id);
            entity.setName(name);
        }
        if (form.serverUrl() != null) {
            entity.setServerUrl(requireServerUrl(form.serverUrl()));
        }
        if (form.rootPath() != null) {
            entity.setRootPath(normalizePath(form.rootPath()));
        }
        if (form.status() != null) {
            entity.setStatus(form.status());
        }
        // 带 apiToken / sessionId = 更换 token（先探测再落库）
        if (notBlank(form.apiToken()) || notBlank(form.sessionId())) {
            String token = resolveToken(form.apiToken(), form.sessionId());
            probe(entity.getServerUrl(), token, entity.getRootPath());
            entity.setApiToken(cipher.encrypt(token));
        }
        sourceMapper.updateById(entity);
        return new AdminVos.IdResultVo(id);
    }

    public AdminVos.SourceDeleteResultVo remove(Long id) {
        require(id);
        long count = movieMapper.selectCount(new LambdaQueryWrapper<MovieEntity>()
                .eq(MovieEntity::getSourceId, id));
        if (count > 0) {
            throw new BizException(ErrorCode.CONFLICT,
                    "该源已刮出 " + count + " 部影片，请改用停用",
                    Map.of("id", id, "movieCount", count));
        }
        sourceMapper.deleteById(id);
        return new AdminVos.SourceDeleteResultVo(id, 0);
    }

    /** 连通性测试：ok=false 时也返回 HTTP 200，原因放 data.message（契约 §4.8）。 */
    public AdminVos.SourceTestResult test(Long id) {
        OpenListSourceEntity entity = require(id);
        try {
            String version = client.version(entity.getServerUrl());
            client.list(entity.getServerUrl(), cipher.decrypt(entity.getApiToken()),
                    entity.getRootPath() == null ? "/" : entity.getRootPath(), 1);
            return new AdminVos.SourceTestResult(true, version, "连接正常");
        } catch (BizException e) {
            return new AdminVos.SourceTestResult(false, null, e.getMessage());
        }
    }

    // ---------------- 内部 ----------------

    public OpenListSourceEntity require(Long id) {
        OpenListSourceEntity entity = id == null ? null : sourceMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据源不存在");
        }
        return entity;
    }

    /** 解密 token（仅服务端内部使用）。 */
    public String tokenOf(OpenListSourceEntity entity) {
        return cipher.decrypt(entity.getApiToken());
    }

    public void probe(String serverUrl, String token, String rootPath) {
        client.version(serverUrl);
        client.list(serverUrl, token, rootPath, 1);
    }

    private String resolveToken(String apiToken, String sessionId) {
        if (notBlank(apiToken)) {
            return apiToken.trim();
        }
        if (notBlank(sessionId)) {
            return sessions.require(sessionId).token();
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "缺少 API Token（apiToken 或 sessionId 二选一）");
    }

    private String requireName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数据源名称不能为空");
        }
        return n;
    }

    private String requireServerUrl(String serverUrl) {
        String u = serverUrl == null ? "" : serverUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "serverUrl 必须是 http(s) 地址");
        }
        return u;
    }

    private static String normalizePath(String path) {
        String p = path == null || path.isBlank() ? "/" : path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private void ensureNameFree(String name, Long excludeId) {
        LambdaQueryWrapper<OpenListSourceEntity> qw = new LambdaQueryWrapper<OpenListSourceEntity>()
                .eq(OpenListSourceEntity::getName, name);
        if (excludeId != null) {
            qw.ne(OpenListSourceEntity::getId, excludeId);
        }
        if (sourceMapper.selectCount(qw) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "已存在同名数据源");
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
