package com.hypermux.server.cast;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 设备在线状态（内存判定，不落库）。
 *
 * 在线 = 最近 30s 内该设备调过 devices/register 或 cast/poll。
 * 之所以不落库：TV 每 2s 轮一次 poll，落库就是每 2s 一次写库（写放大）；
 * 与 10-data-model §6.6「无 Redis：单进程 + Caffeine 足够」一致。
 * user_device.last_seen 只是"上次在线"展示字段，落库另做 30s 节流。
 */
@Slf4j
@Service
public class DevicePresenceService {

    private static final Duration ONLINE_TTL = Duration.ofSeconds(30);
    private static final Duration DB_WRITE_TTL = Duration.ofSeconds(30);

    private final UserDeviceMapper deviceMapper;

    /** key = userId:deviceId → 最近一次心跳时间（expireAfterWrite：每次 touch 续期）。 */
    private final Cache<String, OffsetDateTime> online = Caffeine.newBuilder()
            .expireAfterWrite(ONLINE_TTL)
            .maximumSize(100_000)
            .build();

    /** 同一设备 last_seen 落库节流窗口（命中即跳过写库）。 */
    private final Cache<String, Boolean> dbWritten = Caffeine.newBuilder()
            .expireAfterWrite(DB_WRITE_TTL)
            .maximumSize(100_000)
            .build();

    public DevicePresenceService(UserDeviceMapper deviceMapper) {
        this.deviceMapper = deviceMapper;
    }

    private static String key(Long userId, String deviceId) {
        return userId + ":" + deviceId;
    }

    /** 心跳：register / poll 都调。内存立即续期，落库按 30s 节流。 */
    public void touch(Long userId, String deviceId) {
        if (userId == null || deviceId == null || deviceId.isBlank()) {
            return;
        }
        String k = key(userId, deviceId);
        online.put(k, OffsetDateTime.now());
        if (dbWritten.getIfPresent(k) == null) {
            dbWritten.put(k, Boolean.TRUE);
            try {
                deviceMapper.touchLastSeen(userId, deviceId, OffsetDateTime.now());
            } catch (Exception e) {
                // 展示字段写失败不影响在线判定
                log.warn("[cast] last_seen 落库失败 userId={} deviceId={}: {}", userId, deviceId, e.getMessage());
            }
        }
    }

    public boolean isOnline(Long userId, String deviceId) {
        return userId != null && deviceId != null && online.getIfPresent(key(userId, deviceId)) != null;
    }
}
