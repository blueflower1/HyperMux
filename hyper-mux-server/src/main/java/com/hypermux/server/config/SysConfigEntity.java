package com.hypermux.server.config;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** sys_config：键值配置（TMDB Key、刮削来源等）。 */
@Data
@TableName("sys_config")
public class SysConfigEntity {

    @TableId
    private String configKey;

    private String configValue;

    private OffsetDateTime updateTime;
}