package com.hypermux.server.admin.controller;

import com.hypermux.server.admin.service.UserAdminService;
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

/** 用户后台接口（契约 §4.7）。 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserAdminService service;

    public AdminUserController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping
    public R<PageResult<AdminVos.UserVo>> page(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Integer role,
                                               @RequestParam(required = false) Integer status) {
        return R.ok(service.page(page, size, keyword, role, status));
    }

    @PostMapping
    public R<AdminVos.UserCreateResultVo> create(@RequestBody AdminVos.UserForm form) {
        AdminVos.IdResultVo created = service.create(form);
        return R.ok(new AdminVos.UserCreateResultVo(created.id(), form.username()));
    }

    @PutMapping("/{id}")
    public R<AdminVos.IdResultVo> update(@PathVariable Long id, @RequestBody AdminVos.UserForm form) {
        return R.ok(service.update(id, form));
    }

    @PostMapping("/{id}/reset-password")
    public R<AdminVos.ResetPasswordResultVo> resetPassword(@PathVariable Long id,
                                                          @RequestBody(required = false) AdminVos.ResetPasswordReq req) {
        return R.ok(service.resetPassword(id, req == null ? null : req.password()));
    }

    @DeleteMapping("/{id}")
    public R<AdminVos.DeleteUserResultVo> remove(@PathVariable Long id) {
        return R.ok(service.remove(id));
    }
}
