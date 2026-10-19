package com.hypermux.server.movie;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** movie_tag：影片-标签关联（复合主键）。 */
@Data
@TableName("movie_tag")
public class MovieTagEntity {

    private Long movieId;

    private Long tagId;
}