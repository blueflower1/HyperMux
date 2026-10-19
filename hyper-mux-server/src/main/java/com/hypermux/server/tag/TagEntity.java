package com.hypermux.server.tag;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** tag：标签（0 类型 / 1 地区 / 2 自定义）。 */
@Data
@TableName("tag")
public class TagEntity {

    public static final int TYPE_GENRE = 0;
    public static final int TYPE_REGION = 1;
    public static final int TYPE_CUSTOM = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Integer type;

    private Integer sortNo;

    private Integer status;

    private OffsetDateTime createTime;
}