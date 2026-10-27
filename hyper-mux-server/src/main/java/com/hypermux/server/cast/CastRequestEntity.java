package com.hypermux.server.cast;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * cast_request：一次投屏请求的生命周期（不是长连接会话）。
 * 状态机：PENDING → ACCEPTED | REJECTED | EXPIRED | CANCELLED；ACCEPTED → CLOSED。
 * 单并发：每账号同时最多 1 条 PENDING（部分唯一索引 uk_cast_pending_user 兜底）。
 */
@Data
@TableName("cast_request")
public class CastRequestEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_ACCEPTED = "ACCEPTED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_EXPIRED = "EXPIRED";
    public static final String ST_CANCELLED = "CANCELLED";
    public static final String ST_CLOSED = "CLOSED";

    public static final String RS_USER_YES = "user-yes";
    public static final String RS_COUNTDOWN = "countdown";
    public static final String RS_USER_NO = "user-no";
    public static final String RS_SENDER_CANCEL = "sender-cancel";
    public static final String RS_PREEMPTED = "preempted";
    public static final String RS_TIMEOUT = "timeout";
    public static final String RS_TV_EXIT = "tv-exit";
    public static final String RS_WEB_EXIT = "web-exit";
    public static final String RS_DESKTOP_EXIT = "desktop-exit";
    public static final String RS_MOBILE_EXIT = "mobile-exit";
    public static final String RS_IDLE = "idle";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String senderDeviceId;

    private String targetDeviceId;

    private String senderName;

    private String targetName;

    private Long movieId;

    private Long videoId;

    /** 发起时快照：TV 弹窗直接用，不再多跑一次详情接口。 */
    private String movieTitle;

    private String episodeLabel;

    private String coverUrl;

    private Long positionMs;

    private String status;

    /** 过期判定的唯一依据（Caffeine 会被轮询续期，绝不能拿它判过期）。 */
    private OffsetDateTime expireTime;

    private OffsetDateTime decidedTime;

    private OffsetDateTime closedTime;

    /** cancelledIds 一次性下发标记（null = 还没通知过目标设备）。 */
    private OffsetDateTime notifiedAt;

    private String endReason;

    private OffsetDateTime createTime;

    private OffsetDateTime updateTime;

    public boolean isTerminal() {
        return ST_REJECTED.equals(status) || ST_EXPIRED.equals(status)
                || ST_CANCELLED.equals(status) || ST_CLOSED.equals(status);
    }
}
