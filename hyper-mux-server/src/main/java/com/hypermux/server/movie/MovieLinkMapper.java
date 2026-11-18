package com.hypermux.server.movie;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

/** movie_link：影片关联（「其他季」）。因为写入侧保证成对，读取只需按 movie_id 单侧取。 */
@Mapper
public interface MovieLinkMapper extends BaseMapper<MovieLinkEntity> {

    /**
     * 某片的全部关联（按 sort_no 升序），一趟带出目标影片的基本盘（片名/封面/状态/集数），
     * 免得后台列表与 App 详情各自再 N+1 查一遍 movie。
     *
     * 这里**不过滤可见性**：后台要看住未上架的那条（显灰提示"App 端不会出现"），
     * App 侧的可见性由 MovieQueryService 复用同一条铁律（PUBLISHED + episode_total&gt;0 + 分类启用）收口。
     */
    @Select("SELECT ml.id AS linkId, ml.movie_id AS movieId, ml.related_movie_id AS relatedMovieId, "
            + "ml.relation_type AS relationType, ml.label AS label, ml.sort_no AS sortNo, ml.create_time AS createTime, "
            + "rv.id AS reverseLinkId, rv.label AS reverseLabel, false AS incomingOnly, "
            + "m.id AS targetId, m.title AS targetTitle, m.cover_url AS targetCoverUrl, m.status AS targetStatus, "
            + "m.episode_total AS targetEpisodeTotal, m.year AS targetYear "
            + "FROM movie_link ml JOIN movie m ON m.id = ml.related_movie_id "
            + "LEFT JOIN movie_link rv ON rv.movie_id = ml.related_movie_id AND rv.related_movie_id = ml.movie_id "
            + "WHERE ml.movie_id = #{movieId} "
            + "ORDER BY ml.sort_no ASC, ml.id ASC")
    List<MovieLinkRow> rowsOfMovie(@Param("movieId") Long movieId);

    /**
     * 「只有对方那一侧落了行」的关联（半截数据：A→B 有、B→A 没有）。
     *
     * 为什么要单独捞这一批：关联本该成对写，但只要有一侧的行因为手工改库/历史数据/中途失败缺失，
     * 就会出现"B 的页面看不到 A"这种说不清的怪状态。让两侧都看得见，后台保存一下即自动补齐成对。
     */
    @Select("SELECT ml.id AS linkId, ml.movie_id AS movieId, ml.related_movie_id AS relatedMovieId, "
            + "ml.relation_type AS relationType, ml.label AS label, ml.sort_no AS sortNo, ml.create_time AS createTime, "
            + "NULL::bigint AS reverseLinkId, NULL::text AS reverseLabel, true AS incomingOnly, "
            + "m.id AS targetId, m.title AS targetTitle, m.cover_url AS targetCoverUrl, m.status AS targetStatus, "
            + "m.episode_total AS targetEpisodeTotal, m.year AS targetYear "
            + "FROM movie_link ml JOIN movie m ON m.id = ml.movie_id "
            + "WHERE ml.related_movie_id = #{movieId} "
            + "AND NOT EXISTS (SELECT 1 FROM movie_link own WHERE own.movie_id = #{movieId} "
            + "AND own.related_movie_id = ml.movie_id) "
            + "ORDER BY ml.sort_no ASC, ml.id ASC")
    List<MovieLinkRow> rowsIncomingOnly(@Param("movieId") Long movieId);

    /** 某片的关联条数（后台展示 + 上限校验）。 */
    @Select("SELECT count(1) FROM movie_link WHERE movie_id = #{movieId}")
    long countOfMovie(@Param("movieId") Long movieId);

    /**
     * 本片涉及的所有行（本侧的 + 对方那一侧指向本片的），只取「按季号重排」要用的字段。
     *
     * 和 rowsOfMovie / rowsIncomingOnly 的区别：那两个是为了"这一侧的列表显示什么"而去重过的，
     * 这里要的是**成对的两条都拿到**——重排时每一行的 sort_no 都该等于它自己那一头的对方季号。
     */
    @Select("SELECT ml.id AS linkId, ml.movie_id AS movieId, ml.related_movie_id AS relatedMovieId, "
            + "ml.relation_type AS relationType, ml.sort_no AS sortNo, "
            + "CASE WHEN ml.movie_id = #{movieId} THEN ml.related_movie_id ELSE ml.movie_id END AS targetId "
            + "FROM movie_link ml WHERE ml.movie_id = #{movieId} OR ml.related_movie_id = #{movieId}")
    List<MovieLinkRow> rowsEitherSide(@Param("movieId") Long movieId);

    /** 一条关联的行数据（列名在 Java 侧映射，避免 MyBatis 对 record 的构造歧义）。 */
    @Data
    class MovieLinkRow {
        private Long linkId;
        private Long movieId;
        private Long relatedMovieId;
        private String relationType;
        private String label;
        private Integer sortNo;
        private OffsetDateTime createTime;
        /** 反向那条（对方页面上的行）：让后台在一侧就能把两边的文案都改对 */
        private Long reverseLinkId;
        private String reverseLabel;
        /** true = 只有对方那一侧落了行（本侧缺行），见 rowsIncomingOnly */
        private boolean incomingOnly;
        private Long targetId;
        private String targetTitle;
        private String targetCoverUrl;
        private String targetStatus;
        private Integer targetEpisodeTotal;
        private Integer targetYear;
    }
}
