package com.hypermux.server.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.user.SysUserEntity;
import com.hypermux.server.user.UserMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 扫码登录（TV 显示二维码 → 手机扫码确认 → TV 轮询拿到 token）：
 * - ticket 存 Caffeine 缓存（60s 有效，按创建时间判定），不落库；
 * - qrId 为随机 UUID，二维码内容只含该 id，不含任何秘密；
 * - confirm 必须携带手机端有效 token（role=0），签发与密码登录同规则的 JWT；
 * - token 一次性投递：CONFIRMED 后由第一次 status 轮询取走 session，随后 ticket 进入 DELIVERED
 *   （防止二维码被拍照后在确认之后被人扫描盗 token）。
 */
@Service
public class QrLoginService {

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_SCANNED = "SCANNED";
    public static final String STATE_CONFIRMED = "CONFIRMED";
    public static final String STATE_EXPIRED = "EXPIRED";
    private static final String STATE_DELIVERED = "DELIVERED";

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final int MAX_CREATE_PER_MIN = 20;

    private static final class QrTicket {
        /** 真实过期判据：Caffeine 的 compute 会续期 expireAfterWrite，TV 每 2s 轮询会把
         *  缓存 TTL 无限续命，所以业务过期必须按创建时间算（缓存只兜底清理）。 */
        final long createdAt = System.currentTimeMillis();
        volatile String state = STATE_PENDING;
        volatile SessionVo session;

        boolean expired() {
            return System.currentTimeMillis() - createdAt > TTL.toMillis();
        }
    }

    private final UserMapper userMapper;
    private final JwtTokenService jwt;

    private final Cache<String, QrTicket> tickets = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(10_000)
            .build();

    /** create 限频：按 IP 每分钟上限（TV 过期自动重建，正常用量远低于该值）。 */
    private final Cache<String, AtomicInteger> createCounts = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(100_000)
            .build();

    public QrLoginService(UserMapper userMapper, JwtTokenService jwt) {
        this.userMapper = userMapper;
        this.jwt = jwt;
    }

    public QrVos.CreateVo create(String clientIp) {
        AtomicInteger c = createCounts.get(clientIp == null ? "unknown" : clientIp, k -> new AtomicInteger());
        if (c.incrementAndGet() > MAX_CREATE_PER_MIN) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "扫码登录创建过于频繁，请稍候再试");
        }
        String qrId = UUID.randomUUID().toString();
        tickets.put(qrId, new QrTicket());
        return new QrVos.CreateVo(qrId, TTL.toSeconds());
    }

    /** 手机端已扫到码（弹确认框之前）：PENDING → SCANNED，TV 显示「已扫码，请在手机确认」。 */
    public void scan(String qrId) {
        tickets.asMap().compute(qrId, (k, t) -> {
            if (t == null || t.expired()) {
                throw new BizException(ErrorCode.NOT_FOUND, "登录码已过期，请在 TV 上刷新二维码");
            }
            if (STATE_PENDING.equals(t.state)) {
                t.state = STATE_SCANNED;
            }
            return t;
        });
    }

    /** 手机端确认登录：绑定当前手机用户并签发 token（幂等：重复 confirm 不重复签发）。 */
    public void confirm(String qrId, Long userId) {
        QrTicket existing = tickets.getIfPresent(qrId);
        if (existing == null || existing.expired() || STATE_DELIVERED.equals(existing.state)) {
            throw new BizException(ErrorCode.NOT_FOUND, "登录码已过期，请在 TV 上刷新二维码");
        }
        if (existing.session != null) {
            return;
        }
        SysUserEntity user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() == 0) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号不可用");
        }
        JwtTokenService.Issued issued = jwt.issue(
                user.getId(), user.getUsername(), user.getRole(), jwt.ttlForRole(user.getRole()));
        SessionVo session = new SessionVo(issued.token(), issued.expiresAt(),
                new SessionVo.UserInfo(user.getId(), user.getUsername(), user.getNickName()));
        // compute 保证与 status 轮询的「一次性投递」互斥
        tickets.asMap().compute(qrId, (k, cur) -> {
            if (cur == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "登录码已过期，请在 TV 上刷新二维码");
            }
            cur.session = session;
            cur.state = STATE_CONFIRMED;
            return cur;
        });
    }

    /** TV 轮询：PENDING / SCANNED / CONFIRMED（带 session，仅此一次）/ EXPIRED。 */
    public QrVos.StatusVo status(String qrId) {
        SessionVo[] delivered = new SessionVo[1];
        tickets.asMap().compute(qrId, (k, t) -> {
            if (t == null || t.expired()) {
                return null;
            }
            if (STATE_CONFIRMED.equals(t.state)) {
                delivered[0] = t.session;
                t.session = null;
                t.state = STATE_DELIVERED;
            }
            return t;
        });
        if (delivered[0] != null) {
            return new QrVos.StatusVo(STATE_CONFIRMED, delivered[0]);
        }
        QrTicket t = tickets.getIfPresent(qrId);
        if (t == null || t.expired() || STATE_DELIVERED.equals(t.state)) {
            return new QrVos.StatusVo(STATE_EXPIRED, null);
        }
        return new QrVos.StatusVo(t.state, null);
    }
}
