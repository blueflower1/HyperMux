package com.hypermux.server.admin.controller;

import com.hypermux.server.admin.service.CategoryAdminService;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.common.PageResult;
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

/** 影视类型后台接口（契约 §4.3）。 */
@RestController
@RequestMapping("/api/admin/categories")
public class AdminCategoryController {

    private final CategoryAdminService service;

    public AdminCategoryController(CategoryAdminService service) {
        this.service = service;
    }

    @GetMapping
    public R<PageResult<AdminVos.CategoryVo>> page(@RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size,
                                                   @RequestParam(required = false) String keyword) {
        return R.ok(service.page(page, size, keyword));
    }

    @PostMapping
    public R<AdminVos.IdResultVo> create(@RequestBody AdminVos.CategoryForm form) {
        return R.ok(service.create(form));
    }

    @PutMapping("/{id}")
    public R<AdminVos.IdResultVo> update(@PathVariable Long id, @RequestBody AdminVos.CategoryForm form) {
        return R.ok(service.update(id, form));
    }

    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        service.remove(id);
        return R.ok();
    }
}
