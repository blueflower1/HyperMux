package com.hypermux.server.cast;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface UserDeviceMapper extends BaseMapper<UserDeviceEntity> {

    /** 按 (user_id, device_id) 定位设备——跨账号一律查不到（契约 §3.13：不区分"不存在/不属于你"）。 */
    @Select("SELECT * FROM user_device WHERE user_id = #{userId} AND device_id = #{deviceId}")
    UserDeviceEntity findByUserAndDevice(@Param("userId") Long userId, @Param("deviceId") String deviceId);

    /** 设备列表（platform 可空 = 全部），按 last_seen 降序（null 排最后）。 */
    @Select("<script>"
            + "SELECT * FROM user_device WHERE user_id = #{userId} "
            + "<if test='platform != null and platform != \"\"'>AND platform = #{platform}</if> "
            + "ORDER BY last_seen DESC NULLS LAST, id DESC"
            + "</script>")
    List<UserDeviceEntity> listByUser(@Param("userId") Long userId, @Param("platform") String platform);

    /** last_seen 落库节流用（poll 每 2s 一次，不能每 poll 写库）。 */
    @Update("UPDATE user_device SET last_seen = #{at}, update_time = #{at} "
            + "WHERE user_id = #{userId} AND device_id = #{deviceId}")
    int touchLastSeen(@Param("userId") Long userId, @Param("deviceId") String deviceId,
                      @Param("at") OffsetDateTime at);
}
