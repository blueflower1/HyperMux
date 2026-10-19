package com.hypermux.server.app.controller;

import com.hypermux.server.app.vo.TvVos;
import com.hypermux.server.apprelease.AppReleaseEntity;
import com.hypermux.server.apprelease.AppReleaseService;
import com.hypermux.server.common.R;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * 应用自更新（契约 §3.11，匿名端点：未登录/登录态都要能查更新，与 login 同等待遇，见 WebConfig 豁免）。
 * 更新包跟随用户配置的服务器（App 侧 ServerUrlInterceptor 热切换），自托管部署者独立控制版本。
 */
@RestController
@RequestMapping("/api/app/update")
public class AppUpdateController {

    private final AppReleaseService releaseService;

    public AppUpdateController(AppReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    /**
     * 检查更新：仅当该平台最新发布 versionCode &gt; [code] 时返回 200 + 更新信息；
     * 无更新（未发布 / 已最新）→ 204。客户端传自身 versionCode，平台 mobile/tv。
     */
    @GetMapping("/check")
    public ResponseEntity<R<TvVos.UpdateCheckVo>> check(@RequestParam("code") int code,
                                                        @RequestParam(value = "platform", defaultValue = "mobile") String platform) {
        AppReleaseEntity latest = releaseService.latest(platform);
        if (latest == null || latest.getVersionCode() == null || latest.getVersionCode() <= code) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(R.ok(new TvVos.UpdateCheckVo(
                latest.getVersionCode(),
                latest.getVersionName(),
                latest.getSha256(),
                latest.getSizeBytes() == null ? 0L : latest.getSizeBytes(),
                latest.getReleaseNotes(),
                latest.getMandatory() != null && latest.getMandatory() == 1)));
    }

    /** 下载更新包（无 Range 断点，全量流式；失败客户端重下）。 */
    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(
            @RequestParam(value = "platform", defaultValue = "mobile") String platform) {
        AppReleaseService.ReleasePackage pkg = releaseService.resolvePackage(platform);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.android.package-archive"))
                .contentLength(pkg.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pkg.downloadName() + "\"")
                .cacheControl(CacheControl.noCache())
                .body(outputStream -> {
                    try (InputStream in = Files.newInputStream(pkg.path())) {
                        in.transferTo(outputStream);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }
}
