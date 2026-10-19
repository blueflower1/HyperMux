package com.hypermux.server.admin.service;

import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import com.hypermux.server.config.ConfigService;
import org.springframework.stereotype.Service;

/** 系统设置（契约 §4.11）：元数据来源 + TMDB Key。 */
@Service
public class SettingsService {

    private final ConfigService configService;

    public SettingsService(ConfigService configService) {
        this.configService = configService;
    }

    public AdminVos.SettingsVo get() {
        return new AdminVos.SettingsVo(configService.metadataProvider(), configService.tmdbApiKey());
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
        return get();
    }
}
