package com.hypermux.server.scrape;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SourceMapper extends BaseMapper<OpenListSourceEntity> {

    /** 各源已刮出（关联）的影片数。 */
    @Select("SELECT source_id AS id, COUNT(*) AS cnt FROM movie WHERE source_id IS NOT NULL GROUP BY source_id")
    List<CountRow> movieCounts();

    @Data
    class CountRow {
        private Long id;
        private Long cnt;
    }
}
