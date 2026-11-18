package com.hypermux.server.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.common.Ts;
import com.hypermux.server.movie.MovieEntity;
import com.hypermux.server.movie.MovieLinkEntity;
import com.hypermux.server.movie.MovieLinkMapper;
import com.hypermux.server.movie.MovieMapper;
import com.hypermux.server.video.VideoMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 影片关联（「其他季 / 衍生 / 前传」）的后台维护，契约 §4.13。
 *
 * 为什么必须成对落两条（双向对称）：库里每一季各自是一条 movie 记录，观众从哪一季进来都有可能；
 * 只落 A→B 会出现"第一季页面看得到第二季、第二季页面看不到第一季"的半截状态，
 * 而管理员得记着去两边各加一遍——实际一定会漏。所以这里写入/删除都成对处理，管理员只点一次。
 *
 * 关联只记录"关系"，不复制任何影片数据：App 侧读到的卡片走详情接口现成的卡片装配
 * （{@link com.hypermux.server.app.service.MovieQueryService#relatedMovies}），
 * 未上架 / 0 集的关联由可见性铁律自动挡掉，后台这边则故意显灰显示出来，方便纠正。
 */
@Service
public class MovieLinkAdminService {

    /** 每片关联上限：够放一部长剧的全部季，又不至于把误操作连出来一片 */
    private static final int MAX_LINKS = 50;
    private static final int LABEL_MAX = 64;
    private static final Set<String> VALID_TYPES =
            Set.of(MovieLinkEntity.TYPE_SEASON, MovieLinkEntity.TYPE_SPINOFF, MovieLinkEntity.TYPE_CUSTOM);

    private final MovieMapper movieMapper;
    private final MovieLinkMapper movieLinkMapper;
    private final VideoMapper videoMapper;

    public MovieLinkAdminService(MovieMapper movieMapper, MovieLinkMapper movieLinkMapper, VideoMapper videoMapper) {
        this.movieMapper = movieMapper;
        this.movieLinkMapper = movieLinkMapper;
        this.videoMapper = videoMapper;
    }

    // ==================== 列表 ====================

    public List<AdminVos.MovieLinkVo> list(Long movieId) {
        requireMovie(movieId);
        List<MovieLinkMapper.MovieLinkRow> rows = new ArrayList<>(movieLinkMapper.rowsOfMovie(movieId));
        // 「只有对方那一侧落了行」的也一起摆出来：否则"第一季看得到第九季、第九季看不到第一季"这种
        // 半截状态既没法自证、也没法纠正。这种行在本侧保存一次就会自动补齐成对。
        rows.addAll(movieLinkMapper.rowsIncomingOnly(movieId));
        rows.sort(Comparator.comparingInt((MovieLinkMapper.MovieLinkRow r) -> nz(r.getSortNo()))
                .thenComparingLong(r -> nzL(r.getLinkId())));

        // 对方影片各自是第几季：后台要显示，也是"排序留空"时服务端的默认值来源
        Map<Long, Integer> seasons = seasonOfMovies(idsOf(movieId, rows));

        List<AdminVos.MovieLinkVo> out = new ArrayList<>();
        for (MovieLinkMapper.MovieLinkRow row : rows) {
            boolean oneWay = row.isIncomingOnly();
            // 本侧有行：label = 本侧文案、reverseLabel = 对方侧文案；
            // 本侧没行：本侧文案为空（客户端直接用片名），对方侧文案照样显示出来。
            String ownLabel = oneWay ? null : row.getLabel();
            String otherLabel = oneWay ? row.getLabel() : row.getReverseLabel();
            out.add(new AdminVos.MovieLinkVo(row.getLinkId(), movieId, row.getTargetId(),
                    row.getTargetTitle(), row.getTargetCoverUrl(), row.getTargetYear(), row.getTargetStatus(),
                    nz(row.getTargetEpisodeTotal()), seasons.get(row.getTargetId()), typeOf(row.getRelationType()),
                    ownLabel, otherLabel, nz(row.getSortNo()), Ts.milli(row.getCreateTime()), oneWay));
        }
        return out;
    }

    // ==================== 添加 / 修改（成对） ====================

    /**
     * 添加关联；已存在同一对时改为"以本次提交为准"更新（label / reverseLabel / sortNo / relationType
     * 传了才改，传 null 表示不动），因此前端"编辑这一行"和"新增一条"共用这一个入口，不需要第二个端点。
     */
    @Transactional
    public AdminVos.MovieLinkOpResultVo add(Long movieId, AdminVos.MovieLinkAddReq req) {
        requireMovie(movieId);
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请至少选择一部要关联的影片");
        }
        // 一次提交里同一目标出现两次：只处理第一次，避免自己和自己打架
        Set<Long> targets = new LinkedHashSet<>();
        for (AdminVos.MovieLinkItemReq item : req.items()) {
            if (item != null && item.relatedMovieId() != null) {
                targets.add(item.relatedMovieId());
            }
        }
        if (targets.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请至少选择一部要关联的影片");
        }

        // 「排序留空」的默认值来源：这些影片各自代表第几季（一次查完，别一行一趟）
        Set<Long> wanted = new LinkedHashSet<>(targets);
        wanted.add(movieId);
        Map<Long, Integer> seasons = seasonOfMovies(wanted);

        int added = 0;
        int updated = 0;
        for (Long targetId : targets) {
            if (targetId.equals(movieId)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "不能与自身关联");
            }
            MovieEntity target = movieMapper.selectById(targetId);
            if (target == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "目标影片不存在（可能已被删除）");
            }

            boolean exists = findPair(movieId, targetId) != null;
            // 只有新增才占额度；更新已有条目不应该被上限卡住
            if (!exists) {
                requireRoom(movieId);
                requireRoom(targetId);
            }

            String rawType = itemType(req, targetId);
            // 没带 type 就当"不改类型"，别让"只想改个文案"的提交把类型重置回 season
            String type = rawType == null || rawType.isBlank() ? null : typeOf(rawType);
            String label = cleanLabel(labelOf(req, targetId));
            String reverseLabel = cleanLabel(reverseLabelOf(req, targetId));
            Integer providedSort = sortOf(req, targetId);
            // 排序留空 = 按"对方那一部是第几季"落（第一季→1、第九季→9）；
            // 反向那一行按**本片**的季号：各自的排序归各自那一页管，不能把这一页的数字贴到对方页上去。
            Integer ownSort = providedSort == null ? seasons.get(targetId) : providedSort;
            Integer otherSort = providedSort == null ? seasons.get(movieId) : providedSort;

            upsertPair(movieId, targetId, type, label, ownSort);
            upsertPair(targetId, movieId, type, reverseLabel, otherSort);
            if (exists) {
                updated++;
            } else {
                added++;
            }
        }
        return new AdminVos.MovieLinkOpResultVo(added, updated, 0, (int) movieLinkMapper.countOfMovie(movieId));
    }

    // ==================== 删除（成对） ====================

    @Transactional
    public AdminVos.MovieLinkOpResultVo remove(Long movieId, Long linkId) {
        requireMovie(movieId);
        MovieLinkEntity row = linkId == null ? null : movieLinkMapper.selectById(linkId);
        // 两侧任一条都行：list() 会把"只有对方那一侧落了行"的关联也列出来，那行的 linkId 自然也在这一侧被删掉
        if (row == null || (!movieId.equals(row.getMovieId()) && !movieId.equals(row.getRelatedMovieId()))) {
            throw new BizException(ErrorCode.NOT_FOUND, "关联不存在");
        }
        Long other = movieId.equals(row.getMovieId()) ? row.getRelatedMovieId() : row.getMovieId();
        movieLinkMapper.deleteById(row.getId());
        MovieLinkEntity partner = findPair(other, movieId);
        if (partner != null) {
            movieLinkMapper.deleteById(partner.getId());
        }
        return new AdminVos.MovieLinkOpResultVo(0, 0, 1, (int) movieLinkMapper.countOfMovie(movieId));
    }

    // ==================== 按季号重排 ====================

    /**
     * 把本片涉及的「同剧其他季」关联的 sort_no 归位成「对方那一部是第几季就是几」，两侧一起改：
     * 这一页指向第九季的那行 = 9，第九季那页指向本片的行 = 本片自己的季号。
     * 手动定过别的数字的行会被覆盖（这就是点这个按钮的语义，前端弹窗里讲清楚）。
     * 关系是「衍生 / 自定义」的行不动——那种次序是管理员自己排的。
     */
    @Transactional
    public AdminVos.MovieLinkOpResultVo resort(Long movieId) {
        requireMovie(movieId);
        List<MovieLinkMapper.MovieLinkRow> rows = movieLinkMapper.rowsEitherSide(movieId);
        Map<Long, Integer> seasons = seasonOfMovies(idsOf(movieId, rows));

        int changed = 0;
        for (MovieLinkMapper.MovieLinkRow row : rows) {
            if (!MovieLinkEntity.TYPE_SEASON.equals(row.getRelationType())) {
                continue;
            }
            Integer season = seasons.get(row.getTargetId());
            if (season == null || season.equals(nz(row.getSortNo()))) {
                continue; // 季号未知（没有剧集）与已经对上的，一律不动
            }
            MovieLinkEntity patch = new MovieLinkEntity();
            patch.setId(row.getLinkId());
            patch.setSortNo(season);
            changed += movieLinkMapper.updateById(patch);
        }
        return new AdminVos.MovieLinkOpResultVo(0, changed, 0, (int) movieLinkMapper.countOfMovie(movieId));
    }

    // ==================== 内部 ====================

    /** 影片 → 它自己代表第几季（库里每季各自是一条 movie 记录，季号只落在 video.season_no 上）。 */
    private Map<Long, Integer> seasonOfMovies(Collection<Long> movieIds) {
        Map<Long, Integer> out = new HashMap<>();
        if (movieIds == null || movieIds.isEmpty()) {
            return out;
        }
        for (VideoMapper.MovieSeasonRow row : videoMapper.minSeasonNoByMovie(movieIds)) {
            if (row.getId() != null && row.getSeasonNo() != null && row.getSeasonNo() > 0) {
                out.put(row.getId(), row.getSeasonNo());
            }
        }
        return out;
    }

    private static Set<Long> idsOf(Long movieId, List<MovieLinkMapper.MovieLinkRow> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(movieId);
        for (MovieLinkMapper.MovieLinkRow row : rows) {
            ids.add(row.getTargetId());
        }
        return ids;
    }

    private void requireMovie(Long movieId) {
        MovieEntity m = movieId == null ? null : movieMapper.selectById(movieId);
        if (m == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "影片不存在");
        }
    }

    private void requireRoom(Long movieId) {
        if (movieLinkMapper.countOfMovie(movieId) >= MAX_LINKS) {
            throw new BizException(ErrorCode.BAD_REQUEST, "关联数量已达上限 " + MAX_LINKS + " 条");
        }
    }

    private MovieLinkEntity findPair(Long movieId, Long targetId) {
        return movieLinkMapper.selectOne(new LambdaQueryWrapper<MovieLinkEntity>()
                .eq(MovieLinkEntity::getMovieId, movieId)
                .eq(MovieLinkEntity::getRelatedMovieId, targetId)
                .last("LIMIT 1"));
    }

    /**
     * 存在则按需更新（null 字段保持原值），不存在则插入。
     * 排序默认值（对方是第几季）由调用方算好传进来，这里只兜"季号取不到"的情况：接在当前最大值后面 +10。
     */
    private void upsertPair(Long movieId, Long targetId, String type, String label, Integer sortNo) {
        MovieLinkEntity row = findPair(movieId, targetId);
        if (row == null) {
            MovieLinkEntity fresh = new MovieLinkEntity();
            fresh.setMovieId(movieId);
            fresh.setRelatedMovieId(targetId);
            // 列是 NOT NULL：新行没有类型就落 season
            fresh.setRelationType(type == null ? MovieLinkEntity.TYPE_SEASON : type);
            fresh.setLabel(label);
            fresh.setSortNo(sortNo == null ? nextSortNo(movieId) : sortNo);
            movieLinkMapper.insert(fresh);
            return;
        }
        if (type != null) {
            row.setRelationType(type);
        }
        if (label != null) {
            row.setLabel(label);
        }
        if (sortNo != null) {
            row.setSortNo(sortNo);
        }
        movieLinkMapper.updateById(row);
    }

    /** 新条目的默认排位：接在当前最大 sort_no 后面留 10 的空隙，方便以后往中间插 */
    private int nextSortNo(Long movieId) {
        int max = 0;
        for (MovieLinkMapper.MovieLinkRow row : movieLinkMapper.rowsOfMovie(movieId)) {
            max = Math.max(max, nz(row.getSortNo()));
        }
        return max + 10;
    }

    private static String typeOf(String raw) {
        String t = raw == null ? "" : raw.trim();
        return VALID_TYPES.contains(t) ? t : MovieLinkEntity.TYPE_SEASON;
    }

    private static String cleanLabel(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.length() > LABEL_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "关联文案最长 " + LABEL_MAX + " 字");
        }
        return s;
    }

    private static AdminVos.MovieLinkItemReq itemFor(AdminVos.MovieLinkAddReq req, Long targetId) {
        for (AdminVos.MovieLinkItemReq item : req.items()) {
            if (item != null && targetId.equals(item.relatedMovieId())) {
                return item;
            }
        }
        return null;
    }

    private static String itemType(AdminVos.MovieLinkAddReq req, Long targetId) {
        AdminVos.MovieLinkItemReq item = itemFor(req, targetId);
        return item == null ? null : item.relationType();
    }

    private static String labelOf(AdminVos.MovieLinkAddReq req, Long targetId) {
        AdminVos.MovieLinkItemReq item = itemFor(req, targetId);
        return item == null ? null : item.label();
    }

    private static String reverseLabelOf(AdminVos.MovieLinkAddReq req, Long targetId) {
        AdminVos.MovieLinkItemReq item = itemFor(req, targetId);
        return item == null ? null : item.reverseLabel();
    }

    private static Integer sortOf(AdminVos.MovieLinkAddReq req, Long targetId) {
        AdminVos.MovieLinkItemReq item = itemFor(req, targetId);
        return item == null ? null : item.sortNo();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nzL(Long v) {
        return v == null ? 0L : v;
    }
}
