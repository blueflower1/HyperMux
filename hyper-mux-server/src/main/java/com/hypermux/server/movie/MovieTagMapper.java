package com.hypermux.server.movie;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MovieTagMapper extends BaseMapper<MovieTagEntity> {

    /** 批量取影片标签（按标签 sort_no 排序）。 */
    @Select("<script>"
            + "SELECT mt.movie_id AS movieId, t.id AS tagId, t.name AS name, t.type AS type "
            + "FROM movie_tag mt JOIN tag t ON t.id = mt.tag_id "
            + "WHERE mt.movie_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach> "
            + "ORDER BY t.sort_no ASC, t.id ASC"
            + "</script>")
    List<MovieTagRow> rowsOfMovies(@Param("ids") List<Long> ids);

    @Data
    class MovieTagRow {
        private Long movieId;
        private Long tagId;
        private String name;
        private Integer type;
    }
}
