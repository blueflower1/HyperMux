package com.hypermux.server.video;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface VideoMapper extends BaseMapper<VideoEntity> {

    /** 各影片的剧集总数 / 缺失数（后台列表用，一次查完避免 N+1）。 */
    @Select("SELECT movie_id AS id, COUNT(*) AS total, "
            + "COALESCE(SUM(CASE WHEN missing = 1 THEN 1 ELSE 0 END), 0) AS missingCnt "
            + "FROM video GROUP BY movie_id")
    List<MovieCountRow> countsByMovie();

    /** 某季当前最大集号（粘贴导入缺省续号用）。 */
    @Select("SELECT COALESCE(MAX(episode_no), 0) FROM video WHERE movie_id = #{movieId} AND season_no = #{seasonNo}")
    int maxEpisodeNo(@Param("movieId") Long movieId, @Param("seasonNo") Integer seasonNo);

    /**
     * 各影片各自代表第几季（库里每季各自是一条 movie 记录，季号只落在 video.season_no 上）。
     * 影片关联（「其他季」）的默认排序就按这个数落：第一季→1、第九季→9。
     */
    @Select("<script>"
            + "SELECT movie_id AS id, MIN(season_no) AS seasonNo FROM video "
            + "WHERE movie_id IN "
            + "<foreach collection='movieIds' item='i' open='(' separator=',' close=')'>#{i}</foreach> "
            + "GROUP BY movie_id"
            + "</script>")
    List<MovieSeasonRow> minSeasonNoByMovie(@Param("movieIds") Collection<Long> movieIds);

    @Data
    class MovieCountRow {
        private Long id;
        private Long total;
        private Long missingCnt;
    }

    @Data
    class MovieSeasonRow {
        private Long id;
        private Integer seasonNo;
    }
}
