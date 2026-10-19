package com.hypermux.server.movie;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** movie：影片（发布后列表可见）。 */
@Data
@TableName("movie")
public class MovieEntity {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_OFF = "OFF";

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_SCRAPE = "SCRAPE";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long categoryId;

    private String title;

    private String originalTitle;

    private Integer year;

    private String coverUrl;

    /** 横版剧照（首页 16:9 信息流用；可为空） */
    private String backdropUrl;

    private BigDecimal rating;

    private String area;

    private String language;

    private String intro;

    /** 检索辅助：片名全拼（小写无分隔，服务层维护，见 PinyinUtils） */
    private String titlePinyin;

    /** 检索辅助：片名首字母（小写，如 灌篮高手 → glgs） */
    private String titleInitials;

    /** 冗余：非 missing 剧集数（服务层维护） */
    private Integer episodeTotal;

    /** DRAFT / PUBLISHED / OFF */
    private String status;

    /** MANUAL / SCRAPE */
    private String sourceType;

    private Long sourceId;

    private String sourcePath;

    private Integer sortNo;

    private OffsetDateTime createTime;

    private OffsetDateTime updateTime;
}