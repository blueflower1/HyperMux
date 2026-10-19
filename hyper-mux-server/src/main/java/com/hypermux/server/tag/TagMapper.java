package com.hypermux.server.tag;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TagMapper extends BaseMapper<TagEntity> {

    /** 各标签被多少影片绑定（后台列表用）。 */
    @Select("SELECT tag_id AS id, COUNT(*) AS cnt FROM movie_tag GROUP BY tag_id")
    List<CountRow> counts();

    @Data
    class CountRow {
        private Long id;
        private Long cnt;
    }
}
