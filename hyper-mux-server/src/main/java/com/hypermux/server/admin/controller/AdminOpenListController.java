package com.hypermux.server.admin.controller;

import com.hypermux.server.admin.service.MovieImportService;
import com.hypermux.server.admin.service.SourceAdminService;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.common.R;
import com.hypermux.server.scrape.OpenListClient;
import com.hypermux.server.scrape.OpenListSessionStore;
import com.hypermux.server.scrape.OpenListSourceEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** 建源向导：连接会话 + 目录浏览 + 目录解析导入（契约 §4.8）。 */
@RestController
@RequestMapping("/api/admin/openlist")
public class AdminOpenListController {

    private final SourceAdminService sourceAdminService;
    private final MovieImportService movieImportService;
    private final OpenListClient client;
    private final OpenListSessionStore sessions;

    public AdminOpenListController(SourceAdminService sourceAdminService, MovieImportService movieImportService,
                                   OpenListClient client, OpenListSessionStore sessions) {
        this.sourceAdminService = sourceAdminService;
        this.movieImportService = movieImportService;
        this.client = client;
        this.sessions = sessions;
    }

    /** 建立连接会话（粘贴 Token / 账密登录 / 沿用已存源，三选一），探测通过才返回。 */
    @PostMapping("/session")
    public R<AdminVos.OpenlistSessionInfo> session(@RequestBody AdminVos.OpenlistSessionReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        String serverUrl;
        String token;
        String probePath = "/";

        if (req.sourceId() != null) {
            OpenListSourceEntity source = sourceAdminService.require(req.sourceId());
            serverUrl = source.getServerUrl();
            token = sourceAdminService.tokenOf(source);
            probePath = source.getRootPath() == null || source.getRootPath().isBlank() ? "/" : source.getRootPath();
        } else {
            serverUrl = req.serverUrl() == null ? "" : req.serverUrl().trim();
            if (serverUrl.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "缺少 serverUrl");
            }
            if (req.apiToken() != null && !req.apiToken().isBlank()) {
                token = req.apiToken().trim();
            } else if (req.username() != null && !req.username().isBlank()) {
                token = client.login(serverUrl, req.username(), req.password(), req.otpCode());
            } else {
                throw new BizException(ErrorCode.BAD_REQUEST, "缺少凭据（apiToken / 用户名密码 / sourceId 三选一）");
            }
        }
        String version = client.version(serverUrl);
        client.list(serverUrl, token, probePath, 1);
        OpenListSessionStore.Session session = sessions.create(serverUrl, token, version);
        return R.ok(new AdminVos.OpenlistSessionInfo(session.sessionId(), version));
    }

    /** 以会话列目录（单页，page 从 1 起）。 */
    @PostMapping("/session/{sessionId}/list")
    public R<AdminVos.OpenlistListResult> list(@PathVariable String sessionId,
                                              @RequestBody(required = false) AdminVos.OpenlistListReq req) {
        OpenListSessionStore.Session session = sessions.require(sessionId);
        String path = req == null ? null : req.path();
        int page = req == null || req.page() == null ? 1 : Math.max(req.page(), 1);
        OpenListClient.ListPage result = client.list(session.serverUrl(), session.token(),
                path == null || path.isBlank() ? "/" : path, page);

        List<AdminVos.OpenlistEntry> list = new ArrayList<>();
        for (OpenListClient.Entry e : result.entries()) {
            list.add(new AdminVos.OpenlistEntry(e.name(), e.path(), e.size(), e.dir(), e.modifiedMs()));
        }
        long total = result.total() > 0 ? result.total()
                : (long) (page - 1) * client.pageSize() + list.size();
        boolean hasMore = (long) page * client.pageSize() < total;
        return R.ok(new AdminVos.OpenlistListResult(list, total, page, hasMore));
    }

    /** 扫描指定数据源的某目录并解析（新建影片「从 OpenList 导入」）。 */
    @PostMapping("/import")
    public R<AdminVos.MovieImportVo> importFolder(@RequestBody AdminVos.ImportFolderReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        return R.ok(movieImportService.importFolder(req.sourceId(), req.path()));
    }
}
