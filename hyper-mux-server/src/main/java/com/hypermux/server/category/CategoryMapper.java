package com.hypermux.server.category;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CategoryMapper extends BaseMapper<CategoryEntity> {

    /** App 首页：可见分类 + 可见影片数（movieCount=0 由服务层过滤不下发）。 */
    @Select("SELECT c.id AS id, c.name AS name, c.cover_url AS coverUrl, c.sort_no AS sortNo, "
            + "(SELECT COUNT(*) FROM movie m WHERE m.category_id = c.id "
            + " AND m.status = 'PUBLISHED' AND m.episode_total > 0) AS movieCount "
            + "FROM category c WHERE c.status = 1 ORDER BY c.sort_no ASC, c.id ASC")
    List<CategoryCardRow> listVisibleWithCount();

    /** 后台：各分类的影片总数（含 DRAFT/OFF）。 */
    @Select("SELECT category_id AS id, COUNT(*) AS cnt FROM movie WHERE category_id IS NOT NULL GROUP BY category_id")
    List<CountRow> counts();

    @Data
    class CategoryCardRow {
        private Long id;
        private String name;
        private String coverUrl;
        private Integer sortNo;
        private Long movieCount;
    }

    @Data
    class CountRow {
        private Long id;
        private Long cnt;
    }
}
