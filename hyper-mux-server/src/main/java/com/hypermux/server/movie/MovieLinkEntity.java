package com.hypermux.server.movie;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * movie_link：影片之间的手动关联（「金装律师 第一季」↔「第二季」这类，也容纳衍生/前传）。
 *
 * 为什么不是"给季加字段"：季只挂在 {@code video.season_no} 上，movie 本身没有季概念
 * （见 sql/schema.sql 的 movie / video 表），各季在库里就是各自独立的 movie 记录，
 * 所以关联只能建在影片与影片之间。
 *
 * 语义是**双向对称**：服务层（MovieLinkAdminService）每次写入都成对落两条并成对删除，
 * 于是任意一侧读取只需 {@code movie_id = ?}，管理员也只需要点一次。
 */
@Data
@TableName("movie_link")
public class MovieLinkEntity {

    public static final String TYPE_SEASON = "season";
    public static final String TYPE_SPINOFF = "spinoff";
    public static final String TYPE_CUSTOM = "custom";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long movieId;

    private Long relatedMovieId;

    /** season / spinoff / custom */
    private String relationType;

    /** 卡片文案（如「第二季」）；null = 客户端直接用目标片名 */
    private String label;

    private Integer sortNo;

    private OffsetDateTime createTime;
}
