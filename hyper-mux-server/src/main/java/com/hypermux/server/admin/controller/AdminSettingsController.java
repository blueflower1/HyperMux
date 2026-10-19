package com.hypermux.server.admin.controller;

import com.hypermux.server.admin.service.SettingsService;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 系统设置（契约 §4.11）。 */
@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingsController {

    private final SettingsService settingsService;

    public AdminSettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public R<AdminVos.SettingsVo> get() {
        return R.ok(settingsService.get());
    }

    @PutMapping
    public R<AdminVos.SettingsVo> update(@RequestBody AdminVos.SettingsUpdateReq req) {
        return R.ok(settingsService.update(req));
    }
}
