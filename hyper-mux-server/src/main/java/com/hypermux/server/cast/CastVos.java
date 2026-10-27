package com.hypermux.server.cast;

import java.util.List;

/** 投屏与设备端点的 req/vo（契约 §3.13）。时间一律毫秒。 */
public final class CastVos {

    private CastVos() {
    }

    // ---------------- 设备 ----------------

    public record RegisterReq(String deviceId, String platform, String deviceName) {
    }

    /** POST devices/register 的应答（safeApiCall 不接受 null data，因此包一层而不是直接回 String）。 */
    public record DeviceIdVo(String deviceId) {
    }

    public record DeviceVo(String deviceId, String platform, String deviceName,
                           boolean online, long lastSeen, boolean casting) {
    }

    // ---------------- 投屏请求 ----------------

    public record RequestReq(String targetDeviceId, String senderDeviceId,
                             Long movieId, Long videoId, Long positionMs) {
    }

    /** POST cast/request 的应答。 */
    public record RequestedVo(long requestId, long expiresIn, String targetName) {
    }

    /** GET cast/request/status 的应答（手机轮询）。 */
    public record StatusVo(long id, String status, String targetName, String endReason) {
    }

    /** GET cast/poll 的 pending 载荷（TV 弹窗直接渲染，不需要再查详情）。 */
    public record PendingVo(long id, String senderDeviceId, String senderName,
                            long movieId, long videoId, String movieTitle, String episodeLabel,
                            String coverUrl, long positionMs, long expireTime, int confirmSeconds) {
    }

    /** GET cast/poll 的应答（TV 轮询）。 */
    public record PollVo(PendingVo pending, List<Long> cancelledIds, int confirmSeconds) {
    }

    /** POST cast/accept 的 req（reason = user-yes | countdown，缺省 user-yes）。 */
    public record AcceptReq(Long id, String reason) {
    }

    /** POST cast/accept 的应答（TV 据此导航到播放页并 seek 到 positionMs）。 */
    public record AcceptedVo(long id, long movieId, long videoId, long positionMs,
                             String movieTitle, String episodeLabel) {
    }

    /** POST cast/reject 与 cast/cancel 的 req（cancel 可带 reason = sender-cancel | tv-exit）。 */
    public record IdReq(Long id, String reason) {
    }

    /**
     * GET cast/current 的应答（两端重启/切后台回来恢复投屏状态）。
     * 外层包一层：data 恒非空，"没有投屏"表达为 active=null
     * （客户端 safeApiCall 把 data=null 当作异常，不能拿它表达"无记录"）。
     */
    public record CurrentVo(CastBriefVo active) {
    }

    public record CastBriefVo(long id, String status, String senderDeviceId, String targetDeviceId,
                              String senderName, String targetName, long movieId, long videoId,
                              String movieTitle, String episodeLabel, String coverUrl,
                              long positionMs, long startedTime) {
    }

    /** GET cast/config 的应答（两端投屏入口打开时各取一次）。 */
    public record ConfigVo(boolean enabled, int confirmSeconds, long requestTtlSeconds, long pollIntervalMs) {
    }
}
