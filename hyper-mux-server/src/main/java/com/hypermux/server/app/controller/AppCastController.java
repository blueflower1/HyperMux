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

/**
 * 远程投屏（契约 §3.13）：手机发起 → 服务器中转 → 接收端（TV / 网页端）轮询取到并确认。
 * 全部端点需普通用户 token；user_id 一律取自 token，请求体里的设备 id 只用于在同账号范围内定位。
 */
@RestController
@RequestMapping("/api/app/cast")
public class AppCastController {

    private final CastService castService;

    public AppCastController(CastService castService) {
        this.castService = castService;
    }

    /** 手机发起投屏（单并发：新请求抢占旧请求）。 */
    @PostMapping("/request")
    public R<CastVos.RequestedVo> request(@RequestBody CastVos.RequestReq req) {
        return R.ok(castService.request(UserContext.userId(), req));
    }

    /** 手机轮询请求结果（每 1s，上限 17s）。 */
    @GetMapping("/request/status")
    public R<CastVos.StatusVo> status(@RequestParam Long id) {
        return R.ok(castService.status(UserContext.userId(), id));
    }

    /** 接收端轮询（每 2s）：待确认请求 + 需要停止的投屏 id。顺带即设备在线心跳。 */
    @GetMapping("/poll")
    public R<CastVos.PollVo> poll(@RequestParam String deviceId) {
        return R.ok(castService.poll(UserContext.userId(), deviceId));
    }

    /** 接收端确认（点「是」或倒计时归零）。 */
    @PostMapping("/accept")
    public R<CastVos.AcceptedVo> accept(@RequestBody CastVos.AcceptReq req) {
        return R.ok(castService.accept(UserContext.userId(), req));
    }

    /** 接收端拒绝（点「否」或按 BACK）。幂等。 */
    @PostMapping("/reject")
    public R<Boolean> reject(@RequestBody CastVos.IdReq req) {
        return R.ok(castService.reject(UserContext.userId(), req));
    }

    /** 取消投屏：手机「取消投屏」或接收端退出投屏播放页。幂等。 */
    @PostMapping("/cancel")
    public R<Boolean> cancel(@RequestBody CastVos.IdReq req) {
        return R.ok(castService.cancel(UserContext.userId(), req));
    }

    /** 本账号当前投屏（两端重启/切后台回来恢复状态）。无则 data=null。 */
    @GetMapping("/current")
    public R<CastVos.CurrentVo> current() {
        return R.ok(castService.current(UserContext.userId()));
    }

    /** 投屏配置（开关 / 确认倒计时 / 轮询节奏）。 */
    @GetMapping("/config")
    public R<CastVos.ConfigVo> config() {
        return R.ok(castService.config());
    }
}
