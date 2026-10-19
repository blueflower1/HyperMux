package com.hypermux.server.scrape;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** openlist_source：OpenList 数据源（api_token 经 AES-256-GCM 加密落库）。 */
@Data
@TableName("openlist_source")
public class OpenListSourceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String serverUrl;

    /** 加密后的 token */
    private String apiToken;

    /** 挂载根路径，默认 / */
    private String rootPath;

    private Integer status;

    private OffsetDateTime createTime;

    private OffsetDateTime updateTime;
}