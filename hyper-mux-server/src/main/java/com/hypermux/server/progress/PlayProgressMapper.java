package com.hypermux.server.progress;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface PlayProgressMapper extends BaseMapper<PlayProgressEntity> {

    /** 历史记录（**同一影片只保留最近观看的那一条**，再按该记录时间降序；仅可见影片）。写法与 continueWatching 一致。 */
    @Select("SELECT * FROM ("
            + "  SELECT DISTINCT ON (p.movie_id) p.video_id AS videoId, p.movie_id AS movieId, m.title AS title, "
            + "  m.cover_url AS coverUrl, m.backdrop_url AS backdropUrl, c.name AS categoryName, "
            + "  v.season_no AS seasonNo, v.episode_no AS episodeNo, p.position_ms AS positionMs, "
            + "  COALESCE(p.duration_ms, 0) AS durationMs, COALESCE(p.finished, 0) AS finished, "
            + "  p.update_time AS updateTime "
            + "  FROM play_progress p "
            + "  JOIN movie m ON m.id = p.movie_id "
            + "  JOIN video v ON v.id = p.video_id "
            + "  LEFT JOIN category c ON c.id = m.category_id "
            + "  WHERE p.user_id = #{userId} AND m.status = 'PUBLISHED' "
            + "  ORDER BY p.movie_id, p.update_time DESC"
            + ") t ORDER BY t.updateTime DESC LIMIT #{limit}")
    List<HistoryRow> history(@Param("userId") Long userId, @Param("limit") int limit);

    /** 继续观看：同一影片只保留最近观看的那一集，再按该记录时间降序取前 N。 */
    @Select("SELECT * FROM ("
            + "  SELECT DISTINCT ON (p.movie_id) p.movie_id AS movieId, m.title AS title, "
            + "  m.cover_url AS coverUrl, m.backdrop_url AS backdropUrl, c.name AS categoryName, v.id AS lastVideoId, "
            + "  v.season_no AS seasonNo, v.episode_no AS episodeNo, p.position_ms AS positionMs, "
            + "  COALESCE(p.duration_ms, 0) AS durationMs, p.update_time AS updateTime "
            + "  FROM play_progress p "
            + "  JOIN movie m ON m.id = p.movie_id "
            + "  JOIN video v ON v.id = p.video_id "
            + "  LEFT JOIN category c ON c.id = m.category_id "
            + "  WHERE p.user_id = #{userId} AND m.status = 'PUBLISHED' "
            + "    AND COALESCE(p.finished, 0) = 0 AND m.episode_total > 0 "
            + "    AND (c.status IS NULL OR c.status = 1) "
            + "  ORDER BY p.movie_id, p.update_time DESC"
            + ") t ORDER BY t.updateTime DESC LIMIT #{limit}")
    List<ContinueRow> continueWatching(@Param("userId") Long userId, @Param("limit") int limit);

    /** 单集断点（续播位置）。 */
    @Select("SELECT p.video_id AS videoId, v.season_no AS seasonNo, v.episode_no AS episodeNo, "
            + "p.position_ms AS positionMs, COALESCE(p.duration_ms, 0) AS durationMs, "
            + "COALESCE(p.finished, 0) AS finished "
            + "FROM play_progress p JOIN video v ON v.id = p.video_id "
            + "WHERE p.user_id = #{userId} AND p.video_id = #{videoId}")
    VideoProgressRow videoProgress(@Param("userId") Long userId, @Param("videoId") Long videoId);

    /** 卡片用：批量取用户在这些影片上的最新断点。 */
    @Select("<script>"
            + "SELECT p.movie_id AS movieId, p.video_id AS videoId, v.season_no AS seasonNo, "
            + "v.episode_no AS episodeNo, p.position_ms AS positionMs, COALESCE(p.duration_ms, 0) AS durationMs, "
            + "p.update_time AS updateTime "
            + "FROM play_progress p JOIN video v ON v.id = p.video_id "
            + "WHERE p.user_id = #{userId} AND p.movie_id IN "
            + "<foreach collection='movieIds' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<CardProgressRow> cardProgress(@Param("userId") Long userId, @Param("movieIds") List<Long> movieIds);

    /** 剧集列表用：批量取用户在这些剧集上的断点。 */
    @Select("<script>"
            + "SELECT p.video_id AS videoId, p.position_ms AS positionMs, "
            + "COALESCE(p.duration_ms, 0) AS durationMs, COALESCE(p.finished, 0) AS finished "
            + "FROM play_progress p WHERE p.user_id = #{userId} AND p.video_id IN "
            + "<foreach collection='videoIds' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<VideoProgressRow> videoProgressBatch(@Param("userId") Long userId,
                                             @Param("videoIds") List<Long> videoIds);

    @Data
    class HistoryRow {
        private Long videoId;
        private Long movieId;
        private String title;
        private String coverUrl;
        private String backdropUrl;
        private String categoryName;
        private Integer seasonNo;
        private Integer episodeNo;
        private Long positionMs;
        private Long durationMs;
        private Integer finished;
        private OffsetDateTime updateTime;
    }

    @Data
    class ContinueRow {
        private Long movieId;
        private String title;
        private String coverUrl;
        private String backdropUrl;
        private String categoryName;
        private Long lastVideoId;
        private Integer seasonNo;
        private Integer episodeNo;
        private Long positionMs;
        private Long durationMs;
        private OffsetDateTime updateTime;
    }

    @Data
    class VideoProgressRow {
        private Long videoId;
        private Integer seasonNo;
        private Integer episodeNo;
        private Long positionMs;
        private Long durationMs;
        private Integer finished;
    }

    @Data
    class CardProgressRow {
        private Long movieId;
        private Long videoId;
        private Integer seasonNo;
        private Integer episodeNo;
        private Long positionMs;
        private Long durationMs;
        private OffsetDateTime updateTime;
    }
}
