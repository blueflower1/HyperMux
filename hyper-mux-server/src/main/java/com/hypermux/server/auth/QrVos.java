package com.hypermux.server.auth;

/** 扫码登录端点 VO。 */
public final class QrVos {

    private QrVos() {
    }

    /** POST /qr/create 返回：qrId 为不透明随机 id，expiresIn 秒。 */
    public record CreateVo(String qrId, long expiresIn) {
    }

    /** GET /qr/status 返回：state = PENDING / SCANNED / CONFIRMED / EXPIRED；session 仅 CONFIRMED 时非空（一次性投递）。 */
    public record StatusVo(String state, SessionVo session) {
    }

    /** POST /qr/scan、POST /qr/confirm 请求体。 */
    public record QrIdReq(String id) {
    }
}
