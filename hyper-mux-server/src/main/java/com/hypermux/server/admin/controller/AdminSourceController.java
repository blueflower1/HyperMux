package com.hypermux.server.admin.controller;

import com.hypermux.server.admin.service.SourceAdminService;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.common.R;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** OpenList 数据源后台接口（契约 §4.8）。 */
@RestController
@RequestMapping("/api/admin/sources")
public class AdminSourceController {

    private final SourceAdminService service;

    public AdminSourceController(SourceAdminService service) {
        this.service = service;
    }

    @GetMapping
    public R<Map<String, List<AdminVos.SourceVo>>> list() {
        return R.ok(Map.of("list", service.list()));
    }

    @PostMapping
    public R<AdminVos.IdResultVo> create(@RequestBody AdminVos.SourceForm form) {
        return R.ok(service.create(form));
    }

    @PutMapping("/{id}")
    public R<AdminVos.IdResultVo> update(@PathVariable Long id, @RequestBody AdminVos.SourceForm form) {
        return R.ok(service.update(id, form));
    }

    @DeleteMapping("/{id}")
    public R<AdminVos.SourceDeleteResultVo> remove(@PathVariable Long id) {
        return R.ok(service.remove(id));
    }

    @PostMapping("/{id}/test")
    public R<AdminVos.SourceTestResult> test(@PathVariable Long id) {
        return R.ok(service.test(id));
    }
}
