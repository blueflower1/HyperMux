package com.hypermux.server.progress;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** play_progress：用户播放断点（用户+剧集维度）。 */
@Data
@TableName("play_progress")
public class PlayProgressEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long movieId;

    private Long videoId;

    private Long positionMs;

    private Long durationMs;

    /** 1 = 已看完 */
    private Integer finished;

    private OffsetDateTime updateTime;
}