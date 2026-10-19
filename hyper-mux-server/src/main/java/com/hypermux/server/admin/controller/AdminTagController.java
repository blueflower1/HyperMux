package com.hypermux.server.admin.controller;

import com.hypermux.server.admin.service.TagAdminService;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.common.R;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 标签后台接口（契约 §4.6）。 */
@RestController
@RequestMapping("/api/admin/tags")
public class AdminTagController {

    private final TagAdminService service;

    public AdminTagController(TagAdminService service) {
        this.service = service;
    }

    @GetMapping
    public R<Map<String, List<AdminVos.TagVo>>> list(@RequestParam(required = false) Integer type) {
        return R.ok(Map.of("list", service.list(type)));
    }

    @PostMapping
    public R<AdminVos.IdResultVo> create(@RequestBody AdminVos.TagForm form) {
        return R.ok(service.create(form));
    }

    @PutMapping("/{id}")
    public R<AdminVos.IdResultVo> update(@PathVariable Long id, @RequestBody AdminVos.TagForm form) {
        return R.ok(service.update(id, form));
    }

    @DeleteMapping("/{id}")
    public R<AdminVos.UnboundResultVo> remove(@PathVariable Long id) {
        return R.ok(service.remove(id));
    }
}
