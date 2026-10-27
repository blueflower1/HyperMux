package com.hypermux.server.cast;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * hypermux.cast.*：远程投屏（M7）运行参数。
 * 其中 enabled / confirmSeconds 可被 sys_config（管理后台「系统设置」）覆盖，改完即时生效不用重启。
 */
@ConfigurationProperties(prefix = "hypermux.cast")
public class CastProperties {

    /** 投屏总开关（sys_config.cast_enabled 优先）。 */
    private boolean enabled = true;

    /** 投屏请求有效期：TV 弹窗 6s + 轮询 2s + 余量。过期判定只认落库的 expire_time。 */
    private Duration requestTtl = Duration.ofSeconds(15);

    /**
     * ACCEPTED 记录自动 CLOSED 的兜底时长。
     * 取 3 小时而不是 30 分钟：投屏成功后 TV 可能连看一部 2 小时的电影，
     * 提前关闭会让手机的「已投送至」胶囊与设备列表的 casting 标记凭空消失。
     */
    private Duration acceptedTtl = Duration.ofHours(3);

    /** TV 确认弹窗倒计时秒数（管理后台可覆盖，取值 3~15）。 */
    private int confirmSeconds = 6;

    /** 同一设备两次 poll 的最小间隔（护栏：客户端 bug 不至于把 DB 打爆）。 */
    private long pollMinIntervalMs = 1000L;

    /** cancelledIds 的下发窗口：只通知这个窗口内被终态化、且尚未通知过的请求。 */
    private Duration notifyWindow = Duration.ofMinutes(2);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getRequestTtl() {
        return requestTtl;
    }

    public void setRequestTtl(Duration requestTtl) {
        this.requestTtl = requestTtl;
    }

    public Duration getAcceptedTtl() {
        return acceptedTtl;
    }

    public void setAcceptedTtl(Duration acceptedTtl) {
        this.acceptedTtl = acceptedTtl;
    }

    public int getConfirmSeconds() {
        return confirmSeconds;
    }

    public void setConfirmSeconds(int confirmSeconds) {
        this.confirmSeconds = confirmSeconds;
    }

    public long getPollMinIntervalMs() {
        return pollMinIntervalMs;
    }

    public void setPollMinIntervalMs(long pollMinIntervalMs) {
        this.pollMinIntervalMs = pollMinIntervalMs;
    }

    public Duration getNotifyWindow() {
        return notifyWindow;
    }

    public void setNotifyWindow(Duration notifyWindow) {
        this.notifyWindow = notifyWindow;
    }
}
