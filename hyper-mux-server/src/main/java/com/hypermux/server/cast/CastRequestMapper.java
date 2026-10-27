package com.hypermux.server.cast;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface CastRequestMapper extends BaseMapper<CastRequestEntity> {

    /** 按 (id, user_id) 定位——跨账号一律查不到。 */
    @Select("SELECT * FROM cast_request WHERE id = #{id} AND user_id = #{userId}")
    CastRequestEntity findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    /** 本账号当前活跃请求（PENDING 优先，其次 ACCEPTED）。 */
    @Select("SELECT * FROM cast_request WHERE user_id = #{userId} AND status IN ('PENDING','ACCEPTED') "
            + "ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, create_time DESC LIMIT 1")
    CastRequestEntity findActiveForUser(@Param("userId") Long userId);

    /** 本账号所有活跃请求（抢占前遍历用）。 */
    @Select("SELECT * FROM cast_request WHERE user_id = #{userId} AND status IN ('PENDING','ACCEPTED') "
            + "ORDER BY create_time DESC")
    List<CastRequestEntity> listActiveForUser(@Param("userId") Long userId);

    /** 该目标设备当前待确认的请求（未过期）。 */
    @Select("SELECT * FROM cast_request WHERE user_id = #{userId} AND target_device_id = #{deviceId} "
            + "AND status = 'PENDING' AND expire_time > #{now} ORDER BY create_time DESC LIMIT 1")
    CastRequestEntity findPendingForTarget(@Param("userId") Long userId, @Param("deviceId") String deviceId,
                                           @Param("now") OffsetDateTime now);

    /** 设备列表的 casting 标记：本账号活跃请求指向哪些设备。 */
    @Select("SELECT DISTINCT target_device_id FROM cast_request WHERE user_id = #{userId} "
            + "AND status IN ('PENDING','ACCEPTED')")
    List<String> listActiveTargetIds(@Param("userId") Long userId);

    /**
     * 需要告知目标设备的"已被取消/接管"请求 id（一次性下发）：
     * 只取 CANCELLED / CLOSED（被手机取消、被抢占、TV 退出、兜底 idle），
     * REJECTED / EXPIRED 不通知——那两种是 TV 自己（或它不在场时）造成的，它不需要再被告知。
     */
    @Select("SELECT * FROM cast_request WHERE user_id = #{userId} AND target_device_id = #{deviceId} "
            + "AND status IN ('CANCELLED','CLOSED') AND notified_at IS NULL AND update_time >= #{since} "
            + "ORDER BY update_time DESC LIMIT 10")
    List<CastRequestEntity> listToNotify(@Param("userId") Long userId, @Param("deviceId") String deviceId,
                                         @Param("since") OffsetDateTime since);

    /** 清扫：PENDING 过期（过期判定只认落库 expire_time）。 */
    @Update("UPDATE cast_request SET status = 'EXPIRED', end_reason = 'timeout', decided_time = #{now}, "
            + "update_time = #{now} WHERE status = 'PENDING' AND expire_time <= #{now}")
    int expirePending(@Param("now") OffsetDateTime now);

    /**
     * 清扫：ACCEPTED 长期未关闭（TV 崩溃/未 cancel 的兜底）。
     * **同时把 notified_at 置上**——这是纯账目清理，绝不能让正在播放的 TV 收到"停止"信号。
     */
    @Update("UPDATE cast_request SET status = 'CLOSED', end_reason = 'idle', closed_time = #{now}, "
            + "notified_at = #{now}, update_time = #{now} WHERE status = 'ACCEPTED' AND decided_time <= #{since}")
    int closeStaleAccepted(@Param("now") OffsetDateTime now, @Param("since") OffsetDateTime since);
}
