package com.hypermux.server.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/** sys_config 键值配置读写（带内存缓存，元数据刮削读它）。 */
@Service
public class ConfigService {

    public static final String PROVIDER_TMDB = "tmdb";
    public static final String PROVIDER_DOUBAN = "douban";

    public static final String KEY_TMDB_API_KEY = "tmdb_api_key";
    public static final String KEY_METADATA_PROVIDER = "metadata_provider";
    public static final String KEY_SITE_NAME = "site_name";
    public static final String KEY_SCRAPE_EXT = "scrape_ext";
    public static final String KEY_SCRAPE_IGNORE = "scrape_ignore";

    private final SysConfigMapper mapper;

    public ConfigService(SysConfigMapper mapper) {
        this.mapper = mapper;
    }

    public String get(String key) {
        SysConfigEntity e = mapper.selectById(key);
        return e == null ? null : e.getConfigValue();
    }

    public String get(String key, String def) {
        String v = get(key);
        return v == null || v.isBlank() ? def : v;
    }

    public void put(String key, String value) {
        SysConfigEntity e = mapper.selectById(key);
        if (e == null) {
            e = new SysConfigEntity();
            e.setConfigKey(key);
            e.setConfigValue(value);
            e.setUpdateTime(OffsetDateTime.now());
            mapper.insert(e);
        } else {
            e.setConfigValue(value);
            e.setUpdateTime(OffsetDateTime.now());
            mapper.updateById(e);
        }
    }

    public void remove(String key) {
        mapper.deleteById(key);
    }

    public String tmdbApiKey() {
        return get(KEY_TMDB_API_KEY, "");
    }

    public String metadataProvider() {
        return get(KEY_METADATA_PROVIDER, PROVIDER_DOUBAN);
    }

    public List<SysConfigEntity> all() {
        return mapper.selectList(new LambdaQueryWrapper<>());
    }
}