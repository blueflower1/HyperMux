package com.hypermux.server.video;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** video：剧集（url_kind=OPENLIST 走 openlist_path；REMOTE 走 remote_url）。 */
@Data
@TableName("video")
public class VideoEntity {

    public static final String KIND_OPENLIST = "OPENLIST";
    public static final String KIND_REMOTE = "REMOTE";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long movieId;

    private Integer seasonNo;

    private Integer episodeNo;

    private String title;

    /** 秒 */
    private Integer duration;

    private Long size;

    /** OPENLIST / REMOTE */
    private String urlKind;

    /** 相对数据源 rootPath 的路径 */
    private String openlistPath;

    private String remoteUrl;

    private String sourceFile;

    /** 重扫指纹（用于识别同一文件） */
    private String fingerprint;

    /** 1 = 文件已不存在 */
    private Integer missing;

    private Integer sortNo;

    private OffsetDateTime createTime;

    private OffsetDateTime updateTime;
}