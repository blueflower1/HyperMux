package com.hypermux.server.apprelease;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * app_release：应用自更新发布记录，每个平台（mobile/tv）仅保留最新一条。
 * APK 文件存 HYPERMUX_APK_DIR/{platform}-latest.apk（覆盖式发布，与"仅最新"模型一致）。
 */
@Data
@TableName("app_release")
public class AppReleaseEntity {

    /** mobile / tv */
    @TableId(value = "platform", type = IdType.INPUT)
    private String platform;

    /** Android versionCode（比较用，发布时强制递增） */
    private Integer versionCode;

    /** versionName（展示用） */
    private String versionName;

    /** 磁盘文件名（{platform}-latest.apk） */
    private String fileName;

    private String sha256;

    private Long sizeBytes;

    private String releaseNotes;

    /** 1 强制更新 / 0 可选 */
    private Integer mandatory;

    private OffsetDateTime updateTime;
}
