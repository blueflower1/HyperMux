package com.hypermux.server.admin.service;

import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.cast.CastProperties;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.config.ConfigService;
import org.springframework.stereotype.Service;

/**
 * 系统设置（契约 §4.12）：元数据来源 + TMDB Key + 投屏开关与确认倒计时。
 *
 * 投屏两项落 sys_config，**覆盖** hypermux.cast.* 的 yml 默认值 —— 改完即时生效不用重启。
 */
@Service
public class SettingsService {

    private static final int CONFIRM_MIN = 3;
    private static final int CONFIRM_MAX = 15;

    private final ConfigService configService;
    private final CastProperties castProps;

    public SettingsService(ConfigService configService, CastProperties castProps) {
        this.configService = configService;
        this.castProps = castProps;
    }

    public AdminVos.SettingsVo get() {
        return new AdminVos.SettingsVo(
                configService.metadataProvider(),
                configService.tmdbApiKey(),
                castEnabled(),
                castConfirmSeconds());
    }

    public AdminVos.SettingsVo update(AdminVos.SettingsUpdateReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        if (req.metadataProvider() != null) {
            String provider = req.metadataProvider().trim().toLowerCase();
            if (!ConfigService.PROVIDER_TMDB.equals(provider) && !ConfigService.PROVIDER_DOUBAN.equals(provider)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "metadataProvider 只能是 tmdb 或 douban");
            }
            configService.put(ConfigService.KEY_METADATA_PROVIDER, provider);
        }
        if (req.tmdbApiKey() != null) {
            configService.put(ConfigService.KEY_TMDB_API_KEY, req.tmdbApiKey().trim());
        }
        if (req.castEnabled() != null) {
            configService.put(ConfigService.KEY_CAST_ENABLED, req.castEnabled() ? "true" : "false");
        }
        if (req.castConfirmSeconds() != null) {
            int v = req.castConfirmSeconds();
            if (v < CONFIRM_MIN || v > CONFIRM_MAX) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "castConfirmSeconds 取值范围 " + CONFIRM_MIN + "~" + CONFIRM_MAX);
            }
            configService.put(ConfigService.KEY_CAST_CONFIRM_SECONDS, String.valueOf(v));
        }
        return get();
    }

    private boolean castEnabled() {
        String v = configService.get(ConfigService.KEY_CAST_ENABLED);
        if (v == null || v.isBlank()) {
            return castProps.isEnabled();
        }
        return !"false".equalsIgnoreCase(v.trim());
    }

    private int castConfirmSeconds() {
        String v = configService.get(ConfigService.KEY_CAST_CONFIRM_SECONDS);
        if (v == null || v.isBlank()) {
            return castProps.getConfirmSeconds();
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return castProps.getConfirmSeconds();
        }
    }
}
