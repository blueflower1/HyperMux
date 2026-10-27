package com.hypermux.server.cast;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * user_device：账号设备（投屏目标注册表）。
 * "在线"不落库（见 DevicePresenceService 的内存判定），last_seen 仅供展示。
 */
@Data
@TableName("user_device")
public class UserDeviceEntity {

    public static final String PLATFORM_MOBILE = "mobile";
    public static final String PLATFORM_TV = "tv";
    public static final String PLATFORM_WEB = "web";
    public static final String PLATFORM_WINDOWS = "windows";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 客户端安装级 UUID（卸载重装即新 id）。 */
    private String deviceId;

    /** mobile / tv / web / windows。 */
    private String platform;

    private String deviceName;

    private OffsetDateTime lastSeen;

    private OffsetDateTime createTime;

    private OffsetDateTime updateTime;
}
