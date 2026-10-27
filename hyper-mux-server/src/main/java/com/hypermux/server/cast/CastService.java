package com.hypermux.server.cast;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hypermux.server.app.service.MovieQueryService;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.common.Ts;
import com.hypermux.server.config.ConfigService;
import com.hypermux.server.movie.MovieEntity;
import com.hypermux.server.video.VideoEntity;
import com.hypermux.server.video.VideoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 远程投屏（M7）：请求生命周期 + 单并发 + 设备注册与在线。
 *
 * 流程口径（docs/plans/80-cast-detailed.md §1）：
 * 手机发起 → 服务器中转 → TV 弹窗（默认「是」，confirmSeconds 秒倒计时自动接受）→ TV accept → 手机收到 ACCEPTED。
 * 后端只中转指令，**不中转视频流**：TV 自己调 /api/app/play-url 换直链。
 *
 * 单并发 = 每账号同时最多 1 条 PENDING：服务层先抢占（旧 → CANCELLED/CLOSED(preempted)），
 * DB 部分唯一索引 uk_cast_pending_user 兜底。
 */
@Slf4j
@Service
public class CastService {

    private static final int MAX_DEVICE_ID = 64;
    private static final int MAX_NAME = 64;
    private static final int CONFIRM_MIN = 3;
    private static final int CONFIRM_MAX = 15;

    private final UserDeviceMapper deviceMapper;
    private final CastRequestMapper castMapper;
    private final VideoMapper videoMapper;
    private final MovieQueryService movieQueryService;
    private final DevicePresenceService presence;
    private final CastProperties props;
    private final ConfigService configService;

    /** poll 限频护栏：key = userId:deviceId → 上次 poll 时刻。 */
    private final Cache<String, OffsetDateTime> lastPoll = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();

    public CastService(UserDeviceMapper deviceMapper, CastRequestMapper castMapper, VideoMapper videoMapper,
                       MovieQueryService movieQueryService, DevicePresenceService presence,
                       CastProperties props, ConfigService configService) {
        this.deviceMapper = deviceMapper;
        this.castMapper = castMapper;
        this.videoMapper = videoMapper;
        this.movieQueryService = movieQueryService;
        this.presence = presence;
        this.props = props;
        this.configService = configService;
    }

    // ==================== 运行时开关（sys_config 覆盖 yml） ====================

    public boolean enabled() {
        String v = configService.get(ConfigService.KEY_CAST_ENABLED);
        if (v == null || v.isBlank()) {
            return props.isEnabled();
        }
        return !"false".equalsIgnoreCase(v.trim());
    }

    public int confirmSeconds() {
        String v = configService.get(ConfigService.KEY_CAST_CONFIRM_SECONDS);
        if (v == null || v.isBlank()) {
            return clampConfirm(props.getConfirmSeconds());
        }
        try {
            return clampConfirm(Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return clampConfirm(props.getConfirmSeconds());
        }
    }

    private static int clampConfirm(int v) {
        return Math.min(Math.max(v, CONFIRM_MIN), CONFIRM_MAX);
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new BizException(ErrorCode.CONFLICT, "投屏功能已关闭");
        }
    }

    // ==================== 设备 ====================

    public String register(Long userId, CastVos.RegisterReq req) {
        String deviceId = requireDeviceId(req == null ? null : req.deviceId());
        String platform = normalizePlatform(req == null ? null : req.platform());
        String name = sanitizeName(req == null ? null : req.deviceName());

        UserDeviceEntity existing = deviceMapper.findByUserAndDevice(userId, deviceId);
        OffsetDateTime now = OffsetDateTime.now();
        if (existing == null) {
            UserDeviceEntity e = new UserDeviceEntity();
            e.setUserId(userId);
            e.setDeviceId(deviceId);
            e.setPlatform(platform);
            e.setDeviceName(name);
            e.setLastSeen(now);
            e.setCreateTime(now);
            e.setUpdateTime(now);
            try {
                deviceMapper.insert(e);
            } catch (DataIntegrityViolationException dup) {
                // 并发首登：另一条已插入，改走更新
                UserDeviceEntity again = deviceMapper.findByUserAndDevice(userId, deviceId);
                if (again == null) {
                    throw new BizException(ErrorCode.CONFLICT, "设备注册冲突，请重试");
                }
                applyDeviceUpdate(again, platform, name, now);
            }
        } else {
            applyDeviceUpdate(existing, platform, name, now);
        }
        presence.touch(userId, deviceId);
        return deviceId;
    }

    private void applyDeviceUpdate(UserDeviceEntity e, String platform, String name, OffsetDateTime now) {
        e.setPlatform(platform);
        e.setDeviceName(name);
        e.setLastSeen(now);
        e.setUpdateTime(now);
        deviceMapper.updateById(e);
    }

    public List<CastVos.DeviceVo> devices(Long userId, String platform) {
        String p = (platform == null || platform.isBlank()) ? null : platform.trim().toLowerCase();
        List<CastVos.DeviceVo> out = new ArrayList<>();
        Set<String> castingIds = new HashSet<>(castMapper.listActiveTargetIds(userId));
        for (UserDeviceEntity d : deviceMapper.listByUser(userId, p)) {
            out.add(new CastVos.DeviceVo(
                    d.getDeviceId(),
                    d.getPlatform(),
                    (d.getDeviceName() == null || d.getDeviceName().isBlank()) ? defaultName(d.getPlatform()) : d.getDeviceName(),
                    presence.isOnline(userId, d.getDeviceId()),
                    Ts.milli(d.getLastSeen()),
                    castingIds.contains(d.getDeviceId())));
        }
        return out;
    }

    // ==================== 投屏请求 ====================

    public CastVos.RequestedVo request(Long userId, CastVos.RequestReq req) {
        requireEnabled();

        String targetId = requireDeviceId(req == null ? null : req.targetDeviceId());
        String senderId = requireDeviceId(req == null ? null : req.senderDeviceId());
        if (req == null || req.movieId() == null || req.videoId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "movieId/videoId 不能为空");
        }
        if (targetId.equals(senderId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能投屏到本设备");
        }

        UserDeviceEntity target = deviceMapper.findByUserAndDevice(userId, targetId);
        if (target == null) {
            // 不区分"不存在"与"不属于你"：避免被枚举出别人的设备是否存在
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在");
        }
        // 接收端 = 全部四端（TV / 网页端 / Windows 桌面端 / 手机端）。
        // platform 在 register 时已被 normalizePlatform 收敛成这四个值，这里不再拦任何一端。
        if (!presence.isOnline(userId, targetId)) {
            throw new BizException(ErrorCode.CONFLICT, "目标端不在线，请先在电视 / 网页端 / 电脑端 / 手机端打开 HyperMux");
        }
        if (deviceMapper.findByUserAndDevice(userId, senderId) == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "发送设备未注册，请先在本机登录一次");
        }

        MovieEntity movie = movieQueryService.requireVisible(req.movieId());
        VideoEntity video = videoMapper.selectById(req.videoId());
        if (video == null || (video.getMissing() != null && video.getMissing() == 1)
                || !movie.getId().equals(video.getMovieId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "剧集不存在或文件已丢失");
        }

        // 单并发：先抢占本账号当前活跃请求（旧目标在下一次 poll 收到 cancelledIds 后关弹窗/停播）
        preemptActive(userId);

        OffsetDateTime now = OffsetDateTime.now();
        long ttlSeconds = Math.max(5L, props.getRequestTtl().getSeconds());

        CastRequestEntity e = new CastRequestEntity();
        e.setUserId(userId);
        e.setSenderDeviceId(senderId);
        e.setTargetDeviceId(targetId);
        e.setSenderName(deviceName(userId, senderId));
        e.setTargetName(deviceName(userId, targetId));
        e.setMovieId(movie.getId());
        e.setVideoId(video.getId());
        e.setMovieTitle(movie.getTitle());
        e.setEpisodeLabel(episodeLabel(video));
        e.setCoverUrl(movie.getCoverUrl());
        e.setPositionMs(req.positionMs() == null || req.positionMs() < 0 ? 0L : req.positionMs());
        e.setStatus(CastRequestEntity.ST_PENDING);
        e.setExpireTime(now.plusSeconds(ttlSeconds));
        e.setCreateTime(now);
        e.setUpdateTime(now);

        try {
            castMapper.insert(e);
        } catch (DataIntegrityViolationException dup) {
            // uk_cast_pending_user 兜底：同账号并发发起
            throw new BizException(ErrorCode.CONFLICT, "已有投屏请求待确认，请稍候再试");
        }
        log.info("[cast] 请求 id={} 手机={} → 目标={}（{} 第{}集, {}ms）",
                e.getId(), senderId, targetId, movie.getTitle(), video.getEpisodeNo(), e.getPositionMs());
        return new CastVos.RequestedVo(e.getId(), ttlSeconds, deviceName(userId, targetId));
    }

    public CastVos.StatusVo status(Long userId, Long id) {
        CastRequestEntity e = requireRequest(userId, id);
        if (CastRequestEntity.ST_PENDING.equals(e.getStatus()) && isExpired(e)) {
            decide(e, CastRequestEntity.ST_EXPIRED, CastRequestEntity.RS_TIMEOUT);
        }
        return new CastVos.StatusVo(e.getId(), e.getStatus(), e.getTargetName(), e.getEndReason());
    }

    /**
     * 接收端轮询（TV / 网页端 / 电脑端 / 手机端，每 2s）。返回待确认请求 + 需要告知该设备的"已取消/被接管"请求 id（一次性下发）。
     * 投屏关闭时返回空 pending（不报错）：否则接收端每 2s 弹一次错误。
     */
    public CastVos.PollVo poll(Long userId, String deviceId) {
        String devId = requireDeviceId(deviceId);
        OffsetDateTime now = OffsetDateTime.now();

        String k = userId + ":" + devId;
        OffsetDateTime prev = lastPoll.getIfPresent(k);
        if (prev != null && now.toInstant().toEpochMilli() - prev.toInstant().toEpochMilli() < props.getPollMinIntervalMs()) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "轮询过于频繁");
        }
        lastPoll.put(k, now);
        presence.touch(userId, devId);

        if (!enabled()) {
            return new CastVos.PollVo(null, List.of(), confirmSeconds());
        }

        CastRequestEntity pending = castMapper.findPendingForTarget(userId, devId, now);
        // 过期但还没被清扫任务扫到的，就地判过期（清扫是兜底，不是唯一路径）
        if (pending != null && isExpired(pending)) {
            decide(pending, CastRequestEntity.ST_EXPIRED, CastRequestEntity.RS_TIMEOUT);
            pending = null;
        }

        List<Long> cancelledIds = new ArrayList<>();
        OffsetDateTime since = now.minus(props.getNotifyWindow());
        for (CastRequestEntity e : castMapper.listToNotify(userId, devId, since)) {
            e.setNotifiedAt(now);
            e.setUpdateTime(now);
            castMapper.updateById(e);
            cancelledIds.add(e.getId());
        }

        return new CastVos.PollVo(toPending(pending), cancelledIds, confirmSeconds());
    }

    public CastVos.AcceptedVo accept(Long userId, CastVos.AcceptReq req) {
        requireEnabled();
        CastRequestEntity e = requireRequest(userId, req == null ? null : req.id());
        if (!CastRequestEntity.ST_PENDING.equals(e.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "该投屏请求已结束");
        }
        if (isExpired(e)) {
            decide(e, CastRequestEntity.ST_EXPIRED, CastRequestEntity.RS_TIMEOUT);
            throw new BizException(ErrorCode.CONFLICT, "该投屏请求已结束");
        }
        String reason = CastRequestEntity.RS_COUNTDOWN.equals(req.reason())
                ? CastRequestEntity.RS_COUNTDOWN : CastRequestEntity.RS_USER_YES;
        decide(e, CastRequestEntity.ST_ACCEPTED, reason);
        log.info("[cast] 请求 id={} 被目标端={} 接受（{}）", e.getId(), e.getTargetDeviceId(), reason);
        return new CastVos.AcceptedVo(e.getId(), e.getMovieId(), e.getVideoId(),
                e.getPositionMs() == null ? 0L : e.getPositionMs(), e.getMovieTitle(), e.getEpisodeLabel());
    }

    /** 幂等：已是终态直接返回成功（TV 重复点「否」不报错）。 */
    public boolean reject(Long userId, CastVos.IdReq req) {
        CastRequestEntity e = requireRequest(userId, req == null ? null : req.id());
        if (!CastRequestEntity.ST_PENDING.equals(e.getStatus())) {
            return true;
        }
        decide(e, CastRequestEntity.ST_REJECTED, CastRequestEntity.RS_USER_NO);
        return true;
    }

    /** 幂等取消：PENDING → CANCELLED(sender-cancel)；ACCEPTED → CLOSED(sender-cancel / tv-exit / web-exit / desktop-exit / mobile-exit)。 */
    public boolean cancel(Long userId, CastVos.IdReq req) {
        CastRequestEntity e = requireRequest(userId, req == null ? null : req.id());
        if (e.isTerminal()) {
            return true;
        }
        String reason = req != null && isTargetExitReason(req.reason()) ? req.reason() : CastRequestEntity.RS_SENDER_CANCEL;
        if (CastRequestEntity.ST_PENDING.equals(e.getStatus())) {
            decide(e, CastRequestEntity.ST_CANCELLED, reason);
        } else {
            close(e, reason);
        }
        return true;
    }

    public CastVos.CurrentVo current(Long userId) {
        CastRequestEntity e = castMapper.findActiveForUser(userId);
        if (e != null && CastRequestEntity.ST_PENDING.equals(e.getStatus()) && isExpired(e)) {
            decide(e, CastRequestEntity.ST_EXPIRED, CastRequestEntity.RS_TIMEOUT);
            e = castMapper.findActiveForUser(userId);
        }
        if (e == null) {
            return new CastVos.CurrentVo(null);
        }
        return new CastVos.CurrentVo(new CastVos.CastBriefVo(
                e.getId(), e.getStatus(), e.getSenderDeviceId(), e.getTargetDeviceId(),
                e.getSenderName(), e.getTargetName(), e.getMovieId(), e.getVideoId(),
                e.getMovieTitle(), e.getEpisodeLabel(), e.getCoverUrl(),
                e.getPositionMs() == null ? 0L : e.getPositionMs(), Ts.milli(e.getCreateTime())));
    }

    public CastVos.ConfigVo config() {
        return new CastVos.ConfigVo(enabled(), confirmSeconds(),
                Math.max(5L, props.getRequestTtl().getSeconds()), props.getPollMinIntervalMs() * 2);
    }

    // ==================== 内部 ====================

    /** 抢占：把本账号当前 PENDING/ACCEPTED 全部置终态，并留待 poll 通知各自目标。 */
    private void preemptActive(Long userId) {
        for (CastRequestEntity e : castMapper.listActiveForUser(userId)) {
            if (CastRequestEntity.ST_PENDING.equals(e.getStatus())) {
                decide(e, CastRequestEntity.ST_CANCELLED, CastRequestEntity.RS_PREEMPTED);
            } else {
                close(e, CastRequestEntity.RS_PREEMPTED);
            }
            log.info("[cast] 请求 id={} 被同账号新请求抢占（TV={} 将在下次 poll 收到停止信号）",
                    e.getId(), e.getTargetDeviceId());
        }
    }

    private void decide(CastRequestEntity e, String status, String reason) {
        OffsetDateTime now = OffsetDateTime.now();
        e.setStatus(status);
        e.setEndReason(reason);
        e.setDecidedTime(now);
        e.setUpdateTime(now);
        if (CastRequestEntity.ST_ACCEPTED.equals(status)) {
            // ACCEPTED 不是"已通知"：它还需要在被抢占时能进入 cancelledIds，所以 notifiedAt 保持 null
            e.setNotifiedAt(null);
        }
        castMapper.updateById(e);
    }

    private void close(CastRequestEntity e, String reason) {
        OffsetDateTime now = OffsetDateTime.now();
        e.setStatus(CastRequestEntity.ST_CLOSED);
        e.setEndReason(reason);
        e.setClosedTime(now);
        e.setNotifiedAt(null);
        e.setUpdateTime(now);
        castMapper.updateById(e);
    }

    private CastRequestEntity requireRequest(Long userId, Long id) {
        if (id == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "id 不能为空");
        }
        CastRequestEntity e = castMapper.findByIdAndUser(id, userId);
        if (e == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "投屏请求不存在");
        }
        return e;
    }

    private static boolean isExpired(CastRequestEntity e) {
        return e.getExpireTime() != null && e.getExpireTime().isBefore(OffsetDateTime.now());
    }

    private CastVos.PendingVo toPending(CastRequestEntity e) {
        if (e == null) {
            return null;
        }
        return new CastVos.PendingVo(e.getId(), e.getSenderDeviceId(), e.getSenderName(),
                e.getMovieId(), e.getVideoId(), e.getMovieTitle(), e.getEpisodeLabel(), e.getCoverUrl(),
                e.getPositionMs() == null ? 0L : e.getPositionMs(), Ts.milli(e.getExpireTime()), confirmSeconds());
    }

    private String deviceName(Long userId, String deviceId) {
        UserDeviceEntity d = deviceMapper.findByUserAndDevice(userId, deviceId);
        if (d == null || d.getDeviceName() == null || d.getDeviceName().isBlank()) {
            return defaultName(d == null ? null : d.getPlatform());
        }
        return d.getDeviceName();
    }

    private static String defaultName(String platform) {
        if (UserDeviceEntity.PLATFORM_TV.equals(platform)) {
            return "电视";
        }
        if (UserDeviceEntity.PLATFORM_WEB.equals(platform)) {
            return "网页端";
        }
        if (UserDeviceEntity.PLATFORM_WINDOWS.equals(platform)) {
            return "Windows 端";
        }
        return "手机";
    }

    private static String episodeLabel(VideoEntity v) {
        if (v.getTitle() != null && !v.getTitle().isBlank()) {
            return v.getTitle().trim();
        }
        int ep = v.getEpisodeNo() == null ? 0 : v.getEpisodeNo();
        int season = v.getSeasonNo() == null ? 1 : v.getSeasonNo();
        return season > 1 ? ("第" + season + "季 第" + ep + "集") : ("第" + ep + "集");
    }

    /** 接收端自己退出播放页的收尾原因（按端区分，后台能看出"是谁停的"） */
    private static boolean isTargetExitReason(String reason) {
        return CastRequestEntity.RS_TV_EXIT.equals(reason)
                || CastRequestEntity.RS_WEB_EXIT.equals(reason)
                || CastRequestEntity.RS_DESKTOP_EXIT.equals(reason)
                || CastRequestEntity.RS_MOBILE_EXIT.equals(reason);
    }

    private static String requireDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "deviceId 不能为空");
        }
        String v = deviceId.trim();
        if (v.length() > MAX_DEVICE_ID) {
            throw new BizException(ErrorCode.BAD_REQUEST, "deviceId 最长 " + MAX_DEVICE_ID + " 位");
        }
        return v;
    }

    private static String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "platform 不能为空");
        }
        String p = platform.trim().toLowerCase();
        if (!UserDeviceEntity.PLATFORM_MOBILE.equals(p) && !UserDeviceEntity.PLATFORM_TV.equals(p)
                && !UserDeviceEntity.PLATFORM_WEB.equals(p) && !UserDeviceEntity.PLATFORM_WINDOWS.equals(p)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "platform 只能是 mobile/tv/web/windows");
        }
        return p;
    }

    /** 设备名清洗：去控制字符 + 截断（TV 弹窗与手机列表直接渲染它）。 */
    private static String sanitizeName(String name) {
        if (name == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < MAX_NAME; i++) {
            char c = name.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || Character.isISOControl(c)) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }
}
