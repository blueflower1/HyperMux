package com.hypermux.server.app.controller;

import com.hypermux.server.auth.UserContext;
import com.hypermux.server.cast.CastService;
import com.hypermux.server.cast.CastVos;
import com.hypermux.server.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 账号设备注册与列表（契约 §3.13）。 */
@RestController
@RequestMapping("/api/app/devices")
public class AppDeviceController {

    private final CastService castService;

    public AppDeviceController(CastService castService) {
        this.castService = castService;
    }

    /** 幂等 upsert：登录成功 / App 启动恢复会话 / 打开投屏入口时调用。 */
    @PostMapping("/register")
    public R<CastVos.DeviceIdVo> register(@RequestBody CastVos.RegisterReq req) {
        String deviceId = castService.register(UserContext.userId(), req);
        return R.ok(new CastVos.DeviceIdVo(deviceId));
    }

    /** 本账号设备列表（platform 可空 = 全部），带在线与"正被投屏"标记。 */
    @GetMapping
    public R<List<CastVos.DeviceVo>> list(@RequestParam(required = false) String platform) {
        return R.ok(castService.devices(UserContext.userId(), platform));
    }
}
