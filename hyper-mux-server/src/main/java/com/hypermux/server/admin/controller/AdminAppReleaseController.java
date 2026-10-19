package com.hypermux.server.admin.controller;

import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.apprelease.AppReleaseEntity;
import com.hypermux.server.apprelease.AppReleaseService;
import com.hypermux.server.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;

/** 应用自更新发布（契约 §3.11）：multipart 上传 APK + 版本信息，每平台仅保留最新一条。 */
@RestController
@RequestMapping("/api/admin/app-release")
public class AdminAppReleaseController {

    private final AppReleaseService service;

    public AdminAppReleaseController(AppReleaseService service) {
        this.service = service;
    }

    /** 当前发布（?platform=mobile|tv；无发布时 data=null）。 */
    @GetMapping
    public R<AdminVos.AppReleaseVo> current(@RequestParam("platform") String platform) {
        return R.ok(toVo(service.latest(platform)));
    }

    /** 发布新版本（覆盖平台当前包；versionCode 必须大于已发布值）。 */
    @PostMapping
    public R<AdminVos.AppReleaseVo> publish(@RequestParam("file") MultipartFile file,
                                            @RequestParam("platform") String platform,
                                            @RequestParam("versionCode") int versionCode,
                                            @RequestParam("versionName") String versionName,
                                            @RequestParam(value = "releaseNotes", required = false) String releaseNotes,
                                            @RequestParam(value = "mandatory", defaultValue = "false") boolean mandatory) {
        return R.ok(toVo(service.publish(platform, file, versionCode, versionName, releaseNotes, mandatory)));
    }

    private static AdminVos.AppReleaseVo toVo(AppReleaseEntity e) {
        if (e == null) {
            return null;
        }
        OffsetDateTime t = e.getUpdateTime();
        return new AdminVos.AppReleaseVo(
                e.getPlatform(),
                e.getVersionCode(),
                e.getVersionName(),
                e.getSha256(),
                e.getSizeBytes() == null ? 0L : e.getSizeBytes(),
                e.getReleaseNotes(),
                e.getMandatory() != null && e.getMandatory() == 1,
                t == null ? 0L : t.toInstant().toEpochMilli());
    }
}
