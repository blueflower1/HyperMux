package com.hypermux.server.migrate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hypermux.server.auth.MigrateProperties;
import com.hypermux.server.common.PinyinUtils;
import com.hypermux.server.movie.MovieEntity;
import com.hypermux.server.movie.MovieMapper;
import com.hypermux.server.user.SysUserEntity;
import com.hypermux.server.user.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

/**
 * 启动迁移（幂等）：
 * 1) 执行 classpath:sql/schema.sql（CREATE TABLE IF NOT EXISTS / ALTER ADD COLUMN IF NOT EXISTS）；
 * 2) 回填影片拼音检索列（只处理 title_pinyin IS NULL 的存量行）；
 * 3) 若库内无管理员且配置了 admin-bootstrap-password，则创建初始管理员。
 */
@Slf4j
@Component
public class SchemaMigrateRunner implements ApplicationRunner {

    private final DataSource dataSource;
    private final UserMapper userMapper;
    private final MovieMapper movieMapper;
    private final MigrateProperties props;

    public SchemaMigrateRunner(DataSource dataSource, UserMapper userMapper, MovieMapper movieMapper,
                               MigrateProperties props) {
        this.dataSource = dataSource;
        this.userMapper = userMapper;
        this.movieMapper = movieMapper;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isEnabled()) {
            log.info("[migrate] 已关闭（hypermux.migrate.enabled=false）");
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            ClassPathResource script = new ClassPathResource("sql/schema.sql");
            if (script.exists()) {
                ScriptUtils.executeSqlScript(conn, script);
                log.info("[migrate] schema.sql 已执行（幂等）");
            }
        } catch (Exception e) {
            log.error("[migrate] schema.sql 执行失败", e);
        }

        backfillPinyin();

        Long adminCount = userMapper.selectCount(
                new LambdaQueryWrapper<SysUserEntity>().eq(SysUserEntity::getRole, SysUserEntity.ROLE_ADMIN));
        if (adminCount != null && adminCount > 0) {
            return;
        }
        String password = props.getAdminBootstrapPassword();
        if (password == null || password.isBlank()) {
            log.warn("[migrate] 库内无管理员，且未配置 admin-bootstrap-password，跳过创建");
            return;
        }
        SysUserEntity admin = new SysUserEntity();
        admin.setUsername(props.getAdminBootstrapUsername());
        admin.setPasswordHash(new BCryptPasswordEncoder().encode(password));
        admin.setNickName("超级管理员");
        admin.setRole(SysUserEntity.ROLE_ADMIN);
        admin.setStatus(1);
        userMapper.insert(admin);
        log.info("[migrate] 已创建初始管理员: {}", admin.getUsername());
    }

    /** 存量影片拼音列回填：只处理 title_pinyin IS NULL 的行（新建/更新由 MovieAdminService 维护）。 */
    private void backfillPinyin() {
        try {
            List<MovieEntity> rows = movieMapper.selectList(
                    new LambdaQueryWrapper<MovieEntity>().isNull(MovieEntity::getTitlePinyin));
            if (rows.isEmpty()) {
                return;
            }
            for (MovieEntity m : rows) {
                m.setTitlePinyin(PinyinUtils.fullPinyin(m.getTitle()));
                m.setTitleInitials(PinyinUtils.initials(m.getTitle()));
                movieMapper.updateById(m);
            }
            log.info("[migrate] 拼音检索列已回填 {} 部存量影片", rows.size());
        } catch (Exception e) {
            log.error("[migrate] 拼音回填失败（不影响启动）", e);
        }
    }
}