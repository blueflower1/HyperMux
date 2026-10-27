package com.hypermux.server.cast;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 投屏请求清扫（M7）。
 *
 * 过期判定只认落库的 expire_time —— Caffeine 会被 TV 的 2s 轮询无限续期，
 * 绝不能拿缓存 TTL 判业务过期（QrLoginService 已踩过这个坑，其注释写明了原因）。
 * 另外 poll 路径上也有"就地判过期"，本任务是兜底（没人 poll 也要能落终态）。
 */
@Slf4j
@Component
public class CastSweeper {

    private static final long SWEEP_INTERVAL_MS = 10_000L;

    private final CastRequestMapper castMapper;
    private final CastProperties props;

    public CastSweeper(CastRequestMapper castMapper, CastProperties props) {
        this.castMapper = castMapper;
        this.props = props;
    }

    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS)
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now();
        try {
            int expired = castMapper.expirePending(now);
            int closed = castMapper.closeStaleAccepted(now, now.minus(props.getAcceptedTtl()));
            if (expired > 0 || closed > 0) {
                log.info("[cast] 清扫：{} 条待确认超时置 EXPIRED，{} 条投屏记录兜底置 CLOSED", expired, closed);
            }
        } catch (Exception e) {
            log.warn("[cast] 清扫失败（不影响投屏主链路）: {}", rootMsg(e));
            log.debug("[cast] 清扫异常详情", e);
        }
    }

    /** MyBatis 的异常 message 带一整段 SQL 上下文，日志里只留最里层的数据库报错。 */
    private static String rootMsg(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
