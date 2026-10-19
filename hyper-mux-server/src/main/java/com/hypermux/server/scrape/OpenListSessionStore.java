package com.hypermux.server.scrape;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 建源向导的 OpenList 连接会话：明文 token 只存在服务端内存（TTL 30 分钟，每次使用续期，重启即失效），
 * 永不出参（契约 §4.8）。
 */
@Component
public class OpenListSessionStore {

    public record Session(String sessionId, String serverUrl, String token, String version) {
    }

    private final Cache<String, Session> sessions = Caffeine.newBuilder()
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .maximumSize(2_000)
            .build();

    public Session create(String serverUrl, String token, String version) {
        String id = UUID.randomUUID().toString().replace("-", "");
        Session s = new Session(id, serverUrl, token, version);
        sessions.put(id, s);
        return s;
    }

    /** 取会话（并续期）；不存在/过期 → 400。 */
    public Session require(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 sessionId");
        }
        Session s = sessions.getIfPresent(sessionId);
        if (s == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "连接会话已过期，请重新连接");
        }
        sessions.put(sessionId, s);
        return s;
    }

    public void remove(String sessionId) {
        sessions.invalidate(sessionId);
    }
}
