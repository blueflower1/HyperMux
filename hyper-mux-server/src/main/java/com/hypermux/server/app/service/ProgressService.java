package com.hypermux.server.app.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hypermux.server.app.vo.TvVos;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.common.Ts;
import com.hypermux.server.progress.PlayProgressEntity;
import com.hypermux.server.progress.PlayProgressMapper;
import com.hypermux.server.video.VideoEntity;
import com.hypermux.server.video.VideoMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** 断点上报 / 看完移除 / 历史（契约 §3.7/§3.8/§3.9）。 */
@Service
public class ProgressService {

    /** 距片尾 30s 内视为看完（契约 §3.8）。 */
    private static final long FINISH_THRESHOLD_MS = 30_000L;

    private final PlayProgressMapper progressMapper;
    private final VideoMapper videoMapper;

    public ProgressService(PlayProgressMapper progressMapper, VideoMapper videoMapper) {
        this.progressMapper = progressMapper;
        this.videoMapper = videoMapper;
    }

    public TvVos.ProgressVo report(Long userId, TvVos.ProgressReq req) {
        if (req == null || req.videoId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "videoId 不能为空");
        }
        if (req.positionMs() == null || req.positionMs() < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "positionMs 必须 >= 0");
        }
        VideoEntity video = videoMapper.selectById(req.videoId());
        if (video == null || (video.getMissing() != null && video.getMissing() == 1)) {
            throw new BizException(ErrorCode.NOT_FOUND, "剧集不存在或文件已丢失");
        }

        long position = req.positionMs();
        Long duration = req.durationMs();
        int finished = req.finished() != null && req.finished() == 1 ? 1 : 0;
        if (finished == 0 && duration != null && duration > 0 && position >= duration - FINISH_THRESHOLD_MS) {
            finished = 1;
        }

        PlayProgressEntity existing = progressMapper.selectOne(new LambdaQueryWrapper<PlayProgressEntity>()
                .eq(PlayProgressEntity::getUserId, userId)
                .eq(PlayProgressEntity::getVideoId, req.videoId()));

        OffsetDateTime now = OffsetDateTime.now();
        PlayProgressEntity entity = existing == null ? new PlayProgressEntity() : existing;
        entity.setUserId(userId);
        entity.setMovieId(video.getMovieId());
        entity.setVideoId(video.getId());
        entity.setPositionMs(position);
        if (duration != null && duration > 0) {
            entity.setDurationMs(duration);
        }
        entity.setFinished(finished);
        entity.setUpdateTime(now);

        if (existing == null) {
            progressMapper.insert(entity);
        } else {
            progressMapper.updateById(entity);
        }
        return new TvVos.ProgressVo(entity.getVideoId(), position, finished, Ts.milli(now));
    }

    public TvVos.FinishVo finish(Long userId, TvVos.FinishReq req) {
        if (req == null || req.videoId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "videoId 不能为空");
        }
        String action = (req.action() == null || req.action().isBlank()) ? "finish" : req.action().trim();
        PlayProgressEntity existing = progressMapper.selectOne(new LambdaQueryWrapper<PlayProgressEntity>()
                .eq(PlayProgressEntity::getUserId, userId)
                .eq(PlayProgressEntity::getVideoId, req.videoId()));
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "无此播放记录");
        }
        if ("remove".equals(action)) {
            progressMapper.deleteById(existing.getId());
        } else {
            existing.setFinished(1);
            existing.setUpdateTime(OffsetDateTime.now());
            progressMapper.updateById(existing);
        }
        return new TvVos.FinishVo(req.videoId(), action);
    }

    public List<TvVos.HistoryVo> history(Long userId, Integer limit) {
        int n = limit == null ? 20 : Math.min(Math.max(limit, 1), 50);
        List<TvVos.HistoryVo> out = new ArrayList<>();
        for (PlayProgressMapper.HistoryRow row : progressMapper.history(userId, n)) {
            out.add(new TvVos.HistoryVo(
                    row.getVideoId(), row.getMovieId(), row.getTitle(), row.getCoverUrl(), row.getCategoryName(),
                    row.getSeasonNo() == null ? 0 : row.getSeasonNo(),
                    row.getEpisodeNo() == null ? 0 : row.getEpisodeNo(),
                    row.getPositionMs() == null ? 0L : row.getPositionMs(),
                    row.getDurationMs() == null ? 0L : row.getDurationMs(),
                    row.getFinished() == null ? 0 : row.getFinished(),
                    Ts.milli(row.getUpdateTime()),
                    row.getBackdropUrl()));
        }
        return out;
    }
}
