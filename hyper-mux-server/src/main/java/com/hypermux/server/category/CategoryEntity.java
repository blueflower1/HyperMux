package com.hypermux.server.category;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** category：影视类型（电视剧/电影…）。 */
@Data
@TableName("category")
public class CategoryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String coverUrl;

    private String description;

    private Integer sortNo;

    /** 1 可见 / 0 隐藏 */
    private Integer status;

    private OffsetDateTime createTime;

    private OffsetDateTime updateTime;
}